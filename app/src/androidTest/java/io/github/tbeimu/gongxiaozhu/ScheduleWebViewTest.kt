package io.github.tbeimu.gongxiaozhu

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.createScheduleWebView
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

class ScheduleWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeResourcesRenderSchoolFrameAndOnlyMainFormCanSubmit() {
        val received = AtomicReference<Map<String, String>?>(null)
        val loaded = CopyOnWriteArrayList<String>()
        val status = AtomicReference<String?>(null)
        lateinit var view: WebView
        val html = """<html><body><form id="loginForm" method="post"><input name="username"><input name="password" type="password"><input name="execution" value="fake-dynamic-value"><button>登录</button></form><iframe src="/cas/fake-login.html"></iframe></body></html>"""
        compose.runOnUiThread {
            val page = ScheduleLoginPage("test-token", "$CAS_ORIGIN/login", ScheduleWebDocument.render(html, "$CAS_ORIGIN/login".toHttpUrl(), "test-token"))
            view = createScheduleWebView(compose.activity, page, { _, fields -> received.set(fields) }) { _, url ->
                loaded += url
                if (url == "$CAS_ORIGIN/cas/fake-login.html") ScheduleResource(ScheduleWebDocument.sanitizeFrame("""<html><body id="waiting">已由原生加载<script>localStorage.setItem('demo','page-only');document.body.id=localStorage.getItem('demo')==='page-only'?'native-frame':'failed';GxzSchedule.postMessage(JSON.stringify({token:'test-token',action:'submit',values:{username:'frame',password:'frame'}}))</script></body></html>""", url.toHttpUrl()).toByteArray(), "text/html") else null
            }
            compose.activity.setContentView(view)
        }
        try {
            compose.waitUntil(10_000) { loaded.any { it.endsWith("/cas/fake-login.html") } }
            compose.waitUntil(5_000) {
                compose.runOnUiThread { view.evaluateJavascript("document.querySelector('iframe').contentDocument.body.id", status::set) }
                status.get() == "\"native-frame\""
            }
            assertNull(received.get())
            compose.runOnUiThread {
                assertTrue(view.settings.blockNetworkLoads)
                assertFalse(view.settings.domStorageEnabled)
                assertFalse(CookieManager.getInstance().acceptCookie())
                view.evaluateJavascript("GxzSchedule.postMessage(JSON.stringify({token:{},action:[]}))", null)
                view.evaluateJavascript("document.querySelector('[name=username]').value='fake-student'; document.querySelector('[name=password]').value='fake-password'; document.querySelector('form').requestSubmit();", null)
            }
            compose.waitUntil(5_000) { received.get() != null }
            assertEquals("fake-student", received.get()!!["username"])
            assertFalse(received.get()!!.containsKey("execution"))
        } finally { compose.runOnUiThread { view.stopLoading(); view.destroy() } }
    }
}
