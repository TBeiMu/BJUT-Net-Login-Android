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

    @Test fun onlineTrafficUsesSchoolUploadAndDownloadCountersInMbWithThreeDecimals() {
        val rows = """[
            {"sessionId":"traffic-1","downFlow":"1024","upFlow":512},
            {"sessionId":"traffic-2","downFlow":0,"upFlow":0},
            {"sessionId":"traffic-3","downFlow":1,"upFlow":0},
            {"sessionId":"traffic-4","downFlow":"1024.9","upFlow":512.7}
        ]"""
        assertEquals(listOf(BillingMetric("1.500", "MB"), BillingMetric("0.000", "MB"), BillingMetric("0.001", "MB"),
            BillingMetric("1.500", "MB")),
            BillingParser.devices(rows).map { it.traffic })
    }

    @Test fun missingOrInvalidTrafficIsUnknownRatherThanZeroOrAnUnverifiedField() {
        listOf("", "\"downFlow\":10", "\"useFlow\":10", "\"downFlow\":-1,\"upFlow\":0",
            "\"downFlow\":null,\"upFlow\":0", "\"downFlow\":true,\"upFlow\":0",
            "\"downFlow\":\"not-a-number\",\"upFlow\":0", "\"downFlow\":\"1MB\",\"upFlow\":0").forEach { fields ->
            val row = "{\"sessionId\":\"fictional\"${if (fields.isEmpty()) "" else "," + fields}}"
            assertNull(BillingParser.devices("[$row]").single().traffic)
        }
    }

    @Test fun repeatedSessionTrafficIsNeverAddedAndConflictingCountersStayUnknown() {
        val row = """{"sessionId":"fictional","downFlow":1024,"upFlow":512}"""
        assertEquals(BillingMetric("1.500", "MB"), BillingParser.devices("[$row,$row]").single().traffic)
        assertEquals(BillingMetric("1.500", "MB"),
            BillingParser.devices("[{\"sessionId\":\"fictional\"},$row]").single().traffic)
        assertNull(BillingParser.devices("[$row,${row.replace("1024", "2048")}]").single().traffic)
    }

    @Test fun offlineRequiresExplicitBooleanSuccess() {
        assertTrue(BillingParser.offlineSuccess("{\"success\":true}"))
        listOf("{\"success\":false}", "{\"success\":\"false\"}", "{}", "{\"success\":1}").forEach {
            assertFalse(BillingParser.offlineSuccess(it))
        }
        assertThrows(BillingException::class.java) { BillingParser.offlineSuccess("<html>unknown</html>") }
    }
}
