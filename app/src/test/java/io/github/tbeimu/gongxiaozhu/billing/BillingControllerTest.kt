package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.PortalTransport
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillingControllerTest {
    private class FakeSession : BillingSession {
        var calls = 0
        var closed = false
        var offlineCalls = 0
        var selected: String? = null
        var onRefresh: suspend () -> BillingSnapshot = { demoSnapshot }
        var onOffline: suspend () -> Unit = {}
        override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot { calls++; selected = credentials.id; return onRefresh() }
        override suspend fun offlineTip() = "模拟设备注销提示"
        override suspend fun offline(device: OnlineDevice) { offlineCalls++; assertEquals(demoDevice.sessionId, device.sessionId); onOffline() }
        override fun close() { closed = true }
    }
    private fun wifi(session: BillingSession, key: String = "wifi-demo") = WifiConnection(key, PortalTransport { "" }) { session }
    private fun draft(id: String) = AccountDraft(id, "模拟", "demo-$id", "demo-only-password")

    @Test fun openingPageQueriesOnceAndSettingsOrRepeatedVisibilityDoNotRetry() = runTest {
        val fake = FakeSession()
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.enterForeground(); controller.setVisible(true); controller.accountChanged("demo-1")
        runCurrent(); assertEquals(0, fake.calls)
        controller.wifiChanged(wifi(fake)); runCurrent()
        assertEquals(1, fake.calls)
        assertEquals("demo-1", fake.selected)
        controller.setVisible(false); controller.setVisible(true); controller.accountChanged("demo-1")
        controller.enterForeground(); runCurrent()
        assertEquals(1, fake.calls)
        assertNotNull(states.last().loadedAt)
        controller.query(); runCurrent(); assertEquals(2, fake.calls)
    }

    @Test fun failureDoesNotRetryAfterNetworkChangeAndManualQueryCanRetry() = runTest {
        val first = FakeSession().apply { onRefresh = { throw BillingException(BillingProblem.REJECTED) } }
        val second = FakeSession()
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(first))
        controller.enterForeground(); controller.setVisible(true); runCurrent()
        assertEquals(1, first.calls); assertTrue(first.closed); assertFalse(states.last().running)
        controller.wifiChanged(wifi(second, "wifi-other")); controller.setVisible(true); runCurrent()
        assertEquals(0, second.calls)
        controller.query(); runCurrent(); assertEquals(1, second.calls)
    }

    @Test fun switchAccountClearsOldDataCookiesAndPendingDeviceConfirmation() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1")
        controller.wifiChanged(WifiConnection("wifi-demo", PortalTransport { "" }) { FakeSession().also { sessions += it } })
        controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        assertNotNull(states.last().confirmation)
        controller.accountChanged("demo-2"); controller.confirmOffline(); runCurrent()
        assertNull(states.last().snapshot); assertNull(states.last().confirmation)
        assertTrue(sessions.first().closed); assertEquals(0, sessions.first().offlineCalls)
        controller.query(); runCurrent()
        assertEquals(2, sessions.size); assertEquals("demo-2", sessions.last().selected)
    }

    @Test fun staleResultAfterAccountChangeIsDiscardedEvenIfRequestIgnoresCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = FakeSession().apply { onRefresh = { withContext(NonCancellable) { gate.await() }; demoSnapshot } }
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(first)); controller.enterForeground(); controller.query(); runCurrent()
        repeat(5) { controller.query() }; assertEquals(1, first.calls)
        controller.accountChanged("demo-2"); gate.complete(Unit); runCurrent()
        assertNull(states.last().snapshot); assertFalse(states.last().running)
    }

    @Test fun backgroundCancelsAndFreshForegroundWaitsForPriorJobToFinish() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val gate = CompletableDeferred<Unit>()
        val controller = BillingController(backgroundScope, { draft(it) }, {}, {}, {})
        controller.accountChanged("demo-1")
        controller.wifiChanged(WifiConnection("wifi-demo", PortalTransport { "" }) {
            FakeSession().also { session ->
                if (sessions.isEmpty()) session.onRefresh = { withContext(NonCancellable) { gate.await() }; demoSnapshot }
                sessions += session
            }
        })
        controller.enterForeground(); controller.setVisible(true); runCurrent()
        controller.leaveForeground(); controller.enterForeground(); runCurrent()
        assertEquals(1, sessions.size); assertTrue(sessions.first().closed)
        gate.complete(Unit); runCurrent()
        assertEquals(2, sessions.size); assertEquals(1, sessions.last().calls)
    }

    @Test fun storageFailureClearsBillingAndDoesNotExposeExceptionPayload() = runTest {
        var blocked = false
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { throw VaultUnavailableException() }, { states += it }, { blocked = true }, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(FakeSession())); controller.enterForeground(); controller.query(); runCurrent()
        assertTrue(blocked); assertNull(states.last().snapshot); assertFalse(states.last().running)
    }

    @Test fun offlineNeedsConfirmationAndSendsOnlyOneSelectedSessionThenRefreshes() = runTest {
        val fake = FakeSession()
        var suppressed = 0
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, { suppressed++ })
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.confirmOffline(); runCurrent(); assertEquals(0, fake.offlineCalls)
        controller.requestOffline("not-in-list"); runCurrent(); assertNull(states.last().confirmation)
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        assertNotNull(states.last().confirmation); assertEquals(0, fake.offlineCalls)
        controller.dismissOffline(); controller.confirmOffline(); runCurrent(); assertEquals(0, fake.offlineCalls)
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        controller.confirmOffline(); controller.confirmOffline(); runCurrent()
        assertEquals(1, fake.offlineCalls); assertEquals(1, suppressed); assertEquals(2, fake.calls)
        assertTrue(states.last().message.contains("学校已确认"))
    }

    @Test fun networkSwitchInvalidatesConfirmationAndDoesNotSubmitOffline() = runTest {
        val fake = FakeSession()
        val controller = BillingController(backgroundScope, { draft(it) }, {}, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        controller.wifiChanged(null); controller.confirmOffline(); runCurrent()
        assertEquals(0, fake.offlineCalls)
    }

    @Test fun confirmedOfflineIsNotReportedAsFailureWhenRefreshCannotReadStorage() = runTest {
        val fake = FakeSession()
        var reads = 0
        var blocked = false
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, {
            reads++
            if (reads > 1) throw VaultUnavailableException()
            draft(it)
        }, { states += it }, { blocked = true }, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent(); controller.confirmOffline(); runCurrent()
        assertEquals(1, fake.offlineCalls)
        assertTrue(blocked); assertTrue(fake.closed)
        assertNull(states.last().snapshot)
        assertTrue(states.last().message.contains("学校已确认设备注销"))
    }

    @Test fun networkLossDuringOfflineReportsUnconfirmedOutcomeWithoutRepeatingIt() = runTest {
        val fake = FakeSession().apply { onOffline = { awaitCancellation() } }
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent(); controller.confirmOffline(); runCurrent()
        controller.wifiChanged(null); runCurrent()
        assertEquals(1, fake.offlineCalls); assertTrue(states.last().message.contains("未确认")); assertFalse(states.last().running)
    }
}
