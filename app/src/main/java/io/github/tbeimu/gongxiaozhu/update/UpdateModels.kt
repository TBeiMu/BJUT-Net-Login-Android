package io.github.tbeimu.gongxiaozhu.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val RELEASES_URL = "https://github.com/TBeiMu/BJUT_gxz/releases"
internal const val LEGACY_RELEASES_URL = "https://github.com/TBeiMu/BJUT-Net-Login-Android/releases"
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

enum class UpdateNetworkKind(val title: String) { WIFI("WiFi"), CELLULAR("移动数据") }

data class MobileDownloadConfirmation(val id: String, val versionName: String, val apkBytes: Long)
data class UpdatePrompt(val id: String, val manifest: UpdateManifest, val error: String? = null)
data class UpdateCheckNotice(val id: String, val message: String, val canOpenReleasePage: Boolean = false)

internal data class UpdateRoute<T>(val id: T, val kind: UpdateNetworkKind, val validated: Boolean)

/** Prefer working WiFi, then working mobile data. Unvalidated networks remain a last resort. */
internal fun <T> selectUpdateRoute(routes: List<UpdateRoute<T>>, active: T?): T? = routes.minWithOrNull(
    compareBy<UpdateRoute<T>> { if (it.validated) 0 else 1 }
        .thenBy { if (it.kind == UpdateNetworkKind.WIFI) 0 else 1 }
        .thenBy { if (it.id == active) 0 else 1 }
)?.id

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val bytes: Long = 0,
    val message: String = "点击检查更新；使用流量下载前会询问",
    val enabled: Boolean = true,
    val networkKind: UpdateNetworkKind? = null,
    val mobileConfirmation: MobileDownloadConfirmation? = null,
    val downloadStarting: Boolean = false,
    val prompt: UpdatePrompt? = null,
    val checkNotice: UpdateCheckNotice? = null,
) {
    val busy: Boolean get() = downloadStarting || mobileConfirmation != null ||
        phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

class UpdateFailure(message: String) : Exception(message)

/** Every redirect is checked before a socket is opened. No server-provided arbitrary APK URL. */
internal fun allowedUpdateUrl(url: HttpUrl, initial: HttpUrl): Boolean {
    val initialPath = trustedReleasePath(initial) ?: return false
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.fragment != null) return false
    if (url.host == "release-assets.githubusercontent.com") return true
    val path = trustedReleasePath(url) ?: return false
    // Repository rename may change only the repository segment, never an APK's tag or name.
    if (path == initialPath) return true
    return initialPath == "/latest/download/update.json" &&
        Regex("/download/v[0-9]+\\.[0-9]+\\.[0-9]+/update\\.json").matches(path)
}

private fun trustedReleasePath(url: HttpUrl): String? {
    if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = listOf("/TBeiMu/BJUT_gxz/releases", "/TBeiMu/BJUT-Net-Login-Android/releases")
        .firstOrNull { url.encodedPath.startsWith("$it/") }?.let { url.encodedPath.removePrefix(it) } ?: return null
    return path.takeIf { it == "/latest/download/update.json" ||
        Regex("/download/v[0-9]+\\.[0-9]+\\.[0-9]+/(?:update\\.json|gongxiaozhu\\.apk)").matches(it) }
}

internal fun parseUpdateManifest(text: String): UpdateManifest = try {
    updateJson.decodeFromString<UpdateManifest>(text).validate()
} catch (error: UpdateFailure) { throw error
} catch (_: Exception) { throw UpdateFailure("更新信息无法识别，请打开发布页查看") }

internal fun updateHttpUrl(value: String) = value.toHttpUrl()
