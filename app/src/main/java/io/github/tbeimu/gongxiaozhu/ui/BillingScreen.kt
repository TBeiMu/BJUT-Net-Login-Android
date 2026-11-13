package io.github.tbeimu.gongxiaozhu.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
    val palette = gxz
    val quietBorder = BorderStroke(1.dp, palette.ink.copy(alpha = .16f))
    if (choosingDevice) {
        AlertDialog(onDismissRequest = { choosingDevice = false }, title = { Text("选择要注销的在线设备") },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                billing.snapshot?.devices.orEmpty().forEachIndexed { index, device ->
                    OutlinedButton(onClick = { choosingDevice = false; onOffline(device.sessionId) }, enabled = enabled,
                        shape = RoundedCornerShape(16.dp), border = quietBorder,
                        modifier = Modifier.fillMaxWidth().testTag("choose_offline_$index")) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("设备 ${index + 1} · ${device.ip.ifBlank { "IPv6 设备" }}")
                            Text(device.mac.ifBlank { "MAC 未提供" }, style = MaterialTheme.typography.bodySmall, color = palette.inkSoft) }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { choosingDevice = false }) { Text("返回") } })
    }
    val scroll = rememberScrollState()
    // While the school is being asked, empty figures shimmer instead of sitting still.
    val waiting = billing.running
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BillingHeader(state.data.defaultAccount?.let { "${it.label} · ${maskUsername(it.username)}" } ?: "未添加校园网账号",
            billing.message, billing.running, Modifier.parallaxHeader(scroll))
        // The four figures arrive as a Z: left to right, then the next row.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("已用流量", metrics["已用流量"], AppGlyph.TRAFFIC, waiting, Modifier.weight(1f).fillMaxHeight().entrance(40, fromScale = .96f))
            MetricCard("可用流量", metrics["可用流量"], AppGlyph.GAUGE, waiting,
                Modifier.weight(1f).fillMaxHeight().entrance(40 + Motion.STAGGER, fromScale = .96f))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("消费保护", metrics["消费保护"], AppGlyph.SHIELD, waiting,
                Modifier.weight(1f).fillMaxHeight().entrance(40 + Motion.STAGGER * 2, fromScale = .96f))
            MetricCard("账户余额", metrics["账户余额"], AppGlyph.COIN, waiting,
                Modifier.weight(1f).fillMaxHeight().entrance(40 + Motion.STAGGER * 3, fromScale = .96f))
        }
        billing.captcha?.let { bytes ->
            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            GlassCard {
                Text("学校自助服务验证码", style = MaterialTheme.typography.titleSmall)
                bitmap?.let { Image(it, contentDescription = "登录验证码", modifier = Modifier.size(128.dp, 64.dp).clip(RoundedCornerShape(10.dp))) }
                    ?: Text("验证码图片无法显示，请重新查询或打开学校网页", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = code, onValueChange = { code = it.take(4) }, label = { Text("验证码") },
                    singleLine = true, enabled = enabled, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("billing_captcha"))
            }
        }
        CtaButton(if (billing.snapshot == null || billing.captcha != null) "登录查询" else "刷新资费",
            { onQuery(if (billing.captcha != null) code else null) }, Modifier.entrance(40 + Motion.STAGGER * 4).testTag("billing_login"),
            enabled = enabled && state.data.defaultAccount != null && (billing.captcha == null || code.length == 4))
        Row(Modifier.fillMaxWidth().entrance(40 + Motion.STAGGER * 5), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAccounts, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
                border = quietBorder, modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("billing_accounts")) { Text("管理账号") }
            OutlinedButton(onClick = { choosingDevice = true }, enabled = enabled && !billing.snapshot?.devices.isNullOrEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp), border = quietBorder,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("billing_offline")) { Text("注销设备") }
        }
        SectionLabel("在线信息", Modifier.padding(top = 6.dp), trailing = billing.loadedAt?.let {
            "更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" })
        when {
            billing.snapshot == null -> Hint("暂无在线信息", palette.inkFaint)
            billing.snapshot.devicesError != null -> Hint(billing.snapshot.devicesError, MaterialTheme.colorScheme.error)
            billing.snapshot.devices.isEmpty() -> Hint("暂无在线设备", palette.inkFaint)
            else -> billing.snapshot.devices.forEachIndexed { index, device ->
                GlassCard(Modifier.entrance(40 + Motion.STAGGER * (6 + index.coerceAtMost(3))).testTag("online_device_$index"),
                    padding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 16.dp), spacing = 7.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlyphChip(AppGlyph.DEVICE)
                        Text("设备 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
        TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, "https://jfself.bjut.edu.cn/Self/dashboard".toUri())) }
            catch (_: ActivityNotFoundException) { /* Devices without a browser can still query in-app. */ }
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("打开学校自助服务网页")
            Spacer(Modifier.width(6.dp))
            LineGlyph(AppGlyph.EXTERNAL, Modifier.size(15.dp))
        }
    }
}

@Composable
private fun BillingHeader(account: String, message: String, running: Boolean, modifier: Modifier) {
    val statusStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyMedium)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("校园网资费", style = MaterialTheme.typography.headlineLarge, color = gxz.ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(account, Modifier.weight(1f).testTag("billing_account"), style = statusStyle,
                color = gxz.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.weight(1f)) {
                val textWidth = (constraints.maxWidth - with(density) { 22.dp.roundToPx() + 4.dp.roundToPx() }).coerceAtLeast(1)
                // Reserve for a longest compact hint, independently of the current message, at the shared font size.
                val lines = maxOf(2, measurer.measure("已注销，刷新失败，请重查", statusStyle,
                    constraints = Constraints(maxWidth = textWidth)).lineCount)
                val statusPixels = measurer.measure(List(lines) { "查询 WiFi" }.joinToString("\n"), statusStyle).size.height
                val statusHeight = maxOf(48.dp, with(density) { statusPixels.toDp() })
                Row(Modifier.fillMaxWidth().height(statusHeight).testTag("billing_status_area"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    Box(Modifier.size(22.dp).testTag("billing_status_loader")) { if (running) BrandLoader(Modifier.fillMaxSize()) }
                    Text(message, Modifier.testTag("billing_status"), style = statusStyle,
                        color = gxz.inkSoft, maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

/** One figure from the school: label, large value, unit. A missing value stays a dash, never a made-up zero. */
@Composable
private fun MetricCard(label: String, metric: BillingMetric?, glyph: AppGlyph, waiting: Boolean, modifier: Modifier) {
    val palette = gxz
    val valueStyle = LocalTextStyle.current.merge(MaterialTheme.typography.headlineMedium).copy(fontFeatureSettings = "tnum")
    val unitStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelMedium)
    val measurer = rememberTextMeasurer()
    val valueHeight = with(LocalDensity.current) { measurer.measure("未设置 0123—", valueStyle, maxLines = 1).size.height.toDp() }
    val unitHeight = with(LocalDensity.current) { measurer.measure("MB 元", unitStyle, maxLines = 1).size.height.toDp() }
    Column(modifier.heightIn(min = 124.dp).testTag("metric_$label").glass(palette)
        .shimmer(waiting, Color.White.copy(alpha = if (palette.dark) .07f else .5f)).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = palette.inkSoft)
            LineGlyph(glyph, Modifier.size(18.dp), palette.inkFaint)
        }
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // Figures count up from zero on arrival, and from the old value after a refresh.
            // Numeric placeholders, CJK values and units share measured slots before and after a query.
            Box(Modifier.fillMaxWidth().height(valueHeight), contentAlignment = Alignment.CenterStart) {
                RollingNumber(metric?.value ?: "—", valueStyle, if (metric == null) palette.inkFaint else palette.ink)
            }
            Box(Modifier.fillMaxWidth().height(unitHeight), contentAlignment = Alignment.CenterStart) {
                Text(metric?.unit.orEmpty(), style = unitStyle, color = palette.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DeviceInfo(label: String, value: String, modifier: Modifier = Modifier) {
    InfoRow(label, value, modifier.padding(end = 8.dp))
}
