package io.github.tbeimu.gongxiaozhu.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import io.github.tbeimu.gongxiaozhu.MainActivity
import io.github.tbeimu.gongxiaozhu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounded, user-started data transfer. No credentials or restartable session is carried by an Intent. */
class ScheduleImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ScheduleController
    private var ownedId: String? = null
    private var latestStart = 0
    private var observer: Job? = null
    private var deadline: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        controller = ScheduleImportRuntime.get(this).controller
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "课表导入进度", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val id = intent?.getStringExtra(TASK_ID)
        if (intent?.action == PAUSE) {
            if (id != null) controller.pauseTask(id)
            if (ownedId == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (id != null && id == ownedId && controller.state.value.taskId == id) return START_NOT_STICKY
        if (ownedId != null && controller.state.value.taskId == ownedId) return START_NOT_STICKY
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(controller.state.value, id),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            if (id == null || !controller.serviceStarted(id)) {
                finishService()
                return START_NOT_STICKY
            }
            ownedId = id
            observer?.cancel()
            deadline?.cancel()
            releaseWakeLock()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:schedule-import").apply {
                setReferenceCounted(false)
                acquire(SCHEDULE_IMPORT_TIMEOUT_MS)
            }
            deadline = scope.launch { delay(SCHEDULE_IMPORT_TIMEOUT_MS); timeout() }
            observer = scope.launch(start = CoroutineStart.LAZY) {
                controller.state.collect { state ->
                    if (state.taskId != id) finishService()
                    else {
                        // Notification permission denial must not cancel a running import.
                        runCatching { getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(state, id)) }
                    }
                }
            }
            observer?.start()
        } catch (_: Exception) {
            if (id != null) controller.pauseTask(id, "导入服务无法运行，进度保留；请返回 App 手动重试")
            finishService()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: ScheduleUiState, id: String?): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra(OPEN_SCHEDULE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, NOTIFICATION_ID + 1,
            Intent(this, ScheduleImportService::class.java).setAction(PAUSE)
                .setData("gongxiaozhu://pause-schedule/$id".toUri()).putExtra(TASK_ID, id),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_mark)
            .setContentTitle("正在导入课表")
            .setContentText(if (state.total > 0) "已导入 ${state.imported}/${state.total} 周" else "正在连接学校，请稍候")
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(state.total, state.imported, state.total == 0)
            .addAction(0, "暂停导入", pause).build()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun finishService() {
        observer?.cancel()
        deadline?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStart)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ownedId?.let { controller.pauseTask(it, "已从最近任务关闭 App，导入已暂停；进度保留") }
        finishService()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (startId == latestStart) timeout()
    }

    private fun timeout() {
        ownedId?.let { controller.pauseTask(it, "导入达到运行时限，已暂停；进度保留，请手动继续") }
        finishService()
    }

    override fun onDestroy() {
        ownedId?.let(controller::serviceStopped)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TASK_ID = "schedule_import_task"
        const val OPEN_SCHEDULE = "open_schedule"
        private const val CHANNEL = "schedule_import"
        private const val NOTIFICATION_ID = 501
        private const val PAUSE = "io.github.tbeimu.gongxiaozhu.PAUSE_SCHEDULE_IMPORT"
    }
}
