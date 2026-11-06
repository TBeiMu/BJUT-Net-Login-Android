package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

data class UpdateNetwork(val key: String, val source: UpdateSource)

@Serializable
internal data class SavedUpdate(val manifest: UpdateManifest, val ready: Boolean = false, val etag: String? = null,
    // null denotes a v0.3.0 record, which did not distinguish discovery from a started download.
    val downloadStarted: Boolean? = null)

/** Non-secret update metadata; entirely separate from the account vault. */
class UpdateStore(val directory: File) {
    val partial = File(directory, "download.part")
    val verified = File(directory, "verified/update.apk")
    private val metadata = File(directory, "state.json")
    @Synchronized internal fun load(): SavedUpdate? = if (metadata.isFile) {
        if (metadata.length() > 262_144) throw UpdateFailure("更新记录损坏，请重新检查更新")
        updateJson.decodeFromString<SavedUpdate>(metadata.readText()).also { it.manifest.validate() }
    } else null
    @Synchronized internal fun save(value: SavedUpdate) {
        directory.mkdirs()
        val temp = File(directory, "state.tmp")
        temp.writeText(updateJson.encodeToString(value))
        java.nio.file.Files.move(temp.toPath(), metadata.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun clear() {
        // Only fixed update-owned files are removed; never recurse into the account directory.
        listOf(partial, verified, metadata, File(directory, "state.tmp")).forEach {
            if (it.exists() && !it.delete()) throw UpdateFailure("旧更新文件无法清理，请稍后重试")
        }
    }
    fun promote() {
        verified.parentFile?.mkdirs()
        java.nio.file.Files.move(partial.toPath(), verified.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Calls are serialized on the main dispatcher; only byte progress is published from IO. */
class UpdateRepository(private val scope: CoroutineScope, private val store: UpdateStore,
    private val verifier: ApkVerifier, private val installedVersion: Long, private val sdk: Int,
    enabled: Boolean = true) {
    private val mutable = MutableStateFlow(UpdateUiState(enabled = enabled,
        message = if (enabled) "点击检查更新，仅使用 WiFi" else "调试版不提供正式包覆盖更新，请使用发布页"))
    val state = mutable.asStateFlow()
    private var network: UpdateNetwork? = null
    private var task: Job? = null
    private var activeNetwork: String? = null
    private var saved: SavedUpdate? = null
    private var stopMessage = "下载已暂停，请手动继续"
    private var clearing = false
    @Volatile private var generation = 0L

    init {
        if (enabled) try {
            saved = store.load()
            saved?.let { record ->
                if (record.manifest.versionCode <= installedVersion) {
                    store.clear(); saved = null
                    mutable.value = state.value.copy(phase = UpdatePhase.CURRENT, message = "已安装新版本，旧安装包已清理")
                } else {
                    val ready = record.ready && store.verified.isFile
                    val started = record.downloadStarted ?: (record.ready || store.partial.exists() || record.etag != null)
                    saved = record.copy(downloadStarted = started)
                    val phase = when { ready -> UpdatePhase.READY; started -> UpdatePhase.PAUSED; else -> UpdatePhase.AVAILABLE }
                    mutable.value = state.value.copy(phase = phase,
                        manifest = record.manifest, bytes = if (ready) store.verified.length() else store.partial.length(),
                        message = when (phase) {
                            UpdatePhase.READY -> "安装包已下载，点击安装时将重新校验"
                            UpdatePhase.PAUSED -> "上次下载已暂停，请手动继续"
                            else -> "上次检查发现新版本，可手动下载"
                        })
                }
            }
        } catch (_: Exception) {
            saved = null
            mutable.value = state.value.copy(phase = UpdatePhase.ERROR, message = "更新记录无法读取，请重新检查更新")
        }
    }

    fun networkChanged(value: UpdateNetwork?) {
        network = value
        if (activeNetwork != null && activeNetwork != value?.key) pause("WiFi 已断开或切换，请手动继续")
    }

    fun check() {
        if (!state.value.enabled || task != null || state.value.manifest != null) return
        val selected = network ?: run { fail("请先连接可上网的 WiFi"); return }
        activeNetwork = selected.key
        mutable.value = state.value.copy(phase = UpdatePhase.CHECKING, message = "正在检查更新…")
        task = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                val manifest = withTimeout(60_000) { selected.source.check().validate() }
                if (manifest.versionCode <= installedVersion) {
                    mutable.value = state.value.copy(phase = UpdatePhase.CURRENT, message = "当前已是最新版本")
                } else if (manifest.minSdk > sdk) {
                    fail("新版需要 Android API ${manifest.minSdk} 或以上，请打开发布页查看")
                } else {
                    store.clear()
                    saved = SavedUpdate(manifest, downloadStarted = false).also(store::save)
                    mutable.value = state.value.copy(phase = UpdatePhase.AVAILABLE, manifest = manifest, message = "发现新版本")
                }
            } catch (_: CancellationException) { fail("检查已停止，请手动重试")
            } catch (error: Exception) { fail(error.safeUpdateMessage("检查更新失败，请检查 WiFi 后手动重试"))
            } finally { activeNetwork = null; if (task === ownJob) task = null }
        }
        task?.start()
    }

    fun leaveForeground() { if (state.value.phase == UpdatePhase.CHECKING) task?.cancel() }

    fun download(): Boolean {
        if (!state.value.enabled || task != null) return false
        val record = saved?.copy(downloadStarted = true) ?: return false
        if (state.value.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.PAUSED, UpdatePhase.ERROR)) return false
        val selected = network ?: run {
            mutable.value = state.value.copy(phase = UpdatePhase.PAUSED, message = "请先连接可上网的 WiFi，再手动继续")
            return false
        }
        val token = ++generation
        activeNetwork = selected.key
        stopMessage = "下载已暂停，请手动继续"
        mutable.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, message = "正在通过 WiFi 下载…")
        task = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                withTimeout(30 * 60_000L) {
                    // Persist user intent before DNS/HTTP, including failures before the first byte.
                    store.save(record)
                    selected.source.download(record.manifest, store.partial, record.etag,
                        saveEtag = { tag -> store.save(record.copy(etag = tag)) },
                        progress = { count -> mutable.update { if (generation == token) it.copy(bytes = count) else it } })
                    mutable.value = state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在校验安装包…")
                    try { verifier.verify(store.partial, record.manifest) }
                    catch (error: UpdateFailure) {
                        store.partial.delete()
                        throw error
                    }
                    store.promote()
                    saved = record.copy(ready = true, etag = null).also(store::save)
                    mutable.value = state.value.copy(phase = UpdatePhase.READY, bytes = record.manifest.apkSize,
                        message = "校验通过，点击安装更新")
                }
            } catch (_: TimeoutCancellationException) {
                mutable.value = state.value.copy(phase = UpdatePhase.PAUSED, message = "下载超时，请手动继续")
            } catch (_: CancellationException) {
                mutable.value = state.value.copy(phase = UpdatePhase.PAUSED,
                    message = if (generation == token) stopMessage else "任务已取消")
            } catch (error: Exception) {
                mutable.value = state.value.copy(phase = UpdatePhase.PAUSED,
                    message = error.safeUpdateMessage("下载已暂停，请检查 WiFi 和存储空间后手动继续"))
            } finally {
                generation++
                saved = try { store.load() } catch (_: Exception) { record.copy(etag = null) }
                activeNetwork = null; if (task === ownJob) task = null
            }
        }
        task?.start()
        return true
    }

    fun pause(message: String = "下载已暂停，请手动继续") {
        stopMessage = message
        task?.cancel()
    }

    fun serviceStopped() { if (activeNetwork != null && state.value.phase != UpdatePhase.CHECKING) pause("下载服务已停止，请手动继续") }

    fun cancel() {
        if (clearing) return
        clearing = true
        val previous = task
        activeNetwork = null
        generation++
        previous?.cancel()
        mutable.value = state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在清理更新文件…")
        task = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                store.clear(); saved = null
                mutable.value = UpdateUiState(enabled = state.value.enabled, message = "已取消，可重新检查更新")
            } catch (_: Exception) { fail("更新文件未能清理，请再次取消")
            } finally { task = null; clearing = false }
        }
        task?.start()
    }

    suspend fun prepareInstall(): File? {
        if (task != null || state.value.phase != UpdatePhase.READY) return null
        val record = saved ?: return null
        // Own the same task slot as checking/downloading so cancel cannot race file verification.
        val current = kotlinx.coroutines.currentCoroutineContext()[Job]
        task = current
        mutable.value = state.value.copy(phase = UpdatePhase.VERIFYING, message = "安装前重新校验…")
        return try {
            verifier.verify(store.verified, record.manifest)
            mutable.value = state.value.copy(phase = UpdatePhase.READY, message = "请在系统页面确认安装；取消后仍可再次安装")
            store.verified
        } catch (error: CancellationException) {
            mutable.value = state.value.copy(phase = UpdatePhase.READY, message = "安装操作已取消")
            throw error
        } catch (error: Exception) {
            store.verified.delete()
            saved = record.copy(ready = false, etag = null)
            try { store.save(saved!!) } catch (_: Exception) { /* Keep the safe in-memory state; no install is offered. */ }
            mutable.value = state.value.copy(phase = UpdatePhase.PAUSED, bytes = 0,
                message = error.safeUpdateMessage("安装包无法校验，请重新下载"))
            null
        } finally { if (task === current) task = null }
    }

    fun installationMessage(message: String) { mutable.update { it.copy(message = message) } }
    private fun fail(message: String) { mutable.value = state.value.copy(phase = UpdatePhase.ERROR, message = message) }
}

internal fun Exception.safeUpdateMessage(fallback: String) = if (this is UpdateFailure) message ?: fallback else fallback
