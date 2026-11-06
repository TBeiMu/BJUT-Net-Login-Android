package io.github.tbeimu.gongxiaozhu.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tbeimu.gongxiaozhu.BuildConfig
import io.github.tbeimu.gongxiaozhu.update.RELEASES_URL
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.update.UpdatePhase
import io.github.tbeimu.gongxiaozhu.update.UpdateRuntime
import io.github.tbeimu.gongxiaozhu.update.UpdateUiState
import io.github.tbeimu.gongxiaozhu.update.MobileDownloadConfirmation
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun UpdatePanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = UpdateRuntime.get(context).repository
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun install() {
        scope.launch {
            val file = repository.prepareInstall() ?: return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                repository.installationMessage("请回到更新页面，点击安装更新")
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { repository.installationMessage("无法打开系统安装器，请使用发布页下载安装") }
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else repository.installationMessage("尚未允许工小助安装更新；安装包已保留，可稍后再试")
    }
    fun startDownload(permit: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)
                .putExtra(UpdateDownloadService.DOWNLOAD_PERMIT, permit))
        } catch (_: Exception) { repository.downloadStartFailed(permit) }
    }
    fun requestDownload() {
        repository.requestDownload()?.let(::startDownload)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestDownload() }
    state.mobileConfirmation?.let { confirmation ->
        MobileDownloadDialog(confirmation, onConfirm = {
            repository.confirmMobileDownload(confirmation.id)?.let(::startDownload)
        }, onDismiss = { repository.dismissMobileDownload(confirmation.id) })
    }
    UpdateCard(state, repository::check, onDownload = {
        val preferences = context.getSharedPreferences("update_ui", android.content.Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("notification_asked", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit { putBoolean("notification_asked", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestDownload()
    }, onCancel = repository::cancel, onInstall = {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else try {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        } catch (_: Exception) { repository.installationMessage("无法打开安装权限设置，请在系统设置中允许工小助安装未知应用") }
    }, onReleasePage = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri())) }
        catch (_: Exception) { repository.installationMessage("没有可打开发布页的浏览器") }
    })
}

@Composable
internal fun MobileDownloadDialog(confirmation: MobileDownloadConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("使用移动数据下载更新？") },
        text = { Text("版本 ${confirmation.versionName} 的安装包约 ${String.format(Locale.CHINA, "%.1f", confirmation.apkBytes / 1048576.0)} MB。下载会消耗手机流量，本次是否继续？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("同意并下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun UpdateCard(state: UpdateUiState, onCheck: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onInstall: () -> Unit, onReleasePage: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("update_card")) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            if (state.enabled) Text("可用更新网络：${state.networkKind?.title ?: "未连接"}", style = MaterialTheme.typography.bodySmall)
            Text(state.message, modifier = Modifier.testTag("update_status"))
            state.manifest?.let { manifest ->
                Text("新版 ${manifest.versionName} · ${String.format(Locale.CHINA, "%.1f", manifest.apkSize / 1048576.0)} MB")
                Text(manifest.releaseNotes.ifBlank { "暂无更新说明" }, style = MaterialTheme.typography.bodySmall)
            }
            if (state.downloadStarting) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state.phase) {
                UpdatePhase.DOWNLOADING -> {
                    val size = state.manifest?.apkSize ?: 1
                    val progress = (state.bytes.toFloat() / size).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${(progress * 100).toInt()}% · 可离开 App")
                }
                UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
            if (state.enabled) {
                when {
                    state.phase == UpdatePhase.READY -> Button(onClick = onInstall, modifier = Modifier.testTag("install_update")) { Text("安装更新") }
                    state.manifest != null && !state.busy -> Button(onClick = onDownload) {
                        Text(if (state.phase == UpdatePhase.AVAILABLE) "下载更新" else "继续下载")
                    }
                    state.manifest == null && !state.busy -> Button(onClick = onCheck) { Text("检查更新") }
                }
                if ((state.manifest != null || state.phase == UpdatePhase.CHECKING) && state.phase != UpdatePhase.VERIFYING) {
                    OutlinedButton(onClick = onCancel) { Text("取消更新") }
                }
                Text("优先使用可上网的 WiFi；使用移动数据下载前会询问。切换网络后需手动继续。安装需系统确认，账号与设置会保留。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onReleasePage) { Text("打开发布页") }
        }
    }
}
