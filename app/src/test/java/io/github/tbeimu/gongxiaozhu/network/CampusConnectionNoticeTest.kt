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
        advanceTimeBy(3000); runCurrent()
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
        advanceTimeBy(1500);runCurrent()
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
}
