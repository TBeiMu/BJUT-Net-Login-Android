package io.github.tbeimu.gongxiaozhu.network

import org.junit.Assert.*
import org.junit.Test

class CampusConnectionNoticeTest {
    @Test fun onlyCompletedWifiTasksProduceASingleResult() {
        val notice = CampusConnectionNotice()
        notice.update(LoginUiState(completed = true, wifiConnected = true))
        assertNull(notice.pending.value)
        val success = LoginUiState(completed = true, wifiConnected = true, taskId = "fake-task")
        notice.update(success.copy(running = true)); assertNull(notice.pending.value)
        notice.update(success); assertEquals("fake-task", notice.pending.value)
        assertTrue(notice.consume("fake-task")); assertFalse(notice.consume("fake-task"))
        notice.update(success); assertNull(notice.pending.value)
        notice.update(success.copy(taskId = "next")); assertEquals("next", notice.pending.value)
    }
    @Test fun networkChangesNewTasksAndFailureDiscardPendingSuccess() {
        val notice = CampusConnectionNotice()
        val result = LoginUiState(completed = true, wifiConnected = true, taskId = "old")
        notice.update(result); notice.update(LoginUiState(wifiConnected = true)); assertNull(notice.pending.value)
        notice.update(result); notice.update(result.copy(taskId = "new", running = true)); assertNull(notice.pending.value)
        notice.update(result.copy(taskId = "new", completed = false)); assertNull(notice.pending.value)
        notice.update(result.copy(wifiConnected = false)); assertNull(notice.pending.value)
    }
    @Test fun backgroundResultWaitsForAFreshWifiSnapshot() {
        val notice = CampusConnectionNotice()
        val success = LoginUiState(completed = true, wifiConnected = true, taskId = "background")
        notice.suspendObservation();notice.update(success);assertNull(notice.pending.value)
        notice.resumeObservation(success);assertEquals("background",notice.pending.value)
        notice.suspendObservation();notice.resumeObservation(LoginUiState(wifiConnected = true))
        assertNull(notice.pending.value)
    }
}
