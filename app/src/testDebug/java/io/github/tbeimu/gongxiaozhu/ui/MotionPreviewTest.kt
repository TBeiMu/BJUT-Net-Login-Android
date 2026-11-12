package io.github.tbeimu.gongxiaozhu.ui

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders motion as numbered frames (20 ms apart) by driving the Compose clock by hand, so the
 * animations can be reviewed as video without a device. Run with `-PgxzMotion=true`; frames go to
 * `<gxzPreviewDir>/motion/<clip>/`. Skipped in ordinary test runs.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-hdpi")
class MotionPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val root = File(System.getProperty("gxz.preview.dir") ?: "build/design-preview", "motion")
    private lateinit var clip: File
    private var frame = 0

    @Before fun setUp() {
        assumeTrue("Motion previews are rendered only on request", System.getProperty("gxz.motion") == "true")
        val fonts = listOf(FontWeight.Normal to "Noto Sans SC (TrueType).otf", FontWeight.Medium to "Noto Sans SC Medium (TrueType).otf",
            FontWeight.Bold to "Noto Sans SC Bold (TrueType).otf").map { (weight, name) -> weight to File("C:/Windows/Fonts", name) }
        ShowcaseActivity.fontFamily = if (fonts.all { it.second.isFile }) FontFamily(fonts.map { (weight, file) -> Font(file, weight) }) else null
    }

    private fun record(name: String, tab: String? = null, page: String? = null, settle: Boolean = false, brand: Boolean = false, script: () -> Unit) {
        clip = File(root, name).apply { deleteRecursively(); mkdirs() }
        frame = 0
        compose.mainClock.autoAdvance = false
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShowcaseActivity::class.java)
        tab?.let { intent.putExtra("tab", it) }; page?.let { intent.putExtra("page", it) }; intent.putExtra("brand", brand)
        ActivityScenario.launch<ShowcaseActivity>(intent).use {
            compose.mainClock.advanceTimeByFrame()
            // Clips about an interaction start from a page at rest; the intro clip starts at once.
            if (settle) compose.mainClock.advanceTimeBy(2500)
            script()
        }
    }

    private fun frames(count: Int, step: Long = 20) = repeat(count) {
        compose.mainClock.advanceTimeBy(step)
        captureScreenRoboImage(File(clip, "f%04d.png".format(++frame)).path)
    }

    @Test fun intro() = record("1-intro") { frames(80) }

    /** The mark: its one-time intro, then three loading cycles. */
    @Test fun brand() = record("8-brand", brand = true) { frames(360) }

    @Test fun daySwitch() = record("0-day-switch", settle = true) {
        frames(4)
        compose.onNodeWithTag("agenda_day_3").performClick(); frames(40)
        compose.onNodeWithTag("agenda_day_2").performClick(); frames(40)
        compose.onNodeWithTag("schedule_mode").performClick(); frames(45)
        compose.onNodeWithTag("schedule_mode").performClick(); frames(40)
    }

    @Test fun weekSwipe() = record("2-week-swipe", settle = true) {
        compose.onNodeWithTag("schedule_mode").performClick(); compose.mainClock.advanceTimeBy(2500)
        frames(6)
        val grid = compose.onNodeWithTag("week_grid_11")
        val fixedTags = (0..6).map { "weekday_$it" } + (1..12).map { "lesson_time_$it" }
        val fixed = fixedTags.associateWith { tag -> compose.onNodeWithTag(tag).fetchSemanticsNode().let { it.id to it.boundsInRoot } }
        val course = compose.onNodeWithTag("course_11_0_1").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("date_11_0").fetchSemanticsNode().boundsInRoot
        grid.performTouchInput { down(center) }
        repeat(18) { grid.performTouchInput { moveBy(Offset(0f, -16f)) }; frames(1, 24) }
        assertTrue("Courses follow the finger before release", compose.onNodeWithTag("course_11_0_1").fetchSemanticsNode().boundsInRoot.bottom < course.bottom)
        assertTrue("Dates follow the same pager before release", compose.onNodeWithTag("date_11_0").fetchSemanticsNode().boundsInRoot.bottom < date.bottom)
        fixedTags.forEach { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals("Fixed node identity: $tag", fixed.getValue(tag).first, node.id)
            assertEquals("Fixed node position: $tag", fixed.getValue(tag).second, node.boundsInRoot)
        }
        grid.performTouchInput { up() }
        frames(1)
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 12 周")
        compose.onNodeWithTag("week_grid_12").assertExists()
        frames(44)
    }

    @Test fun weekList() = record("3-week-list", settle = true) {
        compose.onNodeWithTag("schedule_mode").performClick(); compose.mainClock.advanceTimeBy(2500)
        frames(4)
        compose.onNodeWithContentDescription("选择周次").performClick(); frames(22)
        compose.onNodeWithTag("week_choice_14").performClick(); frames(40)
    }

    @Test fun tabs() = record("4-tabs", settle = true) {
        frames(4)
        compose.onNodeWithText("资费").performClick(); frames(70)
        compose.onNodeWithText("我的").performClick(); frames(45)
    }

    @Test fun refresh() = record("5-refresh", tab = "BILLING", settle = true) {
        frames(4)
        compose.onNodeWithTag("billing_login").performClick()
        frames(75, 40)
    }

    @Test fun appearance() = record("6-appearance", tab = "MY", settle = true) {
        frames(4)
        compose.onNodeWithTag("my_APPEARANCE").performClick(); frames(36)
        compose.onNodeWithTag("wallpaper_preset_PINK").performClick(); frames(50)
        compose.onNodeWithTag("wallpaper_preset_MINT").performClick(); frames(50)
        compose.onNodeWithTag("wallpaper_mode_PRESET").performClick()
        compose.onNodeWithContentDescription("返回").performClick(); frames(30)
    }
}
