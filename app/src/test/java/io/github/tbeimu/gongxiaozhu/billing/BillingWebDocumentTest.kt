package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BillingWebDocumentTest {
    private val base = "https://jfself.bjut.edu.cn/Self/login/?302=LI".toHttpUrl()
    @Test fun documentEmbedsOnlyAllowlistedAssetsAndNeverAutoSubmitsOrStoresCredentials() {
        val html = loginHtml().replace("<html>", "<html><head><script src='/Self/resources/demo.js'></script><script src='https://evil.example/x.js'></script></head>")
        val document = BillingWebDocument.create(html, base, mapOf("https://jfself.bjut.edu.cn/Self/resources/demo.js" to
            BillingAsset("text/javascript", "window.demo=true".toByteArray())), "demo-image".toByteArray(), "demo-token",
            AccountDraft("demo-id", "模拟", "demo-account", "<script>demo&password"))
        val doc = Jsoup.parse(document)
        assertTrue(doc.select("script[src]").isEmpty())
        assertTrue(doc.select("form").single().attr("action") == "#")
        assertTrue(doc.select("input[name=account]").single().hasAttr("readonly"))
        assertTrue(document.contains("connect-src 'none'"))
        assertFalse(document.contains("https://evil.example"))
        assertFalse(document.contains("<script>demo&password"))
        assertTrue(document.contains("Element.prototype.setAttribute"))
        assertFalse(document.contains("localStorage"))
        assertFalse(document.contains("document.cookie="))
        assertTrue(doc.select("script").first()!!.data().contains("GxzBilling"))
    }

    @Test fun accountCheckUsesOnlyUsernameAndRejectsOtherAccountOrMissingIdentity() {
        BillingParser.requireAccount(dashboardHtml, "demo-account")
        assertThrows(BillingException::class.java) { BillingParser.requireAccount(dashboardHtml, "other-account") }
        assertThrows(BillingException::class.java) { BillingParser.requireAccount("<dl>账户余额</dl>", "demo-account") }
    }

    @Test fun loginProblemDistinguishesExplicitErrorsFromReturningToLogin() {
        assertEquals(BillingProblem.REJECTED, BillingParser.loginProblem(loginHtml(tip = "账号或密码出现错误！")))
        assertEquals(BillingProblem.CAPTCHA_REJECTED, BillingParser.loginProblem(loginHtml(tip = "验证码错误")))
        assertEquals(BillingProblem.UNCONFIRMED, BillingParser.loginProblem(loginHtml()))
        assertEquals(BillingProblem.UNCONFIRMED, BillingParser.loginProblem(loginHtml(tip = "未知服务器错误")))
    }
}
