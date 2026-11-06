package io.github.tbeimu.gongxiaozhu

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tbeimu.gongxiaozhu.billing.BillingWebDocument
import io.github.tbeimu.gongxiaozhu.billing.BillingWebPage
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.ui.BillingWebDialog
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillingWebDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun page(token: String, captchaAttributes: String = "class=\"form-group\""): BillingWebPage {
        val base = "https://jfself.bjut.edu.cn/Self/login/".toHttpUrl()
        // Synthetic school behavior: a successful validation disables the submit button.
        val html = """<html><body><form class="v-form" action="/Self/login/verify" method="post">
            <input name="account"><input name="password" type="password"><input name="checkcode" value="demo">
            <div id="randomDiv" $captchaAttributes><input name="code"></div>
            <button type="submit" name="submit">登录</button></form><p id="page-marker">$token</p>
            <script>document.addEventListener('DOMContentLoaded',function(){
              document.querySelector('form').addEventListener('submit',function(){
                document.querySelector('[name=submit]').disabled=true;
              });
            });</script></body></html>""".trimIndent()
        return BillingWebPage(token, base.toString(), BillingWebDocument.create(html, base, emptyMap(),
            "demo-image".toByteArray(), token, AccountDraft("demo", "模拟", "demo-account", "demo-password")))
    }

    private fun find(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { find(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun mountedWebView(): WebView {
        val result = AtomicReference<WebView?>()
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                result.set(WindowInspector.getGlobalWindowViews().flatMap(::find).firstOrNull { it.isAttachedToWindow })
            }
            result.get() != null
        }
        return result.get()!!
    }

    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference<String?>()
        compose.runOnUiThread { view.evaluateJavascript(script, result::set) }
        compose.waitUntil(10_000) { result.get() != null }
        return result.get()!!
    }

    private fun waitForPage(view: WebView, token: String) {
        compose.waitUntil(10_000) {
            evaluate(view, "document.readyState === 'complete' && document.querySelector('[name=account]')?.value === 'demo-account' && document.getElementById('page-marker')?.textContent === '$token'") == "true"
        }
    }

    @Test fun returnedLoginPageReplacesWebViewAndSubmitsWithNewToken() {
        val currentPage = mutableStateOf(page("demo-page-one", "class=\"form-group hide\""))
        val busy = mutableStateOf(false)
        val submitted = AtomicReference<List<String>?>(null)
        compose.setContent {
            GongXiaoZhuTheme {
                BillingWebDialog(currentPage.value, busy.value, "模拟登录", { token, password, code ->
                    submitted.set(listOf(token, password, code))
                }, { _, _ -> }, {}, {})
            }
        }
        val first = mountedWebView()
        waitForPage(first, "demo-page-one")
        compose.runOnUiThread { busy.value = true }
        compose.waitForIdle()
        assertSame(first, mountedWebView())
        compose.runOnUiThread {
            currentPage.value = page("demo-page-two", "class=\"form-group hide\"")
            busy.value = false
        }
        compose.waitForIdle()
        val next = mountedWebView()
        assertNotSame(first, next)
        compose.runOnUiThread { assertFalse(first.isAttachedToWindow) }
        waitForPage(next, "demo-page-two")
        assertNull(submitted.get())
        evaluate(next, "document.querySelector('button').click(); true")
        compose.waitUntil(10_000) { submitted.get() != null }
        assertEquals(listOf("demo-page-two", "demo-password", ""), submitted.get())
    }

    @Test fun incompleteCaptchaLeavesButtonUsableAndCorrectedInputSubmitsOnlyOnce() {
        val requests = AtomicInteger()
        val submitted = AtomicReference<List<String>?>(null)
        compose.setContent {
            GongXiaoZhuTheme {
                BillingWebDialog(page("demo-captcha"), false, "模拟登录", { token, password, code ->
                    requests.incrementAndGet(); submitted.set(listOf(token, password, code))
                }, { _, _ -> }, {}, {})
            }
        }
        val view = mountedWebView()
        waitForPage(view, "demo-captcha")
        for (code in listOf("", "12")) {
            assertEquals("false", evaluate(view, "document.querySelector('[name=code]').value='$code'; document.querySelector('button').click(); document.querySelector('button').disabled"))
            assertEquals(0, requests.get())
            assertEquals("true", evaluate(view, "document.getElementById('gxz-login-error').textContent === '请填写四位验证码'"))
        }
        // A direct form.submit() call is also guarded and restores a button disabled by page code.
        assertEquals("false", evaluate(view, "document.querySelector('button').disabled=true; HTMLFormElement.prototype.submit.call(document.querySelector('form')); document.querySelector('button').disabled"))
        assertEquals(0, requests.get())
        evaluate(view, "document.querySelector('[name=code]').value='1234'; document.querySelector('button').click(); true")
        compose.waitUntil(10_000) { requests.get() == 1 }
        assertEquals(listOf("demo-captcha", "demo-password", "1234"), submitted.get())
        assertEquals("true", evaluate(view, "HTMLFormElement.prototype.submit.call(document.querySelector('form')); document.getElementById('gxz-login-error').textContent === ''"))
        compose.waitForIdle()
        assertEquals(1, requests.get())
    }

    @Test fun hiddenCaptchaDoesNotRequireFourCharacters() {
        val submitted = AtomicReference<List<String>?>(null)
        compose.setContent {
            GongXiaoZhuTheme {
                BillingWebDialog(page("demo-hidden", "style=\"display:none\""), false, "模拟登录", { token, password, code ->
                    submitted.set(listOf(token, password, code))
                }, { _, _ -> }, {}, {})
            }
        }
        val view = mountedWebView()
        waitForPage(view, "demo-hidden")
        evaluate(view, "document.querySelector('button').click(); true")
        compose.waitUntil(10_000) { submitted.get() != null }
        assertEquals(listOf("demo-hidden", "demo-password", ""), submitted.get())
    }
}
