package io.github.tbeimu.gongxiaozhu

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import java.time.LocalDate
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WeekPagerUiTest {
    @get:Rule val compose = createAndroidComposeRule<WallpaperTestActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val visible = mutableStateOf(true)
    private val unblocked = mutableStateOf(true)
    private val data = mutableStateOf(schedule())

    private fun schedule(count: Int = 4, lastLesson: Int = 12): ScheduleUiState {
        val identity = ScheduleIdentity("fictional-drag-student")
        val weeks = (1..count).map { n ->
            val dates = (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }
            ScheduleWeek(n, dates, listOf(
                ScheduleCourse("first", "第一节测试课程", dates[0], listOf(1, 2), "虚构教室"),
                ScheduleCourse("late", "长名称晚间课程用于验证完整显示", dates[1], listOf(lastLesson), "虚构教室"),
                ScheduleCourse("overlap", "重叠课", dates[1], listOf(lastLesson), "另一教室")))
        }
        return ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", count, 1), weeks, 1)))
    }

    private fun show(fontScale: Float = 1f) {
        compose.runOnUiThread { WallpaperTestActivity.scene = { GongXiaoZhuTheme {
            val density = LocalDensity.current
            val pages = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                if (visible.value) pages.SaveableStateProvider("schedule") { AppBackdrop(WallpaperState()) {
                    ScheduleScreen(data.value, Modifier.fillMaxSize(), {}, {}, {}, {}, todayProvider = { monday },
                        titleUnobstructed = unblocked.value)
                } }
            }
        } } }
        compose.onNodeWithTag("week_grid_1").assertIsDisplayed()
    }

    @After fun cleanup() { compose.mainClock.autoAdvance = true; compose.runOnUiThread { WallpaperTestActivity.scene = null } }

    private fun grid() = compose.onNodeWithTag("week_grid_${selectedWeek()}")
    private fun selectedWeek(): Int = (1..data.value.data.complete!!.term.weekCount).first { n ->
        compose.onAllNodes(hasTestTag("week_grid_$n")).fetchSemanticsNodes().isNotEmpty()
    }
    private fun title(n: Int) = compose.onNodeWithTag("schedule_week_title").assertTextContains("第 $n 周")
    private fun turnTo(n: Int) {
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("week_choice_$n").performClick()
        title(n)
    }
    private fun settle() { repeat(50) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() } }
    private fun drag(fraction: Float, release: Boolean = true, duration: Long = 1600) {
        grid().performTouchInput {
            val start = center
            val total = height * fraction
            down(start)
            val stepTime = duration / 16
            repeat(16) { n -> moveTo(start + Offset(0f, total * (n + 1) / 16), delayMillis = stepTime) }
            if (release) { advanceEventTime(250); up() }
        }
    }

    @Test fun unfinishedDragMovesCoursesAndDatesTogetherWhileRailsAndTitleStayFixed() {
        show()
        val tags = (0..6).map { "weekday_$it" } + (1..12).flatMap { listOf("lesson_$it", "lesson_start_$it", "lesson_end_$it") }
        val fixed = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().let { node -> node.id to node.boundsInRoot } }
        val course = compose.onNodeWithTag("course_1_0_1").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("date_1_0").fetchSemanticsNode().boundsInRoot
        val courseHeight = compose.onNodeWithTag("week_course_viewport").fetchSemanticsNode().boundsInRoot.height
        val dateHeight = compose.onNodeWithTag("week_date_viewport").fetchSemanticsNode().boundsInRoot.height
        drag(-.08f, release = false)
        title(1)
        val movedCourse = compose.onNodeWithTag("course_1_0_1").fetchSemanticsNode().boundsInRoot
        val movedDate = compose.onNodeWithTag("date_1_0").fetchSemanticsNode().boundsInRoot
        assertTrue("Course must move before finger release", movedCourse.bottom < course.bottom - 5f)
        assertTrue("Date must move before finger release", movedDate.bottom < date.bottom)
        assertEquals((course.bottom - movedCourse.bottom) / courseHeight,
            (date.bottom - movedDate.bottom) / dateHeight, .025f)
        compose.onNodeWithTag("week_courses_2").assertExists()
        tags.forEach { tag -> val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(fixed.getValue(tag).first, node.id); assertEquals(fixed.getValue(tag).second, node.boundsInRoot)
        }
        screenshot("following-finger")
        grid().performTouchInput { advanceEventTime(500); up() }
        title(1)
        compose.onNodeWithTag("week_courses_2").assertDoesNotExist()
    }

    @Test fun slowShortDragReturnsAndQuarterPageReleaseUpdatesTitleOnNextFrame() {
        show()
        drag(-.18f)
        title(1)
        compose.mainClock.autoAdvance = false
        drag(-.35f)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        title(2)
        compose.onNodeWithTag("week_courses_1").assertExists()
        compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
        title(2); screenshot("settling")
        settle()
        title(2)
        compose.onNodeWithTag("date_2_0").assertTextEquals("11/9")
    }

    @Test fun fastShortFlingAndLongSwipeMoveOnlyOneWeek() {
        show()
        grid().performTouchInput { swipeWithVelocity(center, center + Offset(0f, -height * .12f), 1500f) }
        title(2)
        grid().performTouchInput { swipeUp() }
        title(3)
        grid().performTouchInput { swipeDown() }
        title(2)
    }

    @Test fun reversalBeforeReleaseAndFirstLastWeekResistanceReturnCorrectly() {
        show()
        drag(.15f, release = false)
        val first = compose.onNodeWithTag("course_1_0_1").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("week_course_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top > viewport.top)
        assertTrue(first.top - viewport.top < viewport.height * .07f)
        grid().performTouchInput { advanceEventTime(300); up() }
        title(1)
        turnTo(4); drag(-.15f); title(4)
        turnTo(2)
        grid().performTouchInput {
            val start = center; down(start)
            repeat(8) { n -> moveTo(start + Offset(0f, -height * .35f * (n + 1) / 8), delayMillis = 100) }
            repeat(8) { n -> moveTo(start + Offset(0f, -height * .35f * (7 - n) / 8), delayMillis = 100) }
            advanceEventTime(400); up()
        }
        title(2)
    }

    @Test fun newDragTakesOverAnAnimationWithoutResettingItsOffset() {
        show(); compose.mainClock.autoAdvance = false
        drag(-.35f); compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
        val viewport = compose.onNodeWithTag("week_course_viewport").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("week_courses_2").fetchSemanticsNode().boundsInRoot.top
        grid().performTouchInput { down(center); moveBy(Offset(0f, 2f), delayMillis = 16) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val after = compose.onNodeWithTag("week_courses_2").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Interruption must keep the existing position", abs(after - before) < viewport.height * .08f)
        grid().performTouchInput { advanceEventTime(250); up() }
        settle(); title(2)
    }

    @Test fun rapidFlicksAcceptEveryGestureAndKeepThePointerNodeAndFixedRails() {
        data.value = schedule(count = 9)
        show(); compose.mainClock.autoAdvance = false
        val tags = listOf("week_course_viewport", "weekday_0", "lesson_1", "lesson_end_12")
        val fixed = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().let { n -> n.id to n.boundsInRoot } }
        fun flick(direction: Int, expected: Int) {
            compose.onNodeWithTag("week_course_viewport").performTouchInput {
                swipeWithVelocity(center, center + Offset(0f, direction * height * .12f), 1500f)
            }
            // Only one frame between gestures: the 150 ms settling animation cannot finish.
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            title(expected)
            tags.forEach { tag -> val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                assertEquals(fixed.getValue(tag).first, node.id)
                assertEquals(fixed.getValue(tag).second, node.boundsInRoot)
            }
            compose.onNodeWithText("课程详情").assertDoesNotExist()
        }
        (2..7).forEach { flick(-1, it) }
        screenshot("rapid-forward")
        (6 downTo 1).forEach { flick(1, it) }
        settle(); title(1)
        compose.onNodeWithTag("date_1_0").assertTextEquals("11/2")
        compose.onNodeWithTag("course_1_0_1").assertIsDisplayed()
    }

    @Test fun releasedTargetSurvivesRotationAndPageRemovalBeforeAnimationFinishes() {
        show(); compose.mainClock.autoAdvance = false
        drag(-.35f)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); title(2)
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); title(2)
        drag(-.35f)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); title(3)
        compose.runOnUiThread { visible.value = false }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnUiThread { visible.value = true }
        settle(); title(3)
        compose.onNodeWithTag("date_3_0").assertTextEquals("11/16")
    }

    @Test fun dragOnACourseDoesNotOpenDetailsButAStillTapDoes() {
        show()
        compose.onNodeWithTag("course_1_0_1").performTouchInput {
            down(center); repeat(8) { moveBy(Offset(0f, 4f), delayMillis = 80) }; advanceEventTime(250); up()
        }
        title(1)
        compose.onNodeWithText("课程详情").assertDoesNotExist()
        compose.onNodeWithTag("course_1_0_1").performClick()
        compose.onNodeWithText("课程详情").assertIsDisplayed()
    }

    @Test fun cancelledTouchObscuringBackgroundAndDataReplacementDiscardPendingTurn() {
        show()
        drag(-.4f, release = false)
        grid().performTouchInput { cancel() }
        title(1)
        compose.onNodeWithTag("week_courses_2").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        drag(-.4f)
        compose.runOnUiThread { unblocked.value = false }
        settle(); title(2)
        compose.runOnUiThread { unblocked.value = true }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        drag(-.4f)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); title(3)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // There is no resumed Compose root while the activity is in the background.
        compose.mainClock.advanceTimeBy(1600)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        title(3)
        drag(-.4f)
        compose.runOnUiThread { data.value = schedule(lastLesson = 13) }
        settle(); title(4)
        compose.onNodeWithTag("lesson_13").assertIsDisplayed()
    }

    @Test fun settledWeekSurvivesRotationAndProgrammaticSelectionDiscardsOldAnimation() {
        show(); turnTo(3)
        compose.activityRule.scenario.recreate(); title(3)
        drag(-.4f, release = false)
        compose.activityRule.scenario.recreate(); title(3)
        compose.mainClock.autoAdvance = false
        drag(-.35f)
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        // Exercise programmatic selection while the old pager animation is still running.
        // Pointer coordinates are unstable while the week list itself is sliding into place.
        compose.onNodeWithTag("week_choice_2").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(); title(2)
        compose.onNodeWithTag("back_current_week").performClick()
        settle(); title(1)
        compose.onNodeWithTag("back_current_week").assertDoesNotExist()
    }

    @Test fun singleWeekEmptyWeekOverlapAndLaterLessonsWorkAtLargeFont() {
        data.value = schedule(count = 1, lastLesson = 13)
        show(fontScale = 1.8f)
        drag(-.4f); title(1)
        compose.onNodeWithTag("lesson_13").assertIsDisplayed()
        compose.onNodeWithTag("course_1_1_13").assertIsDisplayed().performClick()
        compose.onNodeWithText("重叠课程", substring = true).assertExists()
        screenshot("large-font-overlap")
    }

    @Test fun emptyWeekAndReturningAfterPageRemovalDoNotResumeAnOldTurn() {
        val original = schedule()
        val semester = original.data.complete!!
        data.value = original.copy(data = original.data.copy(complete = semester.copy(weeks = semester.weeks.map {
            if (it.number == 2) it.copy(courses = emptyList()) else it
        })))
        show(); turnTo(2)
        compose.onNodeWithText("本周无课").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        drag(-.4f, release = false)
        compose.runOnUiThread { visible.value = false }
        settle()
        compose.runOnUiThread { visible.value = true }
        settle()
        title(2)
        compose.onNodeWithText("本周无课").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("无法取得测试截图")
        try { instrumentation.targetContext.getExternalFilesDir(null)?.resolve("week-drag-$name.png")?.outputStream()?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
