package io.github.tbeimu.gongxiaozhu.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import io.github.tbeimu.gongxiaozhu.network.LoginUiState

@Composable
internal fun LoginNotificationPermission() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enabled = it }
    if (!enabled) TextButton(onClick = {
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }) { Text("允许通知以查看后台认证进度（可选）") }
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
