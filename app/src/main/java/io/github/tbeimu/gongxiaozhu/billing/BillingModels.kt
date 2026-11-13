package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft

data class BillingMetric(val value: String, val unit: String)

data class OnlineDevice(
    val sessionId: String,
    val ip: String,
    val ipv6: String,
    val mac: String,
    val loginTime: String,
    val minutes: Long?,
    val traffic: BillingMetric? = null,
) {
    override fun toString() = "OnlineDevice(session=<redacted>)"
}

data class BillingSnapshot(
    val metrics: Map<String, BillingMetric>,
    val devices: List<OnlineDevice>,
    val devicesError: String? = null,
    val notice: String? = null,
)

enum class BillingProblem(val hint: String) {
    CONNECTION("连接失败，请重试"),
    RESPONSE("响应异常，请用学校网页"),
    REDIRECT("跳转异常，查询已停止"),
    REJECTED("登录未通过，请检查输入"),
    CAPTCHA_REJECTED("验证码未通过，请重填"),
    UNCONFIRMED("登录未确认，请重查"),
    CAPTCHA("请填写验证码"),
    EXPIRED("会话失效，请重查"),
    OFFLINE("注销未确认，请刷新核对"),
    ACCOUNT_MISMATCH("账号不符，请重新登录"),
}

class BillingException(val problem: BillingProblem) : Exception(problem.hint)

/** One account and one physical WiFi per session. Cookies and captcha stay only in memory. */
interface BillingSession {
    val captcha: ByteArray? get() = null
    val diagnostics: List<BillingDiagnostic> get() = emptyList()
    suspend fun refresh(credentials: AccountDraft, code: String? = null): BillingSnapshot
    suspend fun openWeb(credentials: AccountDraft): BillingWebPage = throw BillingException(BillingProblem.RESPONSE)
    suspend fun submitWeb(token: String, password: String, code: String): BillingWebResult = throw BillingException(BillingProblem.RESPONSE)
    suspend fun refreshWebCaptcha(token: String): ByteArray = throw BillingException(BillingProblem.RESPONSE)
    suspend fun offlineTip(): String
    suspend fun offline(device: OnlineDevice)
    fun close()
}

/** Only allowlisted categories and flags, never response text, cookies, account identifiers or URLs. */
data class BillingDiagnostic(val stage: String, val httpStatus: Int?, val page: String, val hasSessionCookie: Boolean)

class BillingWebPage(val token: String, val baseUrl: String, val html: String) {
    override fun toString() = "BillingWebPage(content=<redacted>)"
}

sealed interface BillingWebResult {
    class Success(val snapshot: BillingSnapshot) : BillingWebResult
    class Continue(val page: BillingWebPage, val problem: BillingProblem) : BillingWebResult
}
