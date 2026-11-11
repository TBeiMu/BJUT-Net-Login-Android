package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusLoginControllerTest {
    private val wifi = WifiConnection("wifi-1", PortalTransport { "" })
    private val secondWifi = WifiConnection("wifi-2", PortalTransport { "" })
    private class Fixture(scope: CoroutineScope, authenticator: CampusAuthenticator,
        credentials: suspend (String?) -> AccountDraft? = { null }) {
        val starts = mutableListOf<String>()
        var blocked = false
        val controller = CampusLoginController(scope, authenticator, credentials, { starts += it }, { blocked = true })
        fun start(): String = starts.last().also { check(controller.serviceStarted(it)) }
        fun ready(wifi: WifiConnection, automatic: Boolean = true) {
            controller.configure(true, automatic, NetworkMode.AUTO, "demo-1")
            controller.wifiChanged(wifi)
            controller.enterForeground()
        }
    }

    @Test fun waitsForWifiAndServiceBeforeMakingAnyRequests() = runTest {
        var calls = 0
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> calls++; LoginResult("完成") })
        f.controller.enterForeground(); f.controller.configure(true, true, NetworkMode.AUTO, "demo")
        runCurrent(); assertTrue(f.starts.isEmpty())
        f.controller.wifiChanged(wifi); runCurrent()
        assertEquals(1, f.starts.size); assertEquals(0, calls)
        assertFalse(f.controller.serviceStarted("stale"))
        f.start(); runCurrent(); assertEquals(1, calls)
    }

    @Test fun backgroundAndReturnReuseRunningAttemptWithoutSecondSubmission() = runTest {
        var calls = 0
        val result = CompletableDeferred<LoginResult>()
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> calls++; result.await() })
        f.ready(wifi); val id = f.start(); runCurrent()
        f.controller.leaveForeground(); f.controller.enterForeground(); runCurrent()
        assertEquals(1, calls); assertEquals(id, f.controller.state.value.taskId)
        result.complete(LoginResult("已联网", completed = true)); runCurrent()
        assertTrue(f.controller.state.value.completed); assertEquals(1, f.starts.size)
        assertEquals(LoginTaskOutcome.SUCCESS, f.controller.state.value.outcome)
    }

    @Test fun firstReturnConsumesBackgroundResultWithoutRetryingFailure() = runTest {
        val result = CompletableDeferred<LoginResult>()
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> result.await() })
        f.ready(wifi); val id = f.start(); runCurrent()
        f.controller.leaveForeground(); result.complete(LoginResult("失败")); runCurrent()
        f.controller.serviceStopped(id)
        f.controller.enterForeground(); runCurrent()
        assertEquals("失败", f.controller.state.value.message); assertEquals(1, f.starts.size)
        assertEquals(LoginTaskOutcome.FAILED, f.controller.state.value.outcome)
        f.controller.login(); assertEquals(2, f.starts.size)
    }

    @Test fun cancellationWaitsForCleanupAndDiscardsLateResultAndProgress() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, progress ->
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanup.await(); progress("过期内容") }
            }
        })
        f.ready(wifi); val id = f.start(); runCurrent()
        f.controller.cancel(id); runCurrent(); f.controller.login()
        assertTrue(f.controller.state.value.running); assertEquals(1, f.starts.size)
        assertEquals(LoginTaskOutcome.CANCELLED, f.controller.state.value.outcome)
        cleanup.complete(Unit); runCurrent(); f.controller.serviceStopped(id)
        assertFalse(f.controller.state.value.running); assertFalse(f.controller.state.value.message.contains("过期"))
        f.controller.login(); assertEquals(2, f.starts.size)
    }

    @Test fun wifiSwitchCancelsAndDoesNotAutomaticallyRestart() = runTest {
        var cancelled = false
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        f.ready(wifi); f.start(); runCurrent()
        f.controller.leaveForeground(); f.controller.wifiChanged(secondWifi); runCurrent()
        assertTrue(cancelled); assertFalse(f.controller.state.value.completed)
        f.controller.enterForeground(); runCurrent(); assertEquals(1, f.starts.size)
    }

    @Test fun duplicateStartsAndOldCancellationCannotAffectNewTask() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> awaitCancellation() })
        f.ready(wifi); val old = f.start(); runCurrent()
        assertFalse(f.controller.serviceStarted(old)); repeat(5) { f.controller.login() }
        f.controller.cancel(old); runCurrent()
        f.controller.login(); assertEquals(1, f.starts.size) // Wait for the old Service.onDestroy.
        f.controller.serviceStopped(old); f.controller.login(); val next = f.start(); runCurrent()
        f.controller.cancel(old); f.controller.serviceStopped(old); runCurrent()
        assertEquals(next, f.controller.state.value.taskId); assertTrue(f.controller.state.value.running)
    }

    @Test fun taskTimeoutAlsoBoundsMissingServiceStartup() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> error("no request before service") })
        f.ready(wifi); runCurrent(); advanceTimeBy(LOGIN_TASK_TIMEOUT_MS); runCurrent()
        assertFalse(f.controller.state.value.running)
        assertTrue(f.controller.state.value.message.contains("超时"))
        assertEquals(LoginTaskOutcome.TIMED_OUT, f.controller.state.value.outcome)
        assertFalse(f.controller.serviceStarted(f.starts.single()))
    }

    @Test fun authenticationHardDeadlineStopsRunningTask() = runTest {
        var cancelled = false
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        f.ready(wifi); f.start(); runCurrent(); advanceTimeBy(LOGIN_TASK_TIMEOUT_MS); runCurrent()
        assertTrue(cancelled); assertFalse(f.controller.state.value.running)
    }

    @Test fun offlineRequestPreventsAutomaticAuthenticationButAllowsLaterManualLogin() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> awaitCancellation() })
        f.ready(wifi); val id = f.start(); runCurrent()
        f.controller.deviceOfflineRequested(); runCurrent(); f.controller.serviceStopped(id)
        f.controller.wifiChanged(secondWifi); runCurrent(); assertEquals(1, f.starts.size)
        f.controller.login(); assertEquals(2, f.starts.size)
    }

    @Test fun changingDefaultOnlyAffectsNextAttemptButEditingSelectedAccountCancels() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<String?>()
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, credentials, _ -> gate.await(); credentials(); awaitCancellation() },
            credentials = { reads += it; null })
        f.ready(wifi); f.start(); runCurrent()
        f.controller.configure(true, true, NetworkMode.AUTO, "demo-2")
        gate.complete(Unit); runCurrent(); assertEquals(listOf("demo-1"), reads)
        f.controller.accountWillChange("demo-2"); runCurrent(); assertTrue(f.controller.state.value.running)
        f.controller.accountWillChange("demo-1"); runCurrent(); assertFalse(f.controller.state.value.running)
    }

    @Test fun unavailableStorageStopsWithoutLeakingPayload() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, credentials, _ -> credentials(); error("unreachable") },
            credentials = { throw VaultUnavailableException() })
        f.ready(wifi); f.start(); runCurrent()
        assertTrue(f.blocked); assertFalse(f.controller.state.value.running)
        assertEquals(LoginTaskOutcome.FAILED, f.controller.state.value.outcome)
        assertEquals("账号存储不可用，认证已停止", f.controller.state.value.message)
    }

    @Test fun restoredControllerRejectsOldIntentAndDisabledAutomaticDoesNotStart() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> error("must not run") })
        f.ready(wifi, false); runCurrent()
        assertTrue(f.starts.isEmpty()); assertFalse(f.controller.serviceStarted("old-process-id"))
    }

    @Test fun newUiWaitsForReloadedAccountConfigurationWithoutCancellingExistingTask() = runTest {
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> awaitCancellation() })
        f.ready(wifi); val id = f.start(); runCurrent()
        f.controller.awaitAccountReadiness()
        assertTrue(f.controller.state.value.running)
        f.controller.cancel(id); runCurrent(); f.controller.serviceStopped(id)
        f.controller.leaveForeground(); f.controller.enterForeground(); runCurrent()
        assertEquals(1, f.starts.size)
        f.controller.configure(true, false, NetworkMode.AUTO, "demo-2")
        runCurrent(); assertEquals(1, f.starts.size)
    }

    @Test fun failedServiceStartCanBeRetriedManually() = runTest {
        val controller = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ -> error("no request") },
            { null }, { throw IllegalStateException("secret platform error") }, {})
        controller.configure(true, true, NetworkMode.AUTO, "demo")
        controller.wifiChanged(wifi); controller.enterForeground(); runCurrent()
        assertFalse(controller.state.value.running); assertFalse(controller.state.value.message.contains("secret"))
    }

    @Test fun slowTitleDoesNotCancelAuthenticationOrSubmitAgainAndLateSuccessIsShown() = runTest {
        var calls = 0
        val result = CompletableDeferred<LoginResult>()
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> calls++; result.await() })
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true)
        backgroundScope.launch { f.controller.state.collect { notice.update(it) } }
        f.ready(wifi); f.start(); runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        assertTrue(f.controller.state.value.running); assertEquals(1, calls)
        advanceTimeBy(2500); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        assertTrue(f.controller.state.value.running); assertEquals(1, calls)
        result.complete(LoginResult("虚构延迟成功", completed = true)); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
        assertTrue(f.controller.state.value.completed); assertEquals(1, calls)
    }

    @Test fun slowTitleFlowsToFailureAtHardDeadlineWithoutRestartingAuthentication() = runTest {
        var calls = 0
        val f = Fixture(backgroundScope, CampusAuthenticator { _, _, _, _ -> calls++; awaitCancellation() })
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true)
        backgroundScope.launch { f.controller.state.collect { notice.update(it) } }
        f.ready(wifi); f.start(); runCurrent()
        advanceTimeBy(74_999); runCurrent()
        assertTrue(f.controller.state.value.running)
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent()
        assertFalse(f.controller.state.value.running)
        assertEquals(LoginTaskOutcome.TIMED_OUT, f.controller.state.value.outcome)
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        assertEquals(1, calls)
        advanceTimeBy(2000); runCurrent(); assertNull(notice.state.value)
    }
}
