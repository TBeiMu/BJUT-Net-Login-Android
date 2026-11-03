package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundLoginControllerTest {
    private val wifi = WifiConnection("wifi-1", PortalTransport { "" })
    private val secondWifi = WifiConnection("wifi-2", PortalTransport { "" })

    @Test fun initialNetworkAndStorageReadinessDoNotRaceAutomaticLogin() = runTest {
        var calls = 0
        val states = mutableListOf<LoginUiState>()
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ ->
            calls++; LoginResult("模拟完成")
        }, { { null } }, { states += it }, {})
        controller.enterForeground()
        controller.configure(true, true, NetworkMode.AUTO)
        runCurrent()
        assertEquals(0, calls)
        controller.wifiChanged(wifi)
        runCurrent()
        assertEquals(1, calls)
        controller.configure(true, true, NetworkMode.PUBLIC_WIFI)
        controller.enterForeground()
        runCurrent()
        assertEquals(1, calls)
        controller.leaveForeground()
        controller.enterForeground()
        runCurrent()
        assertEquals(2, calls)
        assertEquals("模拟完成", states.last().message)
    }

    @Test fun automaticFailureDoesNotRetryOnNetworkChangeOrSettingsChanges() = runTest {
        var calls = 0
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ ->
            calls++; LoginResult("模拟失败")
        }, { { null } }, {}, {})
        controller.wifiChanged(wifi)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        runCurrent()
        controller.wifiChanged(secondWifi)
        controller.configure(true, true, NetworkMode.DORM_WIFI)
        runCurrent()
        assertEquals(1, calls)
        controller.login()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun noWifiOrDisabledAutomaticNeverCallsAuthenticator() = runTest {
        var calls = 0
        val states = mutableListOf<LoginUiState>()
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ ->
            calls++; LoginResult("完成")
        }, { { null } }, { states += it }, {})
        controller.wifiChanged(null)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        controller.login()
        runCurrent()
        assertEquals(0, calls)
        assertTrue(states.last().message.contains("未连接"))
        controller.leaveForeground()
        controller.configure(true, false, NetworkMode.AUTO)
        controller.wifiChanged(wifi)
        controller.enterForeground()
        runCurrent()
        assertEquals(0, calls)
        controller.login()
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun duplicateClicksAreIgnoredAndNetworkLossCancelsWithoutStaleResult() = runTest {
        var calls = 0
        var cancellations = 0
        val states = mutableListOf<LoginUiState>()
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, _, progress ->
            calls++
            try { awaitCancellation() } finally { cancellations++; progress("过期进度") }
        }, { { null } }, { states += it }, {})
        controller.wifiChanged(wifi)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        runCurrent()
        repeat(10) { controller.login() }
        assertEquals(1, calls)
        controller.wifiChanged(null)
        runCurrent()
        assertEquals(1, cancellations)
        assertFalse(states.last().running)
        assertFalse(states.any { it.message == "过期进度" })
        controller.wifiChanged(secondWifi)
        runCurrent()
        assertEquals(1, calls)
        controller.login()
        runCurrent()
        assertEquals(2, calls)
        controller.leaveForeground()
        runCurrent()
        assertEquals(2, cancellations)
    }

    @Test fun backgroundCancelsLoginAndFreshEntryStartsOnlyAfterPriorJobFinishes() = runTest {
        var calls = 0
        var cancelled = false
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ ->
            calls++; try { awaitCancellation() } finally { cancelled = true }
        }, { { null } }, {}, {})
        controller.wifiChanged(wifi)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        runCurrent()
        controller.leaveForeground()
        controller.enterForeground()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(2, calls)
    }

    @Test fun storageFailureBlocksFlowAndDoesNotExposeExceptionPayload() = runTest {
        var blocked = false
        val states = mutableListOf<LoginUiState>()
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, credentials, _ ->
            credentials(); error("Must stop")
        }, { { throw VaultUnavailableException() } }, { states += it }, { blocked = true })
        controller.wifiChanged(wifi)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        runCurrent()
        assertTrue(blocked)
        assertFalse(states.last().running)
        assertTrue(states.last().message.contains("存储不可用"))
    }

    @Test fun changingDefaultDuringPreflightOnlyAffectsNextLogin() = runTest {
        var defaultId = "first-demo"
        val gate = CompletableDeferred<Unit>()
        val sentIds = mutableListOf<String?>()
        val controller = ForegroundLoginController(backgroundScope, CampusAuthenticator { _, _, credentials, _ ->
            gate.await()
            sentIds += credentials()?.id
            LoginResult("完成")
        }, credentials = {
            val selectedId = defaultId
            suspend { AccountDraft(selectedId, "模拟", "demo-user", "demo-password") }
        }, {}, {})
        controller.wifiChanged(wifi)
        controller.configure(true, true, NetworkMode.AUTO)
        controller.enterForeground()
        runCurrent()
        defaultId = "second-demo"
        gate.complete(Unit)
        runCurrent()
        controller.login()
        runCurrent()
        assertEquals(listOf("first-demo", "second-demo"), sentIds)
    }
}
