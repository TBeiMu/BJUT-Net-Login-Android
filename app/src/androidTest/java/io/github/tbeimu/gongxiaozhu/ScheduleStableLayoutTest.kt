package io.github.tbeimu.gongxiaozhu

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.ScheduleHome
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleStableLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)

    private fun show(fontScale: Float, notice: CampusConnectionNotice? = null) {
        val identity = ScheduleIdentity("fictional-stable-layout")
        val week = ScheduleWeek(1, (0L..6L).map { monday.plusDays(it).toString() }, emptyList())
        val state = ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", 1, 1), listOf(week), 1)))
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(360.dp).height(680.dp).background(MaterialTheme.colorScheme.background)
                    .testTag("layout_host")) {
                    ScheduleHome(state, Modifier.fillMaxSize(), {}, {}, {}, {}, todayProvider = { monday },
                        connectionNotice = notice, nowProvider = { LocalTime.NOON })
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun verifyModeSwitch(fontScale: Float) {
        show(fontScale)
        val original = bounds("schedule_mode")
        repeat(6) { index ->
            compose.onNodeWithTag("schedule_mode").performClick()
            compose.waitForIdle()
            assertEquals("第 ${index + 1} 次切换后按钮位置改变", original, bounds("schedule_mode"))
        }
    }

    @Test fun modeSwitchKeepsTheSamePosition() = verifyModeSwitch(1f)
    @Test fun modeSwitchKeepsTheSamePositionWithLargeFonts() = verifyModeSwitch(1.8f)

    private fun TestScope.verifyHeaders(fontScale: Float) {
        val notice = CampusConnectionNotice(backgroundScope)
        show(fontScale, notice)
        for (week in listOf(false, true)) {
            if (week) compose.onNodeWithTag("schedule_mode").performClick()
            compose.waitForIdle()
            val contentTag = if (week) "week_grid_1" else "agenda_day_0"
            val tags = listOf("schedule_overline", "schedule_week_title", "import_schedule", "schedule_mode", contentTag)
            val original = tags.associateWith(::bounds)
            fun verify(label: String) {
                compose.waitForIdle()
                tags.forEach { tag -> assertEquals("$label 时 $tag 移动", original[tag], bounds(tag)) }
            }
            val mode = if (week) "week" else "today"
            capture("$mode-$fontScale-resting")
            val running = LoginUiState(running = true, wifiConnected = true, taskId = "fictional-$mode")
            compose.runOnUiThread { notice.update(running) }; runCurrent()
            compose.onNodeWithText("正在登录校园网").assertIsDisplayed()
            verify("正在登录")
            capture("$mode-$fontScale-connecting")
            advanceTimeBy(3500); runCurrent()
            compose.onNodeWithText("登录超时").assertIsDisplayed()
            verify("超时")
            compose.runOnUiThread { notice.update(running.copy(completed = true, outcome = LoginTaskOutcome.SUCCESS)) }
            runCurrent()
            compose.onNodeWithText("校园网已连接").assertIsDisplayed()
            verify("成功")
            advanceTimeBy(2000); runCurrent()
            compose.onNodeWithTag("campus_connection_title").assertDoesNotExist()
            verify("恢复标题")
            compose.runOnUiThread { notice.update(LoginUiState(wifiSnapshotKnown = true, wifiConnected = false)) }
            runCurrent()
            compose.onNodeWithText("未连接WiFi").assertIsDisplayed()
            verify("无 WiFi")
            advanceTimeBy(1000); runCurrent()
            verify("恢复标题")
        }
    }

    @Test fun headersStayStillAcrossEveryConnectionNotice() = runTest { verifyHeaders(1f) }
    @Test fun largeFontHeadersStayStillAcrossEveryConnectionNotice() = runTest { verifyHeaders(1.8f) }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "stable-layout")
            .apply { mkdirs() }
        val image = compose.onNodeWithTag("layout_host").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
