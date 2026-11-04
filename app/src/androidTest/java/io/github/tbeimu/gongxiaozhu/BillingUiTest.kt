package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import io.github.tbeimu.gongxiaozhu.billing.OnlineDevice
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.BillingScreen
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillingUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountSummary("fake-id", "模拟资费账号", "billing-demo-only")
    private val data = AccountState(listOf(account), account.id)
    private val metrics = mapOf("已用流量" to BillingMetric("1234", "MB"), "可用流量" to BillingMetric("5678", "MB"),
        "消费保护" to BillingMetric("未设置", ""), "账户余额" to BillingMetric("12.3456", "元"))
    private val device = OnlineDevice("fake-session", "192.0.2.10", "2001:db8::1", "00-11-22-33-44-55", "2026-01-01 10:00:00", 2)

    @Test fun cardsShowRealStateAndOfflineClickSelectsOnlyOneDevice() {
        var selected: String? = null
        var queried = false
        compose.setContent { GongXiaoZhuTheme {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(metrics, listOf(device)))),
                Modifier, {}, { queried = true }, { selected = it })
        } }
        compose.onNodeWithText("1234").assertExists()
        compose.onNodeWithText("12.3456").assertExists()
        compose.onNodeWithText("未设置").assertExists()
        // Optional visual review output contains only the invented state above.
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir(null)?.resolve("billing-preview.png")?.outputStream()?.use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("offline_device_0").performScrollTo().performClick()
        assertEquals(device.sessionId, selected)
        selected = null
        compose.onNodeWithTag("billing_offline").performScrollTo().performClick()
        compose.onNodeWithText("选择要注销的在线设备").assertExists()
        assertNull(selected)
        compose.onNodeWithTag("choose_offline_0").performClick()
        assertEquals(device.sessionId, selected)
        compose.onNodeWithTag("billing_login").performScrollTo().performClick()
        assertTrue(queried)
    }

    @Test fun missingMetricsAndOnlineErrorDoNotInventValuesOrDevices() {
        compose.setContent { GongXiaoZhuTheme {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(snapshot = BillingSnapshot(
                mapOf("可用流量" to BillingMetric("不限量", "")), emptyList(), "在线设备读取失败"))), Modifier, {}, {}, {})
        } }
        compose.onNodeWithText("不限量").assertExists()
        assertEquals(3, compose.onAllNodes(hasText("—")).fetchSemanticsNodes().size)
        compose.onNodeWithText("在线设备读取失败").performScrollTo().assertExists()
        compose.onNodeWithTag("offline_device_0").assertDoesNotExist()
    }

    @Test fun runningQueryDisablesDuplicateAndDeviceOperations() {
        compose.setContent { GongXiaoZhuTheme {
            BillingScreen(AppUiState(data = data, loading = false, billing = BillingUiState(running = true,
                snapshot = BillingSnapshot(metrics, listOf(device)))), Modifier, {}, {}, {})
        } }
        compose.onNodeWithTag("offline_device_0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("billing_login").performScrollTo().assertIsNotEnabled()
    }
}
