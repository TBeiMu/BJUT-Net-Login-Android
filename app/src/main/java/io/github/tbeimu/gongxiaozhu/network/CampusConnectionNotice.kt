package io.github.tbeimu.gongxiaozhu.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CampusTitlePhase { CONNECTING, CONNECTED }
data class CampusTitleNotice(val taskId: String, val phase: CampusTitlePhase)

/** One authoritative, process-local title state. UI lifecycle never starts authentication. */
class CampusConnectionNotice(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<CampusTitleNotice?>(null)
    val state = mutable.asStateFlow()
    private var login = LoginUiState()
    private var observing = true
    private var visible = false
    private var consumed: String? = null
    private var displayed: String? = null
    private var expiry: Job? = null

    fun update(value: LoginUiState) { login = value; publish() }
    fun suspendObservation() { observing = false; publish() }
    fun resumeObservation(value: LoginUiState) { observing = true; update(value) }

    fun setVisible(value: Boolean) {
        visible = value
        publish()
    }

    private fun publish() {
        val id = login.taskId?.takeIf { observing && login.wifiConnected }
        if (id == null || !login.running && !login.completed) {
            stopDisplay()
            mutable.value = null
            return
        }
        if (login.running) {
            stopDisplay()
            mutable.value = CampusTitleNotice(id, CampusTitlePhase.CONNECTING)
            return
        }
        if (displayed != id) stopDisplay()
        if (displayed == id || consumed != id) {
            mutable.value = CampusTitleNotice(id, CampusTitlePhase.CONNECTED)
            if (visible && displayed != id) {
                consumed = id
                displayed = id
                expiry = scope.launch {
                    delay(2000)
                    if (displayed == id) {
                        displayed = null
                        expiry = null
                        publish()
                    }
                }
            }
        } else mutable.value = null
    }

    private fun stopDisplay() {
        expiry?.cancel()
        expiry = null
        displayed = null
    }
}
