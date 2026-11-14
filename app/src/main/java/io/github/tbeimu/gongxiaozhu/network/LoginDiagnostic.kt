package io.github.tbeimu.gongxiaozhu.network

import kotlinx.coroutines.CancellationException

enum class LoginStep(val title: String) {
    PUBLIC_STATUS("公共认证状态"), PUBLIC_LOGIN("公共认证提交"),
    DORM_STATUS("宿舍认证状态"), DORM_LOGIN("宿舍认证提交"), INTERNET("IPv4 联网检查"),
}
enum class LoginStepResult(val title: String) { RESPONSE("已返回"), ONLINE("已联网"), UNCONFIRMED("未确认"), FAILED("请求失败"), CANCELLED("已取消") }
data class LoginDiagnostic(val step: LoginStep, val elapsedMs: Long, val result: LoginStepResult)

/** Fixed labels only: no request, credentials, server content or addresses enter diagnostics. */
internal class DiagnosticPortalTransport(private val delegate: PortalTransport, private val report: (LoginDiagnostic) -> Unit) : PortalTransport {
    override suspend fun execute(request: PortalRequest): String = measure(LoginStep.valueOf(request.endpoint.name)) { delegate.execute(request) }
    override suspend fun hasIpv4Internet(): Boolean = measure(LoginStep.INTERNET) { delegate.hasIpv4Internet() }
    private suspend fun <T> measure(step: LoginStep, block: suspend () -> T): T {
        val started = System.nanoTime()
        var result = LoginStepResult.FAILED
        try {
            return block().also { result = when (it) {
                true -> LoginStepResult.ONLINE
                false -> LoginStepResult.UNCONFIRMED
                else -> LoginStepResult.RESPONSE
            } }
        } catch (error: CancellationException) {
            result = LoginStepResult.CANCELLED
            throw error
        } finally { report(LoginDiagnostic(step, (System.nanoTime() - started) / 1_000_000, result)) }
    }
}
