package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class LoginUiState(
    val running: Boolean = false,
    val message: String = "连接校园 WiFi 后，可使用默认账号登录",
    val wifiConnected: Boolean = false,
    val mode: NetworkMode? = null,
    val needsChoice: Boolean = false,
    val completed: Boolean = false,
)

/** Called only on the UI thread; retained by the ViewModel and independently testable. */
class ForegroundLoginController(
    private val scope: CoroutineScope,
    private val authenticator: CampusAuthenticator,
    private val credentials: () -> (suspend () -> AccountDraft?),
    private val publish: (LoginUiState) -> Unit,
    private val storageFailure: () -> Unit,
) {
    private var foreground = false
    private var attempted = false
    private var ready = false
    private var automatic = false
    private var mode = NetworkMode.AUTO
    private var wifi: WifiConnection? = null
    private var hasWifiSnapshot = false
    private var job: Job? = null
    private var generation = 0L

    fun enterForeground() {
        if (foreground) return
        foreground = true
        attempted = false
        maybeAutomatic()
    }

    fun leaveForeground() {
        foreground = false
        cancel("已停止登录；重新进入 App 后可检查认证状态")
    }

    fun wifiChanged(connection: WifiConnection?) {
        val firstSnapshot = !hasWifiSnapshot
        hasWifiSnapshot = true
        if (!firstSnapshot && wifi?.key == connection?.key) return
        wifi = connection
        cancel(if (connection == null) "未连接 WiFi，请先连接校园 WiFi" else "WiFi 已连接，请点击登录检查认证状态")
        maybeAutomatic()
        // A switch during this foreground entry never starts another automatic attempt.
    }

    fun configure(ready: Boolean, automatic: Boolean, mode: NetworkMode) {
        if (this.mode != mode || !ready) {
            if (job?.isActive == true) cancel("设置或账号状态发生变化，已停止登录，请手动重试")
        }
        this.ready = ready
        this.automatic = automatic
        this.mode = mode
        maybeAutomatic()
    }

    private fun maybeAutomatic() {
        if (!foreground || !ready || !hasWifiSnapshot || attempted) return
        if (automatic && job?.isCompleted == false) return
        attempted = true
        if (automatic) login()
    }

    fun login() {
        if (!foreground || !ready || job?.isCompleted == false) return
        val selected = wifi ?: run { publish(LoginUiState(message = "未连接 WiFi，请先连接校园 WiFi")); return }
        val selectedCredentials = credentials()
        val token = ++generation
        publish(LoginUiState(running = true, message = "正在检查校园认证状态…", wifiConnected = true))
        job = scope.launch {
            try {
                val result = authenticator.login(selected.transport, mode, selectedCredentials) { message ->
                    if (generation == token) publish(LoginUiState(true, message, wifiConnected = true))
                }
                if (generation == token) publish(LoginUiState(message = result.message, wifiConnected = true,
                    mode = result.mode, needsChoice = result.needsChoice, completed = result.completed))
            } catch (error: CancellationException) {
                throw error
            } catch (_: VaultUnavailableException) {
                if (generation == token) { storageFailure(); publish(LoginUiState(message = "账号存储不可用，认证已停止", wifiConnected = true)) }
            } catch (_: Exception) {
                if (generation == token) publish(LoginUiState(message = "登录未完成，请检查网络和默认账号后手动重试", wifiConnected = true))
            }
        }.also { task ->
            task.invokeOnCompletion { scope.launch { maybeAutomatic() } }
        }
    }

    private fun cancel(message: String) {
        generation++
        job?.cancel()
        publish(LoginUiState(message = message, wifiConnected = wifi != null))
    }
}
