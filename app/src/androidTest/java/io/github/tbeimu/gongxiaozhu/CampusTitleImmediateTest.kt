package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.ScheduleScreen
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusTitleImmediateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val running = LoginUiState(running = true, wifiConnected = true, taskId = "fictional-immediate-task")
    private fun show(notice: CampusConnectionNotice) {
        val identity = ScheduleIdentity("fictional-title-student")
        val week = ScheduleWeek(1, (0L..6L).map { monday.plusDays(it).toString() }, emptyList())
        val state = ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", 1, 1), listOf(week), 1)))
        compose.setContent { GongXiaoZhuTheme {
            ScheduleScreen(state, Modifier.fillMaxSize(), {}, {}, {}, {}, todayProvider = { monday }, connectionNotice = notice)
        } }
    }

    @Test fun confirmedNoWifiShowsOneShortTitleWithoutStartingAuthentication() = runTest {
        var calls = 0; var reads = 0; var services = 0
        val notice = CampusConnectionNotice(backgroundScope)
        val controller = CampusLoginController(backgroundScope,
            CampusAuthenticator { _, _, _, _ -> calls++; error("must not authenticate") },
            { reads++; null }, { services++ }, {})
        backgroundScope.launch { controller.state.collect(notice::update) }
        show(notice)
        compose.runOnUiThread {
            controller.enterForeground(); controller.configure(true, true, NetworkMode.AUTO, "fictional-account")
        }
        runCurrent(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        val bounds = compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread { controller.wifiChanged(null) }
        runCurrent(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("未连接WiFi")
        compose.onNodeWithText("正在登录校园网").assertDoesNotExist()
        compose.runOnUiThread { repeat(3) { controller.login() } }
        runCurrent(); compose.waitForIdle()
        assertEquals(0, services); assertEquals(0, calls); assertEquals(0, reads)
        advanceTimeBy(1000); runCurrent(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        assertEquals(bounds, compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot)
        advanceTimeBy(5000); runCurrent(); compose.waitForIdle()
        compose.onNodeWithText("登录超时").assertDoesNotExist()
    }

    @Test fun successReplacesConnectingInOneFrameAndPreventsTheScheduledTimeout() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        show(notice)
        compose.runOnUiThread { notice.update(running) }; runCurrent()
        compose.onNodeWithText("正在登录校园网").assertIsDisplayed()
        advanceTimeBy(3499); runCurrent()
        compose.onNodeWithText("登录超时").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { notice.update(running.copy(completed = true, outcome = LoginTaskOutcome.SUCCESS)) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); runCurrent()
        compose.onNodeWithText("校园网已连接").assertIsDisplayed()
        compose.onNodeWithText("正在登录校园网").assertDoesNotExist()
        compose.onNodeWithText("登录超时").assertDoesNotExist()
        advanceTimeBy(1); runCurrent(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("校园网已连接").assertIsDisplayed()
        advanceTimeBy(1999); runCurrent(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.mainClock.autoAdvance = true
    }

    @Test fun timeoutStartsAt3500AndSuccessImmediatelyInterruptsItWithoutAWeekFlash() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        show(notice)
        compose.runOnUiThread { notice.update(running) }; runCurrent()
        advanceTimeBy(3499); runCurrent(); compose.waitForIdle()
        compose.onNodeWithText("正在登录校园网").assertIsDisplayed()
        advanceTimeBy(1); runCurrent(); compose.waitForIdle()
        compose.onNodeWithText("登录超时").assertIsDisplayed()
        advanceTimeBy(100); runCurrent()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { notice.update(running.copy(completed = true, outcome = LoginTaskOutcome.SUCCESS)) }
        runCurrent(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("校园网已连接").assertIsDisplayed()
        compose.onNodeWithText("登录超时").assertDoesNotExist()
        compose.onNodeWithText("正在登录校园网").assertDoesNotExist()
        compose.onNodeWithText("第 1 周").assertDoesNotExist()
        advanceTimeBy(900); runCurrent(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("校园网已连接").assertIsDisplayed()
        advanceTimeBy(1100); runCurrent(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.mainClock.autoAdvance = true
    }
}
