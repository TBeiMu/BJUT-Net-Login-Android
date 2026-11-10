package io.github.tbeimu.gongxiaozhu

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.billing.BillingController
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import io.github.tbeimu.gongxiaozhu.billing.BillingSession
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.OnlineDevice
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.CampusAuthenticator
import io.github.tbeimu.gongxiaozhu.network.CampusLogin
import io.github.tbeimu.gongxiaozhu.network.CampusLoginController
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.LoginResult
import io.github.tbeimu.gongxiaozhu.network.PortalTransport
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real runtime and lifecycle, but never sends billing credentials to the school. */
@RunWith(AndroidJUnit4::class)
class BillingLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: CampusLoginRuntime
    private lateinit var model: AppViewModel
    private lateinit var wifiKey: String
    private var loadedAt: Long? = null
    private var sessionClosed = false
    private val snapshot = BillingSnapshot(mapOf(
        "已用流量" to BillingMetric("12", "GB"), "可用流量" to BillingMetric("38", "GB"),
        "消费保护" to BillingMetric("50", "元"), "账户余额" to BillingMetric("20", "元")),
        listOf(OnlineDevice("fictional-device", "192.0.2.1", "", "02:00:00:00:00:01", "", 10)))

    @Before fun setup() {
        check(context.packageName.endsWith(".debug"))
        createAccountRepository(context).apply {
            reset(); load()
            save(AccountDraft(label = "资费回归测试", username = "fictional-lifecycle", password = "fictional-password"))
            settings(AppSettings(autoLogin = false))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        runtime = CampusLoginRuntime.get(context)
        scenario.onActivity { model = ViewModelProvider(it).get(AppViewModel::class.java) }
        waitFor { !model.state.value.loading && currentWifi() != null }
        wifiKey = currentWifi()!!.key
        main {
            val billing = AppViewModel::class.java.getDeclaredField("billingController").apply { isAccessible = true }
                .get(model) as BillingController
            val session = object : BillingSession {
                override suspend fun refresh(credentials: AccountDraft, code: String?) = snapshot
                override suspend fun offlineTip() = "虚构提示"
                override suspend fun offline(device: OnlineDevice) { error("No device logout in this test") }
                override fun close() { sessionClosed = true }
            }
            billing.wifiChanged(null)
            billing.wifiChanged(WifiConnection(wifiKey, PortalTransport { "" }) { session })
            billing.query()
        }
        waitFor { model.state.value.billing.snapshot == snapshot }
        loadedAt = model.state.value.billing.loadedAt
    }

    @After fun cleanup() {
        if (::runtime.isInitialized) {
            main { runtime.controller.cancel() }
            waitFor { !runtime.controller.state.value.running }
            main { replaceAuthenticator(CampusLogin()) }
        }
        if (::scenario.isInitialized) scenario.close()
        shell("svc wifi enable")
        createAccountRepository(context).reset()
    }

    @Test fun homeAndReturnOnSameWifiKeepMetricsDevicesAndTimestampButClearSession() {
        scenario.moveToState(Lifecycle.State.CREATED)
        waitFor { runtime.connections.value == WifiObservation.Suspended }
        instrumentation.waitForIdleSync()
        assertTrue(sessionClosed)
        assertSavedDisplay()
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { currentWifi() != null }
        assertEquals(wifiKey, currentWifi()!!.key)
        assertSavedDisplay()
    }

    @Test fun authenticationCompletingInBackgroundDoesNotEraseBilling() {
        val result = CompletableDeferred<LoginResult>()
        main {
            replaceAuthenticator(CampusAuthenticator { _, _, _, _ -> result.await() })
            runtime.controller.login()
        }
        waitFor { runtime.controller.state.value.running }
        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(runtime.connections.value is WifiObservation.Current)
        result.complete(LoginResult("虚构后台成功", completed = true))
        waitFor { runtime.connections.value == WifiObservation.Suspended }
        instrumentation.waitForIdleSync()
        assertTrue(sessionClosed)
        assertSavedDisplay()
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { currentWifi() != null }
        assertSavedDisplay()
    }

    @Test fun realDisconnectWhileObservationIsPausedClearsOldDisplayOnReturn() {
        scenario.moveToState(Lifecycle.State.CREATED)
        waitFor { runtime.connections.value == WifiObservation.Suspended }
        shell("svc wifi disable")
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        waitFor { manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } }
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { runtime.connections.value == WifiObservation.Current(null) && model.state.value.billing.snapshot == null }
        assertNull(model.state.value.billing.loadedAt)
    }

    private fun assertSavedDisplay() {
        assertEquals(snapshot, model.state.value.billing.snapshot)
        assertEquals(loadedAt, model.state.value.billing.loadedAt)
        assertFalse(model.state.value.billing.running)
    }
    private fun currentWifi() = (runtime.connections.value as? WifiObservation.Current)?.connection
    private fun replaceAuthenticator(value: CampusAuthenticator) {
        CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }.set(runtime.controller, value)
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes(); Unit }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!predicate() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue("等待资费生命周期状态超时", predicate())
    }
}
