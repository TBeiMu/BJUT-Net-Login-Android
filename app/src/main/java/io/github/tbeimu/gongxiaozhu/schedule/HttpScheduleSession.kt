package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.readBounded
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.network.NetworkDns
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SCHEDULE_ORIGIN = "https://itsapp.bjut.edu.cn"
internal const val CAS_ORIGIN = "https://cas.bjut.edu.cn"
internal const val SCHEDULE_ENTRY = "$SCHEDULE_ORIGIN/site/graduateSchedule/index"
internal const val TERM_URL = "$SCHEDULE_ORIGIN/yjskb/wap/default/get-index"
internal const val WEEK_URL = "$SCHEDULE_ORIGIN/yjskb/wap/default/get-datatmp"
internal const val IDENTITY_URL = "$SCHEDULE_ORIGIN/uc/wap/user/get-info"

internal fun allowedScheduleUrl(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() && url.host in setOf("itsapp.bjut.edu.cn", "cas.bjut.edu.cn", "imgitsapp.bjut.edu.cn")

internal fun allowedCasResource(url: HttpUrl): Boolean = allowedScheduleUrl(url) && url.host == "cas.bjut.edu.cn" &&
    (listOf("/cas/", "/js/", "/css/", "/images/", "/img/", "/captcha").any { url.encodedPath.startsWith(it) })

private class ScheduleCookies : CookieJar {
    private val values = mutableListOf<Cookie>()
    private var closed = false
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (closed) return
        cookies.forEach { c -> values.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; if (c.expiresAt > System.currentTimeMillis()) values += c }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.filter { it.matches(url) }
    }
    @Synchronized fun clear() { closed = true; values.clear() }
}

internal class CasForm(val url: HttpUrl, val html: String, val hidden: List<Pair<String, String>>, val editable: Set<String>)
private data class ScheduleResponse(val url: HttpUrl, val bytes: ByteArray, val mime: String) {
    fun text() = bytes.toString(Charsets.UTF_8)
}

/** Independent native transport pinned to one physical network; credentials and CAS cookies stay in memory. */
class HttpScheduleSession(boundClient: OkHttpClient, private val dns: NetworkDns) : ScheduleSession {
    private val closed = AtomicBoolean(false)
    private val cookies = ScheduleCookies()
    private val client = boundClient.newBuilder().cookieJar(cookies).cache(null).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val operations = mutableSetOf<Job>()
    @Volatile private var pageToken: String? = null
    private var form: CasForm? = null
    private var submittedUsername: String? = null
    private var verifiedIdentity: ScheduleIdentity? = null
    private val resources = ConcurrentHashMap<String, ScheduleResource>()

    override suspend fun open(): ScheduleGate = inSession { gate(follow(TERM_URL.toHttpUrl())) }

    override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate = inSession {
        val result = submitForm(token, values)
        if (result.url.host == "cas.bjut.edu.cn") gate(result) else gate(follow(TERM_URL.toHttpUrl()))
    }

    /** Name-only lookup uses a fresh session and never starts or binds a timetable import. */
    suspend fun accountIdentity(account: AccountDraft): ScheduleIdentity = inSession {
        var info = follow(IDENTITY_URL.toHttpUrl())
        val login = loginIfRequired(info)
        if (login != null) {
            val document = Jsoup.parse(form?.html ?: throw ScheduleException(ScheduleProblem.LOGIN))
            val captcha = document.selectFirst("#captchaParent")
            val scripts = document.select("script:not([src])").joinToString("\n") { it.data() }
            val configuredCaptcha = Regex("(?:config\\.captcha\\s*=|[\"']captcha[\"']\\s*:)\\s*([^;,\\n}]+)")
                .findAll(scripts).any { it.groupValues[1].trim() !in setOf("false", "null", "undefined", "0", "\"\"", "''") }
            if (document.selectFirst("input[name=captcha]") != null || (captcha != null &&
                !captcha.attr("style").replace(" ", "").contains("display:none", true)) || configuredCaptcha) {
                throw ScheduleException(ScheduleProblem.LOGIN)
            }
            val result = submitForm(login.page.token, mapOf("username" to account.username,
                "password" to account.password, "type" to "username_password"))
            // Another CAS page means user interaction is required. Never retry the password.
            if (result.url.host == "cas.bjut.edu.cn") throw ScheduleException(ScheduleProblem.LOGIN)
            info = follow(IDENTITY_URL.toHttpUrl())
        }
        if (info.url.host != "itsapp.bjut.edu.cn") throw ScheduleException(ScheduleProblem.IDENTITY)
        ScheduleParser.identity(info.text(), account.username)
    }

    private suspend fun submitForm(token: String, values: Map<String, String>): ScheduleResponse {
        val current = form ?: throw ScheduleException(ScheduleProblem.EXPIRED)
        if (closed.get() || token != pageToken) throw ScheduleException(ScheduleProblem.EXPIRED)
        if (values.size > 12 || values.any { it.key !in current.editable || it.value.length > 1024 }) throw ScheduleException(ScheduleProblem.LOGIN)
        val username = values["username"].orEmpty()
        if (username.isBlank() || username.length > 128 || values["password"].isNullOrEmpty() || values["password"]!!.length > 512)
            throw ScheduleException(ScheduleProblem.LOGIN)
        if (values["type"] != null && values["type"] != "username_password") throw ScheduleException(ScheduleProblem.LOGIN)
        submittedUsername = username
        pageToken = null; form = null; resources.clear()
        val formBody = FormBody.Builder().apply {
            current.hidden.filterNot { it.first in values }.forEach { (key, value) -> add(key, value) }
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        // Connection retries are separate from HTTP follow-ups (including 503 + Retry-After: 0).
        val body = object : RequestBody() {
            override fun contentType() = formBody.contentType()
            override fun contentLength() = formBody.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = formBody.writeTo(sink)
        }
        // A single explicit submit; neither automatic follow-ups nor 307/308 may replay its body.
        return follow(current.url, body, current.url)
    }

    private suspend fun loginIfRequired(response: ScheduleResponse): ScheduleGate.Login? {
        currentCoroutineContext().ensureActive()
        if (response.url.host == "cas.bjut.edu.cn") return chineseLoginPage(response)
        val root = runCatching { Json.parseToJsonElement(response.text()).jsonObject }.getOrNull()
        if (root?.get("e")?.jsonPrimitive?.contentOrNull == "10013") {
            val address = (root["d"] as? JsonObject)?.get("login_url")?.jsonPrimitive?.contentOrNull
                ?: throw ScheduleException(ScheduleProblem.EXPIRED)
            val target = response.url.resolve(address) ?: throw ScheduleException(ScheduleProblem.ADDRESS)
            if (!allowedScheduleUrl(target) || target.host !in setOf("cas.bjut.edu.cn", "itsapp.bjut.edu.cn")) throw ScheduleException(ScheduleProblem.ADDRESS)
            return chineseLoginPage(follow(target))
        }
        return null
    }

    private suspend fun gate(response: ScheduleResponse): ScheduleGate {
        loginIfRequired(response)?.let { return it }
        val term = ScheduleParser.term(response.text())
        val info = follow(IDENTITY_URL.toHttpUrl())
        if (info.url.host != "itsapp.bjut.edu.cn") return chineseLoginPage(info)
        val identity = ScheduleParser.identity(info.text(), submittedUsername)
        verifiedIdentity = identity
        pageToken = null; form = null; resources.clear()
        return ScheduleGate.Ready(identity, term)
    }

    private suspend fun chineseLoginPage(response: ScheduleResponse): ScheduleGate.Login {
        if (response.url.host != "cas.bjut.edu.cn" || response.url.encodedPath != "/login") throw ScheduleException(ScheduleProblem.ADDRESS)
        // This is the locale used by the school's own language switch; no credentials are repeated.
        return loginPage(if (response.url.queryParameter("locale") == "zh_cn") response
            else follow(response.url.newBuilder().setQueryParameter("locale", "zh_cn").build()))
    }

    private fun loginPage(response: ScheduleResponse): ScheduleGate.Login {
        if (response.url.host != "cas.bjut.edu.cn" || response.url.encodedPath != "/login") throw ScheduleException(ScheduleProblem.ADDRESS)
        val doc = Jsoup.parse(response.text(), response.url.toString())
        val login = doc.selectFirst("form#loginForm") ?: throw ScheduleException(ScheduleProblem.LOGIN)
        if (!login.attr("method").equals("post", true) || login.selectFirst("input[name=username]") == null || login.selectFirst("input[name=password]") == null)
            throw ScheduleException(ScheduleProblem.LOGIN)
        val action = response.url.resolve(login.attr("action").ifBlank { response.url.toString() }) ?: throw ScheduleException(ScheduleProblem.ADDRESS)
        if (action.host != response.url.host || action.encodedPath != "/login" || !allowedScheduleUrl(action)) throw ScheduleException(ScheduleProblem.ADDRESS)
        val inputs = login.select("input[name]")
        val editable = setOf("username", "password", "captcha", "type", "submit")
        val hidden = inputs.filter { it.attr("name") !in setOf("username", "password", "captcha") }
            .map { it.attr("name") to it.attr("value") }
        if (hidden.none { it.first == "execution" && it.second.isNotBlank() }) throw ScheduleException(ScheduleProblem.LOGIN)
        val token = UUID.randomUUID().toString()
        form = CasForm(action, response.text(), hidden, editable)
        pageToken = token
        return ScheduleGate.Login(ScheduleLoginPage(token, response.url.toString(), ScheduleWebDocument.render(response.text(), response.url, token)))
    }

    override suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek = inSession {
        if (verifiedIdentity == null) throw ScheduleException(ScheduleProblem.EXPIRED)
        validateTerm(term)
        if (number !in 1..term.weekCount) throw ScheduleException(ScheduleProblem.RESPONSE)
        val body = FormBody.Builder().add("year", term.year).add("term", term.term).add("week", number.toString()).add("type", "2").build()
        val response = follow(WEEK_URL.toHttpUrl(), body, SCHEDULE_ENTRY.toHttpUrl())
        if (response.url.host != "itsapp.bjut.edu.cn") throw ScheduleException(ScheduleProblem.EXPIRED)
        ScheduleParser.week(response.text(), number)
    }

    override suspend fun resource(token: String, url: String): ScheduleResource? = inSession {
        if (token != pageToken) return@inSession null
        val target = runCatching { url.toHttpUrl().newBuilder().fragment(null).build() }.getOrNull() ?: return@inSession null
        if (!allowedCasResource(target)) return@inSession null
        resources[target.toString()]?.let { return@inSession it }
        if (resources.size >= 80) return@inSession null
        try {
            val response = follow(target, resource = true)
            if (closed.get() || pageToken != token) return@inSession null
            val bytes = if (response.mime == "text/html") ScheduleWebDocument.sanitizeFrame(response.text(), response.url).toByteArray() else response.bytes
            ScheduleResource(bytes, response.mime).also { if (!target.encodedPath.startsWith("/captcha")) resources[target.toString()] = it }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }

    private suspend fun follow(initial: HttpUrl, body: RequestBody? = null, referer: HttpUrl? = null, resource: Boolean = false): ScheduleResponse {
        var target = initial
        var post = body
        repeat(8) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw CancellationException("Schedule session closed")
            if (!allowedScheduleUrl(target) || (resource && !allowedCasResource(target))) throw ScheduleException(ScheduleProblem.ADDRESS)
            val response = request(target, post, referer)
            if (response.redirect != null) {
                if (post != null && response.code in setOf(307, 308)) throw ScheduleException(ScheduleProblem.ADDRESS)
                target = target.resolve(response.redirect) ?: throw ScheduleException(ScheduleProblem.ADDRESS)
                post = null
            } else {
                if (response.code !in 200..299) throw ScheduleException(ScheduleProblem.CONNECTION)
                return ScheduleResponse(target, response.bytes, response.mime)
            }
        }
        throw ScheduleException(ScheduleProblem.ADDRESS)
    }

    private class RawResponse(val code: Int, val bytes: ByteArray, val mime: String, val redirect: String?)
    private suspend fun request(url: HttpUrl, body: RequestBody?, referer: HttpUrl?): RawResponse = withTimeoutOrNull(20_000) {
        val addresses = dns.lookup(url.host)
        if (addresses.isEmpty()) throw ScheduleException(ScheduleProblem.CONNECTION)
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("Schedule session closed")
        val resolved = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (hostname != url.host) throw java.net.UnknownHostException("Unexpected schedule host")
                return addresses
            }
        }).build()
        val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .header("Cache-Control", "no-store")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (url.host == "itsapp.bjut.edu.cn" && url.encodedPath in setOf("/yjskb/wap/default/get-index", "/yjskb/wap/default/get-datatmp", "/uc/wap/user/get-info")) {
            builder.header("X-Requested-With", "XMLHttpRequest").header("Referer", SCHEDULE_ENTRY)
        }
        if (referer != null) builder.header("Referer", referer.toString())
        if (body != null) builder.post(body)
        val call = resolved.newCall(builder.build())
        val finished = CompletableDeferred<Unit>()
        calls += call
        if (closed.get()) call.cancel()
        try { suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try { call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(call)
                    finished.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(ScheduleException(ScheduleProblem.CONNECTION))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val redirect = if (it.code in setOf(301, 302, 303, 307, 308)) it.header("Location") else null
                            RawResponse(it.code, if (redirect != null) ByteArray(0) else it.body?.byteStream()?.use { stream -> stream.readBounded(2 * 1024 * 1024) }
                                ?: throw ScheduleException(ScheduleProblem.RESPONSE), it.body?.contentType()?.let { type -> "${type.type}/${type.subtype}" } ?: "application/octet-stream", redirect)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(ScheduleException(ScheduleProblem.RESPONSE)) }
                    finally { calls.remove(call); finished.complete(Unit) }
                }
            }) } catch (_: Exception) {
                calls.remove(call); finished.complete(Unit)
                if (continuation.isActive) continuation.resumeWithException(ScheduleException(ScheduleProblem.CONNECTION))
            }
        } } finally {
            // Cancellation of the coroutine is only a request to stop native HTTP work.
            withContext(NonCancellable) { finished.await() }
        }
    } ?: throw ScheduleException(ScheduleProblem.CONNECTION)

    private suspend fun <T> inSession(action: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext()[Job]!!
        synchronized(operations) {
            if (closed.get()) throw CancellationException("Schedule session closed")
            operations += operation
        }
        try { action() } finally { synchronized(operations) { operations -= operation } }
    }

    override fun close() {
        // Registration and closing share a lock: no new DNS/resource operation can escape cleanup.
        val pending = synchronized(operations) { closed.set(true); operations.toList() }
        pageToken = null; form = null; submittedUsername = null; verifiedIdentity = null
        resources.clear(); cookies.clear()
        pending.forEach { it.cancel() }
        calls.forEach { it.cancel() }
    }

    override suspend fun awaitClosed() {
        val pending = synchronized(operations) { check(closed.get()); operations.toList() }
        pending.joinAll()
    }
}
