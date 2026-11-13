package io.github.tbeimu.gongxiaozhu.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** All identities, pages and networks in this suite are fictional. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleBackgroundImportTest {
    private class Session : ScheduleSession {
        var opens = 0
        var submits = 0
        var closed = false
        val weeks = mutableListOf<Int>()
        val finish = CompletableDeferred<Unit>()
        var loginResult: CompletableDeferred<ScheduleGate>? = null
        var cleanup: CompletableDeferred<Unit>? = null
        var closing: CompletableDeferred<Unit>? = null
        override suspend fun open(): ScheduleGate {
            opens++
            return ScheduleGate.Login(ScheduleLoginPage("fictional-token", "https://cas.bjut.edu.cn/login", "fictional page"))
        }
        override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate {
            submits++
            return loginResult?.await() ?: ScheduleGate.Ready(demoIdentity, demoTerm)
        }
        override suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek {
            weeks += number
            if (number == 2) {
                try { finish.await() }
                catch (error: CancellationException) {
                    cleanup?.let { withContext(NonCancellable) { it.await() } }
                    throw error
                }
            }
            return demoWeek(number)
        }
        override suspend fun resource(token: String, url: String): ScheduleResource? = null
        override fun close() { closed = true }
        override suspend fun awaitClosed() { check(closed); closing?.await() }
    }

    private fun route(session: Session, name: String = "fictional-wifi",
        kind: ScheduleNetworkKind = ScheduleNetworkKind.WIFI) = ScheduleConnection(name, kind) { session }

    @Test fun backgroundAndRepeatedReturnReuseTheSameLoginAndFinishTheWholeSemester() = runTest {
        val session = Session()
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", emptyMap()); runCurrent()
        val task = controller.state.value.taskId
        assertNotNull(task)
        assertEquals(1, controller.state.value.imported)
        repeat(3) {
            controller.leaveForeground(); runCurrent()
            assertTrue(controller.state.value.busy); assertFalse(session.closed)
            controller.enterForeground(); controller.start(); runCurrent()
            assertEquals(task, controller.state.value.taskId)
        }
        controller.leaveForeground(); session.finish.complete(Unit); runCurrent()
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        assertEquals(2, controller.state.value.data.complete!!.weeks.size)
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.taskId)
        assertNull(controller.state.value.page); assertNull(controller.state.value.data.pending)
        assertTrue(session.closed); assertEquals(1, session.opens); assertEquals(1, session.submits)
        controller.enterForeground(); runCurrent()
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        assertEquals(listOf(1, 2), session.weeks)
    }

    @Test fun backgroundImmediatelyAfterSubmitCanStillVerifyIdentityAndImport() = runTest {
        val session = Session().apply { loginResult = CompletableDeferred() }
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", mapOf("password" to "fictional-password"))
        controller.leaveForeground(); runCurrent()
        assertNull(controller.state.value.page); assertFalse(session.closed)
        session.loginResult!!.complete(ScheduleGate.Ready(demoIdentity, demoTerm))
        session.finish.complete(Unit); runCurrent()
        assertNotNull(controller.state.value.data.complete); assertEquals(1, session.submits)
    }

    @Test fun loginReturningAnotherFormInBackgroundClearsSessionAndNeverRetries() = runTest {
        val session = Session().apply { loginResult = CompletableDeferred() }
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", emptyMap()); controller.leaveForeground(); runCurrent()
        session.loginResult!!.complete(ScheduleGate.Login(ScheduleLoginPage("other-token", "https://cas.bjut.edu.cn/login", "fictional")))
        runCurrent()
        assertTrue(session.closed); assertNull(controller.state.value.page)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        controller.enterForeground(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, session.submits); assertTrue(session.weeks.isEmpty())
    }

    @Test fun serviceMustAcknowledgeBeforeAnyNetworkRequestAndLateAcknowledgementIsIgnored() = runTest {
        val ids = mutableListOf<String>()
        val session = Session()
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler), ids::add)
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        assertEquals(0, session.opens)
        assertFalse(controller.serviceStarted("stale-task"))
        advanceTimeBy(SCHEDULE_SERVICE_START_TIMEOUT_MS + 1); runCurrent()
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.taskId)
        assertFalse(controller.serviceStarted(ids.single()))
        assertEquals(0, session.opens)
        controller.start(); runCurrent()
        assertTrue(controller.serviceStarted(ids.last())); runCurrent()
        assertEquals(1, session.opens); assertNotNull(controller.state.value.page)
        controller.leaveForeground(); runCurrent()
    }

    @Test fun rejectedServiceStartSendsNoRequestAndKeepsOldSchedule() = runTest {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
        val old = repo.finish(100).complete
        val session = Session()
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)) { error("fictional rejection") }
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.taskId)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertEquals(old, controller.state.value.data.complete); assertEquals(0, session.opens)
    }

    @Test fun oldServiceCallbacksCannotPauseTheNextTaskAndCurrentStopKeepsProgress() = runTest {
        val session = Session()
        val ids = mutableListOf<String>()
        lateinit var controller: ScheduleController
        controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)) {
            ids += it; controller.serviceStarted(it)
        }
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        val loginPageTask = ids.single()
        controller.submit("fictional-token", emptyMap()); runCurrent()
        val importTask = ids.last()
        assertNotEquals(loginPageTask, importTask)
        controller.serviceStopped(loginPageTask); controller.pauseTask(loginPageTask)
        assertFalse(controller.serviceStarted(loginPageTask))
        runCurrent()
        assertTrue(controller.state.value.busy); assertFalse(session.closed)
        controller.leaveForeground(); controller.serviceStopped(importTask); runCurrent()
        assertTrue(session.closed); assertFalse(controller.state.value.busy)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertNull(controller.state.value.taskId)
    }

    @Test fun thirtyMinuteDeadlineStopsBackgroundImportWithoutRetrying() = runTest {
        val session = Session()
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", emptyMap()); runCurrent(); controller.leaveForeground()
        advanceTimeBy(SCHEDULE_IMPORT_TIMEOUT_MS + 1); runCurrent()
        assertTrue(session.closed); assertFalse(controller.state.value.busy)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        assertNull(controller.state.value.data.complete); assertNull(controller.state.value.taskId)
        assertEquals(1, session.submits)
    }

    @Test fun runtimeKeepsItsNetworkSubscriptionUntilBackgroundImportCleanupFinishes() = runTest {
        val session = Session().apply { cleanup = CompletableDeferred() }
        val connections = MutableStateFlow<ScheduleConnection?>(route(session))
        lateinit var runtime: ScheduleImportRuntime
        runtime = ScheduleImportRuntime(backgroundScope, repository(), connections, StandardTestDispatcher(testScheduler)) {
            runtime.controller.serviceStarted(it)
        }
        runCurrent(); runtime.enterForeground(); runCurrent()
        val controller = runtime.controller
        controller.start(); runCurrent(); controller.submit("fictional-token", emptyMap()); runCurrent()
        runtime.leaveForeground(); runCurrent()
        assertEquals(1, connections.subscriptionCount.value)
        val other = Session()
        connections.value = route(other, "fictional-mobile", ScheduleNetworkKind.CELLULAR); runCurrent()
        assertTrue(session.closed); assertTrue(controller.state.value.busy)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        runtime.enterForeground(); controller.start(); runCurrent()
        assertEquals(0, other.opens)
        runtime.leaveForeground(); runCurrent()
        assertEquals(1, connections.subscriptionCount.value)
        session.cleanup!!.complete(Unit); runCurrent()
        assertFalse(controller.state.value.busy)
        assertEquals(0, connections.subscriptionCount.value)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        runtime.enterForeground(); runCurrent()
        assertEquals(ScheduleNetworkKind.CELLULAR, controller.state.value.networkKind)
        assertEquals(0, other.opens)
        controller.start(); runCurrent(); controller.submit("fictional-token", emptyMap()); runCurrent()
        other.finish.complete(Unit); runCurrent()
        assertEquals(listOf(2), other.weeks)
        assertNotNull(controller.state.value.data.complete)
    }

    @Test fun runtimeReleasesNetworkAfterBackgroundSuccessAndDoesNotRestartOnReturn() = runTest {
        val session = Session()
        val connections = MutableStateFlow<ScheduleConnection?>(route(session))
        lateinit var runtime: ScheduleImportRuntime
        runtime = ScheduleImportRuntime(backgroundScope, repository(), connections, StandardTestDispatcher(testScheduler)) {
            runtime.controller.serviceStarted(it)
        }
        runCurrent(); runtime.enterForeground(); runCurrent()
        val controller = runtime.controller
        controller.start(); runCurrent(); controller.submit("fictional-token", emptyMap()); runCurrent()
        runtime.leaveForeground(); runCurrent()
        assertEquals(1, connections.subscriptionCount.value)
        session.finish.complete(Unit); runCurrent()
        assertEquals(0, connections.subscriptionCount.value)
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        runtime.enterForeground(); runCurrent()
        assertEquals(1, connections.subscriptionCount.value)
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        assertEquals(1, session.opens); assertEquals(1, session.submits)
    }

    @Test fun backgroundDisconnectPreservesCompleteScheduleAndParsedRefreshProgress() = runTest {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
        val old = repo.finish(100).complete
        val session = Session()
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", emptyMap()); runCurrent(); controller.leaveForeground()
        controller.networkChanged(null); runCurrent()
        session.finish.complete(Unit); runCurrent()
        assertTrue(session.closed); assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertEquals(old, controller.state.value.data.complete)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        controller.enterForeground(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, session.submits)
    }

    @Test fun immediateCancellationPublishesPauseBeforeCleanupCanAnnounceIdle() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val session = Session()
        val controller = ScheduleController(scope, repository(), dispatcher)
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start()
        controller.submit("fictional-token", emptyMap())
        assertTrue(controller.state.value.busy)
        val observed = mutableListOf<ScheduleUiState>()
        scope.launch { controller.state.collect { observed += it } }
        controller.pause()
        assertFalse(controller.state.value.busy)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertTrue(observed.none { !it.busy && it.notice == ScheduleNotice.PROGRESS })
        assertTrue(session.closed)
    }

    @Test fun cancelledImportRetainsItsTaskUntilSessionResourcesAreClosed() = runTest {
        val first = Session().apply { closing = CompletableDeferred() }
        val next = Session()
        var selected = first
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(ScheduleConnection("fictional-wifi", ScheduleNetworkKind.WIFI) { selected })
        controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("fictional-token", emptyMap()); runCurrent()
        val task = controller.state.value.taskId
        controller.pause(); runCurrent()
        assertTrue(first.closed)
        assertTrue(controller.state.value.busy)
        assertEquals(task, controller.state.value.taskId)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        selected = next
        controller.start(); runCurrent()
        assertEquals(0, next.opens)
        first.closing!!.complete(Unit); runCurrent()
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.taskId)
        controller.start(); runCurrent()
        assertEquals(1, next.opens)
        controller.leaveForeground(); runCurrent()
    }

    @Test fun reopeningLoginPageWaitsForPreviousPageResources() = runTest {
        val first = Session().apply { closing = CompletableDeferred() }
        val next = Session()
        var selected = first
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(ScheduleConnection("fictional-wifi", ScheduleNetworkKind.WIFI) { selected })
        controller.enterForeground(); controller.start(); runCurrent()
        assertFalse(controller.state.value.busy); assertNotNull(controller.state.value.page)
        selected = next
        controller.start(); runCurrent()
        assertTrue(first.closed); assertTrue(controller.state.value.busy)
        assertNull(controller.state.value.page); assertEquals(0, next.opens)
        first.closing!!.complete(Unit); runCurrent()
        assertEquals(1, next.opens); assertNotNull(controller.state.value.page)
        controller.leaveForeground(); runCurrent()
    }

    @Test fun cancellingIdleLoginPageWaitsBeforeDiscardingSavedProgress() = runTest {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
        val old = repo.finish(100).complete
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1))
        val session = Session().apply { closing = CompletableDeferred() }
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.networkChanged(route(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.cancelImport(); runCurrent()
        assertTrue(session.closed); assertTrue(controller.state.value.busy)
        assertNotNull(repo.snapshot().pending)
        session.closing!!.complete(Unit); runCurrent()
        assertFalse(controller.state.value.busy); assertNull(repo.snapshot().pending)
        assertEquals(old, repo.snapshot().complete)
        assertEquals(demoIdentity, repo.snapshot().identity)
    }
}
