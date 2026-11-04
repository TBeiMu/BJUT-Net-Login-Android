package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.network.portalClientBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpBillingSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var session: HttpBillingSession
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    private val credentials = AccountDraft("demo-id", "模拟", "demo-account", "demo&+ #?=密码%")

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = portalClientBuilder().addInterceptor { chain ->
            val original = chain.request()
            requests += original
            // Only tests rewrite the fixed HTTPS destination; production keeps platform TLS validation.
            val local = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(local).build())
        }.build()
        session = HttpBillingSession(client)
    }

    @After fun cleanup() {
        session.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    private fun html(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "text/html;charset=UTF-8")
    private fun captchaImage() = MockResponse().setHeader("Content-Type", "image/png").setBody("demo-image")
    private fun login(body: String = loginHtml()) { server.enqueue(html(body)); server.enqueue(captchaImage()) }
    private fun ready() { server.enqueue(html(dashboardHtml)); server.enqueue(MockResponse().setBody(devicesJson)) }
    private suspend fun problem(block: suspend () -> Unit): BillingProblem {
        try { block(); fail("Expected safe failure") } catch (e: BillingException) { return e.problem }
        error("unreachable")
    }

    @Test fun loginEncodesSpecialPasswordAndReusesCookieWithoutPuttingCredentialsInUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/login").setHeader("Set-Cookie", "JSESSIONID=demo-cookie; Path=/Self; Secure; HttpOnly"))
        login()
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/Self/dashboard"))
        ready()
        val result = session.refresh(credentials)
        assertEquals(4, result.metrics.size)
        assertEquals(2, result.devices.size)
        val login = requests.single { it.method == "POST" }
        assertEquals("https", login.url.scheme)
        assertEquals("jfself.bjut.edu.cn", login.url.host)
        assertNull(login.url.query)
        val body = login.body as FormBody
        val parameters = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals(credentials.password, parameters["password"])
        assertEquals("demo-check", parameters["checkcode"])
        assertEquals("JSESSIONID=demo-cookie", login.header("Cookie"))
        assertTrue(requests.drop(1).all { it.header("Cookie") == "JSESSIONID=demo-cookie" })
        ready()
        session.refresh(credentials)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun anonymousCookieIsIsolatedFromAnotherAccountSession() = runBlocking {
        server.enqueue(html(dashboardHtml).setHeader("Set-Cookie", "JSESSIONID=demo-one; Path=/Self; Secure"))
        server.enqueue(MockResponse().setBody("[]"))
        session.refresh(credentials)
        assertEquals("JSESSIONID=demo-one", requests.last().header("Cookie"))
        val other = HttpBillingSession(client)
        ready(); other.refresh(credentials.copy(id = "second-demo"))
        assertNull(requests[2].header("Cookie"))
        assertNull(requests[3].header("Cookie"))
        other.close()
        assertEquals(BillingProblem.EXPIRED, problem { other.refresh(credentials) })
    }

    @Test fun observedSchoolLoginRedirectWithSlashAndMarkerLogsInUsingSavedAccount() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://jfself.bjut.edu.cn/Self/login/?302=LI")
            .setHeader("Set-Cookie", "JSESSIONID=demo-observed-cookie; Path=/Self; Secure; HttpOnly"))
        login(loginHtml(action = "/Self/login/verify"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/dashboard"))
        ready()
        val result = session.refresh(credentials)
        assertEquals(4, result.metrics.size)
        assertEquals(2, result.devices.size)
        assertEquals("/Self/login/", requests[1].url.encodedPath)
        assertEquals("LI", requests[1].url.queryParameter("302"))
        assertEquals("JSESSIONID=demo-observed-cookie", requests[1].header("Cookie"))
        val login = requests.single { it.method == "POST" }
        assertNull(login.url.query)
        assertEquals("/Self/login/verify", login.url.encodedPath)
        val body = login.body as FormBody
        val parameters = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals(credentials.username, parameters["account"])
        assertEquals(credentials.password, parameters["password"])
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun loginTrailingSlashWorksWithoutMarkerOrWithUrlRewrittenSession() = runBlocking {
        for (location in listOf("/Self/login/", "/Self/login/;jsessionid=DEMO123?302=LI")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            login()
            server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/Self/dashboard"))
            ready()
            assertEquals(4, session.refresh(credentials).metrics.size)
        }
        assertEquals(2, requests.count { it.method == "POST" })
    }

    @Test fun unrecognizedQueryOrUnexpectedRedirectDestinationStopsBeforeCredentials() = runBlocking {
        val locations = listOf("/Self/login/?302=OTHER", "/Self/login/?302=LI&next=https://example.org",
            "/Self/login/?302=LI&302=LI", "/Self/login/?next=/Self/dashboard", "/Self/dashboard?302=LI",
            "https://example.org/Self/login/?302=LI", "http://jfself.bjut.edu.cn/Self/login/?302=LI")
        locations.forEach { location ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        }
        assertEquals(locations.size, requests.size)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test fun rejectedSavedPasswordAfterObservedRedirectDoesNotAutomaticallyRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/login/?302=LI"))
        login()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/login/?302=LI"))
        server.enqueue(html(loginHtml()))
        assertEquals(BillingProblem.UNCONFIRMED, problem { session.refresh(credentials) })
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(5, requests.size)
    }

    @Test fun redirectWithoutLocationIsRejectedWithoutFollowingOrPosting() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302))
        assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        assertEquals(1, requests.size)
    }

    @Test fun foreignLoginActionIsRejectedBeforePostingAnyCredentials() = runBlocking {
        server.enqueue(html(loginHtml(action = "https://example.com/steal")))
        assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test fun foreignRedirectAndSameOriginUnexpectedPathAreRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/Self/login"))
        assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/service/delete"))
        assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        assertEquals(2, requests.size)
    }

    @Test fun post307NeverResubmitsPasswordAnd503NeverRetries() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/Self/dashboard"))
        assertEquals(BillingProblem.REDIRECT, problem { session.refresh(credentials) })
        assertEquals(1, requests.count { it.method == "POST" })
        login()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        assertEquals(BillingProblem.CONNECTION, problem { session.refresh(credentials) })
        assertEquals(2, requests.count { it.method == "POST" })
        assertEquals(6, requests.size)
    }

    @Test fun wrongPasswordDoesNotRetryOrBecomeBillingSuccess() = runBlocking {
        login(); server.enqueue(html(loginHtml(tip = "账号或密码出现错误")))
        assertEquals(BillingProblem.REJECTED, problem { session.refresh(credentials) })
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun captchaWaitsForUserAndDoesNotAutomaticallyRetryFailedCode() = runBlocking {
        server.enqueue(html(loginHtml(true)))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("demo-image"))
        assertEquals(BillingProblem.CAPTCHA, problem { session.refresh(credentials) })
        assertEquals(0, requests.count { it.method == "POST" })
        assertNotNull(session.captcha)
        server.enqueue(html(loginHtml(true)))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("another-demo-image"))
        assertEquals(BillingProblem.CAPTCHA, problem { session.refresh(credentials, "1234") })
        assertEquals(1, requests.count { it.method == "POST" })
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/dashboard"))
        ready()
        assertEquals(4, session.refresh(credentials, "5678").metrics.size)
        assertNull(session.captcha)
        val lastPost = requests.last { it.method == "POST" }.body as FormBody
        assertEquals("5678", (0 until lastPost.size).associate { lastPost.name(it) to lastPost.value(it) }["code"])
    }

    @Test fun rejectedCaptchaWithoutCaptchaFormNeverRetriesCredentials() = runBlocking {
        server.enqueue(html(loginHtml(true)))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("demo-image"))
        assertEquals(BillingProblem.CAPTCHA, problem { session.refresh(credentials) })
        server.enqueue(html(loginHtml(false, tip = "账号或密码出现错误")))
        assertEquals(BillingProblem.REJECTED, problem { session.refresh(credentials, "1234") })
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun onlineErrorDoesNotHideSuccessfullyReadMetrics() = runBlocking {
        server.enqueue(html(dashboardHtml)); server.enqueue(MockResponse().setBody("unexpected"))
        val result = session.refresh(credentials)
        assertEquals(4, result.metrics.size)
        assertNotNull(result.devicesError)
        assertTrue(result.devices.isEmpty())
    }

    @Test fun csrfAccountRefreshUsesPageTokenThenReadsUpdatedDashboard() = runBlocking {
        val withCsrf = dashboardHtml.replace("</html>", "<script>$.get('/Self/dashboard/refreshaccount', {csrftoken:'demo-token',t:1});</script></html>")
        server.enqueue(html(withCsrf)); server.enqueue(MockResponse().setBody("{}"))
        ready()
        assertEquals(4, session.refresh(credentials).metrics.size)
        assertEquals("demo-token", requests[1].url.queryParameter("csrftoken"))
        assertEquals("/Self/dashboard/refreshaccount", requests[1].url.encodedPath)
        assertEquals("/Self/dashboard", requests[2].url.encodedPath)
    }

    @Test fun offlineUsesOnlySelectedSessionAndNeverAutoAuthenticatesExpiredSession() = runBlocking {
        ready(); session.refresh(credentials)
        server.enqueue(MockResponse().setBody("无感知用户需解绑mac"))
        assertTrue(session.offlineTip().contains("解绑"))
        server.enqueue(MockResponse().setBody("{\"success\":true}"))
        session.offline(demoDevice.copy(sessionId = "demo+& session"))
        val offline = requests.last()
        assertEquals("/Self/dashboard/tooffline", offline.url.encodedPath)
        assertEquals("demo+& session", offline.url.queryParameter("sessionid"))
        assertEquals("192.0.2.10", offline.url.queryParameter("ip"))
        assertEquals("001122334455", offline.url.queryParameter("mac"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/login"))
        assertEquals(BillingProblem.EXPIRED, problem { session.offline(demoDevice) })
        assertEquals(0, requests.count { it.method == "POST" })
    }

    @Test fun responseBoundsAndCancellationStopWaitingForServer() = runBlocking {
        server.enqueue(MockResponse().setBody("a".repeat(1024 * 1024 + 1)))
        assertEquals(BillingProblem.RESPONSE, problem { session.refresh(credentials) })
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async(Dispatchers.IO) { session.refresh(credentials) }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }

    @Test fun hiddenCaptchaInitializationPrecedesPostAndUsesEffectiveReferer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Self/login/?302=LI"))
        login()
        server.enqueue(html(dashboardHtml)); server.enqueue(MockResponse().setBody("[]"))
        session.refresh(credentials)
        assertEquals(listOf("/Self/dashboard", "/Self/login/", "/Self/login/randomCode", "/Self/login/verify", "/Self/dashboard/getOnlineList"),
            requests.map { it.url.encodedPath.substringBefore(';') })
        assertEquals("https://jfself.bjut.edu.cn/Self/login/?302=LI", requests[2].header("Referer"))
        assertEquals(requests[2].header("Referer"), requests[3].header("Referer"))
        assertNull(session.captcha)
        assertTrue(session.diagnostics.any { it.stage == "初始化验证码会话" })
    }

    @Test fun cookiesSetForDifferentPathAreSentOnlyOnMatchingLaterRequest() = runBlocking {
        server.enqueue(html(loginHtml(action = "/Self/login/verify")).setHeader("Set-Cookie", "login-only=demo-value; Path=/Self/login/verify; Secure"))
        server.enqueue(captchaImage())
        server.enqueue(html(dashboardHtml)); server.enqueue(MockResponse().setBody("[]"))
        session.refresh(credentials)
        assertNull(requests[1].header("Cookie"))
        assertEquals("login-only=demo-value", requests[2].header("Cookie"))
        assertNull(requests[3].header("Cookie"))
    }

    @Test fun webLoginKeepsNewPasswordEphemeralAndConsumesTokenOnce() = runBlocking {
        login()
        val page = session.openWeb(credentials)
        assertEquals(0, requests.count { it.method == "POST" })
        server.enqueue(html(dashboardHtml)); server.enqueue(MockResponse().setBody("[]"))
        val result = session.submitWeb(page.token, "changed-demo-password", "")
        assertTrue(result is BillingWebResult.Success)
        val body = requests.single { it.method == "POST" }.body as FormBody
        assertEquals("changed-demo-password", (0 until body.size).associate { body.name(it) to body.value(it) }["password"])
        assertEquals("demo&+ #?=密码%", credentials.password)
        assertEquals(BillingProblem.EXPIRED, problem { session.submitWeb(page.token, "another-password", "") })
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun successfulReplyForAnotherAccountIsRejectedBeforeDeviceRead() = runBlocking {
        server.enqueue(html(dashboardHtml.replace("demo-account", "another-account")))
        assertEquals(BillingProblem.ACCOUNT_MISMATCH, problem { session.refresh(credentials) })
        assertEquals(1, requests.size)
    }
}
