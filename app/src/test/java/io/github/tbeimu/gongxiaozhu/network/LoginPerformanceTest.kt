package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginPerformanceTest {
    @Test fun automaticProbesOverlapButWaitForAmbiguousSlowReply() = runTest {
        val starts = mutableListOf<Long>()
        val transport = PortalTransport { request ->
            starts += testScheduler.currentTime
            delay(if (request.endpoint == PortalEndpoint.PUBLIC_STATUS) 100 else 2000)
            "{\"result\":0}"
        }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("ambiguous network must not read credentials") }, {})
        assertEquals(listOf(0L, 0L), starts)
        assertEquals(2000L, testScheduler.currentTime)
        assertTrue(result.needsChoice)
    }

    @Test fun confirmedDormInternetDoesNotWaitForFiveSecondPublicFailure() = runTest {
        var publicCancelled = false
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest): String = when (request.endpoint) {
                PortalEndpoint.PUBLIC_STATUS -> try {
                    delay(5000); throw PortalException(PortalProblem.CONNECTION)
                } finally { publicCancelled = true }
                PortalEndpoint.DORM_STATUS -> { delay(200); "{\"result\":0}" }
                else -> error("No credentials or second-stage request expected")
            }
            override suspend fun hasIpv4Internet(): Boolean { delay(50); return true }
        }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {})
        assertTrue(result.completed); assertEquals(NetworkMode.DORM_WIFI, result.mode)
        assertTrue(result.message.contains("IPv4 已验证")); assertFalse(result.message.contains("两阶段"))
        assertEquals(250L, testScheduler.currentTime); assertTrue(publicCancelled)
    }

    @Test fun publicOnlineStatusFinishesWithoutWaitingForDormProbe() = runTest {
        var dormCancelled = false
        val transport = PortalTransport { request -> when (request.endpoint) {
            PortalEndpoint.PUBLIC_STATUS -> { delay(200); "{\"result\":1}" }
            PortalEndpoint.DORM_STATUS -> try { delay(5000); "{\"result\":0}" } finally { dormCancelled = true }
            else -> error("Unexpected credential request")
        } }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {})
        assertTrue(result.completed); assertEquals(NetworkMode.PUBLIC_WIFI, result.mode)
        assertEquals(200L, testScheduler.currentTime); assertTrue(dormCancelled)
    }

    @Test fun dormOnlineStatusDoesNotStartInternetCheckOrWaitForPublic() = runTest {
        var publicCancelled = false
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest): String = when (request.endpoint) {
                PortalEndpoint.PUBLIC_STATUS -> try { awaitCancellation() } finally { publicCancelled = true }
                PortalEndpoint.DORM_STATUS -> { delay(200); "{\"result\":1}" }
                else -> error("Unexpected credential request")
            }
            override suspend fun hasIpv4Internet(): Boolean = error("Authenticated status is sufficient")
        }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {})
        assertTrue(result.completed); assertEquals("宿舍 WiFi 已认证，无需重复登录", result.message)
        assertEquals(200L, testScheduler.currentTime); assertTrue(publicCancelled)
    }

    @Test fun offlineDormStillWaitsForOtherEndpointBeforeSelectingCredentials() = runTest {
        val endpoints = mutableListOf<PortalEndpoint>(); val credentialTimes = mutableListOf<Long>()
        val transport = object : PortalTransport {
            override suspend fun hasIpv4Internet() = false
            override suspend fun execute(request: PortalRequest): String {
                endpoints += request.endpoint
                return when (request.endpoint) {
                    PortalEndpoint.PUBLIC_STATUS -> { delay(5000); throw PortalException(PortalProblem.CONNECTION) }
                    PortalEndpoint.DORM_STATUS -> { delay(200); "{\"result\":0}" }
                    else -> error("No login should be submitted without credentials")
                }
            }
        }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { credentialTimes += testScheduler.currentTime; null }, {})
        assertFalse(result.completed); assertEquals(listOf(5000L), credentialTimes)
        assertEquals(listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS), endpoints)
    }

    @Test fun earlySuccessStillWaitsForCancelledRequestCleanup() = runTest {
        var cleaned = false
        val transport = PortalTransport { request -> when (request.endpoint) {
            PortalEndpoint.PUBLIC_STATUS -> { delay(200); "{\"result\":1}" }
            PortalEndpoint.DORM_STATUS -> try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(100); cleaned = true }
            }
            else -> error("Unexpected credential request")
        } }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {})
        assertTrue(result.completed); assertTrue(cleaned); assertEquals(300L, testScheduler.currentTime)
    }

    @Test fun cancellationDuringEarlyChecksNeverReturnsLateSuccess() = runTest {
        var internetCancelled = false; var publicCancelled = false; var completed = false
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest): String = when (request.endpoint) {
                PortalEndpoint.PUBLIC_STATUS -> try { awaitCancellation() } finally { publicCancelled = true }
                PortalEndpoint.DORM_STATUS -> "{\"result\":0}"
                else -> error("Unexpected credential request")
            }
            override suspend fun hasIpv4Internet(): Boolean = try { delay(1000); true } finally { internetCancelled = true }
        }
        val task = launch { CampusLogin().login(transport, NetworkMode.AUTO, { error("Must not read credentials") }, {}); completed = true }
        runCurrent(); advanceTimeBy(100); task.cancel(); task.join()
        assertFalse(completed); assertTrue(internetCancelled); assertTrue(publicCancelled)
    }

    @Test fun diagnosticsContainOnlyFixedLabelsAndDuration() = runTest {
        val entries = mutableListOf<LoginDiagnostic>()
        val transport = DiagnosticPortalTransport(PortalTransport { "server text with fictional secret" }, entries::add)
        transport.execute(PortalRequest(PortalEndpoint.PUBLIC_LOGIN, listOf("password" to "fictional secret")))
        assertEquals(LoginStep.PUBLIC_LOGIN, entries.single().step)
        assertEquals(LoginStepResult.RESPONSE, entries.single().result)
        assertFalse(entries.toString().contains("secret"))
    }
}
