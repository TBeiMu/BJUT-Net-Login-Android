package io.github.tbeimu.gongxiaozhu.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import io.github.tbeimu.gongxiaozhu.MainActivity
import io.github.tbeimu.gongxiaozhu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class CampusLoginService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: CampusLoginController
    private var ownedId: String? = null
    private var latestStart = 0
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deadline: Job? = null

    override fun onCreate() {
        super.onCreate()
        controller = CampusLoginRuntime.get(this).controller
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "校园网认证进度", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val id = intent?.getStringExtra(TASK_ID)
        if (intent?.action == CANCEL) {
            if (id != null) controller.cancel(id)
            if (ownedId == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (id != null && id == ownedId) return START_NOT_STICKY
        if (ownedId != null && controller.state.value.running) return START_NOT_STICKY
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(controller.state.value, id),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0)
            if (id == null || !controller.serviceStarted(id)) {
                finishService()
                return START_NOT_STICKY
            }
            ownedId = id
            deadline?.cancel()
            deadline = scope.launch { delay(LOGIN_TASK_TIMEOUT_MS); timeout() }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:campus-login").apply { setReferenceCounted(false); acquire(LOGIN_TASK_TIMEOUT_MS) }
            observer?.cancel()
            observer = scope.launch {
                controller.state.collect { state ->
                    if (state.taskId != id || !state.running) finishService()
                    else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state, id))
                }
            }
        } catch (_: Exception) {
            if (id != null) controller.cancel(id, "认证服务无法运行，请回到 App 手动重试")
            finishService()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: LoginUiState, id: String?): Notification {
        val open = PendingIntent.getActivity(this, 401,
            Intent(this, MainActivity::class.java).putExtra(OPEN_LOGIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 402,
            Intent(this, CampusLoginService::class.java).setAction(CANCEL)
                .setData("gongxiaozhu://cancel-login/$id".toUri()).putExtra(TASK_ID, id),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_update)
            .setContentTitle("正在认证校园网").setContentText(state.message)
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(0, 0, true).addAction(0, "取消认证", cancel).build()
    }

    private fun finishService() {
        observer?.cancel()
        deadline?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStart)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ownedId?.let { controller.cancel(it, "已从最近任务关闭 App，认证已停止") }
        finishService()
    }
    override fun onTimeout(startId: Int) { timeout() }
    override fun onTimeout(startId: Int, fgsType: Int) { timeout() }
    private fun timeout() {
        ownedId?.let { controller.cancel(it, "系统已停止认证，请手动检查联网状态") }
        finishService()
    }
    override fun onDestroy() {
        ownedId?.let(controller::serviceStopped)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TASK_ID = "campus_login_task"
        const val OPEN_LOGIN = "open_login"
        private const val CHANNEL = "campus_login"
        private const val NOTIFICATION_ID = 401
        private const val CANCEL = "io.github.tbeimu.gongxiaozhu.CANCEL_LOGIN"
    }
}
