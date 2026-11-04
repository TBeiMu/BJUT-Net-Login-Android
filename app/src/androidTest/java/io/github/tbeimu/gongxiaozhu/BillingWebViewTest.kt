package io.github.tbeimu.gongxiaozhu

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import io.github.tbeimu.gongxiaozhu.billing.BillingWebDocument
import io.github.tbeimu.gongxiaozhu.billing.BillingWebPage
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.ui.createBillingWebView
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BillingWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val submission = AtomicReference<List<String>?>(null)
    private val jsResult = AtomicReference<String?>(null)
    private lateinit var view: WebView

    @Test fun loginRendersPrefilledInputsWithoutNetworkAndOnlyUserSubmissionUsesBridge() {
        compose.runOnUiThread {
            assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            val html = """<html><body><form class="v-form" action="/Self/login/verify" method="post">
                <input name="account"><input name="password" type="password"><input name="checkcode" value="demo-check"><input name="code">
                <img id="codeimage"><button type="submit">登录</button></form>
                <script src="/Self/resources/mock-validate.js"></script>
                <script>document.addEventListener('DOMContentLoaded',function(){document.getElementById('codeimage').setAttribute('src','login/randomCode?t=demo');});</script>
                </body></html>""".trimIndent()
            val base = "https://jfself.bjut.edu.cn/Self/login/".toHttpUrl()
            val validation = """document.addEventListener('DOMContentLoaded',function(){
                document.querySelector('form').addEventListener('submit',function(e){
                  e.preventDefault(); this.submit();
                });
            });""".trimIndent()
            val document = BillingWebDocument.create(html, base,
                mapOf("https://jfself.bjut.edu.cn/Self/resources/mock-validate.js" to io.github.tbeimu.gongxiaozhu.billing.BillingAsset("text/javascript", validation.toByteArray())),
                "demo-image".toByteArray(), "demo-token",
                AccountDraft("demo", "模拟", "demo-account", "demo-password"))
            view = createBillingWebView(compose.activity, BillingWebPage("demo-token", base.toString(), document),
                { token, password, code -> submission.set(listOf(token, password, code)) }, { _, _ -> error("Initial image must not refresh") })
            compose.activity.setContentView(view)
        }
        try {
            compose.waitUntil(10_000) {
                compose.runOnUiThread { view.evaluateJavascript("document.readyState === 'complete' && document.querySelector('[name=account]')?.value === 'demo-account'", jsResult::set) }
                jsResult.get() == "true"
            }
            assertNull(submission.get())
            compose.runOnUiThread {
                assertTrue(view.settings.blockNetworkLoads)
                assertFalse(view.settings.domStorageEnabled)
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertFalse(CookieManager.getInstance().acceptCookie())
                assertNull(CookieManager.getInstance().getCookie("https://jfself.bjut.edu.cn"))
                view.evaluateJavascript("GxzBilling.postMessage(JSON.stringify({token:'stale-token',action:'submit',password:'demo',code:''}));", null)
            }
            compose.waitForIdle()
            assertNull(submission.get())
            compose.runOnUiThread {
                view.evaluateJavascript("document.querySelector('[name=password]').value='changed-demo'; document.querySelector('button').click();", null)
            }
            compose.waitUntil(10_000) { submission.get() != null }
            assertEquals(listOf("demo-token", "changed-demo", ""), submission.get())
        } finally { compose.runOnUiThread { view.stopLoading(); view.destroy() } }
    }
}
