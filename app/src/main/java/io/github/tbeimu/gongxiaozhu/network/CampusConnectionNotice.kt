package io.github.tbeimu.gongxiaozhu.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local presentation state; never starts authentication or survives process death. */
class CampusConnectionNotice {
    private val mutable = MutableStateFlow<String?>(null)
    val pending = mutable.asStateFlow()
    private var task: String? = null
    private var consumed: String? = null
    private var candidate: String? = null
    private var observing = true

    fun update(state: LoginUiState) {
        if (state.taskId != task) {
            task = state.taskId
            mutable.value = null
        }
        candidate = if (!state.running && state.completed && state.wifiConnected &&
            state.taskId != null && state.taskId != consumed) state.taskId else null
        mutable.value = candidate.takeIf { observing }
    }

    fun suspendObservation() { observing = false; mutable.value = null }
    fun resumeObservation(state: LoginUiState) { observing = true; update(state) }

    fun consume(id: String): Boolean {
        if (mutable.value != id) return false
        consumed = id
        candidate = null
        mutable.value = null
        return true
    }
}
