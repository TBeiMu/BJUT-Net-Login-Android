package io.github.tbeimu.gongxiaozhu.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Presentation only. The timeout title never changes the real LoginTaskOutcome. */
enum class CampusTitlePhase { CONNECTING, CONNECTED, TIMED_OUT }
data class CampusTitleNotice(val taskId: String, val phase: CampusTitlePhase)

internal const val CAMPUS_WARNING_DURATION_MS = 1000L
internal const val CAMPUS_SUCCESS_DURATION_MS = 2000L
internal const val CAMPUS_TITLE_WAIT_MS = 3000L

/** One authoritative, process-local title state. UI lifecycle never starts authentication. */
class CampusConnectionNotice(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<CampusTitleNotice?>(null)
    val state = mutable.asStateFlow()
    private var login = LoginUiState()
    private var observing = true
    private var visible = false
    private var taskId: String? = null
    private var slow = false
    private var slowDeadline: Job? = null
    private val consumed = mutableSetOf<CampusTitlePhase>()
    private var displayed: CampusTitleNotice? = null
    private var expiry: Job? = null

    fun update(value: LoginUiState) {
        if (taskId != value.taskId) {
            slowDeadline?.cancel(); slowDeadline = null
            stopDisplay()
            taskId = value.taskId
            slow = false
            consumed.clear()
        }
        login = value
        val id = taskId
        if (id != null && value.running && value.outcome != LoginTaskOutcome.CANCELLED) {
            if (!slow && slowDeadline == null) slowDeadline = scope.launch {
                // A presentation deadline only: never cancels authentication or sends credentials.
                delay(CAMPUS_TITLE_WAIT_MS)
                if (taskId == id && login.running && login.outcome != LoginTaskOutcome.CANCELLED) {
                    slow = true
                    slowDeadline = null
                    publish()
                }
            }
        } else {
            slowDeadline?.cancel(); slowDeadline = null
        }
        publish()
    }
    fun suspendObservation() { observing = false; publish() }
    fun resumeObservation(value: LoginUiState) { observing = true; update(value) }

    fun setVisible(value: Boolean) {
        visible = value
        publish()
    }

    private fun publish() {
        val id = login.taskId?.takeIf { observing && login.wifiConnected && login.outcome != LoginTaskOutcome.CANCELLED }
        val phase = when {
            login.completed -> CampusTitlePhase.CONNECTED
            login.outcome == LoginTaskOutcome.NEEDS_CHOICE -> null
            login.outcome == LoginTaskOutcome.TIMED_OUT ||
                (slow && (login.running || login.outcome == LoginTaskOutcome.FAILED)) -> CampusTitlePhase.TIMED_OUT
            login.outcome == LoginTaskOutcome.FAILED -> null
            login.running -> CampusTitlePhase.CONNECTING
            else -> null
        }
        if (id == null || phase == null) {
            stopDisplay()
            mutable.value = null
            return
        }
        if (phase == CampusTitlePhase.CONNECTING) {
            // Only the title expires. Authentication continues independently and can publish success.
            stopDisplay()
            mutable.value = CampusTitleNotice(id, phase)
            return
        }
        val presentation = CampusTitleNotice(id, phase)
        if (displayed != presentation) stopDisplay()
        if (displayed == presentation || phase !in consumed) {
            mutable.value = presentation
            if (visible && displayed != presentation) {
                consumed += phase
                displayed = presentation
                expiry = scope.launch {
                    delay(when (presentation.phase) {
                        CampusTitlePhase.TIMED_OUT -> CAMPUS_WARNING_DURATION_MS
                        else -> CAMPUS_SUCCESS_DURATION_MS
                    })
                    if (displayed == presentation) {
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
