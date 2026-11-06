package io.github.tbeimu.gongxiaozhu.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tbeimu.gongxiaozhu.MainActivity
import io.github.tbeimu.gongxiaozhu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: UpdateRepository
    private var observing = false
    private var ownedPermit: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = UpdateRuntime.get(this).repository
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            repository.cancel()
            if (!observing) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, PROGRESS_ID, notification(repository.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val permit = intent?.getStringExtra(DOWNLOAD_PERMIT)
        if (repository.download(permit)) {
            ownedPermit = permit
        } else if (!observing) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                repository.state.distinctUntilChangedBy { Triple(it.phase, it.bytes / 65_536, it.message) }.collect { state ->
                    if (state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
                        getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, notification(state))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (state.phase in setOf(UpdatePhase.READY, UpdatePhase.PAUSED) && canNotify()) {
                            getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification(state, false))
                        }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: UpdateUiState, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_update)
            .setContentTitle(if (state.phase == UpdatePhase.READY) "工小助更新已下载" else "工小助更新")
            .setContentText(state.message).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(ongoing).setAutoCancel(!ongoing)
        if (ongoing) {
            val size = state.manifest?.apkSize ?: 0
            val percent = if (size > 0) (100 * state.bytes / size).toInt().coerceIn(0, 100) else 0
            builder.setProgress(100, percent, state.phase != UpdatePhase.DOWNLOADING)
            val cancel = PendingIntent.getService(this, 1, Intent(this, UpdateDownloadService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "取消下载", cancel)
        }
        return builder.build()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.serviceStopped(ownedPermit, "系统已停止下载，请回到 App 手动继续")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        repository.serviceStopped(ownedPermit)
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val OPEN_UPDATES = "open_updates"
        const val DOWNLOAD_PERMIT = "download_permit"
        private const val CHANNEL = "app_updates"
        private const val PROGRESS_ID = 301
        private const val RESULT_ID = 302
        private const val CANCEL = "io.github.tbeimu.gongxiaozhu.CANCEL_UPDATE"
    }
}
