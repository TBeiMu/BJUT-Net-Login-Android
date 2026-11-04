package io.github.tbeimu.gongxiaozhu.network

import java.net.Proxy
import java.net.InetAddress
import java.net.UnknownHostException
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
    private val originalRequests = mutableListOf<okhttp3.Request>()

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        // Test-only destination rewrite: production requests retain their fixed campus origins.
        client = portalClientBuilder().addInterceptor { chain ->
            val original = chain.request()
            originalRequests += original
            val local = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
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

    @Test fun connectivityCheckUsesFixedHttpsUrlWithoutCredentialsAndRequires204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(HttpPortalTransport(client, client).hasIpv4Internet())
        val request = originalRequests.single()
        assertEquals("https://connectivitycheck.platform.hicloud.com/generate_204", request.url.toString())
        assertEquals("GET", request.method)
        assertNull(request.url.query)
        assertNull(request.body)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertEquals("no-store", request.header("Cache-Control"))
    }

    @Test fun connectivityCheckRejectsCaptivePagesRedirectsAndHttpErrors() = runBlocking {
        val checker = HttpPortalTransport(client, client)
        server.enqueue(MockResponse().setBody("<html>login required</html>"))
        assertFalse(checker.hasIpv4Internet())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/login")))
        assertFalse(checker.hasIpv4Internet())
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(checker.hasIpv4Internet())
        assertEquals(3, server.requestCount)
    }

    @Test fun connectivityCheckNeverFallsBackToUnboundDefaultNetwork() = runBlocking {
        assertFalse(transport.hasIpv4Internet())
        assertEquals(0, server.requestCount)
    }

    @Test fun connectivityDnsDiscardsIpv6AndNeverFallsBackToSystemDns() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val queriedHosts = mutableListOf<String>()
        val dns = ipv4OnlyDns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                queriedHosts += hostname
                return listOf(ipv6, ipv4)
            }
        })
        assertEquals(listOf(ipv4), dns.lookup("test.example"))
        assertEquals(listOf("test.example"), queriedHosts)
        try {
            ipv4OnlyDns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(ipv6)
            }).lookup("test.example")
            fail("IPv6 alone must not count as IPv4 connectivity")
        } catch (_: UnknownHostException) { /* expected */ }
    }

    @Test fun cancelledConnectivityCheckCancelsBoundHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { HttpPortalTransport(client, client).hasIpv4Internet() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
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
