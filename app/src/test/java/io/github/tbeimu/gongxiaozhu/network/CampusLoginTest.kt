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
        val transport = Script(offline, online, online, ipv4Offline, ipv4Accepted, ipv4Online)
        var reads = 0
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { reads++; account }, {})
        assertEquals("宿舍 WiFi 两阶段 IPv4 认证成功", result.message)
        assertEquals(1, reads)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.DORM_LOGIN,
            PortalEndpoint.DORM_STATUS, PortalEndpoint.IPV4_STATUS, PortalEndpoint.IPV4_LOGIN,
            PortalEndpoint.IPV4_STATUS), transport.endpoints)
        val dorm = transport.requests[1].parameters.toMap()
        assertEquals("${account.username}@campus", dorm["user_account"])
        assertEquals(account.password, dorm["user_password"])
        assertEquals(account.password, transport.requests[4].parameters.toMap()["upass"])
        assertEquals("1", transport.requests[4].parameters.toMap()["v46s"])
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
        val transport = Script(offline, offline)
        assertEquals(PortalProblem.REJECTED.hint, CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {}).message)
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun dormSecondStageFailureReportsPartialState() = runTest {
        val transport = Script(online, ipv4Offline, "<script>Gno=2</script>", ipv4Offline)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.message.contains("第一阶段已认证"))
        assertTrue(result.message.contains("二次认证未通过"))
        assertEquals(4, transport.requests.size)
        assertFalse(result.completed)
    }

    @Test fun alreadyConnectedDormWifiDoesNotReadCredentialsOrSubmitSecondLogin() = runTest {
        val transport = Script(online, connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("Must not read credentials") }, {})
        assertTrue(result.completed)
        assertTrue(result.message.contains("IPv4 已验证"))
        assertTrue(result.message.contains("未单独确认"))
        assertFalse(result.message.contains("两阶段"))
        assertEquals(listOf(PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun firstStageCanRestoreInternetWithoutSubmittingSecondStage() = runTest {
        val transport = Script(offline, online, online, connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.completed)
        assertTrue(result.message.contains("已联网"))
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun unexpectedSecondStageReplyIsReconciledWithRealIpv4Internet() = runTest {
        val transport = Script(online, ipv4Offline, "<html>new portal reply</html>", ipv4Offline,
            connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.completed)
        assertTrue(result.message.contains("IPv4 已验证"))
        assertFalse(result.message.contains("未通过"))
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.IPV4_LOGIN })
    }

    @Test fun lostSecondStageReplyCanStillLeaveWorkingIpv4Internet() = runTest {
        var connectivityChecks = 0
        var logins = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = ++connectivityChecks == 2
            override suspend fun execute(request: PortalRequest) = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> online
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.IPV4_LOGIN -> {
                    logins++
                    throw PortalException(PortalProblem.CONNECTION)
                }
                else -> error("Unexpected request")
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.completed)
        assertTrue(result.message.contains("已联网"))
        assertEquals(1, logins)
    }

    @Test fun changedLoginReplyCanBeConfirmedByExistingPortalStatus() = runTest {
        val transport = Script(online, ipv4Offline, "<html>new login reply</html>", ipv4Online)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.completed)
        assertEquals("宿舍 WiFi 两阶段 IPv4 认证成功", result.message)
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.IPV4_LOGIN })
    }

    @Test fun missingPortalAndFailedConnectivityDoNotClaimSuccess() = runTest {
        val transport = Script(online, "<html>unknown portal</html>", connectivity = listOf(false, false))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed)
        assertTrue(result.message.contains("无法识别"))
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun successfulFirstStageWithStaleOfflineStatusShowsWorkingInternetInsteadOfUnconfirmed() = runTest {
        // Screenshot regression: login accepted, chkstatus still returns 0, but WiFi has internet.
        val transport = Script(offline, online, offline, connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result)
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun retryWithStaleOfflineGatewayChecksInternetBeforeReadingPassword() = runTest {
        // Screenshot regression: a retry must not submit a duplicate login for already-working WiFi.
        val transport = Script(offline, connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("Must not read credentials") }, {})
        assertInternetOnly(result)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS), transport.endpoints)
        assertEquals(1, transport.connectivityChecks)
    }

    @Test fun duplicateFirstStageRejectionIsReconciledWithWorkingWifi() = runTest {
        // Internet becomes reachable after the preflight check, while the portal rejects a duplicate.
        val transport = Script(offline, offline, connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result)
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun lostFirstStageReplyIsReconciledWithoutResubmittingCredentials() = runTest {
        var connectivityChecks = 0
        val endpoints = mutableListOf<PortalEndpoint>()
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = ++connectivityChecks == 2
            override suspend fun execute(request: PortalRequest): String {
                endpoints += request.endpoint
                return when (request.endpoint) {
                    PortalEndpoint.DORM_STATUS -> offline
                    PortalEndpoint.IPV4_STATUS -> ipv4Offline
                    PortalEndpoint.DORM_LOGIN -> throw PortalException(PortalProblem.CONNECTION)
                    else -> error("Unexpected request")
                }
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result)
        assertEquals(1, endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun unknownFirstStageReplyCanBeConfirmedByInternetWithoutClaimingStageAuthentication() = runTest {
        val transport = Script(offline, "<html>changed login reply</html>", connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result)
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun workingInternetWithoutRecognizedCampusEndpointsDoesNotSelectDormOrReadPassword() = runTest {
        val transport = Script("<html>ordinary router</html>", "<html>unrecognized portal</html>", connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {})
        assertFalse(result.completed)
        assertNull(result.mode)
        assertTrue(result.message.contains("未识别"))
        assertEquals(0, transport.connectivityChecks)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun manuallySelectedDormWithoutValidStatusDoesNotUseInternetAsIdentification() = runTest {
        val transport = Script("<html>unrecognized portal</html>", connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("Must not read credentials") }, {})
        assertFalse(result.completed)
        assertEquals(0, transport.connectivityChecks)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun staleGatewayAndUnavailableInternetKeepUnconfirmedAndNeverSubmitSecondStage() = runTest {
        val transport = Script(offline, online, offline, connectivity = listOf(false, false))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed)
        assertEquals(PortalProblem.UNCONFIRMED.hint, result.message)
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
    }

    @Test fun cancellationDuringConnectivityCheckStopsBeforeReadingCredentials() = runTest {
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest) = online
            override suspend fun hasIpv4Internet(): Boolean = throw CancellationException("network switched")
        }
        try {
            CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("Must not read credentials") }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun noDefaultAccountDoesNotSendLogin() = runTest {
        val transport = Script(offline)
        assertTrue(CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { null }, {}).message.contains("默认"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun dormSecondStageTimeoutPreservesConfirmedGatewayState() = runTest {
        val requests = mutableListOf<PortalEndpoint>()
        val transport = PortalTransport { request ->
            requests += request.endpoint
            when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> online
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.IPV4_LOGIN -> awaitCancellation()
                else -> error("Unexpected request")
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.message.contains("第一阶段已认证"))
        assertTrue(result.message.contains("超时"))
        assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.IPV4_STATUS, PortalEndpoint.IPV4_LOGIN), requests)
    }

    @Test fun firstStageFlowTimeoutStillReconcilesWorkingWifiOutsideTheAuthenticationDeadline() = runTest {
        var connectivityChecks = 0
        var logins = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = ++connectivityChecks == 2
            override suspend fun execute(request: PortalRequest) = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> offline
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.DORM_LOGIN -> { logins++; awaitCancellation() }
                else -> error("Unexpected request")
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result)
        assertEquals(1, logins)
        assertEquals(2, connectivityChecks)
    }

    @Test fun fallbackConnectivityCheckHasDeadlineAndDoesNotHideCallerCancellation() = runTest {
        var connectivityChecks = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet(): Boolean {
                connectivityChecks++
                if (connectivityChecks == 1) return false
                awaitCancellation()
            }
            override suspend fun execute(request: PortalRequest) = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> offline
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.DORM_LOGIN -> offline
                else -> error("Unexpected request")
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed)
        assertEquals(PortalProblem.REJECTED.hint, result.message)
        assertEquals(2, connectivityChecks)

        var cancellationChecks = 0
        val cancelled = object : PortalTransport {
            override suspend fun hasIpv4Internet(): Boolean {
                if (++cancellationChecks == 1) return false
                throw CancellationException("network switched during reconciliation")
            }
            override suspend fun execute(request: PortalRequest) = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> offline
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.DORM_LOGIN -> offline
                else -> error("Unexpected request")
            }
        }
        try {
            CampusLogin().login(cancelled, NetworkMode.DORM_WIFI, { account }, {})
            fail("Caller cancellation must propagate from reconciliation")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun dormSecondStageConnectionErrorPreservesNewlyConfirmedGateway() = runTest {
        var gatewayChecks = 0
        val requests = mutableListOf<PortalEndpoint>()
        val transport = PortalTransport { request ->
            requests += request.endpoint
            when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> if (++gatewayChecks == 1) offline else online
                PortalEndpoint.DORM_LOGIN -> online
                PortalEndpoint.IPV4_STATUS -> ipv4Offline
                PortalEndpoint.IPV4_LOGIN -> throw PortalException(PortalProblem.CONNECTION)
                else -> error("Unexpected request")
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertTrue(result.message.contains("第一阶段已认证"))
        assertTrue(result.message.contains("无法连接"))
        assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertEquals(1, requests.count { it == PortalEndpoint.DORM_LOGIN })
        assertEquals(1, requests.count { it == PortalEndpoint.IPV4_LOGIN })
    }

    @Test fun unconfirmedDormGatewayMustNotBeReportedAsCompleted() = runTest {
        val transport = Script(offline, online, offline)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertEquals(PortalProblem.UNCONFIRMED.hint, result.message)
        assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertFalse(transport.endpoints.contains(PortalEndpoint.IPV4_LOGIN))
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

    private fun assertInternetOnly(result: LoginResult) {
        assertTrue(result.completed)
        assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertTrue(result.message.contains("已联网"))
        assertTrue(result.message.contains("IPv4 已验证"))
        assertTrue(result.message.contains("未单独确认"))
        assertFalse(result.message.contains("第一阶段已认证"))
        assertFalse(result.message.contains("两阶段"))
        assertFalse(result.message.contains("未通过"))
    }

    private class Script(vararg responses: String, connectivity: List<Boolean> = emptyList()) : PortalTransport {
        private val responses = responses.toList().iterator()
        private val connectivity = connectivity.iterator()
        var connectivityChecks = 0
        override suspend fun hasIpv4Internet(): Boolean {
            connectivityChecks++
            return connectivity.hasNext() && connectivity.next()
        }
        val requests = mutableListOf<PortalRequest>()
        val endpoints get() = requests.map { it.endpoint }
        override suspend fun execute(request: PortalRequest): String {
            requests += request
            check(responses.hasNext()) { "Unexpected request: ${request.endpoint}" }
            return responses.next()
        }
    }
}
