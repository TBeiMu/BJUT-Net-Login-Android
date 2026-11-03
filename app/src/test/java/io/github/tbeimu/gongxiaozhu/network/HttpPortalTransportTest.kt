package io.github.tbeimu.gongxiaozhu.network

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpPortalTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: HttpPortalTransport

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        // Test-only destination rewrite: production requests retain their fixed campus origins.
        client = portalClientBuilder().addInterceptor { chain ->
            val original = chain.request()
            val local = original.url.newBuilder().host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(local).build())
        }.build()
        transport = HttpPortalTransport(client)
    }

    @After fun cleanup() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun clientDisablesRedirectsProxiesAndAutomaticRetries() {
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(8000, client.callTimeoutMillis)
    }

    @Test fun getEncodesSpecialCharactersWithoutAddingParameters() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"result\":1}"))
        val password = "  demo&+%?=中文#  "
        transport.execute(PortalRequest(PortalEndpoint.PUBLIC_LOGIN, listOf("upass" to password, "DDDDD" to "demo+&user")))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(password, recorded.requestUrl!!.queryParameter("upass"))
        assertEquals("demo+&user", recorded.requestUrl!!.queryParameter("DDDDD"))
        assertEquals(2, recorded.requestUrl!!.querySize)
        assertEquals("GET", recorded.method)
        assertFalse(recorded.path!!.contains("中文"))
    }

    @Test fun postFormEncodesExactPassword() = runBlocking {
        server.enqueue(MockResponse().setBody("OK"))
        val password = "  demo&+%?=中文#  "
        transport.execute(PortalRequest(PortalEndpoint.IPV4_LOGIN, listOf("upass" to password, "v46s" to "1")))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val values = recorded.body.readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(password, values["upass"])
        assertEquals("1", values["v46s"])
        assertNull(recorded.requestUrl!!.query)
    }

    @Test fun credentialRedirectIsRejectedWithoutSecondRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))
        server.enqueue(MockResponse().setBody("must not follow"))
        assertEquals(PortalProblem.REDIRECT, failure(PortalRequest(PortalEndpoint.PUBLIC_LOGIN,
            listOf("upass" to "demo-secret"))).problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun serviceUnavailableMustNotAutomaticallyResubmitCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("{\"result\":1}"))
        assertEquals(PortalProblem.CONNECTION, failure(PortalRequest(PortalEndpoint.PUBLIC_LOGIN,
            listOf("upass" to "demo-secret"))).problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun serviceUnavailableMustNotAutomaticallyRepeatPostLogin() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("<!--Dr.COMWebLoginID_3.htm-->"))
        assertEquals(PortalProblem.CONNECTION, failure(PortalRequest(PortalEndpoint.IPV4_LOGIN,
            listOf("upass" to "demo-secret"))).problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun oversizedAndChunkedResponsesAreBounded() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(128 * 1024 + 1)))
        assertEquals(PortalProblem.RESPONSE, failure(PortalRequest(PortalEndpoint.PUBLIC_STATUS)).problem)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(128 * 1024 + 1), 4096))
        assertEquals(PortalProblem.RESPONSE, failure(PortalRequest(PortalEndpoint.PUBLIC_STATUS)).problem)
    }

    @Test fun legacyHtmlCharsetIsDecoded() = runBlocking {
        val text = "<meta charset=gb2312>模拟认证页面"
        server.enqueue(MockResponse().setBody(okio.Buffer().write(text.toByteArray(charset("GB2312")))))
        assertEquals(text, transport.execute(PortalRequest(PortalEndpoint.IPV4_STATUS)))
    }

    @Test fun cancelledRequestStopsHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { transport.execute(PortalRequest(PortalEndpoint.PUBLIC_STATUS)) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
    }

    @Test fun timeoutAndHttpErrorsNeverEchoServerContent() = runBlocking {
        val fastClient = client.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            HttpPortalTransport(fastClient).execute(PortalRequest(PortalEndpoint.PUBLIC_STATUS))
            fail("Expected timeout")
        } catch (error: PortalException) { assertEquals(PortalProblem.CONNECTION, error.problem) }
        server.enqueue(MockResponse().setResponseCode(500).setBody("secret server echo"))
        val error = failure(PortalRequest(PortalEndpoint.PUBLIC_LOGIN))
        assertFalse(error.message!!.contains("secret server echo"))
        assertNull(error.cause)
    }

    @Test fun requestsNeverExposeParametersInToString() {
        assertFalse(PortalRequest(PortalEndpoint.PUBLIC_LOGIN, listOf("upass" to "demo-secret")).toString().contains("demo-secret"))
    }

    private suspend fun failure(request: PortalRequest): PortalException = try {
        transport.execute(request)
        throw AssertionError("Expected safe transport error")
    } catch (error: CancellationException) { throw error }
    catch (error: PortalException) { error }
}
