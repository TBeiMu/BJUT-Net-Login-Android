package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedLoginCleanupTest {
    private class Fixture(scope: CoroutineScope, winner: PortalEndpoint = PortalEndpoint.DORM_STATUS,
        confirmedAt: Long = 200, cleanupTime: Long = 3600, cleanupFails: Boolean = false) {
        var confirmed = false
        var cleaned = false
        val starts = mutableListOf<String>()
        val transport = object : PortalTransport {
            override suspend fun execute(request: PortalRequest): String {
                if (request.endpoint == winner) {
                    delay(confirmedAt); confirmed = true; return "{\"result\":1}"
                }
                check(request.endpoint in listOf(PortalEndpoint.PUBLIC_STATUS, PortalEndpoint.DORM_STATUS))
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { delay(cleanupTime); cleaned = true }
                    if (cleanupFails) throw IllegalStateException("fictional cleanup error")
                }
            }
            override suspend fun hasIpv4Internet(): Boolean = error("Authenticated status needs no further probe")
        }
        val wifi = WifiConnection("fictional-confirmed-wifi", transport)
        val controller = CampusLoginController(scope, CampusLogin(), { error("Must not read credentials") }, starts::add, {})
        val notice = CampusConnectionNotice(scope)
        init { scope.launch { controller.state.collect(notice::update) } }
        fun start(visible: Boolean = true) {
            notice.setVisible(visible)
            controller.configure(true, false, NetworkMode.AUTO, "fictional-account")
            controller.wifiChanged(wifi); controller.enterForeground(); controller.login()
            check(controller.serviceStarted(starts.single()))
        }
    }

    @Test fun eitherConfirmedEndpointPublishesBeforeCleanupWithoutReplayingSuccess() = runTest {
        for (winner in listOf(PortalEndpoint.DORM_STATUS, PortalEndpoint.PUBLIC_STATUS)) {
            val f = Fixture(backgroundScope, winner); f.start(); runCurrent()
            advanceTimeBy(200); runCurrent()
            assertTrue(f.confirmed); assertFalse(f.cleaned)
            assertTrue(f.controller.state.value.running); assertTrue(f.controller.state.value.completed)
            assertEquals(LoginTaskOutcome.SUCCESS, f.controller.state.value.outcome)
            assertEquals(CampusTitlePhase.CONNECTED, f.notice.state.value?.phase)
            f.controller.login(); runCurrent(); assertEquals(1, f.starts.size)
            advanceTimeBy(2000); runCurrent(); assertNull(f.notice.state.value)
            advanceTimeBy(1300); runCurrent() // The old 3.5-second title deadline must stay cancelled.
            assertTrue(f.controller.state.value.running); assertNull(f.notice.state.value)
            advanceTimeBy(300); runCurrent()
            assertTrue(f.cleaned); assertFalse(f.controller.state.value.running)
            assertTrue(f.controller.state.value.completed); assertNull(f.notice.state.value)
        }
    }

    @Test fun aLateConfirmationImmediatelyReplacesTimeoutWhileCleanupContinues() = runTest {
        val f = Fixture(backgroundScope, confirmedAt = 3800); f.start(); runCurrent()
        advanceTimeBy(3500); runCurrent(); assertEquals(CampusTitlePhase.TIMED_OUT, f.notice.state.value?.phase)
        advanceTimeBy(300); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED, f.notice.state.value?.phase)
        assertTrue(f.controller.state.value.running); assertFalse(f.cleaned)
        advanceTimeBy(700); runCurrent(); assertEquals(CampusTitlePhase.CONNECTED, f.notice.state.value?.phase)
        advanceTimeBy(1300); runCurrent(); assertNull(f.notice.state.value)
        advanceTimeBy(1600); runCurrent()
        assertTrue(f.cleaned); assertFalse(f.controller.state.value.running); assertNull(f.notice.state.value)
    }

    @Test fun hiddenConfirmationWaitsForVisiblePresentationAfterCleanup() = runTest {
        val f = Fixture(backgroundScope); f.start(visible = false); runCurrent()
        advanceTimeBy(200); runCurrent(); assertTrue(f.controller.state.value.completed)
        advanceTimeBy(3600); runCurrent(); assertFalse(f.controller.state.value.running)
        assertEquals(CampusTitlePhase.CONNECTED, f.notice.state.value?.phase)
        f.notice.setVisible(true); runCurrent()
        advanceTimeBy(1999); runCurrent(); assertEquals(CampusTitlePhase.CONNECTED, f.notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertNull(f.notice.state.value)
    }

    @Test fun cleanupErrorCannotDowngradeAnAlreadyConfirmedConnection() = runTest {
        val f = Fixture(backgroundScope, cleanupFails = true); f.start(); runCurrent()
        advanceTimeBy(200); runCurrent(); val message = f.controller.state.value.message
        advanceTimeBy(3600); runCurrent()
        assertTrue(f.cleaned); assertFalse(f.controller.state.value.running)
        assertTrue(f.controller.state.value.completed); assertEquals(LoginTaskOutcome.SUCCESS, f.controller.state.value.outcome)
        assertEquals(message, f.controller.state.value.message); assertNull(f.notice.state.value)
    }

    @Test fun authenticationDeadlineDoesNotEraseSuccessAlreadyConfirmedBeforeCleanup() = runTest {
        val f = Fixture(backgroundScope, cleanupTime = 62000); f.start(); runCurrent()
        advanceTimeBy(200); runCurrent(); assertTrue(f.controller.state.value.completed)
        advanceTimeBy(59800); runCurrent()
        assertTrue(f.controller.state.value.running); assertTrue(f.controller.state.value.completed)
        assertEquals(LoginTaskOutcome.SUCCESS, f.controller.state.value.outcome); assertNull(f.notice.state.value)
        advanceTimeBy(2200); runCurrent()
        assertTrue(f.cleaned); assertFalse(f.controller.state.value.running)
        assertTrue(f.controller.state.value.completed); assertEquals(LoginTaskOutcome.SUCCESS, f.controller.state.value.outcome)
    }

    @Test fun cancellationAfterConfirmationKeepsTheSlotUntilCleanupAndDropsTheTitle() = runTest {
        val f = Fixture(backgroundScope); f.start(); runCurrent(); advanceTimeBy(200); runCurrent()
        f.controller.cancel(); f.controller.serviceStopped(f.starts.single()); runCurrent()
        assertEquals(LoginTaskOutcome.CANCELLED, f.controller.state.value.outcome)
        assertFalse(f.controller.state.value.completed); assertNull(f.notice.state.value)
        f.controller.login(); runCurrent(); assertEquals(1, f.starts.size)
        assertTrue(f.controller.state.value.running); assertFalse(f.cleaned)
        advanceTimeBy(3600); runCurrent()
        assertFalse(f.controller.state.value.running); assertFalse(f.controller.state.value.completed)
        assertNull(f.notice.state.value)
    }

    @Test fun wifiChangeDuringCleanupCannotRestoreTheOldConfirmedConnection() = runTest {
        val f = Fixture(backgroundScope); f.start(); runCurrent(); advanceTimeBy(200); runCurrent()
        f.controller.wifiChanged(WifiConnection("fictional-other-wifi", PortalTransport { error("No new request") })); runCurrent()
        assertFalse(f.controller.state.value.completed); assertNull(f.notice.state.value)
        advanceTimeBy(3600); runCurrent()
        assertFalse(f.controller.state.value.running); assertFalse(f.controller.state.value.completed)
        assertEquals(LoginTaskOutcome.CANCELLED, f.controller.state.value.outcome); assertEquals(1, f.starts.size)
    }

    @Test fun oldConfirmationAndProgressCallbacksCannotWriteIntoANewTask() = runTest {
        val callbacks = mutableListOf<LoginProgress>()
        val results = listOf(CompletableDeferred<LoginResult>(), CompletableDeferred())
        val starts = mutableListOf<String>()
        val controller = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, progress ->
            val index = callbacks.size; callbacks += progress; results[index].await()
        }, { null }, starts::add, {})
        controller.configure(true, false, NetworkMode.AUTO, "fictional-account")
        controller.wifiChanged(WifiConnection("fictional-wifi", PortalTransport { error("No network request") }))
        controller.enterForeground(); controller.login(); controller.serviceStarted(starts.single()); runCurrent()
        callbacks[0].confirmed(LoginResult("first success", completed = true)); runCurrent()
        results[0].complete(LoginResult("first success", completed = true)); runCurrent()
        controller.serviceStopped(starts.single()); controller.login(); controller.serviceStarted(starts.last()); runCurrent()
        callbacks[1]("second progress"); runCurrent()
        callbacks[0].confirmed(LoginResult("stale success", completed = true)); callbacks[0]("stale progress"); runCurrent()
        assertEquals(starts.last(), controller.state.value.taskId); assertEquals("second progress", controller.state.value.message)
        assertTrue(controller.state.value.running); assertFalse(controller.state.value.completed)
        controller.cancel(); runCurrent()
    }

    @Test fun duplicateSignalsAndLateProgressDoNotRestartTheSuccessTimer() = runTest {
        lateinit var callback: LoginProgress
        val result = CompletableDeferred<LoginResult>(); val starts = mutableListOf<String>()
        val controller = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, progress -> callback = progress; result.await() },
            { null }, starts::add, {})
        val notice = CampusConnectionNotice(backgroundScope); notice.setVisible(true)
        backgroundScope.launch { controller.state.collect(notice::update) }
        controller.configure(true, false, NetworkMode.AUTO, "fictional-account")
        controller.wifiChanged(WifiConnection("fictional-wifi", PortalTransport { "" })); controller.enterForeground()
        controller.login(); controller.serviceStarted(starts.single()); runCurrent()
        callback.confirmed(LoginResult("confirmed success", completed = true)); runCurrent()
        advanceTimeBy(1000); callback.confirmed(LoginResult("duplicate success", completed = true)); callback("late progress"); runCurrent()
        assertEquals("confirmed success", controller.state.value.message)
        advanceTimeBy(1000); runCurrent(); assertNull(notice.state.value)
        result.complete(LoginResult("late failure")); runCurrent()
        assertTrue(controller.state.value.completed); assertEquals(LoginTaskOutcome.SUCCESS, controller.state.value.outcome)
        assertNull(notice.state.value)
    }

    @Test fun anUnconfirmedOrAmbiguousSignalCannotClaimConnection() = runTest {
        lateinit var callback: LoginProgress
        val starts = mutableListOf<String>()
        val controller = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, progress -> callback = progress; awaitCancellation() },
            { null }, starts::add, {})
        controller.configure(true, false, NetworkMode.AUTO, "fictional-account")
        controller.wifiChanged(WifiConnection("fictional-wifi", PortalTransport { "" })); controller.enterForeground()
        controller.login(); controller.serviceStarted(starts.single()); runCurrent()
        callback.confirmed(LoginResult("not confirmed"))
        callback.confirmed(LoginResult("ambiguous", needsChoice = true, completed = true)); runCurrent()
        assertFalse(controller.state.value.completed); assertNull(controller.state.value.outcome)
        controller.cancel(); runCurrent()
    }
}
