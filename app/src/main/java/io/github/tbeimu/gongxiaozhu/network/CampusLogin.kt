package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

data class LoginResult(val message: String, val mode: NetworkMode? = null, val needsChoice: Boolean = false,
    val completed: Boolean = false)

/** Confirmed connectivity is distinct from the lifetime of request cleanup. */
fun interface LoginProgress {
    operator fun invoke(message: String)
    fun confirmed(result: LoginResult) {}
}

fun interface CampusAuthenticator {
    suspend fun login(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: LoginProgress): LoginResult
}

/** Recognition and preflight never send credentials; the current dorm flow uses one login endpoint. */
class CampusLogin : CampusAuthenticator {
    override suspend fun login(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: LoginProgress): LoginResult {
        val confirmed = ConfirmedState()
        val result = try {
            withTimeout(60_000) { authenticate(transport, mode, credentials, progress, confirmed) }
        } catch (_: TimeoutCancellationException) {
            confirmed.failure(PortalProblem.CONNECTION)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PortalException) {
            confirmed.failure(error.problem)
        }
        // A stale gateway status or a rejected duplicate request does not prove lack of internet.
        // Recognition requires a valid campus response, but connectivity does not require result=1.
        // Keep this bounded check outside the authentication timeout so a lost reply can be reconciled.
        if (confirmed.dormRecognized && !result.completed) {
            progress("正在通过当前 WiFi 复核 IPv4 联网状态…")
            val online = try { withTimeout(10_000) { transport.hasIpv4Internet() } }
                catch (_: TimeoutCancellationException) { false }
                catch (_: PortalException) { false }
            if (online) return confirmed.success(internetConfirmed(), progress)
        }
        return result
    }

    private suspend fun authenticate(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: LoginProgress, confirmed: ConfirmedState): LoginResult {
        progress("正在检查校园认证状态…")
        var publicStatus: Boolean? = null
        var dormStatus: Boolean? = null
        var dormInternet: Boolean? = null
        val selected = if (mode == NetworkMode.AUTO) {
            val recognition = recognize(transport, confirmed, progress)
            recognition.completed?.let { return it }
            publicStatus = recognition.publicStatus
            dormStatus = recognition.dormStatus
            dormInternet = recognition.dormInternet
            when {
                publicStatus != null && dormStatus == null -> NetworkMode.PUBLIC_WIFI
                dormStatus != null && publicStatus == null -> NetworkMode.DORM_WIFI
                publicStatus != null && dormStatus != null -> return LoginResult(
                    "公共与宿舍认证端点均返回有效状态，请选择当前 WiFi 类型后手动登录", needsChoice = true)
                else -> return LoginResult("未识别到校园认证服务；请确认 WiFi。若接口发生变化，可手动选择网络类型重试")
            }
        } else mode

        confirmed.mode = selected
        val gatewayStatus = when (selected) {
            NetworkMode.PUBLIC_WIFI -> publicStatus ?: requiredStatus(transport, PortalEndpoint.PUBLIC_STATUS)
            NetworkMode.DORM_WIFI -> dormStatus ?: requiredStatus(transport, PortalEndpoint.DORM_STATUS)
            NetworkMode.AUTO -> error("Explicit mode required")
        }
        confirmed.dormRecognized = selected == NetworkMode.DORM_WIFI
        if (gatewayStatus) return confirmed.success(alreadyAuthenticated(selected), progress)
        // Check before reading/sending credentials even when the recognized gateway still says 0.
        if (confirmed.dormRecognized && (dormInternet ?: transport.hasIpv4Internet())) return confirmed.success(internetConfirmed(), progress)
        val account = credentials() ?: return LoginResult("请先添加并设置默认校园网账号", selected)
        progress(if (selected == NetworkMode.PUBLIC_WIFI) "正在登录公共 WiFi…" else "正在登录宿舍 WiFi…")
        val request = if (selected == NetworkMode.PUBLIC_WIFI) publicLogin(account) else dormLogin(account)
        if (!PortalParser.result(transport.execute(request))) throw PortalException(PortalProblem.REJECTED)
        val statusEndpoint = if (selected == NetworkMode.PUBLIC_WIFI) PortalEndpoint.PUBLIC_STATUS else PortalEndpoint.DORM_STATUS
        if (!requiredStatus(transport, statusEndpoint)) throw PortalException(PortalProblem.UNCONFIRMED)
        return confirmed.success(LoginResult(if (selected == NetworkMode.PUBLIC_WIFI) "公共 WiFi 认证成功" else "宿舍 WiFi 认证成功", selected, completed = true), progress)
    }

    private fun alreadyAuthenticated(mode: NetworkMode) = LoginResult(
        if (mode == NetworkMode.PUBLIC_WIFI) "公共 WiFi 已认证，无需重复登录" else "宿舍 WiFi 已认证，无需重复登录",
        mode, completed = true)

    private fun internetConfirmed() = LoginResult("宿舍 WiFi 已联网（IPv4 已验证），无需重复登录", NetworkMode.DORM_WIFI, completed = true)

    private data class AutomaticProbe(val mode: NetworkMode, val status: Boolean?, val internet: Boolean? = null,
        val completed: LoginResult? = null)
    private data class AutomaticRecognition(val publicStatus: Boolean? = null, val dormStatus: Boolean? = null,
        val dormInternet: Boolean? = null, val completed: LoginResult? = null)

    /** Online proof can finish early; selecting a destination for credentials still waits for both probes. */
    private suspend fun recognize(transport: PortalTransport, confirmed: ConfirmedState,
        progress: LoginProgress): AutomaticRecognition = coroutineScope {
        val public = async {
            val status = probe(transport, PortalEndpoint.PUBLIC_STATUS)
            AutomaticProbe(NetworkMode.PUBLIC_WIFI, status, completed = if (status == true) alreadyAuthenticated(NetworkMode.PUBLIC_WIFI) else null)
        }
        val dorm = async {
            val status = probe(transport, PortalEndpoint.DORM_STATUS)
            val internet = if (status == false) try { transport.hasIpv4Internet() } catch (_: PortalException) { false } else null
            val completed = when {
                status == true -> alreadyAuthenticated(NetworkMode.DORM_WIFI)
                internet == true -> internetConfirmed()
                else -> null
            }
            AutomaticProbe(NetworkMode.DORM_WIFI, status, internet, completed)
        }
        try {
            val first = select<AutomaticProbe> { public.onAwait { it }; dorm.onAwait { it } }
            first.completed?.let { return@coroutineScope AutomaticRecognition(completed = confirmed.success(it, progress)) }
            val second = if (first.mode == NetworkMode.PUBLIC_WIFI) dorm.await() else public.await()
            second.completed?.let { return@coroutineScope AutomaticRecognition(completed = confirmed.success(it, progress)) }
            val publicProbe = if (first.mode == NetworkMode.PUBLIC_WIFI) first else second
            val dormProbe = if (first.mode == NetworkMode.DORM_WIFI) first else second
            AutomaticRecognition(publicProbe.status, dormProbe.status, dormProbe.internet)
        } finally {
            // coroutineScope waits for cancelled HTTP/DNS cleanup before releasing this login task.
            public.cancel(); dorm.cancel()
        }
    }

    private suspend fun probe(transport: PortalTransport, endpoint: PortalEndpoint): Boolean? = try {
        PortalParser.status(transport.execute(statusRequest(endpoint)))
    } catch (_: PortalException) { null }

    private suspend fun requiredStatus(transport: PortalTransport, endpoint: PortalEndpoint): Boolean =
        PortalParser.result(transport.execute(statusRequest(endpoint)))

    private fun statusRequest(endpoint: PortalEndpoint) = PortalRequest(endpoint, listOf("callback" to "dr1002"))

    private fun publicLogin(account: AccountDraft) = PortalRequest(PortalEndpoint.PUBLIC_LOGIN, listOf(
        "callback" to "dr1002", "DDDDD" to account.username, "upass" to account.password,
        "0MKKey" to "123456", "R1" to "0", "R2" to "", "R3" to "0", "R6" to "0",
        "para" to "00", "v6ip" to "", "terminal_type" to "1", "lang" to "zh-cn",
        "jsVersion" to "4.1", "v" to "1234",
    ))

    private fun dormLogin(account: AccountDraft) = PortalRequest(PortalEndpoint.DORM_LOGIN, listOf(
        "c" to "Portal", "a" to "login", "user_account" to "${account.username}@campus",
        "user_password" to account.password, "login_method" to "1",
    ))

    private class ConfirmedState {
        var mode: NetworkMode? = null
        var dormRecognized = false
        private var confirmedResult: LoginResult? = null

        fun success(result: LoginResult, progress: LoginProgress): LoginResult {
            check(result.completed && !result.needsChoice)
            if (confirmedResult == null) {
                confirmedResult = result
                mode = result.mode
                progress.confirmed(result)
            }
            return confirmedResult!!
        }

        fun failure(problem: PortalProblem) = confirmedResult ?: LoginResult(problem.hint, mode = mode)
    }
}
