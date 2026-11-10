package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class OfflineConfirmation(val device: OnlineDevice, val tip: String) {
    override fun toString() = "OfflineConfirmation(device=<redacted>)"
}

data class BillingUiState(
    val running: Boolean = false,
    val snapshot: BillingSnapshot? = null,
    val message: String = "使用首页所选账号登录学校自助服务，查询资费与在线设备",
    val captcha: ByteArray? = null,
    val confirmation: OfflineConfirmation? = null,
    val loadedAt: Long? = null,
    val diagnostics: List<BillingDiagnostic> = emptyList(),
    val offerWeb: Boolean = false,
    val webPage: BillingWebPage? = null,
) {
    override fun toString() = "BillingUiState(running=$running, data=<redacted>)"
}

/** UI-thread controller; invalidates responses and confirmations when account/network changes. */
class BillingController(
    private val scope: CoroutineScope,
    private val credentials: suspend (String) -> AccountDraft?,
    private val publish: (BillingUiState) -> Unit,
    private val storageFailure: () -> Unit,
    private val beforeOffline: () -> Unit,
    private val awaitLoginStopped: suspend () -> Unit = {},
) {
    private var state = BillingUiState()
    private var accountId: String? = null
    private var wifi: WifiConnection? = null
    private var wifiObserved = false
    private var session: BillingSession? = null
    private var foreground = false
    private var visible = false
    private var attempted = false
    private var job: Job? = null
    private var generation = 0L
    private var offlineSent = false
    private var offlineConfirmed = false

    fun accountChanged(id: String?, force: Boolean = false) {
        if (accountId == id && !force) return
        val hadAccount = accountId != null
        accountId = id
        reset("账号已更新，请登录查询该账号的资费", clearData = true)
        // Switching accounts is a deliberate action, but querying remains explicit.
        if (hadAccount || force) attempted = true else maybeQuery()
    }

    fun wifiChanged(connection: WifiConnection?) {
        val changed = wifi?.key != connection?.key
        wifiObserved = true
        wifi = connection
        if (changed) reset(if (connection == null) "未连接 WiFi，请先连接校园 WiFi" else "WiFi 已变化，请重新登录查询", clearData = true)
        maybeQuery()
    }

    fun wifiObservationPaused() {
        wifiObserved = false
        // Retain the last display and network identity, but never reuse its query session.
        reset(state.message, clearData = false)
    }

    fun enterForeground() {
        if (foreground) return
        foreground = true
        attempted = false
        maybeQuery()
    }

    fun leaveForeground() {
        foreground = false
        wifiObserved = false
        reset(if (offlineSent) "已停止等待注销结果，请重新查询在线信息核对" else "已停止查询；下次进入资费页会重新查询", clearData = false)
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value && (state.webPage != null || state.offerWeb)) reset("已关闭内置登录，请重新查询资费", false)
        if (value) maybeQuery()
    }

    private fun maybeQuery() {
        if (visible && foreground && wifiObserved && !attempted && accountId != null && wifi != null) query()
    }

    fun query(code: String? = null) {
        if (!foreground || !wifiObserved || job?.isCompleted == false) return
        if (state.webPage != null) return
        attempted = true
        val id = accountId ?: run { emit(state.copy(message = "请先添加并选择校园网账号")); return }
        val selectedWifi = wifi ?: run { emit(state.copy(message = "未连接 WiFi，请先连接校园 WiFi")); return }
        val current = session ?: selectedWifi.billingSession?.invoke()?.also { session = it }
            ?: run { emit(state.copy(message = "当前 WiFi 无法提供自助服务连接")); return }
        val token = ++generation
        emit(state.copy(running = true, message = "正在登录查询校园网资费…", confirmation = null, offerWeb = false))
        job = scope.launch {
            try {
                val selected = credentials(id) ?: throw BillingException(BillingProblem.REJECTED)
                val result = current.refresh(selected, code)
                if (token == generation) emit(BillingUiState(snapshot = result,
                    message = result.notice ?: "资费已更新 · 数据来自学校自助服务", loadedAt = System.currentTimeMillis(), diagnostics = current.diagnostics))
            } catch (error: CancellationException) { throw error }
            catch (_: VaultUnavailableException) {
                if (token == generation) { reset("账号存储不可用，查询已停止", true); storageFailure() }
            } catch (error: BillingException) {
                if (token == generation) {
                    if (error.problem == BillingProblem.ACCOUNT_MISMATCH) {
                        invalidateAccountSession()
                        return@launch
                    }
                    emit(state.copy(running = false, message = error.problem.hint,
                        captcha = if (error.problem in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED)) current.captcha else null,
                        confirmation = null, diagnostics = current.diagnostics,
                        offerWeb = error.problem in listOf(BillingProblem.REJECTED, BillingProblem.UNCONFIRMED) ||
                            (error.problem == BillingProblem.RESPONSE && current.diagnostics.any { it.page == "登录页" })))
                    if (error.problem !in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED)) { current.close(); session = null }
                }
            } catch (_: Exception) {
                if (token == generation) { current.close(); session = null; emit(state.copy(running = false,
                    captcha = null, confirmation = null, message = BillingProblem.RESPONSE.hint)) }
            }
        }.also { task -> task.invokeOnCompletion { scope.launch { maybeQuery() } } }
    }

    fun dismissWebOffer() { emit(state.copy(offerWeb = false)) }

    fun openWeb() {
        if (!foreground || job?.isCompleted == false || !state.offerWeb) return
        val id = accountId ?: return
        val selectedWifi = wifi ?: return
        session?.close()
        val current = selectedWifi.billingSession?.invoke()?.also { session = it } ?: return
        val token = ++generation
        emit(state.copy(running = true, offerWeb = false, captcha = null, message = "正在准备学校内置登录页…"))
        job = scope.launch {
            try {
                val selected = credentials(id) ?: throw BillingException(BillingProblem.REJECTED)
                val page = current.openWeb(selected)
                if (token == generation) emit(state.copy(running = false, webPage = page,
                    message = "账号密码已预填，请在学校页面点击登录；修改本次密码不会保存", diagnostics = current.diagnostics))
            } catch (error: CancellationException) { throw error }
            catch (_: VaultUnavailableException) { if (token == generation) { reset("账号存储不可用，查询已停止", true); storageFailure() } }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun submitWeb(pageToken: String, password: String, code: String) {
        if (!foreground || job?.isCompleted == false || state.webPage?.token != pageToken) return
        val current = session ?: return
        val token = ++generation
        emit(state.copy(running = true, message = "正在提交学校网页登录…"))
        job = scope.launch {
            try {
                when (val result = current.submitWeb(pageToken, password, code)) {
                    is BillingWebResult.Success -> if (token == generation) emit(BillingUiState(snapshot = result.snapshot,
                        message = result.snapshot.notice ?: "网页登录成功，资费已更新", loadedAt = System.currentTimeMillis(), diagnostics = current.diagnostics))
                    is BillingWebResult.Continue -> if (token == generation) emit(state.copy(running = false,
                        webPage = result.page, message = result.problem.hint, diagnostics = current.diagnostics))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun refreshWebCaptcha(pageToken: String, display: (ByteArray) -> Unit) {
        if (!foreground || job?.isCompleted == false || state.webPage?.token != pageToken) return
        val current = session ?: return
        val token = ++generation
        emit(state.copy(running = true))
        job = scope.launch {
            try {
                val image = current.refreshWebCaptcha(pageToken)
                if (token == generation && state.webPage?.token == pageToken) { display(image); emit(state.copy(running = false, diagnostics = current.diagnostics)) }
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun closeWeb() { reset("已取消内置网页登录，请手动登录查询", clearData = false) }
    fun unsupportedWeb() { reset("系统 WebView 不支持安全网页登录，请更新系统 WebView，或打开学校自助服务网页", false) }

    private fun webFailed(current: BillingSession, problem: BillingProblem) {
        if (problem == BillingProblem.ACCOUNT_MISMATCH) {
            invalidateAccountSession()
            return
        }
        emit(state.copy(running = false, webPage = null, offerWeb = false, captcha = null, message = problem.hint, diagnostics = current.diagnostics))
        current.close(); session = null
    }

    fun requestOffline(sessionId: String) {
        if (!foreground || job?.isCompleted == false) return
        val device = state.snapshot?.devices?.find { it.sessionId == sessionId } ?: return
        val current = session ?: run { emit(state.copy(message = BillingProblem.EXPIRED.hint)); return }
        val token = ++generation
        emit(state.copy(running = true, confirmation = null, message = "正在读取设备注销提示…"))
        job = scope.launch {
            try {
                val tip = current.offlineTip()
                if (token == generation) emit(state.copy(running = false, message = "请确认要注销的设备", confirmation = OfflineConfirmation(device, tip)))
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) emit(state.copy(running = false, message = error.problem.hint)) }
            catch (_: Exception) { if (token == generation) emit(state.copy(running = false, message = BillingProblem.RESPONSE.hint)) }
        }
    }

    fun dismissOffline() { emit(state.copy(confirmation = null)) }

    fun confirmOffline() {
        if (!foreground || job?.isCompleted == false) return
        val confirmation = state.confirmation ?: return
        val current = session ?: return
        val id = accountId ?: return
        val token = ++generation
        beforeOffline()
        attempted = true
        offlineSent = true
        emit(state.copy(running = true, confirmation = null, message = "正在注销选定设备，请勿重复操作…"))
        job = scope.launch {
            try {
                awaitLoginStopped()
                current.offline(confirmation.device)
                if (token != generation) return@launch
                offlineConfirmed = true
                // A successful offline response is conclusive even if the current WiFi then disconnects.
                emit(state.copy(snapshot = state.snapshot?.copy(devices = state.snapshot!!.devices.filterNot {
                    it.sessionId == confirmation.device.sessionId
                }), message = "学校已确认设备注销；正在刷新在线信息…"))
                val refreshed = try {
                    val selected = credentials(id) ?: throw BillingException(BillingProblem.EXPIRED)
                    current.refresh(selected)
                }
                    catch (error: CancellationException) { throw error }
                    catch (_: VaultUnavailableException) {
                        reset("账号存储不可用，刷新已停止", clearData = true)
                        storageFailure()
                        null
                    }
                    catch (error: BillingException) {
                        if (token == generation && error.problem == BillingProblem.ACCOUNT_MISMATCH) {
                            invalidateAccountSession()
                        }
                        null
                    }
                    catch (_: Exception) { null }
                if (token == generation) emit(state.copy(running = false, snapshot = refreshed ?: state.snapshot,
                    loadedAt = if (refreshed != null) System.currentTimeMillis() else state.loadedAt,
                    message = if (refreshed != null) "学校已确认设备注销，在线信息已刷新" else "学校已确认设备注销；刷新失败，请稍后核对在线信息"))
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) emit(state.copy(running = false, message = error.problem.hint)) }
            catch (_: Exception) { if (token == generation) emit(state.copy(running = false, message = BillingProblem.OFFLINE.hint)) }
            finally { if (token == generation) { offlineSent = false; offlineConfirmed = false } }
        }
    }

    /** A mismatched identity invalidates both the session and every action derived from its data. */
    private fun invalidateAccountSession() {
        val message = (if (offlineConfirmed) "学校已确认设备注销；" else "") + BillingProblem.ACCOUNT_MISMATCH.hint
        // Preserve the confirmed outcome above instead of reset's generic connection-change message.
        offlineSent = false
        offlineConfirmed = false
        attempted = true
        reset(message, clearData = true)
    }

    private fun reset(message: String, clearData: Boolean) {
        val actualMessage = if (offlineSent) {
            if (offlineConfirmed) "学校已确认设备注销；连接已变化，请稍后重新查询在线信息"
            else "设备注销结果未确认，请重新查询在线信息核对后再操作"
        } else message
        generation++
        job?.cancel()
        session?.close()
        session = null
        emit(if (clearData) BillingUiState(message = actualMessage) else state.copy(running = false, captcha = null,
            confirmation = null, offerWeb = false, webPage = null, diagnostics = emptyList(), message = actualMessage))
        offlineSent = false
        offlineConfirmed = false
    }

    private fun emit(next: BillingUiState) { state = next; publish(next) }
}
