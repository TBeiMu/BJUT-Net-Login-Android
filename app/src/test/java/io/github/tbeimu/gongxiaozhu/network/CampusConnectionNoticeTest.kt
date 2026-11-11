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

    @Test fun timeoutStartsAtThreeSecondsAndOnlyLastsOneSecondWhileTaskRuns() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(1500); notice.update(running.copy(message = "虚构进度")); runCurrent()
        advanceTimeBy(1499); runCurrent(); assertEquals(CampusTitlePhase.CONNECTING, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(999); runCurrent(); assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertNull(notice.state.value)
        notice.update(running); advanceTimeBy(5000); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun failureDuringTimeoutDoesNotExtendItOrDisplayAnotherTitle() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(3000); runCurrent()
        advanceTimeBy(400); notice.update(running.copy(running = false, outcome = LoginTaskOutcome.FAILED)); runCurrent()
        advanceTimeBy(599); runCurrent(); assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(1); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun quickFailureOnlyRestoresWeekAndNeverTreatsMessageTextAsSuccess() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent()
        notice.update(running.copy(running = false, outcome = LoginTaskOutcome.FAILED, message = "校园网已连接"))
        assertNull(notice.state.value)
        advanceTimeBy(5000); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun successBeforeThreeSecondsHasNoTimeout() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(2999); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTING, notice.state.value?.phase)
        notice.update(success); runCurrent(); advanceTimeBy(1); runCurrent()
        assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
    }

    @Test fun successDuringTimeoutOrAfterWeekStillAppearsOnceForTwoSeconds() = runTest {
        for (finishedAt in listOf(3300L, 5000L)) {
            val notice = CampusConnectionNotice(backgroundScope)
            notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(finishedAt); runCurrent()
            if (finishedAt == 3300L) assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
            else assertNull(notice.state.value)
            notice.update(success); runCurrent(); assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
            advanceTimeBy(1999); runCurrent(); assertEquals(CampusTitlePhase.CONNECTED, notice.state.value?.phase)
            advanceTimeBy(1); runCurrent(); assertNull(notice.state.value)
            notice.update(success); assertNull(notice.state.value)
        }
    }

    @Test fun actualDeadlineOrLateFailureDoesNotReplayConsumedTimeout() = runTest {
        for (outcome in listOf(LoginTaskOutcome.TIMED_OUT, LoginTaskOutcome.FAILED)) {
            val notice = CampusConnectionNotice(backgroundScope)
            notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(5000); runCurrent()
            assertNull(notice.state.value)
            notice.update(running.copy(running = false, outcome = outcome)); runCurrent(); assertNull(notice.state.value)
        }
    }

    @Test fun backgroundTimeoutWaitsForFreshWifiAndVisiblePageBeforeExpiring() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.update(running); runCurrent(); advanceTimeBy(3000); runCurrent(); notice.suspendObservation()
        val failed = running.copy(running = false, outcome = LoginTaskOutcome.FAILED)
        notice.update(failed); notice.setVisible(true); assertNull(notice.state.value)
        notice.setVisible(false); notice.resumeObservation(failed); advanceTimeBy(5000); runCurrent()
        assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        notice.setVisible(true); runCurrent(); advanceTimeBy(1000); runCurrent(); assertNull(notice.state.value)
    }

    @Test fun rotationAndProgressDoNotRestartWaitOrExtendTimeout() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(2500)
        notice.setVisible(false); notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(500); runCurrent(); assertEquals(CampusTitlePhase.TIMED_OUT, notice.state.value?.phase)
        advanceTimeBy(400); notice.setVisible(false); notice.setVisible(true); notice.update(running); runCurrent()
        advanceTimeBy(600); runCurrent(); assertNull(notice.state.value)
        notice.update(running); notice.setVisible(false); notice.setVisible(true); assertNull(notice.state.value)
    }

    @Test fun newTaskCancellationOrWifiChangeDropsTimeoutAndItsOldTimer() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(3000); runCurrent()
        advanceTimeBy(400); notice.update(running.copy(taskId = "next")); runCurrent()
        advanceTimeBy(600); runCurrent(); assertEquals(CampusTitleNotice("next", CampusTitlePhase.CONNECTING), notice.state.value)
        notice.update(running.copy(taskId = "next", outcome = LoginTaskOutcome.CANCELLED)); runCurrent()
        advanceTimeBy(5000); runCurrent(); assertNull(notice.state.value)
        notice.update(LoginUiState(wifiConnected = false)); assertNull(notice.state.value)
    }

    @Test fun needForManualModeSelectionDoesNotBecomeTimeout() = runTest {
        val notice = CampusConnectionNotice(backgroundScope)
        notice.setVisible(true); notice.update(running); runCurrent(); advanceTimeBy(3000); runCurrent()
        notice.update(running.copy(running = false, needsChoice = true, outcome = LoginTaskOutcome.NEEDS_CHOICE))
        assertNull(notice.state.value)
    }
}
