package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CampusLoginTest {
    private val account = AccountDraft(label = "模拟账号", username = "demo &+=中文", password = "  demo&+%?=中文  ")
    private val offline = "dr1002({\"result\":0});"
    private val online = "{\"result\":\"1\",\"ss5\":\"192.0.2.1\"}"
    private val ipv4Offline = "<!--Dr.COMWebLoginID_0.htm--><input name=\"DDDDD\">"
    private val ipv4Online = "<!--Dr.COMWebLoginID_1.htm-->"
    private val ipv4Accepted = "<!--Dr.COMWebLoginID_3.htm-->"

    @Test fun parserAcceptsNumericAndStringResultsButRejectsUnexpectedContent() {
        assertFalse(PortalParser.result(offline))
        assertTrue(PortalParser.result(online))
        listOf("<html>ok</html>", "{\"result\":2}", "{\"result\":true}", "{\"other\":0}",
            "dr({\"result\":1});alert(1)", "dr({\"result\":1});dr({\"result\":1})").forEach {
            assertNull(PortalParser.status(it))
        }
        assertEquals(false, PortalParser.ipv4Status(ipv4Offline))
        assertEquals(true, PortalParser.ipv4Status(ipv4Online))
        assertNull(PortalParser.ipv4Status("<html>normal WiFi</html>"))
        assertNull(PortalParser.ipv4Status(ipv4Accepted))
        assertNull(PortalParser.ipv4Status("<!--Dr.COMWebLoginID_0.htm-->"))
    }

    @Test fun publicLoginConfirmsStateAndPreservesExactCredentials() = runTest {
        val transport = Script(offline, online, online)
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {})
        assertEquals("公共 WiFi 认证成功", result.message)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.PUBLIC_LOGIN, PortalEndpoint.PUBLIC_STATUS), transport.endpoints)
        val parameters = transport.requests[1].parameters.toMap()
        assertEquals(account.username, parameters["DDDDD"])
        assertEquals(account.password, parameters["upass"])
    }

    @Test fun alreadyAuthenticatedPublicWifiDoesNotReadOrSendCredentials() = runTest {
        val transport = Script(online)
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { error("Must not read credentials") }, {})
        assertTrue(result.message.contains("已认证"))
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS), transport.endpoints)
    }

    @Test fun rejectedPasswordStopsWithoutRetryOrLogout() = runTest {
        val transport = Script(offline, "{\"result\":0,\"msg\":\"secret server echo\"}")
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {})
        assertEquals(PortalProblem.REJECTED.hint, result.message)
        assertEquals(2, transport.requests.size)
        assertFalse(result.message.contains("secret server echo"))
    }

    @Test fun loginResponseAloneDoesNotClaimSuccess() = runTest {
        val transport = Script(offline, online, offline)
        assertEquals(PortalProblem.UNCONFIRMED.hint,
            CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {}).message)
    }

    @Test fun automaticRecognitionRequiresValidContentNotReachability() = runTest {
        val transport = Script("<html>200 OK</html>", "{\"result\":42}")
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("No credentials") }, {})
        assertNull(result.mode)
        assertTrue(result.message.contains("未识别"))
        assertEquals(2, transport.requests.size)
    }

    @Test fun bothValidEndpointsRequireManualChoiceWithoutSendingCredentials() = runTest {
        val transport = Script(online, offline)
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("No credentials") }, {})
        assertTrue(result.needsChoice)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun uniquePublicResponseSelectsPublicWifi() = runTest {
        val transport = Script(offline, "<html>ordinary router</html>", online, online)
        assertEquals(NetworkMode.PUBLIC_WIFI, CampusLogin().login(transport, NetworkMode.AUTO, { account }, {}).mode)
    }

    @Test fun dormLoginUsesBothStagesAndOneCredentialSnapshot() = runTest {
        val transport = Script(offline, "unreachable lgn page", online, online, ipv4Offline, ipv4Accepted, ipv4Online)
        var reads = 0
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { reads++; account }, {})
        assertEquals("宿舍 WiFi 两阶段 IPv4 认证成功", result.message)
        assertEquals(1, reads)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.IPV4_STATUS, PortalEndpoint.DORM_LOGIN,
            PortalEndpoint.DORM_STATUS, PortalEndpoint.IPV4_STATUS, PortalEndpoint.IPV4_LOGIN,
            PortalEndpoint.IPV4_STATUS), transport.endpoints)
        val dorm = transport.requests[2].parameters.toMap()
        assertEquals("${account.username}@campus", dorm["user_account"])
        assertEquals(account.password, dorm["user_password"])
        assertEquals(account.password, transport.requests[5].parameters.toMap()["upass"])
        assertEquals("1", transport.requests[5].parameters.toMap()["v46s"])
    }

    @Test fun dormGatewayAlreadyOnlineStillNeedsIpv4Authentication() = runTest {
        val transport = Script(online, ipv4Offline, ipv4Accepted, ipv4Online)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.message.contains("认证成功"))
        assertFalse(transport.endpoints.contains(PortalEndpoint.DORM_LOGIN))
        assertTrue(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun bothDormStagesOnlineNeverReadCredentials() = runTest {
        val transport = Script(online, ipv4Online)
        assertTrue(CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("No credentials") }, {}).message.contains("均已认证"))
        assertEquals(2, transport.requests.size)
    }

    @Test fun dormFirstStageFailureNeverSubmitsSecondStageCredentials() = runTest {
        val transport = Script(offline, ipv4Offline, offline)
        assertEquals(PortalProblem.REJECTED.hint, CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {}).message)
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun dormSecondStageFailureReportsPartialState() = runTest {
        val transport = Script(online, ipv4Offline, "<script>Gno=2</script>")
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.message.contains("第一阶段已认证"))
        assertTrue(result.message.contains("二次认证未通过"))
        assertEquals(3, transport.requests.size)
    }

    @Test fun noDefaultAccountDoesNotSendLogin() = runTest {
        val transport = Script(offline)
        assertTrue(CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { null }, {}).message.contains("默认"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun flowTimeoutAndConnectionFailureUseSafeMessages() = runTest {
        val blocked = PortalTransport { awaitCancellation() }
        assertEquals(PortalProblem.CONNECTION.hint, CampusLogin().login(blocked, NetworkMode.PUBLIC_WIFI, { account }, {}).message)
        val failed = PortalTransport { throw PortalException(PortalProblem.CONNECTION) }
        assertEquals(PortalProblem.CONNECTION.hint, CampusLogin().login(failed, NetworkMode.PUBLIC_WIFI, { account }, {}).message)
    }

    @Test fun callerCancellationIsNotConvertedToAuthenticationFailure() = runTest {
        try {
            CampusLogin().login(PortalTransport { throw CancellationException("cancelled") }, NetworkMode.AUTO, { account }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    private class Script(vararg responses: String) : PortalTransport {
        private val responses = responses.toList().iterator()
        val requests = mutableListOf<PortalRequest>()
        val endpoints get() = requests.map { it.endpoint }
        override suspend fun execute(request: PortalRequest): String {
            requests += request
            check(responses.hasNext()) { "Unexpected request: ${request.endpoint}" }
            return responses.next()
        }
    }
}
