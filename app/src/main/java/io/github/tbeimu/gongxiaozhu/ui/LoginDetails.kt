package io.github.tbeimu.gongxiaozhu.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tbeimu.gongxiaozhu.network.LoginUiState

@Composable
internal fun LoginNotificationPermission() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var settingsError by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                settingsError = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!enabled) {
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("允许通知以查看后台认证进度（可选）")
        }
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                } catch (_: ActivityNotFoundException) { settingsError = true }
            }
        }) { Text("打开系统通知设置") }
        if (settingsError) Text("请在手机设置中打开工小助的通知权限")
    }
}

@Composable
internal fun LoginDiagnostics(state: LoginUiState) {
    var expanded by remember { mutableStateOf(false) }
    if (state.diagnostics.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起认证耗时" else "查看认证耗时") }
        if (expanded) state.diagnostics.forEach {
            Text("${it.step.title} · ${it.elapsedMs} 毫秒 · ${it.result.title}")
        }
    }
}
