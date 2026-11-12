package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.MyDestination
import io.github.tbeimu.gongxiaozhu.ui.MyHomeScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Check the real rendered row throughout the crossfade, including its final outgoing frame. */
class MyUpdateArrowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val checking = mutableStateOf(false)
    private var requests = 0

    private fun show(fontScale: Float) {
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyHomeScreen(AppUiState(loading = false, schedule = ScheduleUiState(loading = false)),
                    Modifier.width(360.dp).height(650.dp), checkingUpdates = checking.value) {
                    assertEquals(MyDestination.UPDATES, it)
                    requests++
                    checking.value = true
                }
            }
        } }
        compose.onNodeWithTag("my_UPDATES").performScrollTo()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun verify(fontScale: Float, fast: Boolean = false) {
        show(fontScale)
        val row = bounds("my_UPDATES")
        val arrow = bounds("my_UPDATES_chevron")
        val label = compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun assertStationary() {
            assertEquals("检查更新行发生移动", row, bounds("my_UPDATES"))
            assertEquals("名称发生移动", label,
                compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
            compose.onAllNodesWithTag("my_UPDATES_chevron", useUnmergedTree = true).fetchSemanticsNodes().forEach {
                assertEquals("淡入淡出或按压期间箭头漂移", arrow, it.boundsInRoot)
            }
        }
        fun frames(count: Int) {
            repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); assertStationary() }
        }
        repeat(2) {
            if (fast) {
                compose.onNodeWithTag("my_UPDATES").performTouchInput { down(center) }
                frames(8)
                compose.onNodeWithTag("my_UPDATES").performTouchInput { up() }
            } else compose.onNodeWithTag("my_UPDATES").performClick()
            frames(if (fast) 1 else 24)
            compose.onNodeWithTag("my_UPDATES").assertIsNotEnabled()
            if (!fast) {
                compose.onNodeWithTag("my_update_checking", useUnmergedTree = true).assertIsDisplayed()
            }
            compose.runOnUiThread { checking.value = false }
            frames(48)
            compose.onNodeWithTag("my_UPDATES").assertIsEnabled()
            compose.onNodeWithTag("my_update_checking").assertDoesNotExist()
            assertEquals(arrow, bounds("my_UPDATES_chevron"))
        }
        assertEquals(2, requests)
        compose.mainClock.autoAdvance = true
    }

    @Test fun completedChecksKeepArrowAndLabelStillThroughoutFade() = verify(1f)
    @Test fun largeFontsKeepArrowAndLabelStillThroughoutFade() = verify(2f)
    @Test fun immediateResultDoesNotReplayPressedArrowMovement() = verify(1f, fast = true)
}
