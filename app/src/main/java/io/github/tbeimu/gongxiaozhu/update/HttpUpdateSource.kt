package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface UpdateSource {
    suspend fun check(): UpdateManifest
    suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
        saveEtag: (String?) -> Unit, progress: (Long) -> Unit)
}

internal fun updateClientBuilder() = OkHttpClient.Builder()
    .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).proxy(java.net.Proxy.NO_PROXY)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)

class HttpUpdateSource(private val client: OkHttpClient, private val dns: UpdateDns) : UpdateSource {
    override suspend fun check(): UpdateManifest = request(UPDATE_URL) { response ->
        if (response.code != 200) throw UpdateFailure("检查更新失败（HTTP ${response.code}），请稍后手动重试")
        val body = response.body ?: throw UpdateFailure("更新信息为空")
        if (body.contentLength() > 131_072) throw UpdateFailure("更新信息过大，已停止读取")
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(4096)
            while (output.size() <= 131_072) {
                val count = input.read(buffer, 0, minOf(buffer.size, 131_073 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size > 131_072) throw UpdateFailure("更新信息过大，已停止读取")
        parseUpdateManifest(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
        saveEtag: (String?) -> Unit, progress: (Long) -> Unit) {
        val size = file.length()
        if (size == manifest.apkSize) return
        val offset = if (size in 1 until manifest.apkSize && etag != null && !etag.startsWith("W/")) size else 0L
        request(manifest.apkUrl, offset, etag) { response ->
            val append = response.code == 206 && offset > 0
            if (response.code != 200 && !append) throw UpdateFailure("下载暂停（HTTP ${response.code}），请手动重试")
            if (append) {
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                val values = match?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                if (values != listOf(offset, manifest.apkSize - 1, manifest.apkSize) ||
                    (response.header("ETag") != null && response.header("ETag") != etag)) {
                    throw UpdateFailure("续传范围或文件标识异常，请取消后重新下载")
                }
            }
            val start = if (append) offset else 0L
            val body = response.body ?: throw UpdateFailure("下载响应为空")
            if (body.contentLength() >= 0 && body.contentLength() != manifest.apkSize - start) {
                throw UpdateFailure("安装包长度与更新信息不一致")
            }
            // Truncate first: never persist a new validator alongside bytes from the old entity.
            FileOutputStream(file, append).use { output ->
                saveEtag(response.header("ETag")?.takeIf { it.length <= 512 && !it.startsWith("W/") })
                var written = start
                val buffer = ByteArray(32 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (written + count > manifest.apkSize) throw UpdateFailure("安装包超过预期大小，已停止下载")
                        output.write(buffer, 0, count)
                        written += count
                        progress(written)
                    }
                }
                output.fd.sync()
                if (written != manifest.apkSize) throw UpdateFailure("下载未完成，请手动继续")
            }
        }
    }

    private suspend fun <T> request(address: String, offset: Long = 0, etag: String? = null,
        read: (Response) -> T): T {
        val initial = updateHttpUrl(address)
        var url: HttpUrl = initial
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            if (!allowedUpdateUrl(url, initial)) throw UpdateFailure("更新地址不受信任，已停止请求")
            val host = url.host
            val addresses = withTimeoutOrNull(15_000) { dns.lookup(host).toList() }
                ?: throw UpdateFailure("WiFi DNS 查询超时，请手动重试")
            if (addresses.isEmpty()) throw UpdateFailure("无法解析更新服务器，请检查 WiFi")
            currentCoroutineContext().ensureActive()
            // OkHttp's synchronous DNS hook only reads the completed result. It never starts another lookup.
            val resolvedClient = client.newBuilder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (hostname != host) throw java.net.UnknownHostException("更新请求域名发生变化")
                    return addresses
                }
            }).build()
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (offset > 0 && etag != null) builder.header("Range", "bytes=$offset-").header("If-Range", etag)
            val call = resolvedClient.newCall(builder.build())
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    // Cancel the call and wait for its blocking IO to finish before reusing the file.
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (!completed.get()) call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                if (hop == 5) throw UpdateFailure("更新地址跳转次数过多")
                                url = response.header("Location")?.let { url.resolve(it) }
                                    ?: throw UpdateFailure("更新地址跳转无效")
                                null
                            } else Result.success(read(response))
                        }
                    } finally { completed.set(true); cancellation.cancel() }
                }
            }
            if (result != null) return result.getOrThrow()
        }
        throw UpdateFailure("更新地址跳转次数过多")
    }
}
