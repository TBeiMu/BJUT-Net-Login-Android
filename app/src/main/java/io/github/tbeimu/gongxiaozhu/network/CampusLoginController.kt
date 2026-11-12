package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class LoginTaskOutcome { SUCCESS, FAILED, TIMED_OUT, CANCELLED, NEEDS_CHOICE }

data class LoginUiState(
    val running: Boolean = false,
    val message: String = "连接校园 WiFi 后，可使用默认账号登录",
    val wifiConnected: Boolean = false,
    val mode: NetworkMode? = null,
    val needsChoice: Boolean = false,
    val completed: Boolean = false,
    val taskId: String? = null,
    val diagnostics: List<LoginDiagnostic> = emptyList(),
    val outcome: LoginTaskOutcome? = null,
    val wifiSnapshotKnown: Boolean = false,
)

/** Main-thread state machine. The service owns execution, never an Activity or ViewModel. */
class CampusLoginController(
    private val scope: CoroutineScope,
    private val authenticator: CampusAuthenticator,
    private val credentials: suspend (String?) -> AccountDraft?,
    private val startService: (String) -> Unit,
    private val storageFailure: () -> Unit,
) {
    private val mutable = MutableStateFlow(LoginUiState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var attempted = false
    private var unseenResult = false
    private var ready = false
    private var automatic = false
    private var mode = NetworkMode.AUTO
    private var accountId: String? = null
    private var wifi: WifiConnection? = null
    private var hasWifiSnapshot = false
    private var job: Job? = null
    private var attempt: Attempt? = null
    private var serviceOwner: String? = null

    private class Attempt(val id: String, val wifi: WifiConnection, val accountId: String?, val mode: NetworkMode) {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        var cancelMessage = "认证已取消；已建立的网络连接不会退出"
    }

    fun awaitWifiSnapshot() { hasWifiSnapshot = false }
    fun awaitAccountReadiness() { ready = false }

    fun enterForeground() {
        if (foreground) return
        foreground = true
        attempted = attempt != null || unseenResult
        unseenResult = false
        maybeAutomatic()
    }

    fun leaveForeground() { foreground = false }

    fun configure(ready: Boolean, automatic: Boolean, mode: NetworkMode, accountId: String?) {
        if (this.mode != mode || !ready) cancel(message = "设置或账号状态发生变化，已停止认证，请手动重试")
        this.ready = ready
        this.automatic = automatic
        this.mode = mode
        this.accountId = accountId
        maybeAutomatic()
    }

    fun wifiChanged(connection: WifiConnection?) {
        val firstSnapshot = !hasWifiSnapshot
        val changed = wifi?.key != connection?.key
        hasWifiSnapshot = true
        wifi = connection
        if (changed || firstSnapshot) {
            val message = if (connection == null) "未连接 WiFi，请先连接校园 WiFi" else "WiFi 已变化，请检查认证状态"
            if (attempt != null && changed) {
                cancel(message = message)
                mutable.value = state.value.copy(wifiConnected = connection != null, wifiSnapshotKnown = true)
            }
            else if (attempt != null) mutable.value = state.value.copy(wifiSnapshotKnown = true)
            else if (changed || state.value.taskId == null) mutable.value = LoginUiState(
                message = message, wifiConnected = connection != null, wifiSnapshotKnown = true)
            else mutable.value = state.value.copy(wifiSnapshotKnown = true)
        }
        maybeAutomatic()
    }

    private fun maybeAutomatic() {
        if (!foreground || !ready || !hasWifiSnapshot || attempted || job != null) return
        attempted = true
        if (automatic && wifi != null) login()
    }

    fun login() {
        if (!foreground || !ready || !hasWifiSnapshot || job != null || serviceOwner != null) return
        attempted = true
        val selected = wifi ?: run {
            mutable.value = LoginUiState(message = "未连接 WiFi，请先连接校园 WiFi", wifiSnapshotKnown = true)
            return
        }
        val current = Attempt(UUID.randomUUID().toString(), selected, accountId, mode)
        attempt = current
        mutable.value = LoginUiState(true, "正在启动认证，可切换到后台…", true,
            taskId = current.id, wifiSnapshotKnown = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(LOGIN_TASK_TIMEOUT_MS) {
                    current.started.await()
                    val transport = DiagnosticPortalTransport(current.wifi.transport) { diagnostic ->
                        if (attempt === current && !current.cancelled) {
                            mutable.value = state.value.copy(diagnostics = (state.value.diagnostics + diagnostic).takeLast(24))
                        }
                    }
                    val result = authenticator.login(transport, current.mode, { credentials(current.accountId) }) { message ->
                        if (!current.cancelled) mutable.value = state.value.copy(message = message)
                    }
                    if (!current.cancelled) mutable.value = state.value.copy(message = result.message,
                        mode = result.mode, needsChoice = result.needsChoice, completed = result.completed,
                        outcome = when {
                            result.completed -> LoginTaskOutcome.SUCCESS
                            result.needsChoice -> LoginTaskOutcome.NEEDS_CHOICE
                            else -> LoginTaskOutcome.FAILED
                        })
                }
            } catch (_: TimeoutCancellationException) {
                mutable.value = state.value.copy(message = "认证超时，已停止；请手动检查联网状态",
                    completed = false, outcome = LoginTaskOutcome.TIMED_OUT)
            } catch (error: CancellationException) {
                mutable.value = state.value.copy(message = current.cancelMessage, completed = false, needsChoice = false,
                    outcome = LoginTaskOutcome.CANCELLED)
                throw error
            } catch (_: VaultUnavailableException) {
                storageFailure()
                mutable.value = state.value.copy(message = "账号存储不可用，认证已停止", outcome = LoginTaskOutcome.FAILED)
            } catch (_: Exception) {
                mutable.value = state.value.copy(message = "认证未完成，请检查网络和默认账号后手动重试",
                    outcome = LoginTaskOutcome.FAILED)
            }
        }.also { task -> task.invokeOnCompletion {
            scope.launch {
                // Also runs when cancellation happens before the coroutine body first executes.
                if (attempt === current) {
                    attempt = null
                    job = null
                    unseenResult = !foreground
                    mutable.value = state.value.copy(running = false, wifiConnected = wifi != null,
                        message = if (current.cancelled) current.cancelMessage else state.value.message)
                }
            }
        } }
        job?.start()
        try { startService(current.id) }
        catch (_: Exception) { cancel(current.id, "无法启动认证服务，请回到 App 后手动重试") }
    }

    /** An in-memory, single-use start. Replayed or restored service intents cannot send credentials. */
    fun serviceStarted(id: String): Boolean {
        val current = attempt ?: return false
        if (current.id != id || current.cancelled || current.started.isCompleted || job?.isActive != true) return false
        serviceOwner = id
        return current.started.complete(Unit)
    }

    fun cancel(id: String? = state.value.taskId, message: String = "认证已取消；已建立的网络连接不会退出") {
        val current = attempt ?: return
        if (id != current.id || current.cancelled) return
        current.cancelled = true
        current.cancelMessage = message
        attempted = true
        mutable.value = state.value.copy(message = "正在停止认证…", completed = false, needsChoice = false,
            outcome = LoginTaskOutcome.CANCELLED)
        job?.cancel()
    }

    fun serviceStopped(id: String) {
        if (serviceOwner == id) serviceOwner = null
        cancel(id, "认证服务已停止，请手动检查联网状态")
    }

    fun accountWillChange(id: String?) {
        if (id == null || attempt?.accountId == id) cancel(message = "本次账号资料发生变化，已停止认证，请手动重试")
    }

    fun deviceOfflineRequested() {
        attempted = true
        cancel(message = "已请求注销在线设备；如需重新连接，请手动登录")
    }

    suspend fun awaitStopped() { job?.join() }
}

internal const val LOGIN_TASK_TIMEOUT_MS = 75_000L
