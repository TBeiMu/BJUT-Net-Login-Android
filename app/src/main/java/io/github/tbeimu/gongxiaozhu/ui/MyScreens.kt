package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.BuildConfig
import io.github.tbeimu.gongxiaozhu.R
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import java.text.DateFormat
import java.util.Date

internal enum class MyDestination(val title: String, val glyph: AppGlyph) {
    SCHEDULE("课表管理", AppGlyph.CALENDAR), ACCOUNTS("校园网账号", AppGlyph.PERSON),
    NETWORK("校园网连接", AppGlyph.WIFI), APPEARANCE("外观设置", AppGlyph.IMAGE),
    UPDATES("应用更新", AppGlyph.DOWNLOAD), ABOUT("关于工小助", AppGlyph.INFO)
}

@Composable
internal fun MyHomeScreen(state: AppUiState, modifier: Modifier, onOpen: (MyDestination) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val identity = state.schedule.data.identity
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(identity?.name?.ifBlank { "同学" } ?: "工小助", fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (state.schedule.blocked) "课表暂不可用" else identity?.let { maskUsername(it.studentId) } ?: "未绑定课表",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }, contentScale = ContentScale.Crop)
            }
        }
        MyGroup(MyDestination.entries.take(3), onOpen)
        MyGroup(MyDestination.entries.drop(3), onOpen)
    }
}

@Composable
private fun MyGroup(entries: List<MyDestination>, onOpen: (MyDestination) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column {
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onOpen(entry) }
                    .testTag("my_${entry.name}").padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    LineGlyph(entry.glyph, Modifier.size(23.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
                    Text(entry.title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp)
                    LineGlyph(AppGlyph.CHEVRON, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
                }
            }
        }
    }
}

@Composable
internal fun MyDetailScreen(page: MyDestination, state: AppUiState, modifier: Modifier, model: AppViewModel,
    wallpaper: WallpaperState, onBack: () -> Unit, onResetAccounts: () -> Unit,
    onResetSchedule: () -> Unit, onDelete: (AccountSummary) -> Unit, updateActions: UpdateActions,
    onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { LineGlyph(AppGlyph.BACK) }
            Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (page == MyDestination.ACCOUNTS && !state.storageBlocked && !state.loading) {
            Accounts(state, Modifier.weight(1f), model::addAccount, model::editAccount, model::setDefault, onDelete)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                MyDestination.SCHEDULE -> {
                    val schedule = state.schedule
                    var cancelImportConfirmation by remember { mutableStateOf(false) }
                    LaunchedEffect(schedule.canCancelImport) { if (!schedule.canCancelImport) cancelImportConfirmation = false }
                    ReportDialogVisibility(schedule.canCancelImport && cancelImportConfirmation, onDialogVisibilityChanged)
                    Panel {
                        Text(schedule.data.identity?.name?.ifBlank { "课表账号" } ?: "研究生课表", style = MaterialTheme.typography.titleMedium)
                        Text(schedule.data.identity?.let { "学号 ${maskUsername(it.studentId)}" } ?: "尚未绑定课表账号")
                        schedule.data.complete?.let {
                            Text(it.term.title)
                            Text("更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("课表网络：${schedule.networkKind?.title ?: "暂不可用"} · 支持 WiFi 或移动数据", style = MaterialTheme.typography.bodySmall)
                        Text(schedule.message, style = MaterialTheme.typography.bodySmall)
                        if (schedule.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { model.scheduleController.pause() }) { Text("暂停导入") }
                        } else if (schedule.blocked) Button(onClick = model.scheduleController::reload) { Text("重试读取课表") }
                        else Button(onClick = model.scheduleController::start, enabled = !schedule.loading) {
                            Text(if (schedule.data.pending != null) "继续导入" else if (schedule.data.complete != null) "刷新整个学期" else "导入研究生课表")
                        }
                        CancelScheduleImportButton(schedule, model.scheduleController::cancelImport, cancelImportConfirmation) {
                            cancelImportConfirmation = it
                            onDialogVisibilityChanged(it && schedule.canCancelImport)
                        }
                    }
                    Text("课表账号独立于校园网账号。登录一次自动导入整个学期，完成后可离线查看。", style = MaterialTheme.typography.bodySmall)
                    if (schedule.data.identity != null || schedule.blocked) TextButton(onClick = onResetSchedule, enabled = !schedule.busy) { Text("更换 / 解绑课表账号") }
                }
                MyDestination.ACCOUNTS -> StorageRecovery(state, model, onResetAccounts)
                MyDestination.NETWORK -> if (state.storageBlocked || state.loading) StorageRecovery(state, model, onResetAccounts) else {
                    state.data.defaultAccount?.let { Text("当前账号：${it.label} · ${maskUsername(it.username)}", style = MaterialTheme.typography.bodySmall) }
                    NetworkPanel(state, model)
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("打开 App 自动登录", style = MaterialTheme.typography.titleSmall)
                                Text("失败后可手动重试", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.data.settings.autoLogin, enabled = !state.busy,
                                onCheckedChange = { model.updateSettings(state.data.settings.copy(autoLogin = it)) },
                                modifier = Modifier.semantics { contentDescription = "打开 App 自动登录" })
                        }
                        HorizontalDivider()
                        Text("网络模式", style = MaterialTheme.typography.titleSmall)
                        NetworkMode.entries.forEach { mode -> Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.data.settings.networkMode == mode, enabled = !state.busy,
                                onClick = { model.updateSettings(state.data.settings.copy(networkMode = mode)) },
                                modifier = Modifier.semantics { contentDescription = "网络模式：${mode.title}" })
                            Text(mode.title)
                        } }
                    }
                }
                MyDestination.APPEARANCE -> {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        wallpaper.image?.let { Image(it, "当前壁纸", Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }
                            ?: Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { Text("蓝青渐变 · 默认背景") }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = wallpaper.choose, enabled = !wallpaper.busy) { Text("更换壁纸") }
                            TextButton(onClick = wallpaper.clear, enabled = !wallpaper.busy) { Text("恢复默认背景") }
                            wallpaper.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                MyDestination.UPDATES -> UpdatePanel(updateActions)
                MyDestination.ABOUT -> Panel {
                    Text("工小助", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("版本 ${BuildConfig.VERSION_NAME}")
                    Text("北京工业大学校园助手 · Android 12 及以上")
                    Text("账号与课表加密保存在本机。卸载或清除应用数据会删除本机资料。", style = MaterialTheme.typography.bodySmall)
                    Text("本机加密保护存储，不代表校园网认证的网络传输已加密。", style = MaterialTheme.typography.bodySmall)
                    Text("课表视觉参考矿小助开源项目，以 Kotlin / Compose 独立实现。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StorageRecovery(state: AppUiState, model: AppViewModel, onReset: () -> Unit) {
    Panel {
        Text(if (state.loading) "正在读取账号…" else "账号存储暂不可用", style = MaterialTheme.typography.titleMedium)
        if (!state.loading) {
            Text("可以重试读取，或确认清除后重新录入。课表和应用更新可独立使用。")
            Button(onClick = model::reload, enabled = !state.busy) { Text("重试读取") }
            TextButton(onClick = onReset, enabled = !state.busy) { Text("清除本机账号并重新录入") }
        }
    }
}
