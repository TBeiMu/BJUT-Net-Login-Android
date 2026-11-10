package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.ScheduleScreen
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class ScheduleDateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val date = AtomicReference(monday)

    private fun showSchedule() {
        val identity = ScheduleIdentity("fake-student")
        val term = ScheduleTerm("2026-2027", "1", 2, 1)
        val weeks = (1..2).map { n -> ScheduleWeek(n, (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }, emptyList()) }
        val state = ScheduleUiState(data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, term, weeks, 1)), loading = false)
        compose.setContent {
            GongXiaoZhuTheme {
                ScheduleScreen(state, Modifier, {}, {}, {}, {}, todayProvider = date::get)
            }
        }
        compose.onNodeWithText("第 1 周").assertExists()
    }

    @Test fun returningToForegroundRefreshesDateWithoutReimporting() {
        showSchedule()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(1))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("回到本周", substring = false).performClick()
        compose.onNodeWithText("第 2 周").assertExists()
    }

    @Test fun thisWeekRechecksClockAtClickTime() {
        showSchedule()
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("week_choice_2").performClick()
        date.set(monday.plusWeeks(1))
        compose.onNodeWithText("回到本周", substring = false).performClick()
        compose.onNodeWithText("第 2 周").assertExists()
    }

    @Test fun semesterBoundaryUpdatesDisabledThisWeekButtonOnResume() {
        showSchedule()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(3))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("回到本周", substring = false).assertDoesNotExist()
        compose.onNodeWithText("第 1 周").assertExists()
    }
}
