package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Uses a WiFi-bound client. Never persist cookies, follow a foreign origin or retry credentials. */
class HttpBillingSession(boundClient: OkHttpClient) : BillingSession {
    private val client = boundClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private val cookies = MemoryCookies()
    private var pending: SelfLoginForm? = null
    private var image: ByteArray? = null
    override val captcha: ByteArray? get() = image

    override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot = withContext(Dispatchers.IO) {
        val submitted = code != null && pending != null
        var page = if (submitted) {
            verify(credentials, pending!!, code)
        } else fetchPage(request(DASHBOARD))
        if (!submitted && BillingParser.isLogin(page)) {
            val form = BillingParser.loginForm(page)
            if (form.captchaRequired) needCaptcha(form)
            page = verify(credentials, form, null)
        }
        if (BillingParser.isLogin(page)) {
            val form = BillingParser.loginForm(page)
            if (form.captchaRequired) needCaptcha(form)
            throw BillingException(BillingProblem.REJECTED)
        }
        pending = null
        image = null
        var notice: String? = null
        // The school's dashboard also requests a server-side account refresh using this token.
        val refreshScript = org.jsoup.Jsoup.parse(page).select("script:not([src])")
            .map { it.data() }.firstOrNull { it.contains("/Self/dashboard/refreshaccount") }
        val start = refreshScript?.indexOf("/Self/dashboard/refreshaccount") ?: -1
        val csrf = if (start >= 0) Regex("csrftoken\\s*:\\s*['\"]([^'\"]+)['\"]")
            .find(refreshScript!!.substring(start).take(1000))?.groupValues?.get(1) else null
        if (!csrf.isNullOrBlank() && csrf.length <= 256) {
            try {
                val url = (ORIGIN + "/Self/dashboard/refreshaccount").toHttpUrl().newBuilder()
                    .addQueryParameter("csrftoken", csrf).addQueryParameter("t", System.nanoTime().toString()).build()
                authenticated(url)
                page = fetchPage(request(DASHBOARD))
                if (BillingParser.isLogin(page)) throw BillingException(BillingProblem.EXPIRED)
            } catch (error: CancellationException) { throw error }
            catch (_: BillingException) { notice = "账户刷新未确认，当前显示学校网页返回的数据" }
        }
        val metrics = BillingParser.metrics(page)
        var deviceError: String? = null
        val devices = try { BillingParser.devices(authenticated(ONLINE.toHttpUrl())) }
        catch (error: CancellationException) { throw error }
        catch (error: BillingException) { deviceError = "在线设备读取失败：${error.problem.hint}"; emptyList() }
        BillingSnapshot(metrics, devices, deviceError, notice)
    }

    private suspend fun verify(credentials: AccountDraft, form: SelfLoginForm, code: String?): String {
        val action = DASHBOARD.toHttpUrl().resolve(form.action) ?: throw BillingException(BillingProblem.REDIRECT)
        validateOrigin(action)
        if (action.query != null || action.fragment != null ||
            !Regex("/Self/login/verify(?:;jsessionid=[a-zA-Z0-9]+)?").matches(action.encodedPath)) {
            throw BillingException(BillingProblem.REDIRECT)
        }
        val body = FormBody.Builder(Charsets.UTF_8).add("account", credentials.username)
            .add("password", credentials.password).add("checkcode", form.checkcode)
            .add("code", code.orEmpty()).add("foo", "").add("bar", "").build()
        return fetchPage(request(action.toString()).post(body).build())
    }

    private suspend fun needCaptcha(form: SelfLoginForm): Nothing {
        pending = form
        val response = exchange(request(ORIGIN + "/Self/login/randomCode?t=" + System.nanoTime()).build())
        if (response.code != 200 || !response.type.startsWith("image/")) throw BillingException(BillingProblem.RESPONSE)
        image = response.bytes
        throw BillingException(BillingProblem.CAPTCHA)
    }

    override suspend fun offlineTip(): String = withContext(Dispatchers.IO) {
        BillingParser.tip(authenticated((ORIGIN + "/Self/dashboard/getOfflineTip").toHttpUrl()))
    }

    override suspend fun offline(device: OnlineDevice) = withContext(Dispatchers.IO) {
        // This state-changing GET is the school's actual protocol. No retry or redirect is permitted.
        val url = (ORIGIN + "/Self/dashboard/tooffline").toHttpUrl().newBuilder()
            .addQueryParameter("sessionid", device.sessionId).addQueryParameter("ip", device.ip)
            .addQueryParameter("mac", device.mac.replace("-", "")).build()
        if (!BillingParser.offlineSuccess(authenticated(url))) throw BillingException(BillingProblem.OFFLINE)
    }

    private suspend fun authenticated(url: HttpUrl): String {
        val response = exchange(request(url.toString()).build())
        if (response.code in 300..399) throw BillingException(BillingProblem.EXPIRED)
        if (response.code !in 200..299) throw BillingException(BillingProblem.CONNECTION)
        val text = response.text()
        if (BillingParser.isLogin(text)) throw BillingException(BillingProblem.EXPIRED)
        return text
    }

    private suspend fun fetchPage(builder: Request.Builder): String = fetchPage(builder.build())
    private suspend fun fetchPage(initial: Request): String {
        var next = initial
        repeat(5) {
            val response = exchange(next)
            if (response.code in 300..399) {
                val location = response.location?.takeIf { it.isNotBlank() }
                    ?: throw BillingException(BillingProblem.REDIRECT)
                val target = next.url.resolve(location) ?: throw BillingException(BillingProblem.REDIRECT)
                validateOrigin(target)
                val path = target.encodedPath.substringBefore(";jsessionid=")
                val loginPage = path == "/Self/login" || path == "/Self/login/"
                // The school's unauthenticated dashboard redirects to /Self/login/?302=LI.
                // Accept only that observed marker; arbitrary query parameters stay blocked.
                val knownQuery = target.query == null || (loginPage && target.querySize == 1 &&
                    target.queryParameterNames == setOf("302") && target.queryParameter("302") == "LI")
                if ((!loginPage && path !in listOf("/Self/", "/Self/dashboard")) ||
                    !knownQuery || target.fragment != null ||
                    (next.method == "POST" && response.code !in listOf(302, 303))) {
                    throw BillingException(BillingProblem.REDIRECT)
                }
                next = request(target.toString()).build()
            } else {
                if (response.code !in 200..299) throw BillingException(BillingProblem.CONNECTION)
                return response.text()
            }
        }
        throw BillingException(BillingProblem.REDIRECT)
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "GongXiaoZhu/0.2 Android").header("Cache-Control", "no-store")
        .header("Referer", DASHBOARD).header("Origin", ORIGIN)

    private suspend fun exchange(request: Request): SelfResponse {
        validateOrigin(request.url)
        val cookieHeader = cookies.header(request.url)
        val outgoing = request.newBuilder().apply {
            if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(outgoing)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BillingException(BillingProblem.CONNECTION))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val body = it.body ?: throw BillingException(BillingProblem.RESPONSE)
                            if (body.contentLength() > MAX_BODY || body.source().request(MAX_BODY + 1)) {
                                throw BillingException(BillingProblem.RESPONSE)
                            }
                            val result = SelfResponse(it.code, it.header("Location"),
                                body.contentType()?.toString().orEmpty(), body.source().readByteArray())
                            if (continuation.isActive) cookies.save(request.url, Cookie.parseAll(request.url, it.headers))
                            result
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        val safe = if (error is BillingException) error else BillingException(BillingProblem.CONNECTION)
                        if (continuation.isActive) continuation.resumeWithException(safe)
                    }
                }
            })
        }
    }

    override fun close() { cookies.close(); pending = null; image = null }

    private class SelfResponse(val code: Int, val location: String?, val type: String, val bytes: ByteArray) {
        fun text() = bytes.toString(Charsets.UTF_8)
        override fun toString() = "SelfResponse(code=$code, body=<redacted>)"
    }

    private class MemoryCookies {
        private var closed = false
        private val values = mutableListOf<Cookie>()
        @Synchronized fun save(url: HttpUrl, incoming: List<Cookie>) {
            if (closed) return
            incoming.filter { it.matches(url) }.forEach { cookie ->
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) values.add(cookie)
            }
            if (values.size > 64) { values.clear(); throw BillingException(BillingProblem.RESPONSE) }
        }
        @Synchronized fun header(url: HttpUrl): String {
            if (closed) throw BillingException(BillingProblem.EXPIRED)
            values.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return values.filter { it.matches(url) }.sortedByDescending { it.path.length }.joinToString("; ") { "${it.name}=${it.value}" }
        }
        @Synchronized fun close() { closed = true; values.clear() }
    }

    private fun validateOrigin(url: HttpUrl) {
        if (url.scheme != "https" || url.host != "jfself.bjut.edu.cn" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) throw BillingException(BillingProblem.REDIRECT)
    }

    private companion object {
        const val ORIGIN = "https://jfself.bjut.edu.cn"
        const val DASHBOARD = "$ORIGIN/Self/dashboard"
        const val ONLINE = "$DASHBOARD/getOnlineList"
        const val MAX_BODY = 1024L * 1024
    }
}
