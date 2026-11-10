package io.github.tbeimu.gongxiaozhu

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class ScheduleImportUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val c = InstrumentationRegistry.getInstrumentation().targetContext
            check(c.packageName.endsWith(".debug")); createAccountRepository(c).reset(); createScheduleRepository(c).clear()
        }
        override fun after() {
            val c = InstrumentationRegistry.getInstrumentation().targetContext
            createScheduleRepository(c).clear(); createAccountRepository(c).reset()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun userWebLoginImportsSemesterAndReturnsToNativeTableWithoutSavingPassword() {
        val model = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
        compose.waitUntil(10_000) { !model.state.value.schedule.loading && model.state.value.schedule.networkKind != null }
        var submits = 0; var closed = false
        val fake = object : ScheduleSession {
            override suspend fun open(): ScheduleGate {
                val html = """<html><form id="loginForm" method="post"><input name="username"><input name="password" type="password"><button>登录</button></form></html>"""
                return ScheduleGate.Login(ScheduleLoginPage("mock-token", "$CAS_ORIGIN/login", ScheduleWebDocument.render(html, "$CAS_ORIGIN/login".toHttpUrl(), "mock-token")))
            }
            override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate {
                submits++; assertEquals("mock-token", token)
                assertEquals("fake-entered-password", values["password"])
                return ScheduleGate.Ready(ScheduleIdentity("graduate-fixture", "虚构学生"), ScheduleTerm("2026-2027", "1", 1, 1))
            }
            override suspend fun week(term: ScheduleTerm, number: Int) = ScheduleWeek(1,
                (0L..6L).map { java.time.LocalDate.of(2026, 11, 9).plusDays(it).toString() }, emptyList())
            override suspend fun resource(token: String, url: String): ScheduleResource? = null
            override fun close() { closed = true }
        }
        compose.runOnUiThread {
            model.scheduleController.networkChanged(ScheduleConnection("fake-mobile-route", ScheduleNetworkKind.CELLULAR) { fake })
        }
        compose.onNodeWithText("导入研究生课表", substring = false).performClick()
        compose.onNodeWithText("学校统一身份认证").assertExists()
        assertEquals(0, submits)
        val ready = java.util.concurrent.atomic.AtomicReference<String?>(null)
        compose.waitUntil(5_000) {
            compose.runOnUiThread { webViews().singleOrNull()?.evaluateJavascript("document.querySelectorAll('input').length", ready::set) }
            ready.get() == "2"
        }
        compose.runOnUiThread { webViews().single().evaluateJavascript("document.querySelector('[name=username]').value='graduate-fixture';document.querySelector('[name=password]').value='fake-entered-password';document.querySelector('form').requestSubmit();", null) }
        compose.waitUntil(10_000) { model.state.value.schedule.data.complete != null && !model.state.value.schedule.busy }
        compose.onNodeWithText("学校统一身份认证").assertDoesNotExist()
        compose.onNodeWithTag("schedule_pager").assertExists()
        assertEquals(1, submits); assertTrue(closed)
        val cache = AndroidScheduleStore(compose.activity).read()!!
        assertFalse(cache.decodeToString().contains("fake-entered-password"))
    }
    private fun webViews(): List<WebView> {
        fun collect(view: View): List<WebView> = when (view) {
            is WebView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }
        return WindowInspector.getGlobalWindowViews().flatMap(::collect)
    }
}
