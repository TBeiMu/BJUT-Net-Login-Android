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
    CONNECTION("自助服务无法连接或请求超时，请先完成校园网登录，再手动重试"),
    RESPONSE("自助服务响应无法识别，学校接口可能已变化，请使用学校网页核对"),
    REDIRECT("自助服务返回了不允许的跳转，已停止请求"),
    REJECTED("资费查询登录未通过，请检查所选账号、密码或验证码后手动重试"),
    CAPTCHA_REJECTED("学校未接受验证码，请重新填写后登录"),
    UNCONFIRMED("查询登录状态未确认，返回了登录页；不等同于账号密码错误"),
    CAPTCHA("学校要求验证码，请填写图片中的验证码后登录"),
    EXPIRED("查询会话已失效，请重新登录查询；设备注销结果需核对在线信息"),
    OFFLINE("设备注销未确认，请刷新在线信息核对后再操作"),
    ACCOUNT_MISMATCH("查询页面账号与所选账号不一致，已清除会话，请重新登录"),
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
