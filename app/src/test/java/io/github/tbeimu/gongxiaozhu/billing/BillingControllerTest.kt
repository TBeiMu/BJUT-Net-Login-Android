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
        var onWebSubmit: suspend () -> BillingWebResult = { BillingWebResult.Success(demoSnapshot) }
        override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot { calls++; selected = credentials.id; return onRefresh() }
        override suspend fun offlineTip() = "模拟设备注销提示"
        override suspend fun offline(device: OnlineDevice) { offlineCalls++; assertEquals(demoDevice.sessionId, device.sessionId); onOffline() }
        override fun close() { closed = true }
        override suspend fun openWeb(credentials: AccountDraft) = BillingWebPage("fake-token", "https://jfself.bjut.edu.cn/Self/login/", "fake html")
        override suspend fun submitWeb(token: String, password: String, code: String) = onWebSubmit()
    }
    private fun wifi(session: BillingSession, key: String = "wifi-demo") = WifiConnection(key, PortalTransport { "" }) { session }
    private fun draft(id: String) = AccountDraft(id, "模拟", "demo-$id", "demo-only-password")

    @Test fun pausingObservationPreservesDisplayButRequiresFreshWifiAndNewSession() = runTest {
        val first = FakeSession()
        val next = FakeSession().apply { onRefresh = { throw BillingException(BillingProblem.CONNECTION) } }
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(first))
        controller.enterForeground(); controller.query(); runCurrent()
        val time = state.loadedAt
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        assertNotNull(state.confirmation)
        controller.leaveForeground(); controller.wifiObservationPaused(); runCurrent()
        assertTrue(first.closed)
        assertEquals(demoSnapshot, state.snapshot); assertEquals(time, state.loadedAt)
        assertNull(state.confirmation)
        controller.enterForeground(); controller.setVisible(true); controller.query(); runCurrent()
        assertEquals(1, first.calls); assertFalse(state.running)
        controller.wifiChanged(wifi(next)); runCurrent()
        assertEquals(1, next.calls); assertEquals(1, first.calls)
        assertEquals(demoSnapshot, state.snapshot); assertEquals(time, state.loadedAt)
    }

    @Test fun actualDisconnectAfterSuspensionClearsPreviousData() = runTest {
        val first = FakeSession()
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(first))
        controller.enterForeground(); controller.query(); runCurrent()
        controller.leaveForeground(); controller.wifiObservationPaused()
        controller.enterForeground(); controller.wifiChanged(null); runCurrent()
        assertNull(state.snapshot); assertNull(state.loadedAt)
        assertTrue(state.message.contains("未连接 WiFi"))
    }

    @Test fun changedWifiAfterSuspensionClearsPreviousData() = runTest {
        val first = FakeSession(); val next = FakeSession()
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(first))
        controller.enterForeground(); controller.query(); runCurrent()
        controller.leaveForeground(); controller.wifiObservationPaused()
        controller.enterForeground(); controller.wifiChanged(wifi(next, "another-wifi")); runCurrent()
        assertNull(state.snapshot); assertNull(state.loadedAt)
        assertEquals(0, next.calls)
    }

    @Test fun logoutWaitsForCancelledAuthenticationToReleaseRequests() = runTest {
        val fake = FakeSession()
        val cleanup = CompletableDeferred<Unit>()
        var cancelled = false
        val controller = BillingController(backgroundScope, { draft(it) }, {}, {},
            beforeOffline = { cancelled = true }, awaitLoginStopped = { cleanup.await() })
        controller.enterForeground(); controller.setVisible(true); controller.accountChanged("demo-1")
        controller.wifiChanged(wifi(fake)); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent()
        controller.confirmOffline(); runCurrent()
        assertTrue(cancelled); assertEquals(0, fake.offlineCalls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(1, fake.offlineCalls)
    }

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
        val connection = WifiConnection("wifi-demo", PortalTransport { "" }) {
            FakeSession().also { session ->
                if (sessions.isEmpty()) session.onRefresh = { withContext(NonCancellable) { gate.await() }; demoSnapshot }
                sessions += session
            }
        }
        controller.wifiChanged(connection)
        controller.enterForeground(); controller.setVisible(true); runCurrent()
        controller.leaveForeground(); controller.enterForeground(); controller.wifiChanged(connection); runCurrent()
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

    @Test fun rejectedLoginOffersWebOnceAndCancelDoesNotResubmit() = runTest {
        val fake = FakeSession().apply { onRefresh = { throw BillingException(BillingProblem.UNCONFIRMED) } }
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        assertTrue(states.last().offerWeb)
        controller.dismissWebOffer(); runCurrent()
        assertFalse(states.last().offerWeb); assertEquals(1, fake.calls)
    }

    @Test fun networkFailureDoesNotOfferWebAndSwitchCancelsOpenPage() = runTest {
        val fake = FakeSession().apply { onRefresh = { throw BillingException(BillingProblem.CONNECTION) } }
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        assertFalse(states.last().offerWeb)
        fake.onRefresh = { throw BillingException(BillingProblem.REJECTED) }
        controller.query(); runCurrent(); controller.openWeb(); runCurrent()
        assertNotNull(states.last().webPage)
        controller.accountChanged("demo-2"); controller.submitWeb("fake-token", "demo-password", ""); runCurrent()
        assertNull(states.last().webPage); assertNull(states.last().snapshot)
    }

    @Test fun webSuccessReturnsSnapshotAndBackgroundClosesPendingPage() = runTest {
        val fake = FakeSession().apply { onRefresh = { throw BillingException(BillingProblem.UNCONFIRMED) } }
        val states = mutableListOf<BillingUiState>()
        val controller = BillingController(backgroundScope, { draft(it) }, { states += it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.openWeb(); runCurrent(); controller.submitWeb("fake-token", "changed-demo-password", ""); runCurrent()
        assertNull(states.last().webPage); assertEquals(demoSnapshot, states.last().snapshot)
        controller.query(); runCurrent(); controller.openWeb(); runCurrent()
        assertNotNull(states.last().webPage)
        controller.leaveForeground(); controller.submitWeb("fake-token", "another-demo-password", ""); runCurrent()
        assertNull(states.last().webPage); assertFalse(states.last().running); assertTrue(fake.closed)
    }

    @Test fun mismatchAfterOfflineClearsDataAndBlocksAnotherOfflineUntilNewLogin() = runTest {
        val secondDevice = demoDevice.copy(sessionId = "demo-session-2", ip = "192.0.2.20")
        val fake = FakeSession().apply {
            onRefresh = {
                if (calls > 1) throw BillingException(BillingProblem.ACCOUNT_MISMATCH)
                demoSnapshot.copy(devices = listOf(demoDevice, secondDevice))
            }
        }
        val replacement = FakeSession()
        var sessions = 0
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1")
        controller.wifiChanged(WifiConnection("wifi-demo", PortalTransport { "" }) {
            if (++sessions == 1) fake else replacement
        })
        controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent(); controller.confirmOffline(); runCurrent()
        assertTrue(fake.closed)
        assertNull(state.snapshot); assertNull(state.loadedAt); assertNull(state.confirmation)
        assertNull(state.webPage); assertNull(state.captcha); assertFalse(state.running)
        assertTrue(state.message.contains("学校已确认设备注销"))
        assertTrue(state.message.contains("账号与所选账号不一致"))
        assertTrue(state.message.contains("重新登录"))
        controller.requestOffline(secondDevice.sessionId); controller.confirmOffline(); runCurrent()
        assertEquals(1, fake.offlineCalls)
        assertEquals(0, replacement.calls)
        controller.query(); runCurrent()
        assertEquals(2, sessions); assertEquals(1, replacement.calls)
        assertEquals(demoSnapshot, state.snapshot)
    }

    @Test fun ordinaryRefreshMismatchClearsPreviousSnapshotAndPendingActions() = runTest {
        val fake = FakeSession()
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        fake.onRefresh = { throw BillingException(BillingProblem.ACCOUNT_MISMATCH) }
        controller.query(); runCurrent()
        assertTrue(fake.closed); assertNull(state.snapshot); assertNull(state.loadedAt)
        assertFalse(state.offerWeb); assertFalse(state.running)
        assertEquals(BillingProblem.ACCOUNT_MISMATCH.hint, state.message)
        controller.requestOffline(demoDevice.sessionId); controller.confirmOffline(); runCurrent()
        assertEquals(0, fake.offlineCalls)
    }

    @Test fun webAccountMismatchClearsCachedDataAndPageInsteadOfKeepingDevices() = runTest {
        val fake = FakeSession()
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        fake.onRefresh = { throw BillingException(BillingProblem.UNCONFIRMED) }
        controller.query(); runCurrent(); controller.openWeb(); runCurrent()
        assertNotNull(state.webPage)
        fake.onWebSubmit = { throw BillingException(BillingProblem.ACCOUNT_MISMATCH) }
        controller.submitWeb("fake-token", "changed-demo-password", ""); runCurrent()
        assertTrue(fake.closed); assertNull(state.snapshot); assertNull(state.loadedAt); assertNull(state.webPage)
        assertFalse(state.offerWeb); assertFalse(state.running)
        assertEquals(BillingProblem.ACCOUNT_MISMATCH.hint, state.message)
    }

    @Test fun connectionFailureAfterConfirmedOfflinePreservesKnownOutcomeAndRemainingDevices() = runTest {
        val remaining = demoDevice.copy(sessionId = "demo-session-2")
        val fake = FakeSession().apply {
            onRefresh = {
                if (calls > 1) throw BillingException(BillingProblem.CONNECTION)
                demoSnapshot.copy(devices = listOf(demoDevice, remaining))
            }
        }
        var state = BillingUiState()
        val controller = BillingController(backgroundScope, { draft(it) }, { state = it }, {}, {})
        controller.accountChanged("demo-1"); controller.wifiChanged(wifi(fake)); controller.enterForeground(); controller.query(); runCurrent()
        controller.requestOffline(demoDevice.sessionId); runCurrent(); controller.confirmOffline(); runCurrent()
        assertFalse(fake.closed); assertFalse(state.running)
        assertEquals(listOf(remaining), state.snapshot?.devices)
        assertTrue(state.message.contains("学校已确认设备注销"))
        assertTrue(state.message.contains("刷新失败"))
        assertEquals(1, fake.offlineCalls)
    }
}
