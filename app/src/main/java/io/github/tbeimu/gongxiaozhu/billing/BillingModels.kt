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
    CAPTCHA("学校要求验证码，请填写图片中的验证码后登录"),
    EXPIRED("查询会话已失效，请重新登录查询；未提交设备注销"),
    OFFLINE("设备注销未确认，请刷新在线信息核对后再操作"),
}

class BillingException(val problem: BillingProblem) : Exception(problem.hint)

/** One account and one physical WiFi per session. Cookies and captcha stay only in memory. */
interface BillingSession {
    val captcha: ByteArray? get() = null
    suspend fun refresh(credentials: AccountDraft, code: String? = null): BillingSnapshot
    suspend fun offlineTip(): String
    suspend fun offline(device: OnlineDevice)
    fun close()
}
