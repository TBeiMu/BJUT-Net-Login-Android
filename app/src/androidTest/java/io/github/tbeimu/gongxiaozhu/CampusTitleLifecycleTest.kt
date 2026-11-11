package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.time.LocalDate
import java.time.ZoneId
import java.io.File

/** Full runtime -> title integration with fictional authentication; never sends a password. */
class CampusTitleLifecycleTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val result = CompletableDeferred<LoginResult>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            createAccountRepository(context).apply {
                reset(); load(); save(AccountDraft(label = "虚构账号", username = "fictional-title", password = "fictional-only"))
                settings(AppSettings(autoLogin = false))
            }
            val monday = LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY)
            createScheduleRepository(context).apply {
                clear(); begin(ScheduleIdentity("fictional-student"), ScheduleTerm("2026-2027", "1", 1, 1))
                val dates = (0L..6L).map { monday.plusDays(it).toString() }
                append(ScheduleWeek(1, dates, listOf(ScheduleCourse("fake-course", "虚构课程", dates[0], listOf(11,12)))))
                finish(1)
            }
        }
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val runtime = CampusLoginRuntime.get(context)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runtime.controller.cancel()
                CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }
                    .set(runtime.controller, CampusLogin())
            }
            createScheduleRepository(context).clear(); createAccountRepository(context).reset()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    private fun start(): CampusLoginRuntime {
        val runtime = CampusLoginRuntime.get(compose.activity)
        val model = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
        compose.waitUntil(10_000) { !model.state.value.loading && !model.state.value.schedule.loading &&
            (runtime.connections.value as? WifiObservation.Current)?.connection != null }
        compose.runOnUiThread {
            CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }
                .set(runtime.controller, CampusAuthenticator { _, _, _, _ -> result.await() })
            runtime.controller.login()
        }
        compose.waitUntil(5000) { runtime.controller.state.value.running }
        return runtime
    }

    @Test fun taskProgressAndSuccessReplaceWeekTitleWithoutMovingGrid() {
        start()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("正在登录校园网")
        screenshot("progress")
        val original = compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread { result.complete(LoginResult("虚构联网成功", completed = true)) }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("校园网已连接")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
        assertEquals(original, compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot)
        screenshot("success")
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("schedule_week_title") and hasText("第 1 周")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        screenshot("week")
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithText("课表", substring = false).performClick()
        compose.onNodeWithText("校园网已连接").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
    }

    @Test fun backgroundCompletionWaitsForReturnAndFreshWifiSnapshot() {
        val runtime = start()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { result.complete(LoginResult("虚构后台成功", completed = true)) }
        compose.waitUntil(5000) { !runtime.controller.state.value.running && runtime.connections.value == WifiObservation.Suspended }
        assertNull(runtime.connectionNotice.state.value)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { compose.onAllNodes(hasText("校园网已连接")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("schedule_week_title") and hasText("第 1 周")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
    }

    @Test fun quickFailureRestoresWeekWithoutAFailureTitleOrSuccess() {
        val runtime = start()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("正在登录校园网")
        compose.runOnUiThread { result.complete(LoginResult("虚构认证失败")) }
        compose.waitUntil(5000) { !runtime.controller.state.value.running }
        compose.onNodeWithText("校园网已连接").assertDoesNotExist()
        compose.onNodeWithText("校园网登录失败").assertDoesNotExist()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
    }

    @Test fun threeSecondTimeoutDisappearsAfterOneSecondAndAuthenticationContinues() {
        val runtime = start()
        val original = compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot
        // Rotating during CONNECTING must not restart the three-second wait deadline.
        compose.activityRule.scenario.recreate()
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<CampusTitlePhase?, Long>>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            compose.runOnUiThread {
                scope.launch { runtime.connectionNotice.state.collect { events.add(it?.phase to SystemClock.elapsedRealtime()) } }
            }
            compose.waitUntil(5000) { compose.onAllNodes(hasText("登录超时")).fetchSemanticsNodes().isNotEmpty() }
            screenshot("timeout-short")
            assertTrue(runtime.controller.state.value.running)
            assertNull(runtime.controller.state.value.outcome)
            assertEquals(original, compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot)
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("schedule_week_title") and hasText("第 1 周")).fetchSemanticsNodes().isNotEmpty() }
            val sequence = synchronized(events) { events.dropWhile { it.first != CampusTitlePhase.TIMED_OUT } }
            assertEquals(listOf(CampusTitlePhase.TIMED_OUT, null), sequence.map { it.first })
            assertTrue(sequence[1].second - sequence[0].second in 900..1400)
            assertTrue(runtime.controller.state.value.running)
            compose.runOnUiThread { result.complete(LoginResult("虚构延迟失败")) }
            compose.waitUntil(5000) { !runtime.controller.state.value.running }
            compose.onNodeWithText("校园网登录失败").assertDoesNotExist()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        } finally { scope.cancel() }
    }

    @Test fun successAfterTheShortSequenceHasFinishedStillShowsConnected() {
        val runtime = start()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("登录超时")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("schedule_week_title") and hasText("第 1 周")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runtime.controller.state.value.running); assertNull(runtime.controller.state.value.outcome)
        compose.runOnUiThread { result.complete(LoginResult("虚构延迟成功", completed = true)) }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("校园网已连接")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
        screenshot("late-success")
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("schedule_week_title") and hasText("第 1 周")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val file = File(inst.targetContext.getExternalFilesDir(null), "title-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/gxz-v043-title-$name.png")).use { it.readBytes() }
    }
}
