package io.github.tbeimu.gongxiaozhu

import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Rule

/** This test switches WiFi only on its isolated emulator, never on a physical user device. */
class CampusNoWifiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName.endsWith(".debug"))
            createAccountRepository(context).apply {
                reset(); load(); save(AccountDraft(label = "虚构离线账号", username = "fictional-offline", password = "fictional-only"))
                settings(AppSettings(autoLogin = true))
            }
            command("svc wifi disable")
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val end = android.os.SystemClock.elapsedRealtime() + 5000
            @Suppress("DEPRECATION")
            while (manager.allNetworks.any { isPhysicalWifi(manager.getNetworkCapabilities(it)) } &&
                android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(50)
            @Suppress("DEPRECATION")
            check(manager.allNetworks.none { isPhysicalWifi(manager.getNetworkCapabilities(it)) })
        }
        override fun after() { createAccountRepository(context).reset(); command("svc wifi enable") }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun realNoWifiSnapshotSkipsAutomaticAndManualServiceStarts() {
        val runtime = CampusLoginRuntime.get(context)
        val model = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
        compose.waitUntil(5000) { !model.state.value.loading &&
            (runtime.connections.value as? WifiObservation.Current)?.connection == null &&
            runtime.controller.state.value.wifiSnapshotKnown }
        assertFalse(runtime.controller.state.value.wifiConnected)
        assertFalse(runtime.controller.state.value.running)
        assertNull(runtime.controller.state.value.taskId)
        // The one-second presentation may have expired while the Activity was loading; its exact
        // visibility is verified separately with a controlled clock in CampusTitleImmediateTest.
        compose.runOnUiThread { runtime.controller.login() }
        compose.waitForIdle()
        assertFalse(runtime.controller.state.value.running)
        assertNull(runtime.controller.state.value.taskId)
        assertTrue(runtime.controller.state.value.message.contains("未连接 WiFi"))
        @Suppress("DEPRECATION")
        val services = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
        assertFalse(services.any { it.service.className == CampusLoginService::class.java.name })
        compose.onNodeWithText("正在登录校园网").assertDoesNotExist()
        compose.onNodeWithText("登录超时").assertDoesNotExist()
    }

    private fun command(value: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(value)).use { it.readBytes() }
    }
}
