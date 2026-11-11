package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

data class UpdateNetwork(val key: String, val source: UpdateSource, val kind: UpdateNetworkKind = UpdateNetworkKind.WIFI,
    val validated: Boolean = true) {
    val identity: String get() = "${kind.name}:$key"
}

private data class DownloadAuthorization(val id: String, val networkIdentity: String,
    val manifest: UpdateManifest, val approved: Boolean)

@Serializable
internal data class SavedUpdate(val manifest: UpdateManifest, val ready: Boolean = false, val etag: String? = null,
    // null denotes a v0.3.0 record, which did not distinguish discovery from a started download.
    val downloadStarted: Boolean? = null)

/** Non-secret update metadata; entirely separate from the account vault. */
class UpdateStore(val directory: File) {
    val partial = File(directory, "download.part")
    val verified = File(directory, "verified/update.apk")
    private val metadata = File(directory, "state.json")
    private val ignored = File(directory, "ignored-version.txt")
    @Synchronized internal fun ignoredVersion(): Long? = if (ignored.isFile) {
        if (ignored.length() > 32) throw UpdateFailure("忽略版本记录无法读取")
        ignored.readText().trim().toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?: throw UpdateFailure("忽略版本记录无法读取")
    } else null
    @Synchronized internal fun ignoreVersion(versionCode: Long) {
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        directory.mkdirs()
        val temp = File(directory, "ignored-version.tmp")
        temp.writeText(versionCode.toString())
        java.nio.file.Files.move(temp.toPath(), ignored.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
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
        message = if (enabled) "点击检查更新；使用流量下载前会询问" else "调试版不提供正式包覆盖更新，请使用发布页"))
    val state = mutable.asStateFlow()
    private var network: UpdateNetwork? = null
    private var task: Job? = null
    private var activeNetwork: String? = null
    private var activeDownloadPermit: String? = null
    private var saved: SavedUpdate? = null
    private var stopMessage = "下载已暂停，请手动继续"
    private var clearing = false
    private var authorization: DownloadAuthorization? = null
    private var notificationSelection: UpdateManifest? = null
    private var foreground = false
    private var automaticPending = false
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
        mutable.update { it.copy(networkKind = value?.kind) }
        if (authorization != null && authorization?.networkIdentity != value?.identity) {
            clearAuthorization("更新网络已变化，请重新点击下载")
        }
        if (activeNetwork != null && activeNetwork != value?.identity) pause("网络已断开或切换，请手动继续")
        maybeAutomaticCheck()
    }

    fun enterForeground() {
        if (foreground) return
        foreground = true
        automaticPending = true
        maybeAutomaticCheck()
    }

    private fun maybeAutomaticCheck() {
        if (!foreground || !automaticPending || !state.value.enabled) return
        // Existing downloads/installation remain a user-controlled task; no automatic replacement.
        if (saved?.downloadStarted == true || state.value.downloadStarting || state.value.mobileConfirmation != null) {
            automaticPending = false; return
        }
        if (task != null || state.value.busy) return
        if (network?.validated != true) return
        automaticPending = false
        checkForPrompt(automatic = true)
    }

    fun check() = check(selectImmediately = true, automatic = false)

    fun checkForPrompt(automatic: Boolean = false) {
        if (!foreground) return
        automaticPending = false
        check(selectImmediately = false, automatic = automatic)
    }

    private fun check(selectImmediately: Boolean, automatic: Boolean) {
        if (!state.value.enabled || task != null || state.value.busy || state.value.prompt != null ||
            (selectImmediately && state.value.manifest != null)) return
        val selected = network ?: run { fail("请连接可上网的 WiFi 或开启移动数据"); return }
        val previous = state.value
        activeNetwork = selected.identity
        mutable.value = state.value.copy(phase = UpdatePhase.CHECKING, message = "正在检查更新…")
        task = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                val manifest = withTimeout(60_000) { selected.source.check().validate() }
                currentCoroutineContext().ensureActive()
                if (manifest.versionCode <= installedVersion) {
                    mutable.value = previous.copy(phase = if (previous.manifest == null) UpdatePhase.CURRENT else previous.phase,
                        networkKind = network?.kind, message = "当前已是最新版本")
                } else if (manifest.minSdk > sdk) {
                    if (automatic) mutable.value = previous.copy(networkKind = network?.kind)
                    else if (selectImmediately) fail("新版需要 Android API ${manifest.minSdk} 或以上，请打开发布页查看")
                    else mutable.value = previous.copy(networkKind = network?.kind,
                        message = "新版需要 Android API ${manifest.minSdk} 或以上，请打开发布页查看")
                } else if (!selectImmediately) {
                    // A damaged local preference must never turn into an implicit ignore-all choice.
                    val ignoredVersion = if (automatic) runCatching { store.ignoredVersion() }.getOrNull() else null
                    val ignored = automatic && ignoredVersion == manifest.versionCode
                    mutable.value = previous.copy(networkKind = network?.kind,
                        prompt = if (ignored) null else UpdatePrompt(java.util.UUID.randomUUID().toString(), manifest),
                        message = if (ignored) previous.message else "发现新版本 ${manifest.versionName}")
                } else {
                    store.clear()
                    saved = SavedUpdate(manifest, downloadStarted = false).also(store::save)
                    mutable.value = state.value.copy(phase = UpdatePhase.AVAILABLE, manifest = manifest, message = "发现新版本")
                }
            } catch (_: CancellationException) {
                if (automatic || !selectImmediately) mutable.value = previous.copy(networkKind = network?.kind,
                    prompt = null, message = if (automatic) previous.message else "检查已停止，请手动重试")
                else fail("检查已停止，请手动重试")
            } catch (error: Exception) {
                if (automatic) mutable.value = previous.copy(networkKind = network?.kind, prompt = null)
                else if (!selectImmediately) mutable.value = previous.copy(networkKind = network?.kind,
                    message = error.safeUpdateMessage("检查更新失败，请检查网络后手动重试"))
                else fail(error.safeUpdateMessage("检查更新失败，请检查网络后手动重试"))
            } finally {
                activeNetwork = null
                if (task === ownJob) task = null
                maybeAutomaticCheck()
            }
        }
        task?.start()
    }

    fun leaveForeground() {
        foreground = false
        automaticPending = false
        mutable.update { it.copy(prompt = null) }
        if (state.value.phase == UpdatePhase.CHECKING) task?.cancel()
        // Approved starts may already be queued in the foreground service. Unanswered prompts expire.
        if (state.value.mobileConfirmation != null) clearAuthorization("已取消移动数据下载，可稍后重新选择")
    }

    fun dismissPrompt(id: String) {
        if (state.value.prompt?.id == id) mutable.update { it.copy(prompt = null) }
    }

    fun ignorePrompt(id: String) {
        val prompt = state.value.prompt?.takeIf { it.id == id } ?: return
        try {
            store.ignoreVersion(prompt.manifest.versionCode)
            mutable.update { it.copy(prompt = null, message = "已忽略版本 ${prompt.manifest.versionName}，仍可手动检查更新") }
        } catch (_: Exception) {
            mutable.update { it.copy(prompt = prompt.copy(error = "未能保存忽略选择，请重试")) }
        }
    }

    /** Selecting a version is separate from discovering it; rejection never deletes a partial APK. */
    fun acceptPrompt(id: String): Boolean {
        val prompt = state.value.prompt?.takeIf { it.id == id } ?: return false
        if (!foreground || task != null || state.value.busy) return false
        return try {
            val manifest = prompt.manifest.validate()
            if (manifest.versionCode <= installedVersion || manifest.minSdk > sdk) return false
            if (saved?.manifest?.copy(releaseNotes = "") != manifest.copy(releaseNotes = "")) {
                store.clear()
                saved = SavedUpdate(manifest, downloadStarted = false).also(store::save)
            } else if (saved?.manifest != manifest) {
                saved = saved!!.copy(manifest = manifest).also(store::save)
            }
            val record = saved!!
            val phase = when {
                record.ready && store.verified.isFile -> UpdatePhase.READY
                record.downloadStarted == true -> UpdatePhase.PAUSED
                else -> UpdatePhase.AVAILABLE
            }
            mutable.value = state.value.copy(prompt = null, manifest = manifest, phase = phase,
                bytes = if (phase == UpdatePhase.READY) store.verified.length() else store.partial.length(),
                message = if (phase == UpdatePhase.READY) "安装包已下载，可安装更新" else "已选择版本 ${manifest.versionName}")
            true
        } catch (_: Exception) {
            mutable.update { it.copy(prompt = prompt.copy(error = "更新文件无法准备，请重试")) }
            false
        }
    }

    /** No download or APK DNS request is made here. Only the selected attempt can obtain a permit. */
    fun requestDownload(): String? {
        if (!state.value.enabled || task != null || notificationSelection != null || authorization != null || !canDownload()) return null
        val manifest = saved?.manifest ?: return null
        val selected = network ?: run {
            installationMessage("请连接可上网的 WiFi 或开启移动数据，再重新点击下载")
            return null
        }
        val request = DownloadAuthorization(java.util.UUID.randomUUID().toString(), selected.identity,
            manifest, approved = selected.kind == UpdateNetworkKind.WIFI)
        authorization = request
        mutable.update { it.copy(prompt = null) }
        if (!request.approved) {
            mutable.update { it.copy(mobileConfirmation = MobileDownloadConfirmation(request.id, manifest.versionName, manifest.apkSize)) }
            return null
        }
        mutable.update { it.copy(downloadStarting = true, message = "正在准备 WiFi 下载…") }
        return request.id
    }

    fun confirmMobileDownload(id: String): String? {
        val request = authorization ?: return null
        if (request.id != id || request.approved || state.value.mobileConfirmation?.id != id) return null
        if (task != null || !canDownload() || network?.kind != UpdateNetworkKind.CELLULAR ||
            request.networkIdentity != network?.identity || saved?.manifest != request.manifest) {
            clearAuthorization("网络或更新版本已变化，请重新点击下载")
            return null
        }
        authorization = request.copy(approved = true)
        mutable.update { it.copy(mobileConfirmation = null, downloadStarting = true, message = "正在准备移动数据下载…") }
        return request.id
    }

    fun dismissMobileDownload(id: String) {
        if (state.value.mobileConfirmation?.id == id) clearAuthorization("已取消移动数据下载，可连接 WiFi 后下载")
    }

    fun downloadStartFailed(id: String) {
        if (authorization?.id == id) clearAuthorization("无法启动后台下载，请回到 App 后重新点击下载")
    }

    private fun canDownload() = state.value.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.PAUSED, UpdatePhase.ERROR)

    /** Reserves only the selected version while optional notification UI is open; no APK permit. */
    fun awaitNotificationPermission(): Boolean {
        if (!foreground || !state.value.enabled || task != null || notificationSelection != null ||
            authorization != null || !canDownload()) return false
        notificationSelection = saved?.manifest ?: return false
        automaticPending = false
        mutable.update { it.copy(downloadStarting = true, message = "正在等待通知权限选择…") }
        return true
    }

    fun finishNotificationPermission(): Boolean {
        val selected = notificationSelection ?: return false
        notificationSelection = null
        mutable.update { it.copy(downloadStarting = false) }
        return foreground && state.value.enabled && task == null && canDownload() && saved?.manifest == selected
    }

    private fun clearAuthorization(message: String) {
        authorization = null
        mutable.update { it.copy(mobileConfirmation = null, downloadStarting = false, message = message) }
    }

    fun download(permit: String?): Boolean {
        if (!state.value.enabled || task != null) return false
        val request = authorization ?: return false
        if (permit != request.id || !request.approved) return false
        authorization = null // Single use, never persisted and never replayed after process restart.
        mutable.update { it.copy(downloadStarting = false, mobileConfirmation = null) }
        val record = saved?.copy(downloadStarted = true) ?: return false
        if (!canDownload()) return false
        val selected = network ?: run {
            mutable.value = state.value.copy(phase = UpdatePhase.PAUSED, message = "网络已断开，请重新选择下载")
            return false
        }
        if (request.networkIdentity != selected.identity || request.manifest != record.manifest) {
            installationMessage("网络或更新版本已变化，请重新点击下载")
            return false
        }
        val token = ++generation
        activeNetwork = selected.identity
        activeDownloadPermit = permit
        stopMessage = "下载已暂停，请手动继续"
        mutable.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, message = "正在通过${selected.kind.title}下载…")
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
                    message = error.safeUpdateMessage("下载已暂停，请检查网络和存储空间后手动继续"))
            } finally {
                generation++
                saved = try { store.load() } catch (_: Exception) { record.copy(etag = null) }
                activeNetwork = null; if (task === ownJob) task = null
                if (activeDownloadPermit == permit) activeDownloadPermit = null
            }
        }
        task?.start()
        return true
    }

    fun pause(message: String = "下载已暂停，请手动继续") {
        stopMessage = message
        task?.cancel()
    }

    fun serviceStopped(permit: String?, message: String = "下载服务已停止，请手动继续") {
        if (permit != null && activeDownloadPermit == permit && task?.isCancelled == false) pause(message)
    }

    fun cancel() {
        if (clearing) return
        automaticPending = false
        clearing = true
        authorization = null
        notificationSelection = null
        val previous = task
        activeNetwork = null
        activeDownloadPermit = null
        generation++
        previous?.cancel()
        mutable.value = state.value.copy(phase = UpdatePhase.VERIFYING, mobileConfirmation = null, prompt = null,
            downloadStarting = false, message = "正在清理更新文件…")
        task = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                store.clear(); saved = null
                mutable.value = UpdateUiState(enabled = state.value.enabled, networkKind = network?.kind,
                    message = "已取消，可重新检查更新")
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
