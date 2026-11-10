package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
            "{\"result\":1}"
        }
        val result = CampusLogin().login(transport, NetworkMode.AUTO, { error("ambiguous network must not read credentials") }, {})
        assertEquals(listOf(0L, 0L), starts)
        assertEquals(2000L, testScheduler.currentTime)
        assertTrue(result.needsChoice)
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
