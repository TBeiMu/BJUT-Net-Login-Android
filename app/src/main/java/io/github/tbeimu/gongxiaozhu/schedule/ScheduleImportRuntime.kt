package io.github.tbeimu.gongxiaozhu.schedule

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Import ownership survives Activity/ViewModel recreation; only an active task keeps monitoring alive. */
class ScheduleImportRuntime internal constructor(
    private val scope: CoroutineScope,
    repository: ScheduleRepository,
    private val connections: Flow<ScheduleConnection?>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    startService: (String) -> Unit,
) {
    val controller = ScheduleController(scope, repository, ioDispatcher, startService)
    private var foreground = false
    private var monitoring: Job? = null

    init {
        scope.launch {
            controller.state.collect { if (!foreground && it.taskId == null) stopMonitoring() }
        }
    }

    fun enterForeground() {
        foreground = true
        controller.enterForeground()
        if (monitoring == null) {
            monitoring = scope.launch { connections.collect(controller::networkChanged) }
        }
    }

    fun leaveForeground() {
        foreground = false
        controller.leaveForeground()
        if (controller.state.value.taskId == null) stopMonitoring()
    }

    private fun stopMonitoring() {
        if (monitoring == null) return
        monitoring?.cancel()
        monitoring = null
        controller.networkObservationPaused()
    }

    companion object {
        @Volatile private var instance: ScheduleImportRuntime? = null
        fun get(context: Context): ScheduleImportRuntime = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                ScheduleImportRuntime(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    createScheduleRepository(app), scheduleNetworks(app)) { id ->
                    ContextCompat.startForegroundService(app, Intent(app, ScheduleImportService::class.java)
                        .putExtra(ScheduleImportService.TASK_ID, id))
                    Unit
                }
            }.also { instance = it }
        }
    }
}
