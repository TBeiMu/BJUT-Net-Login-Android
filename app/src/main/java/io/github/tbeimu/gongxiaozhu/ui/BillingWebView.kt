package io.github.tbeimu.gongxiaozhu.ui

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.tbeimu.gongxiaozhu.billing.BillingWebDocument
import io.github.tbeimu.gongxiaozhu.billing.BillingWebPage
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** No WebView transport: school resources are embedded and all posts go through BillingSession. */
@Composable
internal fun BillingWebDialog(page: BillingWebPage, busy: Boolean, message: String,
    onSubmit: (String, String, String) -> Unit, onCaptcha: (String, (ByteArray) -> Unit) -> Unit,
    onDismiss: () -> Unit, onUnsupported: () -> Unit) {
    val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    if (!supported) {
        DisposableEffect(Unit) { onUnsupported(); onDispose {} }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                TextButton(onClick = onDismiss) { Text("返回资费") }
                Text("学校内置网页登录", style = MaterialTheme.typography.titleLarge)
                Text(if (busy) "正在提交，请勿重复操作…" else message, style = MaterialTheme.typography.bodySmall)
                // The mounted AndroidView and its WebView must share the page's identity.
                key(page.token) {
                    AndroidView(
                        factory = { createBillingWebView(it, page, onSubmit, onCaptcha) },
                        modifier = Modifier.weight(1f),
                        update = { it.isEnabled = !busy },
                        onRelease = { view ->
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            view.clearHistory()
                            view.clearCache(true)
                            view.destroy()
                        },
                    )
                }
            }
        }
    }
}

// Required for the school login validation; networking/storage are disabled and bridge origin is exact.
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // Disable legacy database storage on WebView providers that still support it.
internal fun createBillingWebView(context: android.content.Context, page: BillingWebPage,
    onSubmit: (String, String, String) -> Unit, onCaptcha: (String, (ByteArray) -> Unit) -> Unit): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.blockNetworkLoads = true
    settings.domStorageEnabled = false
    settings.databaseEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    isSaveEnabled = false
    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    WebView.setWebContentsDebuggingEnabled(false)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // loadDataWithBaseURL's inline data and embedded images are not network requests.
            if (request.url.scheme == "data") return null
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) { dontResend.sendToTarget() }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?) = true
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, "GxzBilling", setOf("https://jfself.bjut.edu.cn")) { web, msg, origin, mainFrame, _ ->
            if (!mainFrame || origin.scheme != "https" || origin.host != "jfself.bjut.edu.cn" || (origin.port != -1 && origin.port != 443)) return@addWebMessageListener
            val text = msg.data ?: return@addWebMessageListener
            if (text.length > 4096) return@addWebMessageListener
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@addWebMessageListener
            fun value(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (value("token") != page.token) return@addWebMessageListener
            when (value("action")) {
                "submit" -> onSubmit(page.token, value("password"), value("code"))
                "captcha" -> onCaptcha(page.token) { bytes ->
                    web.post { web.evaluateJavascript("window.gxzSetCaptcha(${Json.encodeToString(BillingWebDocument.dataUrl("image/png", bytes))})", null) }
                }
            }
        }
    } else { destroy(); throw IllegalStateException("系统 WebView 不支持安全网页登录") }
    loadDataWithBaseURL(page.baseUrl, page.html, "text/html", "UTF-8", null)
}
