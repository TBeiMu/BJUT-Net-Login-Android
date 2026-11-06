package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String?,
    val minSdk: Int, val certificates: Set<String>)

class ApkVerifier(private val installedVersion: Long, private val packageName: String,
    private val sdk: Int, private val certificate: String, private val identity: (File) -> ApkIdentity?) {
    suspend fun verify(file: File, manifest: UpdateManifest) = withContext(Dispatchers.IO) {
        if (file.length() != manifest.apkSize) throw UpdateFailure("安装包不完整，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hex() != manifest.apkSha256) throw UpdateFailure("安装包 SHA-256 校验失败，请重新下载")
        val apk = identity(file) ?: throw UpdateFailure("安装包无法解析或签名无效")
        if (apk.packageName != packageName || apk.packageName != manifest.packageName ||
            apk.versionCode != manifest.versionCode || apk.versionCode <= installedVersion ||
            apk.versionName != manifest.versionName || apk.minSdk != manifest.minSdk || apk.minSdk > sdk ||
            apk.certificates != setOf(certificate)) throw UpdateFailure("安装包身份、版本或正式签名不匹配，禁止安装")
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
