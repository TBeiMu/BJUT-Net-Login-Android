package io.github.tbeimu.gongxiaozhu.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusConnectionNoticeTest {
    private val running = LoginUiState(running = true, wifiConnected = true, taskId = "fake-task")
    private val success = running.copy(running = false, completed = true)

    @Test fun inProgressTitleChangesToSuccessThenExpiresOnce() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true)
        notice.update(running)
        assertEquals(CampusTitlePhase.CONNECTING, notice.state.value?.phase)
        advanceTimeBy(200); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTING, notice.state.value?.phase)
        notice.update(success); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
        advanceTimeBy(1999); runCurrent()
        assertNotNull(notice.state.value)
        advanceTimeBy(1); runCurrent()
        assertNull(notice.state.value)
        notice.update(success);notice.setVisible(false);notice.setVisible(true)
        assertNull(notice.state.value)
    }
    @Test fun successfulColdStartBeforePageIsVisibleWaitsForPresentation() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.update(success)
        advanceTimeBy(10_000);runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED,notice.state.value?.phase)
        notice.setVisible(true);runCurrent()
        advanceTimeBy(2000);runCurrent()
        assertNull(notice.state.value)
    }
    @Test fun rotationAndPageSwitchCannotResetSuccessDuration() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true);notice.update(success);runCurrent()
        advanceTimeBy(1000);notice.setVisible(false);notice.setVisible(true);runCurrent()
        advanceTimeBy(1000);runCurrent()
        assertNull(notice.state.value)
    }
    @Test fun oldExpiryCannotClearANewTask() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true);notice.update(success);runCurrent()
        advanceTimeBy(1000)
        notice.update(running.copy(taskId = "next"));runCurrent()
        advanceTimeBy(1499);runCurrent()
        assertEquals(CampusTitleNotice("next",CampusTitlePhase.CONNECTING),notice.state.value)
        notice.update(success.copy(taskId = "next"));runCurrent()
        advanceTimeBy(1999);runCurrent();assertNotNull(notice.state.value)
        advanceTimeBy(1);runCurrent();assertNull(notice.state.value)
    }
    @Test fun backgroundResultWaitsForFreshWifiAndNetworkChangeDropsIt() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.suspendObservation();notice.update(success);assertNull(notice.state.value)
        notice.setVisible(true);notice.resumeObservation(success);runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED,notice.state.value?.phase)
        notice.suspendObservation();notice.resumeObservation(LoginUiState(wifiConnected = true))
        assertNull(notice.state.value)
    }
    @Test fun failuresCancellationAndNonTaskStatesNeverBecomeSuccess() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true);notice.update(running)
        notice.update(running.copy(running = false));assertNull(notice.state.value)
        notice.update(LoginUiState(completed = true,wifiConnected = true));assertNull(notice.state.value)
        notice.update(success.copy(wifiConnected = false));assertNull(notice.state.value)
    }

    @Test fun slowWarningStartsAt1500msAndStaysUntilTaskCompletesAcrossProgressAndVisibilityChanges() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1000); notice.update(running.copy(message = "虚构进度变化")); runCurrent()
        advanceTimeBy(499); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTING, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(1000); notice.setVisible(false); notice.setVisible(true); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        notice.update(running.copy(message = "虚构后续进度")); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
    }

    @Test fun slowWarningFlowsDirectlyToFailureThenWeekWithNoIntermediateGap() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        // The old two-second warning timer used to restore the week at 3500 ms.
        advanceTimeBy(3500); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        notice.update(running.copy(outcome = LoginTaskOutcome.FAILED)); runCurrent()
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        notice.update(running.copy(running = false, outcome = LoginTaskOutcome.FAILED)); runCurrent()
        advanceTimeBy(1999); runCurrent()
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertNull(notice.state.value)
        notice.update(running.copy(running = false, outcome = LoginTaskOutcome.FAILED))
        notice.setVisible(false); notice.setVisible(true); assertNull(notice.state.value)
    }

    @Test fun lateSuccessCanReplaceSlowWarningAndStillExpiresOnce() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1800); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        notice.update(success); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
        advanceTimeBy(1700); runCurrent() // The former warning's expiry cannot clear success.
        assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
        advanceTimeBy(300); runCurrent()
        assertNull(notice.state.value)
        notice.update(success); assertNull(notice.state.value)
    }

    @Test fun structuredFailureWaitsForUnobstructedPageAndNeverUsesMessageText() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.update(running.copy(running = false, outcome = LoginTaskOutcome.FAILED, message = "虚构已连接文案"))
        advanceTimeBy(5000); runCurrent()
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        notice.setVisible(true); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertNull(notice.state.value)
        notice.setVisible(false); notice.setVisible(true); assertNull(notice.state.value)
    }

    @Test fun actualTaskTimeoutReplacesWarningWithFailureAndStartsItsOwnResultDuration() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(3500); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        notice.update(running.copy(running = false, outcome = LoginTaskOutcome.TIMED_OUT))
        runCurrent(); assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        advanceTimeBy(1999); runCurrent(); assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun backgroundSlowFailureWaitsForFreshWifiAndUnobstructedPageBeforeExpiring() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1500); runCurrent()
        notice.setVisible(false); notice.suspendObservation()
        val failed = running.copy(running = false, outcome = LoginTaskOutcome.FAILED)
        advanceTimeBy(3500); notice.update(failed); runCurrent()
        assertNull(notice.state.value)
        notice.resumeObservation(failed); advanceTimeBy(5000); runCurrent()
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        notice.setVisible(true); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertNull(notice.state.value)
    }

    @Test fun cancellationAndWifiChangeDiscardWarningAndOldDeadlineCannotAffectNextTask() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1000)
        notice.update(running.copy(outcome = LoginTaskOutcome.CANCELLED))
        assertNull(notice.state.value)
        notice.update(running.copy(taskId = "next")); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(CampusTitleNotice("next", CampusTitlePhase.CONNECTING), notice.state.value)
        advanceTimeBy(1000); runCurrent()
        assertEquals(CampusTitleNotice("next", CampusTitlePhase.TIMED_OUT), notice.state.value)
        notice.update(LoginUiState(wifiConnected = false))
        assertNull(notice.state.value)
        advanceTimeBy(5000); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun backgroundFailureWaitsForFreshWifiAndAChoiceIsNotAFailedLogin() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        val failed = running.copy(running = false, outcome = LoginTaskOutcome.FAILED)
        notice.suspendObservation(); notice.update(failed); notice.setVisible(true)
        assertNull(notice.state.value)
        notice.resumeObservation(failed); runCurrent()
        assertEquals(CampusTitlePhase.FAILED, notice.state.value?.phase)
        notice.update(running.copy(taskId = "choice", running = false, needsChoice = true,
            outcome = LoginTaskOutcome.NEEDS_CHOICE))
        assertNull(notice.state.value)
    }
}
