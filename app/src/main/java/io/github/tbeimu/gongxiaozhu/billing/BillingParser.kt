package io.github.tbeimu.gongxiaozhu.billing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

internal class SelfLoginForm(val action: String, val checkcode: String, val captchaRequired: Boolean) {
    override fun toString() = "SelfLoginForm(fields=<redacted>)"
}

internal object BillingParser {
    val labels = listOf("已用流量", "可用流量", "消费保护", "账户余额")

    fun isLogin(html: String) = Jsoup.parse(html).selectFirst("form input[name=account]") != null

    fun loginForm(html: String): SelfLoginForm {
        val doc = Jsoup.parse(html)
        val form = doc.select("form").firstOrNull {
            it.selectFirst("input[name=account]") != null && it.selectFirst("input[name=password]") != null
        } ?: throw BillingException(BillingProblem.RESPONSE)
        if (!form.attr("method").equals("post", true)) throw BillingException(BillingProblem.RESPONSE)
        // The observed site explicitly disables SM3. Refuse a changed password scheme.
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        if (!Regex("if\\s*\\(\\s*false\\s*&&\\s*passwordVal").containsMatchIn(scripts)) {
            throw BillingException(BillingProblem.RESPONSE)
        }
        val token = form.selectFirst("input[name=checkcode]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: throw BillingException(BillingProblem.RESPONSE)
        val captcha = form.selectFirst("#randomDiv")
        val required = captcha != null && !captcha.hasClass("hide") && !captcha.hasClass("hidden") &&
            !captcha.hasAttr("hidden") && !captcha.attr("style").replace(" ", "").contains("display:none", true)
        return SelfLoginForm(form.attr("action"), token, required)
    }

    fun metrics(html: String): Map<String, BillingMetric> {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("#OnlineLisBig") == null) throw BillingException(BillingProblem.RESPONSE)
        val result = linkedMapOf<String, BillingMetric>()
        doc.select("dl").forEach { dl ->
            val label = dl.selectFirst("dd")?.text()?.trim()
            if (label in labels) {
                val dt = dl.selectFirst("dt")?.clone() ?: return@forEach
                val unit = dt.selectFirst("small.unit")?.text()?.trim().orEmpty()
                dt.select("small.unit").remove()
                val value = dt.text().trim()
                if (value.isNotBlank() && value.length <= 80 && unit.length <= 16) {
                    result[label!!] = BillingMetric(value, unit)
                }
            }
        }
        if (result.isEmpty()) throw BillingException(BillingProblem.RESPONSE)
        return result
    }

    fun devices(text: String): List<OnlineDevice> {
        val root = parseJson(text)
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> root["rows"] as? JsonArray ?: throw BillingException(BillingProblem.RESPONSE)
            else -> throw BillingException(BillingProblem.RESPONSE)
        }
        if (rows.size > 200) throw BillingException(BillingProblem.RESPONSE)
        fun JsonObject.field(name: String): String = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty().let {
            if (it.length > 256 || it.any(Char::isISOControl)) throw BillingException(BillingProblem.RESPONSE)
            it
        }
        val devices = rows.map { element ->
            val row = element as? JsonObject ?: throw BillingException(BillingProblem.RESPONSE)
            val session = row.field("sessionId").takeIf(String::isNotBlank) ?: throw BillingException(BillingProblem.RESPONSE)
            OnlineDevice(session, row.field("ip"), row.field("ipv6"), row.field("mac"), row.field("loginTime"),
                row.field("useTime").toLongOrNull()?.takeIf { it >= 0 }?.div(60))
        }
        // The site repeats IPv4/IPv6 rows with the same session. Send at most one offline request.
        return devices.groupBy { it.sessionId }.map { (_, sameSession) ->
            sameSession.first().copy(ipv6 = sameSession.map { it.ipv6 }.filter(String::isNotBlank).distinct().joinToString(" / "))
        }
    }

    fun offlineSuccess(text: String): Boolean {
        val root = parseJson(text) as? JsonObject ?: throw BillingException(BillingProblem.RESPONSE)
        val success = root["success"] as? JsonPrimitive ?: return false
        return !success.isString && success.booleanOrNull == true
    }

    fun tip(text: String): String {
        val plain = Jsoup.parse(text).text().trim()
        return plain.takeIf { it.isNotBlank() && it.length <= 250 } ?: "无感知用户可能需要解绑 MAC 才能保持离线。"
    }

    private fun parseJson(text: String) = try { Json.parseToJsonElement(text) }
        catch (_: Exception) { throw BillingException(BillingProblem.RESPONSE) }
}
