package io.github.tbeimu.gongxiaozhu.ui

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

@Composable
internal fun ScheduleWebDialog(page: ScheduleLoginPage, busy: Boolean, message: String,
    onSubmit: (String, Map<String, String>) -> Unit, resource: suspend (String, String) -> ScheduleResource?,
    onDismiss: () -> Unit, onUnsupported: () -> Unit) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        DisposableEffect(Unit) { onUnsupported(); onDispose {} }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                TextButton(onClick = onDismiss) { Text("返回并暂停导入") }
                Text("学校统一身份认证", style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                key(page.token) {
                    AndroidView(factory = { createScheduleWebView(it, page, onSubmit, resource) },
                        update = { it.isEnabled = !busy }, modifier = Modifier.weight(1f), onRelease = {
                            it.stopLoading(); it.loadUrl("about:blank"); it.clearHistory(); it.clearCache(true); it.destroy()
                        })
                }
            }
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun createScheduleWebView(context: android.content.Context, page: ScheduleLoginPage,
    onSubmit: (String, Map<String, String>) -> Unit, resource: suspend (String, String) -> ScheduleResource?): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.blockNetworkLoads = true
    settings.domStorageEnabled = false; settings.databaseEnabled = false
    settings.allowContentAccess = false; settings.allowFileAccess = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    isSaveEnabled = false
    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    WebView.setWebContentsDebuggingEnabled(false)
    webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?) = true }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame ||
            request.url.scheme != "https" || request.url.host != "cas.bjut.edu.cn" || !request.url.path.orEmpty().startsWith("/cas/")
        override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) { dontResend.sendToTarget() }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.scheme == "data") return null
            // Called off the UI thread. WebView's own networking remains disabled even if interception fails.
            val result = if (request.method == "GET") runCatching { runBlocking { resource(page.token, request.url.toString()) } }.getOrNull() else null
            return if (result == null) WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            else WebResourceResponse(result.mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to ScheduleWebDocument.POLICY), ByteArrayInputStream(result.bytes))
        }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
    WebViewCompat.addWebMessageListener(this, "GxzSchedule", setOf(CAS_ORIGIN)) { _, msg, origin, main, _ ->
        if (!main || origin.scheme != "https" || origin.host != "cas.bjut.edu.cn" || origin.port !in setOf(-1, 443)) return@addWebMessageListener
        val raw = msg.data ?: return@addWebMessageListener
        if (raw.length > 8192) return@addWebMessageListener
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@addWebMessageListener
        fun scalar(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (scalar("token") != page.token || scalar("action") != "submit") return@addWebMessageListener
        val values = obj["values"] as? JsonObject ?: return@addWebMessageListener
        if (values.size > 12 || values.values.any { it !is JsonPrimitive || it.contentOrNull == null }) return@addWebMessageListener
        onSubmit(page.token, values.mapValues { it.value.jsonPrimitive.content })
    }
    } else { destroy(); throw ScheduleException(ScheduleProblem.WEBVIEW) }
    loadDataWithBaseURL(page.baseUrl, page.html, "text/html", "UTF-8", null)
}
