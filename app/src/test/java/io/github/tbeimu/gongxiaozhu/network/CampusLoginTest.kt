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

    @Test fun parserAcceptsNumericAndStringResultsButRejectsUnexpectedContent() {
        assertFalse(PortalParser.result(offline)); assertTrue(PortalParser.result(online))
        listOf("<html>ok</html>", "{\"result\":2}", "{\"result\":true}", "{\"other\":0}",
            "dr({\"result\":1});alert(1)", "dr({\"result\":1});dr({\"result\":1})").forEach {
            assertNull(PortalParser.status(it))
        }
    }

    @Test fun publicLoginConfirmsStateAndPreservesExactCredentials() = runTest {
        val transport = Script(offline, online, online)
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {})
        assertEquals("公共 WiFi 认证成功", result.message)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.PUBLIC_LOGIN, PortalEndpoint.PUBLIC_STATUS), transport.endpoints)
        val parameters = transport.requests[1].parameters.toMap()
        assertEquals(account.username, parameters["DDDDD"]); assertEquals(account.password, parameters["upass"])
    }

    @Test fun alreadyAuthenticatedPublicWifiDoesNotReadOrSendCredentials() = runTest {
        val transport = Script(online)
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { error("Must not read credentials") }, {})
        assertTrue(result.completed); assertTrue(result.message.contains("已认证"))
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS), transport.endpoints)
    }

    @Test fun rejectedPasswordStopsWithoutRetryOrLogout() = runTest {
        val transport = Script(offline, "{\"result\":0,\"msg\":\"secret server echo\"}")
        val result = CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {})
        assertEquals(PortalProblem.REJECTED.hint, result.message); assertEquals(2, transport.requests.size)
        assertFalse(result.message.contains("secret server echo"))
    }

    @Test fun loginResponseAloneDoesNotClaimSuccess() = runTest {
        val transport = Script(offline, online, offline)
        assertEquals(PortalProblem.UNCONFIRMED.hint, CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { account }, {}).message)
    }

    @Test fun automaticRecognitionRequiresValidContentNotReachability() = runTest {
        val transport = Script("<html>200 OK</html>", "{\"result\":42}")
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("No credentials") }, {})
        assertNull(result.mode); assertTrue(result.message.contains("未识别")); assertEquals(2, transport.requests.size)
    }

    @Test fun bothOfflineEndpointsRequireManualChoiceWithoutSendingCredentials() = runTest {
        val transport = Script(offline, offline)
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("No credentials") }, {})
        assertTrue(result.needsChoice); assertFalse(result.completed)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun uniquePublicResponseSelectsPublicWifi() = runTest {
        val transport = Script(offline, "<html>ordinary router</html>", online, online)
        assertEquals(NetworkMode.PUBLIC_WIFI, CampusLogin().login(transport, NetworkMode.AUTO, { account }, {}).mode)
    }

    @Test fun uniqueDormResponseUsesItsCachedPreflightAndSingleLogin() = runTest {
        val transport = Script("<html>ordinary router</html>", offline, online, online)
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { account }, {})
        assertTrue(result.completed); assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertEquals(1, transport.connectivityChecks)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS, PortalEndpoint.DORM_LOGIN, PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun dormSingleLoginConfirmsStatusAndUsesOneCredentialSnapshot() = runTest {
        val transport = Script(offline, online, online)
        val progress = mutableListOf<String>(); var reads = 0
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { reads++; account }, progress::add)
        assertEquals("宿舍 WiFi 认证成功", result.message); assertTrue(result.completed); assertEquals(1, reads)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.DORM_LOGIN, PortalEndpoint.DORM_STATUS), transport.endpoints)
        val parameters = transport.requests[1].parameters.toMap()
        assertEquals("${account.username}@campus", parameters["user_account"])
        assertEquals(account.password, parameters["user_password"])
        assertEquals("Portal", parameters["c"]); assertEquals("login", parameters["a"])
        assertEquals("1", parameters["login_method"])
        assertTrue(progress.none { it.contains("二次") || it.contains("阶段") })
    }

    @Test fun authenticatedDormStatusDoesNotWaitForInternetOrReadCredentials() = runTest {
        val transport = object : PortalTransport {
            var requests = 0
            override suspend fun execute(request: PortalRequest): String {
                assertEquals(PortalEndpoint.DORM_STATUS, request.endpoint); requests++; return online
            }
            override suspend fun hasIpv4Internet(): Boolean = error("Status already confirms authentication")
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("No credentials") }, {})
        assertTrue(result.completed); assertEquals("宿舍 WiFi 已认证，无需重复登录", result.message)
        assertEquals(1, transport.requests)
    }

    @Test fun failedDormLoginDoesNotRetryAndKeepsSafeError() = runTest {
        val transport = Script(offline, offline)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed); assertEquals(PortalProblem.REJECTED.hint, result.message)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.DORM_LOGIN), transport.endpoints)
    }

    @Test fun dormSubmittedButUnconfirmedStateDoesNotClaimSuccess() = runTest {
        val transport = Script(offline, online, offline)
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed); assertEquals(PortalProblem.UNCONFIRMED.hint, result.message)
        assertEquals(listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.DORM_LOGIN, PortalEndpoint.DORM_STATUS), transport.endpoints)
    }

    @Test fun staleOfflineStatusWithInternetSkipsCredentialsAndDuplicateLogin() = runTest {
        val transport = Script(offline, connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("No credentials") }, {})
        assertInternetOnly(result); assertEquals(listOf(PortalEndpoint.DORM_STATUS), transport.endpoints)
        assertEquals(1, transport.connectivityChecks)
    }

    @Test fun acceptedLoginWithStaleStatusIsReconciledWithInternet() = runTest {
        val transport = Script(offline, online, offline, connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result); assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
    }

    @Test fun duplicateLoginRejectionIsReconciledWithoutResubmission() = runTest {
        val transport = Script(offline, offline, connectivity = listOf(false, true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertInternetOnly(result); assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
    }

    @Test fun lostDormLoginReplyIsReconciledWithoutResubmittingCredentials() = runTest {
        var connectivityChecks = 0; var logins = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = ++connectivityChecks == 2
            override suspend fun execute(request: PortalRequest) = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> offline
                PortalEndpoint.DORM_LOGIN -> { logins++; throw PortalException(PortalProblem.CONNECTION) }
                else -> error("Unexpected request")
            }
        }
        assertInternetOnly(CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {}))
        assertEquals(1, logins); assertEquals(2, connectivityChecks)
    }

    @Test fun unknownLoginResponseCanBeReconciledWithVerifiedInternet() = runTest {
        val transport = Script(offline, "<html>changed login reply</html>", connectivity = listOf(false, true))
        assertInternetOnly(CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {}))
        assertEquals(1, transport.endpoints.count { it == PortalEndpoint.DORM_LOGIN })
    }

    @Test fun unrecognizedEndpointsDoNotUseInternetAsCampusIdentification() = runTest {
        val transport = Script("<html>ordinary router</html>", "<html>unknown</html>", connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("No credentials") }, {})
        assertFalse(result.completed); assertNull(result.mode); assertEquals(0, transport.connectivityChecks)
    }

    @Test fun manuallySelectedDormStillRequiresAValidCampusStatus() = runTest {
        val transport = Script("<html>unknown</html>", connectivity = listOf(true))
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("No credentials") }, {})
        assertFalse(result.completed); assertEquals(0, transport.connectivityChecks)
    }

    @Test fun cancellationDuringInternetPreflightStopsBeforeReadingCredentials() = runTest {
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest) = offline
            override suspend fun hasIpv4Internet(): Boolean = throw CancellationException("network switched")
        }
        try {
            CampusLogin().login(transport, NetworkMode.DORM_WIFI, { error("No credentials") }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun noDefaultAccountDoesNotSendLogin() = runTest {
        val transport = Script(offline)
        assertTrue(CampusLogin().login(transport, NetworkMode.PUBLIC_WIFI, { null }, {}).message.contains("默认"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun dormFlowTimeoutStillReconcilesInternetOutsideAuthenticationDeadline() = runTest {
        var connectivityChecks = 0; var logins = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = ++connectivityChecks == 2
            override suspend fun execute(request: PortalRequest): String = when (request.endpoint) {
                PortalEndpoint.DORM_STATUS -> offline
                PortalEndpoint.DORM_LOGIN -> { logins++; awaitCancellation() }
                else -> error("Unexpected request")
            }
        }
        assertInternetOnly(CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {}))
        assertEquals(1, logins); assertEquals(2, connectivityChecks)
    }

    @Test fun fallbackInternetCheckHasDeadlineAndPreservesCallerCancellation() = runTest {
        var checks = 0
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet(): Boolean { if (++checks == 1) return false; awaitCancellation() }
            override suspend fun execute(request: PortalRequest) = offline
        }
        val result = CampusLogin().login(transport, NetworkMode.DORM_WIFI, { account }, {})
        assertFalse(result.completed); assertEquals(PortalProblem.REJECTED.hint, result.message); assertEquals(2, checks)
        var cancelledChecks = 0
        val cancelled = object : PortalTransport {
            override suspend fun hasIpv4Internet(): Boolean {
                if (++cancelledChecks == 1) return false
                throw CancellationException("network switched during reconciliation")
            }
            override suspend fun execute(request: PortalRequest) = offline
        }
        try {
            CampusLogin().login(cancelled, NetworkMode.DORM_WIFI, { account }, {})
            fail("Caller cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun flowTimeoutAndConnectionFailureUseSafeMessages() = runTest {
        assertEquals(PortalProblem.CONNECTION.hint, CampusLogin().login(PortalTransport { awaitCancellation() }, NetworkMode.PUBLIC_WIFI, { account }, {}).message)
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
        assertTrue(result.completed); assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertTrue(result.message.contains("IPv4 已验证"))
        assertFalse(result.message.contains("二次")); assertFalse(result.message.contains("阶段"))
    }

    private class Script(vararg responses: String, connectivity: List<Boolean> = emptyList()) : PortalTransport {
        private val responses = responses.toList().iterator()
        private val connectivity = connectivity.iterator()
        var connectivityChecks = 0
        override suspend fun hasIpv4Internet(): Boolean { connectivityChecks++; return connectivity.hasNext() && connectivity.next() }
        val requests = mutableListOf<PortalRequest>()
        val endpoints get() = requests.map { it.endpoint }
        override suspend fun execute(request: PortalRequest): String {
            requests += request; check(responses.hasNext()) { "Unexpected request: ${request.endpoint}" }
            return responses.next()
        }
    }
}
