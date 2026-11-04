package io.github.tbeimu.gongxiaozhu.billing

import org.junit.Assert.*
import org.junit.Test

class BillingParserTest {
    @Test fun metricsKeepUnitsPrecisionAndTextFromTheServer() {
        val metrics = BillingParser.metrics(dashboardHtml)
        assertEquals(4, metrics.size)
        assertEquals(BillingMetric("1234", "MB"), metrics["已用流量"])
        assertEquals(BillingMetric("12.3456", "元"), metrics["账户余额"])
        assertEquals(BillingMetric("未设置", ""), metrics["消费保护"])
    }

    @Test fun missingMetricIsAbsentInsteadOfInventedZero() {
        val html = "<dl><dt>不限量</dt><dd>可用流量</dd></dl><table id=OnlineLisBig></table>"
        val result = BillingParser.metrics(html)
        assertEquals(1, result.size)
        assertNull(result["账户余额"])
        assertEquals("不限量", result["可用流量"]?.value)
    }

    @Test fun loginAndUnknownPagesAreNotBillingSuccess() {
        assertTrue(BillingParser.isLogin(loginHtml()))
        assertFalse(BillingParser.isLogin(dashboardHtml))
        assertThrows(BillingException::class.java) { BillingParser.metrics(loginHtml()) }
        assertThrows(BillingException::class.java) { BillingParser.metrics("<html>Maintenance</html>") }
    }

    @Test fun loginFormUsesDynamicCheckcodeAndCaptchaVisibility() {
        val form = BillingParser.loginForm(loginHtml())
        assertEquals("demo-check", form.checkcode)
        assertEquals("/Self/login/verify;jsessionid=DEMO", form.action)
        assertFalse(form.captchaRequired)
        assertTrue(BillingParser.loginForm(loginHtml(true)).captchaRequired)
        assertFalse(BillingParser.loginForm(loginHtml(true).replace("class=\"form-group \"", "style=\"display: none\"")).captchaRequired)
    }

    @Test fun changedPasswordSchemeIsRefusedAndFormsDoNotRevealSecrets() {
        assertThrows(BillingException::class.java) { BillingParser.loginForm(loginHtml(scheme = "true")) }
        assertFalse(BillingParser.loginForm(loginHtml()).toString().contains("demo-check"))
    }

    @Test fun devicesMergeSameSessionAndKeepDistinctSessionsAndIpv6Addresses() {
        val devices = BillingParser.devices(devicesJson)
        assertEquals(2, devices.size)
        assertEquals(2L, devices.first().minutes)
        assertEquals("2001:db8::1 / 2001:db8::2", devices.first().ipv6)
        assertEquals("demo-session-2", devices.last().sessionId)
        assertEquals(devices, BillingParser.devices("{\"rows\":$devicesJson,\"total\":3}"))
        assertEquals(emptyList<OnlineDevice>(), BillingParser.devices("[]"))
        assertFalse(devices.first().toString().contains("demo-session"))
    }

    @Test fun malformedDeviceListsAreNotReportedAsEmptySuccess() {
        listOf("{}", "<html>login</html>", "[{\"ip\":\"192.0.2.1\"}]", "[null]").forEach {
            assertThrows(BillingException::class.java) { BillingParser.devices(it) }
        }
    }

    @Test fun offlineRequiresExplicitBooleanSuccess() {
        assertTrue(BillingParser.offlineSuccess("{\"success\":true}"))
        listOf("{\"success\":false}", "{\"success\":\"false\"}", "{}", "{\"success\":1}").forEach {
            assertFalse(BillingParser.offlineSuccess(it))
        }
        assertThrows(BillingException::class.java) { BillingParser.offlineSuccess("<html>unknown</html>") }
    }
}
