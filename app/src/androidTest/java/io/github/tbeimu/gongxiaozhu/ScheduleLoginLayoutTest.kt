package io.github.tbeimu.gongxiaozhu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.ScheduleWebDialog
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScheduleLoginLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun schoolFrameFillsDialogAndPaintsAfterLoadingAndResizing() {
        // A standards-mode page with a percentage-height iframe and hidden host form,
        // matching the layout that collapsed in a WRAP_CONTENT native WebView.
        val html = """<!doctype html><html><head><style>
            html,body,iframe{width:100%;height:100%;margin:0;padding:0;border:0;overflow:hidden}
            #loginForm{display:none}
            </style></head><body><form id="loginForm" method="post">
            <input name="username"><input name="password" type="password"><input name="execution" value="fake-field">
            </form><iframe id="loginIframe" src="/cas/layout-fixture.html"></iframe></body></html>"""
        val frame = """<!doctype html><html><body style="margin:0;background:#DDF2FF;padding:16px">
            <label>虚构学校账号<input name="username"></label><p><label>密码<input type="password"></label></p><button>登录</button>
            </body></html>"""
        val base = "$CAS_ORIGIN/login".toHttpUrl()
        val page = ScheduleLoginPage("layout-token", base.toString(), ScheduleWebDocument.render(html, base, "layout-token"))
        val busy = mutableStateOf(false)
        compose.setContent { GongXiaoZhuTheme {
            ScheduleWebDialog(page, busy.value, "虚构页面可见性检查", { _, _ -> }, { _, url ->
                if (url.endsWith("/cas/layout-fixture.html")) ScheduleResource(
                    ScheduleWebDocument.sanitizeFrame(frame, url.toHttpUrl()).toByteArray(), "text/html") else null
            }, {}, {})
        } }
        assertVisibleFrame()
        compose.runOnUiThread { busy.value = true }
        compose.waitForIdle()
        compose.runOnUiThread { busy.value = false }
        assertVisibleFrame()

        val ready = AtomicBoolean(false)
        compose.runOnUiThread { webView().postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { ready.set(true) }
        }) }
        compose.waitUntil(5000) { ready.get() }
        compose.runOnUiThread {
            val view = webView()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                var colored = 0
                for (x in 0 until bitmap.width step 8) for (y in 0 until bitmap.height step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) > 0 && Color.red(pixel) < 235) colored++
                }
                assertTrue("网页应实际绘制，不能只有 DOM 元素而显示空白", colored > 50)
            } finally { bitmap.recycle() }
        }
    }

    private fun assertVisibleFrame() {
        val metrics = AtomicReference<String?>(null)
        compose.waitUntil(5000) {
            compose.runOnUiThread { webView().evaluateJavascript("""(function(){
                const f=document.getElementById('loginIframe');
                const p=f?.contentDocument?.querySelector('input[type=password]');
                return [innerHeight,f?.clientHeight||0,p?.getBoundingClientRect().height||0];
            })()""", metrics::set) }
            val values = runCatching { Json.parseToJsonElement(metrics.get().orEmpty()).jsonArray.map { it.jsonPrimitive.double } }.getOrNull()
            values != null && values[0] > 100 && values[1] >= values[0] * .95 && values[2] > 10
        }
        compose.runOnUiThread { assertTrue(webView().width > 100 && webView().height > 100) }
    }

    private fun webView(): WebView {
        fun collect(view: View): List<WebView> = when (view) {
            is WebView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }
        return WindowInspector.getGlobalWindowViews().flatMap(::collect).single()
    }
}
