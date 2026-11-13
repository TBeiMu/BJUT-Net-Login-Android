package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.network.NetworkDns
import io.github.tbeimu.gongxiaozhu.network.HttpAccountNameLookup
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class HttpScheduleSessionTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var session: HttpScheduleSession
    private val original = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
    @Before fun setup() {
        server.start()
        client = OkHttpClient.Builder().addInterceptor {
            original += it.request()
            it.proceed(it.request().newBuilder().url(it.request().url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).build())
        }.build()
        session = HttpScheduleSession(client, NetworkDns { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) })
    }
    @After fun close() { session.close(); client.dispatcher.cancelAll(); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private fun page() = """<html><form id="loginForm" method="post"><input name="username"><input name="password" type="password"><input name="execution" value="fake-changing-execution"><input name="type" value="username_password"><input type="submit" name="submit" value="LOGIN"></form><iframe id="loginIframe"></iframe></html>"""
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
    private suspend fun loginPage(): ScheduleLoginPage {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cas.bjut.edu.cn/login?locale=zh_cn&service=https%3A%2F%2Fitsapp.bjut.edu.cn%2Freturn"))
        server.enqueue(response(page()))
        return (session.open() as ScheduleGate.Login).page
    }

    @Test fun explicitSubmitPreservesPasswordAndConfirmsIdentityBeforeWeekRequests() = runBlocking {
        val page = loginPage()
        assertEquals(2, original.size)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$SCHEDULE_ORIGIN/return?ticket=fake-ticket"))
        server.enqueue(response("<html>done</html>")); server.enqueue(response(termResponse)); server.enqueue(response(identityResponse))
        val password = "  fake&+%中文=  "
        val gate = session.submit(page.token, mapOf("username" to "graduate-demo", "password" to password, "type" to "username_password")) as ScheduleGate.Ready
        assertEquals(demoIdentity, gate.identity)
        repeat(2) { server.takeRequest() }
        val posted = server.takeRequest().body.readUtf8().split('&').associate { val pair = it.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8") }
        assertEquals(password, posted["password"]); assertEquals("fake-changing-execution", posted["execution"])
        assertEquals(1, original.count { it.method == "POST" })
        assertFalse(page.html.contains("fake-changing-execution"))
        server.enqueue(response(weekResponse(1)))
        assertEquals(1, session.week(gate.term, 1).number)
        assertThrows(ScheduleException::class.java) { runBlocking { session.submit(page.token, emptyMap()) } }
        Unit
    }

    @Test fun crossSiteRedirectAndPasswordReplayAreRejected() = runBlocking {
        val page = loginPage()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://cas.bjut.edu.cn/login"))
        assertEquals(ScheduleProblem.ADDRESS, assertThrows(ScheduleException::class.java) { runBlocking {
            session.submit(page.token, mapOf("username" to "graduate-demo", "password" to "fake"))
        } }.problem)
        assertEquals(3, original.size)
    }

    @Test fun failedLoginReturnsNewPageButNeverResubmitsCredentialsAutomatically() = runBlocking {
        val page = loginPage(); server.enqueue(response(page().replace("fake-changing-execution", "next-fake-field")))
        val result = session.submit(page.token, mapOf("username" to "graduate-demo", "password" to "fake")) as ScheduleGate.Login
        assertNotEquals(page.token, result.page.token)
        assertEquals(1, original.count { it.method == "POST" })
        assertNull(session.resource(page.token, "https://cas.bjut.edu.cn/cas/login-mobile.html"))
        assertNull(session.resource(result.page.token, "https://example.com/asset.js"))
    }

    @Test fun serviceUnavailableCannotAutomaticallyReplayPasswordPost() = runBlocking {
        val page = loginPage()
        repeat(2) { server.takeRequest() }
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(response(page())) // Must remain unused until an explicit new login.
        val error = runCatching {
            session.submit(page.token, mapOf("username" to "graduate-demo", "password" to "fictional-password"))
        }.exceptionOrNull()
        assertEquals(ScheduleProblem.CONNECTION, (error as? ScheduleException)?.problem)
        assertEquals("POST", server.takeRequest().method)
        // Count server requests: application interceptors do not observe OkHttp's internal retries.
        assertEquals(3, server.requestCount)
        assertEquals(ScheduleProblem.EXPIRED, (runCatching {
            session.submit(page.token, mapOf("username" to "graduate-demo", "password" to "fictional-password"))
        }.exceptionOrNull() as? ScheduleException)?.problem)
        assertEquals(3, server.requestCount)
    }

    @Test fun anonymousAjaxResponseOpensOnlySchoolLoginUrl() = runBlocking {
        server.enqueue(response("""{"e":10013,"d":{"login_url":"https://itsapp.bjut.edu.cn/uc/wap/login"}}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cas.bjut.edu.cn/login?locale=zh_cn"))
        server.enqueue(response(page()))
        assertTrue(session.open() is ScheduleGate.Login)
        assertEquals("XMLHttpRequest", original.first().header("X-Requested-With"))
        assertEquals(0, original.count { it.method == "POST" })
    }

    private fun identityLogin(html: String = page()) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$CAS_ORIGIN/login?locale=zh_cn"))
        server.enqueue(response(html))
    }
    private fun nameAccount(password: String = "fictional-password") = AccountDraft(
        label = "", username = "graduate-demo", password = password)

    @Test fun nameLookupVerifiesIdentityWithoutAnyTermOrWeekRequest() = runBlocking {
        identityLogin()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$SCHEDULE_ORIGIN/return?ticket=fictional-ticket"))
        server.enqueue(response("<html>done</html>")); server.enqueue(response(identityResponse))
        val password = "  fictional&+%中文=  "
        assertEquals(demoIdentity, session.accountIdentity(nameAccount(password)))
        assertEquals(5, server.requestCount)
        assertTrue(original.none { it.url.toString().startsWith(TERM_URL) || it.url.toString().startsWith(WEEK_URL) })
        repeat(2) { server.takeRequest() }
        val posted = server.takeRequest().body.readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(password, posted["password"])
        assertEquals("fake-changing-execution", posted["execution"])
    }

    @Test fun nameLookupSkipsCredentialsWhenCaptchaIsRequired() = runBlocking {
        for (extra in listOf("<input name='captcha'>", "<div id='captchaParent'></div>",
            "<script>config.captcha='fictional-challenge';</script>")) {
            session.close()
            session = HttpScheduleSession(client, NetworkDns { listOf(InetAddress.getLoopbackAddress()) })
            identityLogin(page().replace("</form>", "$extra</form>"))
            assertEquals(ScheduleProblem.LOGIN, (runCatching { session.accountIdentity(nameAccount()) }
                .exceptionOrNull() as? ScheduleException)?.problem)
        }
        assertEquals(6, server.requestCount)
        assertTrue(original.none { it.method == "POST" })
    }

    @Test fun nameLookupStopsOnPasswordRejectionAndCannotUseAnotherIdentity() = runBlocking {
        identityLogin(); server.enqueue(response(page()))
        assertEquals(ScheduleProblem.LOGIN, (runCatching { session.accountIdentity(nameAccount()) }
            .exceptionOrNull() as? ScheduleException)?.problem)
        assertEquals(3, server.requestCount)
        session.close()
        session = HttpScheduleSession(client, NetworkDns { listOf(InetAddress.getLoopbackAddress()) })
        server.enqueue(response(identityResponse.replace("graduate-demo", "another-fictional-student")))
        assertEquals(ScheduleProblem.IDENTITY, (runCatching { session.accountIdentity(nameAccount()) }
            .exceptionOrNull() as? ScheduleException)?.problem)
        assertEquals(4, server.requestCount)
    }

    @Test fun nameLookupDoesNotRetry503PasswordSubmission() = runBlocking {
        identityLogin()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(response(page()))
        assertEquals(ScheduleProblem.CONNECTION, (runCatching { session.accountIdentity(nameAccount()) }
            .exceptionOrNull() as? ScheduleException)?.problem)
        assertEquals(3, server.requestCount)
    }

    @Test fun separateNameLookupsDoNotShareCookies() = runBlocking {
        val lookup = HttpAccountNameLookup(client, NetworkDns { listOf(InetAddress.getLoopbackAddress()) })
        server.enqueue(response(identityResponse).setHeader("Set-Cookie", "fictional_session=only-once; Path=/"))
        server.enqueue(response(identityResponse))
        assertEquals(demoIdentity, lookup.lookup(nameAccount()))
        assertEquals(demoIdentity, lookup.lookup(nameAccount()))
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun dnsDeadlineIsAConnectionFailureAndCancelsLookup() = runTest {
        var cancelled = false
        session.close()
        session = HttpScheduleSession(client, NetworkDns {
            try { delay(21_000); emptyList() } finally { cancelled = true }
        })
        val error = runCatching { session.open() }.exceptionOrNull()
        assertEquals(ScheduleProblem.CONNECTION, (error as? ScheduleException)?.problem)
        assertTrue(cancelled)
        assertTrue(original.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun callerCancellationIsNotConvertedToConnectionFailure() = runTest {
        session.close()
        session = HttpScheduleSession(client, NetworkDns { delay(21_000); emptyList() })
        var reportedFailure = false
        val request = launch {
            try { session.open() } catch (_: ScheduleException) { reportedFailure = true }
        }
        runCurrent(); request.cancel(); request.join()
        assertTrue(request.isCancelled)
        assertFalse(reportedFailure)
        assertTrue(original.isEmpty())
    }

    @Test fun loginResourceDeadlineReturnsFailureToPageLoader() {
        var slow = false
        session.close()
        session = HttpScheduleSession(client, NetworkDns {
            if (slow) delay(21_000)
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        })
        val page = runBlocking { loginPage() }
        slow = true
        runTest { assertNull(session.resource(page.token, "$CAS_ORIGIN/cas/fake.js")) }
        assertEquals(2, original.size)
    }
}
