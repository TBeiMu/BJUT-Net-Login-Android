package io.github.tbeimu.gongxiaozhu.network

import java.io.IOException
import java.nio.charset.Charset
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Fixed origins, ports, paths and methods; credentials cannot select a destination. */
enum class PortalEndpoint(val url: String, val post: Boolean = false) {
    PUBLIC_STATUS("http://10.21.251.3/drcom/chkstatus"),
    PUBLIC_LOGIN("http://10.21.251.3/drcom/login"),
    DORM_STATUS("http://10.21.221.98/drcom/chkstatus"),
    DORM_LOGIN("http://10.21.221.98:801/eportal/"),
    IPV4_STATUS("http://172.30.201.2/"),
    IPV4_LOGIN("http://172.30.201.2/", post = true),
}

class PortalRequest(val endpoint: PortalEndpoint, val parameters: List<Pair<String, String>> = emptyList()) {
    override fun toString() = "PortalRequest(endpoint=$endpoint, parameters=<redacted>)"
}

fun interface PortalTransport {
    suspend fun execute(request: PortalRequest): String
}

enum class PortalProblem(val hint: String) {
    CONNECTION("认证地址无法连接或请求超时，请确认连接的是校园 WiFi 后手动重试"),
    RESPONSE("认证响应无法识别，接口可能发生变化，请停止重试并记录网络类型"),
    REDIRECT("认证地址返回跳转，已停止请求；请确认网络类型或使用学校登录页面"),
    REJECTED("认证未通过，请检查默认账号、密码或账号状态后手动重试"),
    UNCONFIRMED("认证已提交，但状态未确认，请稍后手动重试检查状态"),
}

class PortalException(val problem: PortalProblem) : Exception(problem.hint)

internal fun portalClientBuilder() = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(8, TimeUnit.SECONDS)

/** Async calls are cancelled by foreground/network lifecycle cancellation. No HTTP logging. */
class HttpPortalTransport(private val calls: Call.Factory) : PortalTransport {
    override suspend fun execute(request: PortalRequest): String = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().header("User-Agent", "GongXiaoZhu/0.1 Android")
            .header("Cache-Control", "no-store")
        if (request.endpoint.post) {
            val form = FormBody.Builder(Charsets.UTF_8).apply {
                request.parameters.forEach { (key, value) -> add(key, value) }
            }.build()
            builder.url(request.endpoint.url).post(form)
        } else {
            val url = request.endpoint.url.toHttpUrl().newBuilder().apply {
                request.parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            builder.url(url)
        }
        val call = calls.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PortalException(PortalProblem.CONNECTION))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (it.code in 300..399) throw PortalException(PortalProblem.REDIRECT)
                        if (!it.isSuccessful) throw PortalException(PortalProblem.CONNECTION)
                        val body = it.body ?: throw PortalException(PortalProblem.RESPONSE)
                        val source = body.source()
                        if (body.contentLength() > MAX_BODY || source.request(MAX_BODY + 1)) {
                            throw PortalException(PortalProblem.RESPONSE)
                        }
                        val bytes = source.readByteArray()
                        // Old lgn pages may declare GB2312/GBK in HTML instead of headers.
                        val headerCharset = body.contentType()?.charset()
                        val meta = Regex("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE)
                            .find(bytes.toString(Charsets.ISO_8859_1).take(4096))?.groupValues?.get(1)
                        val charset = headerCharset ?: meta?.let { name ->
                            runCatching { Charset.forName(name) }.getOrNull()
                        } ?: Charsets.UTF_8
                        bytes.toString(charset)
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    val safe = when (error) {
                        is PortalException -> error
                        is IOException -> PortalException(PortalProblem.CONNECTION)
                        else -> PortalException(PortalProblem.RESPONSE)
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }

    private companion object { const val MAX_BODY = 128L * 1024 }
}
