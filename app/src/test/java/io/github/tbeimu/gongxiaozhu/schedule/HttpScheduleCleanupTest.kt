package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.network.NetworkDns
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTP callbacks with fictional school pages and deterministic cleanup barriers. */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpScheduleCleanupTest {
    private val server = MockWebServer()
    private val holdCleanup = AtomicBoolean(false)
    private val cleaning = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val cleaned = AtomicBoolean(false)
    private lateinit var client: OkHttpClient
    private lateinit var session: HttpScheduleSession
    private val addresses = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    @Before fun setup() {
        server.start()
        client = OkHttpClient.Builder().addInterceptor {
            it.proceed(it.request().newBuilder().url(it.request().url.newBuilder()
                .scheme("http").host("127.0.0.1").port(server.port).build()).build())
        }.addNetworkInterceptor {
            try { it.proceed(it.request()) }
            finally {
                if (holdCleanup.get()) {
                    cleaning.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    cleaned.set(true)
                }
            }
        }.build()
        session = HttpScheduleSession(client, NetworkDns { addresses })
    }

    @After fun close() {
        release.countDown()
        session.close()
        runBlocking { withTimeout(5_000) { session.awaitClosed() } }
        client.dispatcher.cancelAll()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun loginPage(): ScheduleLoginPage {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$CAS_ORIGIN/login?locale=zh_cn"))
        server.enqueue(MockResponse().setBody("""<form id="loginForm" method="post"><input name="username"><input name="password"><input name="execution" value="fictional-token"></form>"""))
        val page = (session.open() as ScheduleGate.Login).page
        repeat(2) { server.takeRequest() }
        return page
    }

    private fun assertHttpCleanup(closeSession: Boolean, resource: Boolean = false) = runBlocking {
        val page = if (resource) loginPage() else null
        holdCleanup.set(true)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) {
            if (page == null) session.open() else session.resource(page.token, "$CAS_ORIGIN/cas/fictional.js")
        }
        try {
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            if (closeSession) session.close() else pending.cancel()
            assertTrue(cleaning.await(3, TimeUnit.SECONDS))
            assertFalse("Caller must remain active until the HTTP callback finishes cleanup", pending.isCompleted)
            session.close()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { session.awaitClosed() }
            assertFalse("Session must include native HTTP cleanup", closing.isCompleted)
            release.countDown()
            withTimeout(3_000) { pending.join(); closing.await() }
            assertTrue(cleaned.get())
            assertTrue(pending.isCancelled)
        } finally { release.countDown() }
    }

    @Test fun callerCancellationWaitsForHttpCleanup() = assertHttpCleanup(closeSession = false)
    @Test fun closingSessionWaitsForHttpCleanup() = assertHttpCleanup(closeSession = true)
    @Test fun closingIdleLoginPageWaitsForResourceHttpCleanup() = assertHttpCleanup(closeSession = true, resource = true)

    @Test fun closingIdleLoginPageCancelsResourceDnsAndWaitsForItsCleanup() {
        var slow = false
        var lookups = 0
        val cleaningDns = CompletableDeferred<Unit>()
        val releaseDns = CompletableDeferred<Unit>()
        session.close()
        session = HttpScheduleSession(client, NetworkDns {
            lookups++
            if (slow) try { awaitCancellation() }
            finally {
                cleaningDns.complete(Unit)
                withContext(NonCancellable) { releaseDns.await() }
            }
            addresses
        })
        val page = runBlocking { loginPage() }
        slow = true
        runTest {
            val pending = async { session.resource(page.token, "$CAS_ORIGIN/cas/fictional.js") }
            runCurrent()
            try {
                session.close(); runCurrent()
                assertTrue(cleaningDns.isCompleted)
                val closing = async { session.awaitClosed() }
                runCurrent()
                assertFalse(pending.isCompleted); assertFalse(closing.isCompleted)
                val rejected = async { session.resource(page.token, "$CAS_ORIGIN/cas/later.js") }
                runCurrent()
                assertTrue(rejected.isCancelled)
                assertEquals(3, lookups)
                releaseDns.complete(Unit); runCurrent()
                pending.join(); closing.await()
                assertEquals(2, server.requestCount)
            } finally { releaseDns.complete(Unit) }
        }
    }
}
