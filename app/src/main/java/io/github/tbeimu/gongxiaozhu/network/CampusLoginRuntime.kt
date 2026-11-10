package io.github.tbeimu.gongxiaozhu.network

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A stopped observer cannot attest that WiFi is disconnected or still available. */
sealed interface WifiObservation {
    data object Suspended : WifiObservation
    data class Current(val connection: WifiConnection?) : WifiObservation
}

/** Shared account repository and a bounded authentication lifetime, independent of the UI. */
class CampusLoginRuntime private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val accounts = createAccountRepository(context)
    val schedules = io.github.tbeimu.gongxiaozhu.schedule.createScheduleRepository(context)
    val connectionNotice = CampusConnectionNotice(scope)
    val scheduleConnections = io.github.tbeimu.gongxiaozhu.schedule.scheduleNetworks(context)
    private val blocked = MutableStateFlow(false)
    val storageBlocked = blocked.asStateFlow()
    private val wifi = MutableStateFlow<WifiObservation>(WifiObservation.Suspended)
    val connections = wifi.asStateFlow()
    private val monitor = WifiMonitor(context)
    private var monitoring: Job? = null
    private var foreground = false
    val controller = CampusLoginController(scope, CampusLogin(),
        credentials = { id -> withContext(Dispatchers.IO) { accounts.credentialsForLogin(id) } },
        startService = { id -> ContextCompat.startForegroundService(context,
            Intent(context, CampusLoginService::class.java).putExtra(CampusLoginService.TASK_ID, id)); Unit },
        storageFailure = { blocked.value = true })

    init { scope.launch { controller.state.collect {
        connectionNotice.update(it)
        if (!it.running && !foreground) stopMonitoring()
    } } }

    fun enterForeground() {
        foreground = true
        if (monitoring == null) {
            controller.awaitWifiSnapshot()
            monitoring = scope.launch {
                monitor.connections.collect {
                    wifi.value = WifiObservation.Current(it)
                    controller.wifiChanged(it)
                    connectionNotice.resumeObservation(controller.state.value)
                }
            }
        }
        controller.enterForeground()
    }

    fun leaveForeground() {
        foreground = false
        connectionNotice.setVisible(false)
        controller.leaveForeground()
        if (!controller.state.value.running) stopMonitoring()
    }

    private fun stopMonitoring() {
        monitoring?.cancel()
        monitoring = null
        wifi.value = WifiObservation.Suspended
        connectionNotice.suspendObservation()
    }
    fun storageReloaded() { blocked.value = false }

    companion object {
        @Volatile private var instance: CampusLoginRuntime? = null
        fun get(context: Context): CampusLoginRuntime = instance ?: synchronized(this) {
            instance ?: CampusLoginRuntime(context.applicationContext).also { instance = it }
        }
    }
}
