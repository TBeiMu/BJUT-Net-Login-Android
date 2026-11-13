package io.github.tbeimu.gongxiaozhu.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TodayDateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val date = AtomicReference(monday)

    private fun show(): StateRestorationTester {
        val identity = ScheduleIdentity("fictional-student")
        val term = ScheduleTerm("2026-2027", "1", 2, 1)
        val weeks = (1..2).map { n -> ScheduleWeek(n,
            (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }, emptyList()) }
        val state = ScheduleUiState(data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, term, weeks, 1)), loading = false)
        return StateRestorationTester(compose).also { tester ->
            tester.setContent {
                GongXiaoZhuTheme {
                    ScheduleHome(state, Modifier, {}, {}, {}, {}, todayProvider = date::get,
                        nowProvider = { LocalTime.NOON })
                }
            }
            compose.onNodeWithText("今天 · 11月2日 · 第 1 周").assertExists()
        }
    }

    private fun changeDate(day: LocalDate) {
        compose.runOnIdle {
            date.set(day)
            compose.activity.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        }
        compose.waitForIdle()
    }

    @Test fun defaultTodayFollowsMidnightAndWeekBoundary() {
        show()
        changeDate(monday.plusDays(1))
        compose.onNodeWithText("今天 · 11月3日 · 第 1 周").assertExists()
        changeDate(monday.plusWeeks(1))
        compose.onNodeWithText("今天 · 11月9日 · 第 2 周").assertExists()
    }

    @Test fun returnToForegroundFollowsTodayWithoutImporting() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(1))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("今天 · 11月9日 · 第 2 周").assertExists()
    }

    @Test fun manualBrowsingSurvivesDateChangeAndStateRestoration() {
        val restoration = show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        changeDate(monday.plusWeeks(1))
        compose.onNodeWithText("11月5日 · 第 1 周").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("11月5日 · 第 1 周").assertExists()
    }

    @Test fun selectingTodayResumesClockFollowingAfterRestoration() {
        val restoration = show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("agenda_day_0").performClick()
        restoration.emulateSavedInstanceStateRestore()
        changeDate(monday.plusDays(1))
        compose.onNodeWithText("今天 · 11月3日 · 第 1 周").assertExists()
    }

    @Test fun selectingAnAlreadyBrowsedTodayResumesFollowing() {
        show()
        compose.onNodeWithTag("agenda_day_1").performClick()
        changeDate(monday.plusDays(1))
        compose.onNodeWithTag("agenda_day_1").performClick()
        changeDate(monday.plusDays(2))
        compose.onNodeWithText("今天 · 11月4日 · 第 1 周").assertExists()
    }

    @Test fun swipeAfterDateChangeUsesTheNewDay() {
        show()
        changeDate(monday.plusWeeks(1))
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("11月10日 · 第 2 周").assertExists()
    }
}
