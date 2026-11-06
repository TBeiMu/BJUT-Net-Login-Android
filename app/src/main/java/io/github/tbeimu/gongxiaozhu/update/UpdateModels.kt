package io.github.tbeimu.gongxiaozhu.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val RELEASES_URL = "https://github.com/TBeiMu/BJUT-Net-Login-Android/releases"
internal const val UPDATE_URL = "$RELEASES_URL/latest/download/update.json"
internal val updateJson = Json { ignoreUnknownKeys = true }

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val minSdk: Int,
    val apkSize: Long,
    val apkSha256: String,
    val releaseNotes: String,
) {
    fun validate(): UpdateManifest {
        if (schemaVersion != 1 || !Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(versionName) ||
            versionName.length > 40 || versionCode !in 1..Int.MAX_VALUE.toLong() ||
            packageName != "io.github.tbeimu.gongxiaozhu" || minSdk !in 31..100 ||
            apkSize !in 1..(128L * 1024 * 1024) || !Regex("[a-f0-9]{64}").matches(apkSha256) ||
            releaseNotes.length > 32_768) throw UpdateFailure("更新信息格式不受支持，请打开发布页查看")
        return this
    }
    val apkUrl: String get() = "$RELEASES_URL/download/v$versionName/gongxiaozhu.apk"
}

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, PAUSED, VERIFYING, READY, ERROR }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val bytes: Long = 0,
    val message: String = "点击检查更新，仅使用 WiFi",
    val enabled: Boolean = true,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

class UpdateFailure(message: String) : Exception(message)

/** Every redirect is checked before a socket is opened. No server-provided arbitrary APK URL. */
internal fun allowedUpdateUrl(url: HttpUrl, initial: HttpUrl): Boolean {
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.fragment != null) return false
    if (url.host == "release-assets.githubusercontent.com") return true
    if (url.host != "github.com" || url.query != null) return false
    if (url == initial) return true
    return initial.toString() == UPDATE_URL &&
        Regex("/TBeiMu/BJUT-Net-Login-Android/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/update\\.json")
            .matches(url.encodedPath)
}

internal fun parseUpdateManifest(text: String): UpdateManifest = try {
    updateJson.decodeFromString<UpdateManifest>(text).validate()
} catch (error: UpdateFailure) { throw error
} catch (_: Exception) { throw UpdateFailure("更新信息无法识别，请打开发布页查看") }

internal fun updateHttpUrl(value: String) = value.toHttpUrl()
