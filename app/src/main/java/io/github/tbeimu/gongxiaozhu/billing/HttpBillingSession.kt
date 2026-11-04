package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
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
    private var loginPage: SelfPage? = null
    private var webAccount: AccountDraft? = null
    private var webToken: String? = null
    private var webSubmitted = false
    private val events = mutableListOf<BillingDiagnostic>()
    private var image: ByteArray? = null
    override val captcha: ByteArray? get() = image
    override val diagnostics: List<BillingDiagnostic> get() = synchronized(events) { events.toList() }

    override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot = withContext(Dispatchers.IO) {
        synchronized(events) { events.clear() }
        val submitted = code != null && pending != null
        var page = if (submitted) {
            verify(credentials, pending!!, code)
        } else fetchPage(request(DASHBOARD))
        if (!submitted && BillingParser.isLogin(page)) {
            val form = BillingParser.loginForm(page)
            initializeLogin(form)
            if (form.captchaRequired) needCaptcha(form)
            page = verify(credentials, form, null)
        }
        if (BillingParser.isLogin(page)) {
            val form = BillingParser.loginForm(page)
            val problem = BillingParser.loginProblem(page)
            if (problem == BillingProblem.REJECTED) throw BillingException(problem)
            if (form.captchaRequired) {
                initializeLogin(form)
                pending = form
                throw BillingException(if (problem == BillingProblem.CAPTCHA_REJECTED) problem else BillingProblem.CAPTCHA)
            }
            throw BillingException(problem)
        }
        pending = null
        image = null
        readSnapshot(page, credentials.username)
    }

    private suspend fun readSnapshot(initial: String, expected: String): BillingSnapshot {
        BillingParser.requireAccount(initial, expected)
        var page = initial
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
                BillingParser.requireAccount(page, expected)
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) {
                if (error.problem == BillingProblem.ACCOUNT_MISMATCH) throw error
                page = initial
                notice = "账户刷新未确认，当前显示学校网页返回的数据"
            }
        }
        val metrics = BillingParser.metrics(page)
        var deviceError: String? = null
        val devices = try { BillingParser.devices(authenticated(ONLINE.toHttpUrl())) }
        catch (error: CancellationException) { throw error }
        catch (error: BillingException) { deviceError = "在线设备读取失败：${error.problem.hint}"; emptyList() }
        return BillingSnapshot(metrics, devices, deviceError, notice)
    }

    private suspend fun verify(credentials: AccountDraft, form: SelfLoginForm, code: String?): String {
        val source = loginPage?.url ?: throw BillingException(BillingProblem.EXPIRED)
        val action = source.resolve(form.action) ?: throw BillingException(BillingProblem.REDIRECT)
        validateOrigin(action)
        if (action.query != null || action.fragment != null ||
            !Regex("/Self/login/verify(?:;jsessionid=[a-zA-Z0-9]+)?").matches(action.encodedPath)) {
            throw BillingException(BillingProblem.REDIRECT)
        }
        val body = FormBody.Builder(Charsets.UTF_8).add("account", credentials.username)
            .add("password", credentials.password).add("checkcode", form.checkcode)
            .add("code", code.orEmpty()).add("foo", "").add("bar", "").add("submit", "").build()
        return fetchPage(request(action.toString()).header("Referer", source.toString()).header("Origin", ORIGIN).post(body).build())
    }

    private suspend fun initializeLogin(form: SelfLoginForm): Map<String, BillingAsset> {
        val page = loginPage ?: throw BillingException(BillingProblem.EXPIRED)
        val action = page.url.resolve(form.action) ?: throw BillingException(BillingProblem.REDIRECT)
        validateOrigin(action)
        if (action.query != null || action.fragment != null || !Regex("/Self/login/verify(?:;jsessionid=[a-zA-Z0-9]+)?").matches(action.encodedPath)) {
            throw BillingException(BillingProblem.REDIRECT)
        }
        val assets = linkedMapOf<String, BillingAsset>()
        var total = 0L
        for (url in BillingParser.loginAssets(page.html, page.url)) {
            val response = exchange(request(url.toString()).header("Referer", page.url.toString()).build())
            if (response.code == 200) {
                total += response.bytes.size
                if (total > 4 * MAX_BODY) throw BillingException(BillingProblem.RESPONSE)
                assets[url.toString()] = BillingAsset(response.type, response.bytes)
            }
        }
        image = loadCaptcha()
        return assets
    }

    private suspend fun loadCaptcha(): ByteArray {
        val source = loginPage?.url ?: throw BillingException(BillingProblem.EXPIRED)
        val response = exchange(request(ORIGIN + "/Self/login/randomCode?t=0." + System.nanoTime().toString().removePrefix("-"))
            .header("Referer", source.toString()).build())
        if (response.code != 200 || !response.type.startsWith("image/") || response.bytes.size > 128 * 1024) throw BillingException(BillingProblem.RESPONSE)
        return response.bytes
    }

    private suspend fun needCaptcha(form: SelfLoginForm): Nothing {
        pending = form
        throw BillingException(BillingProblem.CAPTCHA)
    }

    override suspend fun openWeb(credentials: AccountDraft): BillingWebPage = withContext(Dispatchers.IO) {
        synchronized(events) { events.clear() }
        cookies.clear()
        fetchPage(request(ORIGIN + "/Self/login/"))
        webAccount = credentials
        webPage(credentials)
    }

    private suspend fun webPage(account: AccountDraft): BillingWebPage {
        val page = loginPage ?: throw BillingException(BillingProblem.RESPONSE)
        val form = BillingParser.loginForm(page.html)
        val assets = initializeLogin(form)
        val token = UUID.randomUUID().toString()
        val html = BillingWebDocument.create(page.html, page.url, assets, image!!, token, account)
        pending = form
        webToken = token
        webSubmitted = false
        image = null
        return BillingWebPage(token, page.url.toString(), html)
    }

    override suspend fun submitWeb(token: String, password: String, code: String): BillingWebResult = withContext(Dispatchers.IO) {
        val account = webAccount ?: throw BillingException(BillingProblem.EXPIRED)
        val form = pending ?: throw BillingException(BillingProblem.EXPIRED)
        if (token != webToken || webSubmitted || password.isEmpty() || password.length > 256 || code.length > 4) throw BillingException(BillingProblem.EXPIRED)
        webSubmitted = true
        val page = verify(account.copy(password = password), form, code)
        if (BillingParser.isLogin(page)) {
            val problem = BillingParser.loginProblem(page)
            return@withContext BillingWebResult.Continue(webPage(account.copy(password = password)), problem)
        }
        val snapshot = readSnapshot(page, account.username)
        pending = null; image = null; webToken = null; webAccount = null
        BillingWebResult.Success(snapshot)
    }

    override suspend fun refreshWebCaptcha(token: String): ByteArray = withContext(Dispatchers.IO) {
        if (token != webToken || webSubmitted) throw BillingException(BillingProblem.EXPIRED)
        loadCaptcha()
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
                val text = response.text()
                if (BillingParser.isLogin(text)) loginPage = SelfPage(next.url, text)
                return text
            }
        }
        throw BillingException(BillingProblem.REDIRECT)
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 GongXiaoZhu")
        .header("Cache-Control", "no-store").header("Referer", DASHBOARD)

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
                if (continuation.isActive) { record(request, null, "连接未完成"); continuation.resumeWithException(BillingException(BillingProblem.CONNECTION)) }
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
                            if (continuation.isActive) {
                                cookies.save(request.url, Cookie.parseAll(request.url, it.headers))
                                record(request, it.code, when {
                                    it.code in 300..399 -> "跳转"
                                    result.type.startsWith("image/") -> "图片"
                                    !result.type.contains("html") -> "资源或数据"
                                    BillingParser.isLogin(result.text()) -> "登录页"
                                    result.text().contains("OnlineLisBig") -> "资费页"
                                    else -> "未知页面"
                                })
                            }
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

    private fun record(request: Request, status: Int?, category: String) = synchronized(events) {
        val stage = when (request.url.encodedPath.substringBefore(";")) {
            "/Self/login/randomCode" -> "初始化验证码会话"
            "/Self/login/verify" -> "提交查询登录"
            "/Self/dashboard/getOnlineList" -> "读取在线设备"
            "/Self/dashboard/tooffline" -> "注销选定设备"
            "/Self/dashboard/refreshaccount" -> "刷新学校账户"
            else -> if (request.url.encodedPath.startsWith("/Self/resources/")) "初始化登录资源" else "读取查询页面"
        }
        if (events.size >= 48) events.removeAt(0)
        events.add(BillingDiagnostic(stage, status, category, cookies.hasSession()))
    }

    override fun close() { cookies.close(); pending = null; image = null; loginPage = null; webAccount = null; webToken = null }

    private class SelfPage(val url: HttpUrl, val html: String)

    private class SelfResponse(val code: Int, val location: String?, val type: String, val bytes: ByteArray) {
        fun text() = bytes.toString(Charsets.UTF_8)
        override fun toString() = "SelfResponse(code=$code, body=<redacted>)"
    }

    private class MemoryCookies {
        private var closed = false
        private val values = mutableListOf<Cookie>()
        @Synchronized fun save(url: HttpUrl, incoming: List<Cookie>) {
            if (closed) return
            incoming.filter { it.domain == url.host || (!it.hostOnly && url.host.endsWith("." + it.domain)) }.forEach { cookie ->
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
        @Synchronized fun clear() { if (closed) throw BillingException(BillingProblem.EXPIRED); values.clear() }
        @Synchronized fun hasSession() = !closed && values.any { it.name == "JSESSIONID" && it.expiresAt > System.currentTimeMillis() }
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
