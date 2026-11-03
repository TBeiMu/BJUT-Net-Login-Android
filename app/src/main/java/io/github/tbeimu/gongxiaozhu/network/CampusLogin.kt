package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class LoginResult(val message: String, val mode: NetworkMode? = null, val needsChoice: Boolean = false)

fun interface CampusAuthenticator {
    suspend fun login(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: (String) -> Unit): LoginResult
}

/** Recognition and preflight never send credentials. One snapshot is reused by both dorm stages. */
class CampusLogin : CampusAuthenticator {
    override suspend fun login(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: (String) -> Unit): LoginResult = try {
        withTimeout(45_000) { authenticate(transport, mode, credentials, progress) }
    } catch (_: TimeoutCancellationException) {
        LoginResult(PortalProblem.CONNECTION.hint)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PortalException) {
        LoginResult(error.problem.hint)
    }

    private suspend fun authenticate(transport: PortalTransport, mode: NetworkMode, credentials: suspend () -> AccountDraft?,
        progress: (String) -> Unit): LoginResult {
        progress("正在检查校园认证状态…")
        var publicStatus: Boolean? = null
        var dormStatus: Boolean? = null
        val selected = if (mode == NetworkMode.AUTO) {
            publicStatus = probe(transport, PortalEndpoint.PUBLIC_STATUS)
            dormStatus = probe(transport, PortalEndpoint.DORM_STATUS)
            when {
                publicStatus != null && dormStatus == null -> NetworkMode.PUBLIC_WIFI
                dormStatus != null && publicStatus == null -> NetworkMode.DORM_WIFI
                publicStatus != null && dormStatus != null -> return LoginResult(
                    "公共与宿舍认证端点均返回有效状态，请选择当前 WiFi 类型后手动登录", needsChoice = true)
                else -> return LoginResult("未识别到校园认证服务；请确认 WiFi。若接口发生变化，可手动选择网络类型重试")
            }
        } else mode

        val gatewayStatus = when (selected) {
            NetworkMode.PUBLIC_WIFI -> publicStatus ?: requiredStatus(transport, PortalEndpoint.PUBLIC_STATUS)
            NetworkMode.DORM_WIFI -> dormStatus ?: requiredStatus(transport, PortalEndpoint.DORM_STATUS)
            NetworkMode.AUTO -> error("Explicit mode required")
        }
        if (selected == NetworkMode.PUBLIC_WIFI && gatewayStatus) return LoginResult("公共 WiFi 已认证，无需重复登录", selected)

        // Query lgn again after the first stage if it was previously unreachable.
        val ipv4Before = if (selected == NetworkMode.DORM_WIFI) ipv4Probe(transport) else null
        if (selected == NetworkMode.DORM_WIFI && gatewayStatus && ipv4Before == true) {
            return LoginResult("宿舍 WiFi 两阶段均已认证，无需重复登录", selected)
        }
        val account = credentials() ?: return LoginResult("请先添加并设置默认校园网账号", selected)
        if (!gatewayStatus) {
            progress(if (selected == NetworkMode.PUBLIC_WIFI) "正在登录公共 WiFi…" else "正在进行宿舍 WiFi 第一阶段认证…")
            val request = if (selected == NetworkMode.PUBLIC_WIFI) publicLogin(account) else dormLogin(account)
            if (!PortalParser.result(transport.execute(request))) throw PortalException(PortalProblem.REJECTED)
            val statusEndpoint = if (selected == NetworkMode.PUBLIC_WIFI) PortalEndpoint.PUBLIC_STATUS else PortalEndpoint.DORM_STATUS
            if (!requiredStatus(transport, statusEndpoint)) throw PortalException(PortalProblem.UNCONFIRMED)
        }
        if (selected == NetworkMode.DORM_WIFI) {
            progress("宿舍第一阶段已认证，正在检查 IPv4 二次认证…")
            val ipv4 = (if (gatewayStatus) ipv4Before else ipv4Probe(transport))
                ?: return LoginResult("宿舍第一阶段已认证，IPv4 认证页面无法识别或连接，请使用学校登录页面检查", selected)
            if (!ipv4) {
                if (!PortalParser.ipv4Accepted(transport.execute(ipv4Login(account)))) {
                    return LoginResult("宿舍第一阶段已认证，IPv4 二次认证未通过；请检查账号或使用学校登录页面", selected)
                }
                if (ipv4Probe(transport) != true) return LoginResult("宿舍第一阶段已认证，IPv4 二次认证已提交但状态未确认，请手动重试", selected)
            }
        }
        return LoginResult(if (selected == NetworkMode.PUBLIC_WIFI) "公共 WiFi 认证成功" else "宿舍 WiFi 两阶段 IPv4 认证成功", selected)
    }

    private suspend fun probe(transport: PortalTransport, endpoint: PortalEndpoint): Boolean? = try {
        PortalParser.status(transport.execute(statusRequest(endpoint)))
    } catch (_: PortalException) { null }

    private suspend fun requiredStatus(transport: PortalTransport, endpoint: PortalEndpoint): Boolean =
        PortalParser.result(transport.execute(statusRequest(endpoint)))

    private suspend fun ipv4Probe(transport: PortalTransport): Boolean? = try {
        PortalParser.ipv4Status(transport.execute(PortalRequest(PortalEndpoint.IPV4_STATUS)))
    } catch (_: PortalException) { null }

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

    private fun ipv4Login(account: AccountDraft) = PortalRequest(PortalEndpoint.IPV4_LOGIN, listOf(
        "DDDDD" to account.username, "upass" to account.password, "v46s" to "1",
        "v6ip" to "", "f4serip" to "172.30.201.2", "0MKKey" to "",
    ))
}
