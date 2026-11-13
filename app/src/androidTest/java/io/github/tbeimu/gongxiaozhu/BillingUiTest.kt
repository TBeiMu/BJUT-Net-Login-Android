package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import io.github.tbeimu.gongxiaozhu.billing.BillingDiagnostic
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import io.github.tbeimu.gongxiaozhu.billing.BillingParser
import io.github.tbeimu.gongxiaozhu.billing.BillingProblem
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.BillingScreen
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.AppBackdrop
import io.github.tbeimu.gongxiaozhu.ui.WallpaperState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillingUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountSummary("fake-id", "模拟资费账号", "billing-demo-only")
    private val data = AccountState(listOf(account), account.id)
    // Parse invented server responses on Android itself, exercising the HTML/JSON libraries at minSdk.
    private val metrics = BillingParser.metrics("""
        <dl><dt>1234 <small class="unit">MB</small></dt><dd>已用流量</dd></dl>
        <dl><dt>5678 <small class="unit">MB</small></dt><dd>可用流量</dd></dl>
        <dl><dt>未设置</dt><dd>消费保护</dd></dl>
        <dl><dt>12.3456 <small class="unit">元</small></dt><dd>账户余额</dd></dl>
        <table id="OnlineLisBig"></table>
    """.trimIndent())
    private val device = BillingParser.devices("""[{"sessionId":"fake-session", "ip":"192.0.2.10", "ipv6":"2001:db8::1",
        "mac":"00-11-22-33-44-55", "loginTime":"2026-01-01 10:00:00", "useTime":120,
        "downFlow":1024, "upFlow":512}]""").single()

    @Test fun cardsShowRealStateAndOfflineClickSelectsOnlyOneDevice() {
        var selected: String? = null
        var queried = false
        var chooserVisible = false
        compose.setContent { BillingPreviewTheme { AppBackdrop(WallpaperState()) {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(metrics, listOf(device)),
                diagnostics = listOf(BillingDiagnostic("虚构诊断阶段", 200, "资费页", true)))),
                Modifier, {}, { queried = true }, { selected = it }, { chooserVisible = it })
        } } }
        compose.onNodeWithText("1234").assertExists()
        compose.onNodeWithText("12.3456").assertExists()
        compose.onNodeWithText("未设置").assertExists()
        compose.onNodeWithText("查看查询诊断").assertDoesNotExist()
        compose.onNodeWithText("虚构诊断阶段", substring = true).assertDoesNotExist()
        compose.onNodeWithText("这里显示所选账号的在线设备。注销会让选定设备下线。").assertDoesNotExist()
        // Optional visual review output contains only the invented state above.
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir(null)?.resolve("billing-preview.png")?.outputStream()?.use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("device_traffic_0").performScrollTo()
        compose.onNodeWithText("1.500 MB").assertExists()
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir(null)?.resolve("billing-online-preview.png")?.outputStream()?.use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("offline_device_0").performScrollTo().performClick()
        assertEquals(device.sessionId, selected)
        selected = null
        compose.onNodeWithTag("billing_offline").performScrollTo().performClick()
        compose.onNodeWithText("选择要注销的在线设备").assertExists()
        assertTrue(chooserVisible)
        assertNull(selected)
        compose.onNodeWithTag("choose_offline_0").performClick()
        compose.waitForIdle()
        assertFalse(chooserVisible)
        assertEquals(device.sessionId, selected)
        compose.onNodeWithTag("billing_login").performScrollTo().performClick()
        assertTrue(queried)
    }

    @Test fun missingMetricsAndOnlineErrorDoNotInventValuesOrDevices() {
        compose.setContent { BillingPreviewTheme {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(
                mapOf("可用流量" to BillingMetric("不限量", "")), emptyList(), "在线设备读取失败"))), Modifier, {}, {}, {})
        } }
        compose.onNodeWithText("不限量").assertExists()
        assertEquals(3, compose.onAllNodes(hasText("—")).fetchSemanticsNodes().size)
        compose.onNodeWithText("在线设备读取失败").performScrollTo().assertExists()
        compose.onNodeWithTag("offline_device_0").assertDoesNotExist()
    }

    @Test fun queryStatusKeepsActionsStillAndDisablesDuplicateOperations() {
        val snapshot = BillingSnapshot(metrics, listOf(device))
        val billing = mutableStateOf(BillingUiState())
        val fontScale = mutableStateOf(1f)
        var expectedGap = 0f
        compose.setContent { BillingPreviewTheme { AppBackdrop(WallpaperState()) {
            val density = LocalDensity.current
            expectedGap = with(density) { 4.dp.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                Box(Modifier.width(360.dp).height(680.dp).testTag("billing_layout_host")) {
                    BillingScreen(AppUiState(data = data, loading = false, billing = billing.value), Modifier, {}, {}, {})
                }
            }
        } } }
        // Use unclipped positions and sizes: on a small screen large-font actions can be below the viewport.
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot to it.size }
        val fixed = listOf("metric_已用流量", "metric_可用流量", "metric_消费保护", "metric_账户余额",
            "billing_login", "billing_accounts", "billing_offline")
        for (scale in listOf(1f, 1.8f)) {
            compose.runOnIdle { fontScale.value = scale; billing.value = BillingUiState() }
            val original = fixed.associateWith(::bounds)
            val status = compose.onNodeWithTag("billing_status_area").fetchSemanticsNode().boundsInRoot
            val accountBounds = compose.onNodeWithTag("billing_account").fetchSemanticsNode().boundsInRoot
            val accountLayout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("billing_account").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get -> assertTrue(get(accountLayout)) }
            assertTrue(status.left >= accountBounds.right)
            assertTrue(status.bottom <= bounds("metric_可用流量").first.y)
            val notices = listOf(true to "正在查询", true to "正在准备登录页", false to "查询完成") +
                BillingProblem.entries.map { false to it.hint } + listOf(false to "已注销；账号不符，请重登",
                    false to "已注销，刷新失败，请重查", false to "")
            for ((running, message) in notices) {
                compose.runOnIdle { billing.value = billing.value.copy(running = running, message = message,
                    snapshot = if (message == "查询完成") snapshot else billing.value.snapshot) }
                fixed.forEach { assertEquals("字号 $scale、提示「$message」使 $it 移动", original[it], bounds(it)) }
                compose.onNodeWithTag("billing_status_area").assertHasNoClickAction()
                compose.onNodeWithTag("billing_status").assertTextEquals(message).assertHasNoClickAction()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        assertTrue(get(results))
                        val layout = results.single()
                        assertEquals(accountLayout.single().layoutInput.style.fontSize, layout.layoutInput.style.fontSize)
                        assertEquals(accountLayout.single().layoutInput.style.lineHeight, layout.layoutInput.style.lineHeight)
                        // Content-sized Text can retain a wider paragraph; check painted lines instead of unused layout width.
                        for (line in 0 until layout.lineCount) {
                            assertFalse("字号 $scale 省略提示「$message」", layout.isLineEllipsized(line))
                            assertTrue("字号 $scale 截断提示「$message」", layout.getLineLeft(line) >= -1f &&
                                layout.getLineRight(line) <= layout.size.width + 1f && layout.getLineBottom(line) <= layout.size.height + 1f)
                        }
                    }
                if (running) {
                    val loaderBounds = compose.onNodeWithTag("billing_status_loader").fetchSemanticsNode().boundsInRoot
                    val textBounds = compose.onNodeWithTag("billing_status").fetchSemanticsNode().boundsInRoot
                    assertEquals("加载动画应贴近提示文字", expectedGap, textBounds.left - loaderBounds.right, 1f)
                    assertEquals("加载动画和文字应整体靠右", status.right, textBounds.right, 1f)
                    compose.onNodeWithTag("billing_login").assertIsNotEnabled()
                    compose.onNodeWithTag("billing_accounts").assertIsNotEnabled()
                    compose.onNodeWithTag("billing_offline").assertIsNotEnabled()
                }
                if (message == "正在查询" || message == BillingProblem.CONNECTION.hint) {
                    if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
                        val context = InstrumentationRegistry.getInstrumentation().targetContext
                        context.getExternalFilesDir(null)?.resolve("billing-status-$scale-${if (running) "running" else "error"}.png")
                            ?.outputStream()?.use { compose.onNodeWithTag("billing_layout_host").captureToImage().asAndroidBitmap()
                                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
        compose.runOnIdle { billing.value = billing.value.copy(running = true, message = "正在查询") }
        compose.onNodeWithTag("offline_device_0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("billing_login").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missingDeviceTrafficHasAnExplicitUnknownValue() {
        compose.setContent { BillingPreviewTheme {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(
                metrics, listOf(device.copy(traffic = null))))), Modifier, {}, {}, {})
        } }
        compose.onNodeWithTag("device_traffic_0").performScrollTo()
        compose.onNodeWithText("未获取").assertExists()
        compose.onNodeWithText("0.000 MB").assertDoesNotExist()
    }

    @Test fun largeFontKeepsUsageAndDeviceActionReadableOnSmallScreen() {
        var selected: String? = null
        compose.setContent { BillingPreviewTheme { AppBackdrop(WallpaperState()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(
                    metrics, listOf(device)))), Modifier.height(420.dp), {}, {}, { selected = it })
            }
        } } }
        compose.onNodeWithTag("device_traffic_0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.500 MB").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(get(results)); assertFalse(results.single().hasVisualOverflow)
        }
        compose.onNodeWithTag("offline_device_0").performScrollTo().performClick()
        assertEquals(device.sessionId, selected)
    }
}

@Composable
private fun BillingPreviewTheme(content: @Composable () -> Unit) = GongXiaoZhuTheme {
    // Match the content color supplied by the real app's Scaffold in standalone previews.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
}
