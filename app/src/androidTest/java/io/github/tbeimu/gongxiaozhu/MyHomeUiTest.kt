package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleData
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState
import io.github.tbeimu.gongxiaozhu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference

class MyHomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun bound(name: String = "示例同学") = AppUiState(loading = false,
        schedule = ScheduleUiState(loading = false,
            data = ScheduleData(identity = ScheduleIdentity("fictional-student-2026", name))))

    @Test fun profileShowsNameAndPlainGreetingWithSixCleanEntries() {
        val opened = mutableListOf<MyDestination>()
        compose.setContent { MyPreviewTheme { AppBackdrop(WallpaperState()) {
            MyHomeScreen(bound(), Modifier.fillMaxSize(), nowProvider = { LocalDateTime.of(1970, 1, 1, 13, 0) }, onOpen = opened::add)
        } } }
        compose.onNodeWithText("示例同学").assertIsDisplayed()
        compose.onNodeWithText("fictional-student-2026").assertDoesNotExist()
        compose.onNodeWithTag("my_profile_greeting").assertTextEquals("保持热爱，奔赴山河。")
        compose.onNodeWithText("fict***2026").assertDoesNotExist()
        compose.onNodeWithText("已绑定课表", substring = true).assertDoesNotExist()
        compose.onNodeWithText("校园服务").assertDoesNotExist()
        compose.onNodeWithText("应用", substring = false).assertDoesNotExist()
        compose.onNodeWithText("当前版本", substring = true).assertDoesNotExist()
        compose.onNodeWithText("导入整个学期，离线查看").assertDoesNotExist()
        compose.onNodeWithText("检查更新").assertExists()
        compose.onNodeWithText("应用更新").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir(null)?.resolve("my-home-preview.png")?.outputStream()?.use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        MyDestination.entries.forEach { destination ->
            compose.onNodeWithTag("my_${destination.name}").performScrollTo().performClick()
        }
        assertEquals(MyDestination.entries, opened)
    }

    @Test fun longNameAndLargeFontKeepEveryEntryReadableAndClickableOnSmallScreen() {
        val opened = mutableListOf<MyDestination>()
        compose.setContent { MyPreviewTheme { AppBackdrop(WallpaperState()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyHomeScreen(bound("用于界面检查的虚构长姓名"), Modifier.height(420.dp),
                    nowProvider = { LocalDateTime.of(1970, 1, 3, 0, 0) }, onOpen = opened::add)
            }
        } } }
        compose.onNodeWithTag("my_profile_greeting").performScrollTo().assertIsDisplayed()
            .assertTextEquals("夜很深了，先把心事放一放，好好休息。")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(results)); assertFalse(results.single().hasVisualOverflow)
            }
        MyDestination.entries.forEach { destination ->
            val row = compose.onNodeWithTag("my_${destination.name}")
            row.performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(destination.title).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(results)); assertFalse(results.single().hasVisualOverflow)
            }
            row.performClick()
        }
        assertEquals(MyDestination.entries, opened)
    }

    @Test fun profileUsesDefaultRemarkAndFallsBackToTheFullAccount() {
        val account = AccountSummary("fictional-primary", "自定义备注", "2026000123")
        val other = AccountSummary("fictional-other", "其他账号", "2026000456")
        val state = mutableStateOf(bound().copy(data = AccountState(listOf(account, other), account.id)))
        compose.setContent { MyPreviewTheme { AppBackdrop(WallpaperState()) {
            val current by state
            MyHomeScreen(current, Modifier.fillMaxSize(), onOpen = {})
        } } }
        compose.onNodeWithTag("my_profile_title").assertTextEquals("自定义备注")
        compose.onNodeWithText("示例同学").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(data = AccountState(listOf(account.copy(label = " "), other), account.id)) }
        compose.onNodeWithTag("my_profile_title").assertTextEquals("2026000123")
        compose.runOnIdle { state.value = state.value.copy(data = AccountState(listOf(account, other), other.id)) }
        compose.onNodeWithTag("my_profile_title").assertTextEquals("其他账号")
    }

    @Test fun foregroundAndRestorationRefreshTheClockWithoutRandomChanges() {
        val clock = AtomicReference(LocalDateTime.of(1970, 1, 1, 16, 59))
        val restore = StateRestorationTester(compose)
        restore.setContent { MyPreviewTheme { AppBackdrop(WallpaperState()) {
            MyHomeScreen(bound(), Modifier.fillMaxSize(), nowProvider = clock::get, onOpen = {})
        } } }
        compose.onNodeWithTag("my_profile_greeting").assertTextEquals("保持热爱，奔赴山河。")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        clock.set(LocalDateTime.of(1970, 1, 1, 17, 0))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("my_profile_greeting").assertTextEquals("落日归山海，烟火向星辰。")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("my_profile_greeting").assertTextEquals("落日归山海，烟火向星辰。")
        clock.set(LocalDateTime.of(1970, 1, 2, 0, 0))
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("my_profile_greeting").assertTextEquals("愿你熬过的夜，都有星光回应。")
    }
}

@Composable
private fun MyPreviewTheme(content: @Composable () -> Unit) = GongXiaoZhuTheme {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
}
