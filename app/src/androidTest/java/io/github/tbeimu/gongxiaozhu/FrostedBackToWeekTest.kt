package io.github.tbeimu.gongxiaozhu

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FrostedBackToWeekTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private fun schedule(weekCount: Int = 2): ScheduleUiState {
        val identity = ScheduleIdentity("fictional-frosted-student")
        val weeks = (1..weekCount).map { n -> ScheduleWeek(n, (0L..6L).map {
            monday.plusWeeks(n - 1L).plusDays(it).toString()
        }, emptyList()) }
        return ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", weekCount, 1), weeks, 1)))
    }

    private fun show(fontScale: Float = 1f, weekCount: Int = 2, selectOtherWeek: Boolean = true) {
        val image = Bitmap.createBitmap(160, 320, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xff779cba.toInt() else 0xffc3dde3.toInt())
            }
        }.asImageBitmap()
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppBackdrop(WallpaperState().apply {
                    this.image = image
                    preferences = WallpaperPreferences(mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = "wallpaper.jpg")
                }) {
                    ScheduleScreen(schedule(weekCount), Modifier.fillMaxSize(), {}, {}, {}, {}, todayProvider = { monday })
                }
            }
        } }
        compose.onNodeWithTag("back_current_week").assertDoesNotExist()
        if (selectOtherWeek) {
            compose.onNodeWithContentDescription("选择周次").performClick()
            compose.onNodeWithTag("week_choice_2").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun visiblePillIsNarrowButTouchTargetAndBackToWeekStillWork() {
        show()
        val touch = compose.onNodeWithTag("back_current_week").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(touch.height >= 48 * density - 1)
        assertTrue(surface.height in 27 * density..33 * density)
        assertAnchorBelowDots()
        preview("back-to-week")
        compose.onNodeWithTag("back_current_week").performClick()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.onNodeWithTag("back_current_week").assertDoesNotExist()
    }

    @Test fun largeFontKeepsTextReadableAndDoesNotLoseTapTarget() {
        show(2f)
        preview("back-to-week-large")
        compose.onNodeWithText("回到本周").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(get(results))
            val result = results.single()
            // Compose's paragraph can occupy the parent's width while its actual single-line
            // label is narrower. Check the visible glyph bounds, rather than that paragraph flag.
            assertEquals(4, result.getLineEnd(0, visibleEnd = true))
            assertFalse(result.multiParagraph.didExceedMaxLines)
            for (index in 0..3) {
                val glyph = result.getBoundingBox(index)
                assertTrue("Every enlarged glyph must fit horizontally", glyph.left >= 0 && glyph.right <= result.size.width)
                assertTrue("Every enlarged glyph must fit vertically", glyph.top >= 0 && glyph.bottom <= result.size.height)
            }
        }
        val touch = compose.onNodeWithTag("back_current_week").fetchSemanticsNode().boundsInRoot
        assertTrue(touch.height >= 48 * compose.activity.resources.displayMetrics.density - 1)
        assertAnchorBelowDots()
        compose.onNodeWithTag("back_current_week").performClick()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
    }

    @Test fun buttonFollowsBottomOfLongWeekDotGroupAndHidesForExpandedSelector() {
        show(weekCount = 24)
        assertAnchorBelowDots()
        preview("back-to-week-many-weeks")
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("back_current_week").assertDoesNotExist()
        compose.onNodeWithTag("week_choice_2").performClick()
        compose.onNodeWithTag("back_current_week").assertIsDisplayed()
        assertAnchorBelowDots()
    }

    @Test fun pillSlidesFromScreenRightAndBackOutWithoutMovingDots() {
        show(selectOtherWeek = false)
        val originalDots = compose.onNodeWithTag("week_dots").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("选择周次").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("week_choice_2").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            val entering = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            preview("back-to-week-entering")
            compose.mainClock.advanceTimeBy(96); compose.waitForIdle()
            val halfway = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("The pill must slide leftward from the right edge", entering.left > halfway.left)
            preview("back-to-week-halfway")
            compose.mainClock.advanceTimeBy(320); compose.waitForIdle()
            val shown = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(halfway.left > shown.left)
            assertAnchorBelowDots()
            assertEquals(originalDots, compose.onNodeWithTag("week_dots").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("back_current_week").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.onNodeWithTag("back_current_week").assertIsNotEnabled()
            val leaving = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("The pill must slide out toward the right edge", leaving.left > shown.left)
            preview("back-to-week-leaving")
            compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
            compose.onNodeWithTag("back_current_week").assertDoesNotExist()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun assertAnchorBelowDots() {
        val area = compose.onNodeWithTag("schedule_week_area").fetchSemanticsNode().boundsInRoot
        val dots = compose.onNodeWithTag("week_dots").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("back_current_week").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("The visible pill must touch the screen's right edge", area.right, surface.right, 1f)
        assertTrue("The whole touch target must stay below the dots", button.top >= dots.bottom)
        assertTrue("The button must sit close to the dots", button.top - dots.bottom <= 5 * density)
        assertTrue("The button must remain within the week area", button.bottom <= area.bottom + 1)
    }

    @Test fun actualRenderedBackdropBlursStripesBehindPillWithoutBlurringLabel() {
        compose.setContent { GongXiaoZhuTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val source = rememberScheduleBackdrop()
                Box(Modifier.size(240.dp, 160.dp)) {
                    Canvas(Modifier.fillMaxSize().recordScheduleBackdrop(source)) {
                        val stripe = 2.dp.toPx()
                        for (x in 0..(size.width / stripe).toInt()) drawRect(
                            if (x % 2 == 0) Color.Black else Color.White,
                            topLeft = Offset(x * stripe, 0f), size = Size(stripe, size.height))
                    }
                    FrostedBackToWeekButton(source, Modifier.align(Alignment.CenterEnd)) {}
                }
            }
        } }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("back_current_week_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val density = compose.activity.resources.displayMetrics.density
        val start = (pill.right - root.left - 45 * density).roundToInt()
        val end = (pill.right - root.left - 8 * density).roundToInt()
        val insideY = (pill.top - root.top + 3 * density).roundToInt()
        val outsideY = (pill.top - root.top - 10 * density).roundToInt()
        fun contrast(y: Int): Float = (start + 1..end).sumOf {
            abs(android.graphics.Color.red(image.getPixel(it, y)) - android.graphics.Color.red(image.getPixel(it - 1, y)))
        }.toFloat() / (end - start)
        assertTrue("Background pattern must exist outside the pill", contrast(outsideY) > 20)
        assertTrue("Rendered Gaussian backdrop must soften nearby pattern", contrast(insideY) < contrast(outsideY) * .3f)
        compose.onNodeWithText("回到本周").assertIsDisplayed()
        preview("back-to-week-blur")
    }

    private fun preview(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getExternalFilesDir(null)?.resolve("$name.png")?.outputStream()?.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
