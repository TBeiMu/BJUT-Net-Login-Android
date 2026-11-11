package io.github.tbeimu.gongxiaozhu.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BillingScreen(state: AppUiState, modifier: Modifier, onAccounts: () -> Unit,
    onQuery: (String?) -> Unit, onOffline: (String) -> Unit, onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    val billing = state.billing
    val enabled = !state.busy && !billing.running
    val metrics = billing.snapshot?.metrics.orEmpty()
    var code by remember(billing.captcha) { mutableStateOf("") }
    var choosingDevice by remember(billing.snapshot?.devices) { mutableStateOf(false) }
    ReportDialogVisibility(choosingDevice, onDialogVisibilityChanged)
    val context = LocalContext.current
    if (choosingDevice) {
        AlertDialog(onDismissRequest = { choosingDevice = false }, title = { Text("选择要注销的在线设备") },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                billing.snapshot?.devices.orEmpty().forEachIndexed { index, device ->
                    OutlinedButton(onClick = { choosingDevice = false; onOffline(device.sessionId) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("choose_offline_$index")) {
                        Column { Text("设备 ${index + 1} · ${device.ip.ifBlank { "IPv6 设备" }}")
                            Text(device.mac.ifBlank { "MAC 未提供" }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { choosingDevice = false }) { Text("返回") } })
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("校园网资费", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(state.data.defaultAccount?.let { "${it.label} · ${maskUsername(it.username)}" } ?: "未添加校园网账号",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("已用流量", metrics["已用流量"], Modifier.weight(1f))
            MetricCard("可用流量", metrics["可用流量"], Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("消费保护", metrics["消费保护"], Modifier.weight(1f))
            MetricCard("账户余额", metrics["账户余额"], Modifier.weight(1f))
        }
        if (billing.running || billing.message.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (billing.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(billing.message, modifier = Modifier.testTag("billing_status"), style = MaterialTheme.typography.bodySmall)
            }
        }
        billing.loadedAt?.let { Text("更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        billing.captcha?.let { bytes ->
            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("学校自助服务验证码")
                bitmap?.let { Image(it, contentDescription = "登录验证码", modifier = Modifier.size(128.dp, 64.dp)) }
                    ?: Text("验证码图片无法显示，请重新查询或打开学校网页")
                OutlinedTextField(value = code, onValueChange = { code = it.take(4) }, label = { Text("验证码") },
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("billing_captcha"))
            }
        }
        Text("在线信息", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        when {
            billing.snapshot == null -> Text("暂无在线信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            billing.snapshot.devicesError != null -> Text(billing.snapshot.devicesError, color = MaterialTheme.colorScheme.error)
            billing.snapshot.devices.isEmpty() -> Text("暂无在线设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> billing.snapshot.devices.forEachIndexed { index, device ->
                Surface(Modifier.fillMaxWidth().testTag("online_device_$index"), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("设备 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { onOffline(device.sessionId) }, enabled = enabled,
                                modifier = Modifier.testTag("offline_device_$index")) { Text("注销") }
                        }
                        DeviceInfo("IPv4", device.ip.ifBlank { "未获取" })
                        if (device.ipv6.isNotBlank()) DeviceInfo("IPv6", device.ipv6)
                        DeviceInfo("MAC", device.mac.ifBlank { "未获取" })
                        DeviceInfo("登录时间", device.loginTime.ifBlank { "未获取" })
                        DeviceInfo("在线时长", device.minutes?.let { "$it 分钟" } ?: "未获取")
                        DeviceInfo("使用流量", device.traffic?.let { "${it.value} ${it.unit}" } ?: "未获取",
                            Modifier.testTag("device_traffic_$index"))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAccounts, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f)) { Text("管理账号") }
            Button(onClick = { onQuery(if (billing.captcha != null) code else null) },
                enabled = enabled && state.data.defaultAccount != null && (billing.captcha == null || code.length == 4),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).testTag("billing_login")) {
                Text(if (billing.snapshot == null || billing.captcha != null) "登录查询" else "刷新资费")
            }
            OutlinedButton(onClick = { choosingDevice = true }, enabled = enabled && !billing.snapshot?.devices.isNullOrEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f).testTag("billing_offline")) { Text("注销设备") }
        }
        TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, "https://jfself.bjut.edu.cn/Self/dashboard".toUri())) }
            catch (_: ActivityNotFoundException) { /* Devices without a browser can still query in-app. */ }
        }) { Text("打开学校自助服务网页") }
    }
}

@Composable
private fun MetricCard(label: String, metric: BillingMetric?, modifier: Modifier) {
    Surface(modifier.heightIn(min = 120.dp).testTag("metric_$label"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric?.value ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(metric?.unit.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceInfo(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
