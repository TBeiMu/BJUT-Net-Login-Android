package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.BuildConfig
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import java.text.DateFormat
import java.util.Date

internal enum class MyDestination(val title: String, val glyph: AppGlyph) {
    SCHEDULE("课表管理", AppGlyph.CALENDAR), ACCOUNTS("校园网账号", AppGlyph.PERSON),
    NETWORK("校园网连接", AppGlyph.WIFI), APPEARANCE("外观设置", AppGlyph.IMAGE),
    UPDATES("检查更新", AppGlyph.DOWNLOAD), ABOUT("关于工小助", AppGlyph.INFO)
}

@Composable
internal fun MyHomeScreen(state: AppUiState, modifier: Modifier, checkingUpdates: Boolean = false,
    onOpen: (MyDestination) -> Unit) {
    val palette = gxz
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val identity = state.schedule.data.identity
        // Name and masked student number only: no avatar, no summary.
        Column(Modifier.fillMaxWidth().parallaxHeader(scroll).padding(start = 8.dp, end = 8.dp, top = 36.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(identity?.name?.ifBlank { "同学" } ?: "工小助", Modifier.entrance(0, distance = 10.dp),
                style = MaterialTheme.typography.displaySmall, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val bound = identity != null && !state.schedule.blocked
            Text(if (state.schedule.blocked) "课表暂不可用" else identity?.let { maskUsername(it.studentId) } ?: "未绑定课表",
                Modifier.entrance(Motion.STAGGER, distance = 10.dp).clip(CircleShape).background(palette.glass)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium, color = palette.inkSoft,
                fontFamily = if (bound) FontFamily.Monospace else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MyGroup(MyDestination.entries.take(3), checkingUpdates, onOpen, firstDelay = Motion.STAGGER * 2)
        MyGroup(MyDestination.entries.drop(3), checkingUpdates, onOpen, firstDelay = Motion.STAGGER * 5)
    }
}

@Composable
private fun MyGroup(entries: List<MyDestination>, checkingUpdates: Boolean, onOpen: (MyDestination) -> Unit, firstDelay: Int) {
    val palette = gxz
    Column(Modifier.fillMaxWidth().entrance(firstDelay, distance = 20.dp, fromScale = .97f).glass(palette)) {
        entries.forEachIndexed { index, entry ->
            val checking = entry == MyDestination.UPDATES && checkingUpdates
            if (index > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 20.dp), thickness = .7.dp, color = palette.hairline)
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            // Navigation rows lean toward their destination; the update action keeps its arrow fixed.
            val lean by animateFloatAsState(if (pressed) 1f else 0f, if (pressed) spring(stiffness = 900f) else Motion.bouncy(),
                label = "条目按压")
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(interaction, LocalIndication.current, enabled = !checking) { onOpen(entry) }
                .testTag("my_${entry.name}").padding(horizontal = 16.dp, vertical = 12.dp)
                .entrance(firstDelay + (index + 1) * Motion.STAGGER, distance = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GlyphChip(entry.glyph, Modifier.graphicsLayer { val s = 1 - lean * .1f; scaleX = s; scaleY = s })
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                androidx.compose.animation.Crossfade(checking, modifier = Modifier.size(22.dp), label = "检查更新进度") { busy ->
                    // Both crossfade layers occupy the same slot, including the last outgoing frame.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (busy) BrandLoader(Modifier.size(22.dp).testTag("my_update_checking"))
                        else LineGlyph(AppGlyph.CHEVRON, Modifier.size(15.dp).testTag("my_${entry.name}_chevron")
                            .graphicsLayer { translationX = if (entry == MyDestination.UPDATES) 0f else lean * 4.dp.toPx() },
                            palette.inkFaint.copy(alpha = .7f + lean * .3f))
                    }
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
    val palette = gxz
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
                LineGlyph(AppGlyph.BACK, Modifier.size(20.dp))
            }
            Text(page.title, style = MaterialTheme.typography.titleLarge, color = palette.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (page == MyDestination.ACCOUNTS && !state.storageBlocked && !state.loading) {
            Accounts(state, Modifier.weight(1f), model::addAccount, model::editAccount, model::setDefault, onDelete)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                MyDestination.SCHEDULE -> {
                    val schedule = state.schedule
                    var cancelImportConfirmation by remember { mutableStateOf(false) }
                    LaunchedEffect(schedule.canCancelImport) { if (!schedule.canCancelImport) cancelImportConfirmation = false }
                    ReportDialogVisibility(schedule.canCancelImport && cancelImportConfirmation, onDialogVisibilityChanged)
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GlyphChip(AppGlyph.CALENDAR)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(schedule.data.identity?.name?.ifBlank { "课表账号" } ?: "研究生课表", style = MaterialTheme.typography.titleMedium)
                                Text(schedule.data.identity?.let { "学号 ${maskUsername(it.studentId)}" } ?: "尚未绑定课表账号",
                                    style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                            }
                        }
                        HorizontalDivider(thickness = .7.dp, color = palette.hairline)
                        schedule.data.complete?.let {
                            Text(it.term.title, style = MaterialTheme.typography.bodyMedium)
                            Text("更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                        }
                        Text("课表网络：${schedule.networkKind?.title ?: "暂不可用"} · 支持 WiFi 或移动数据",
                            style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                        Text(schedule.message, style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                        if (schedule.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), trackColor = palette.well)
                            TextButton(onClick = { model.scheduleController.pause() }) { Text("暂停导入") }
                        } else if (schedule.blocked) CtaButton("重试读取课表", model.scheduleController::reload)
                        else CtaButton(if (schedule.data.pending != null) "继续导入" else if (schedule.data.complete != null) "刷新整个学期" else "导入研究生课表",
                            model.scheduleController::start, enabled = !schedule.loading)
                        CancelScheduleImportButton(schedule, model.scheduleController::cancelImport, cancelImportConfirmation) {
                            cancelImportConfirmation = it
                            onDialogVisibilityChanged(it && schedule.canCancelImport)
                        }
                    }
                    Text("课表账号独立于校园网账号。登录一次自动导入整个学期，完成后可离线查看。", Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
                    if (schedule.data.identity != null || schedule.blocked) TextButton(onClick = onResetSchedule, enabled = !schedule.busy) { Text("更换 / 解绑课表账号") }
                }
                MyDestination.ACCOUNTS -> StorageRecovery(state, model, onResetAccounts)
                MyDestination.NETWORK -> if (state.storageBlocked || state.loading) StorageRecovery(state, model, onResetAccounts) else {
                    state.data.defaultAccount?.let { Text("当前账号：${it.label} · ${maskUsername(it.username)}", Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.inkSoft) }
                    NetworkPanel(state, model)
                    GlassCard(padding = PaddingValues(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 10.dp), spacing = 8.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("打开 App 自动登录", style = MaterialTheme.typography.titleSmall)
                                Text("失败后可手动重试", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                            }
                            Switch(checked = state.data.settings.autoLogin, enabled = !state.busy,
                                onCheckedChange = { model.updateSettings(state.data.settings.copy(autoLogin = it)) },
                                modifier = Modifier.semantics { contentDescription = "打开 App 自动登录" })
                        }
                        HorizontalDivider(Modifier.padding(end = 6.dp, top = 4.dp, bottom = 4.dp), thickness = .7.dp, color = palette.hairline)
                        Text("网络模式", style = MaterialTheme.typography.titleSmall)
                        Column(Modifier.selectableGroup()) {
                            NetworkMode.entries.forEach { mode ->
                                val chosen = state.data.settings.networkMode == mode
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                                    .selectable(selected = chosen, enabled = !state.busy, role = Role.RadioButton,
                                        onClick = { model.updateSettings(state.data.settings.copy(networkMode = mode)) })
                                    .semantics { contentDescription = "网络模式：${mode.title}" },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(mode.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    RadioButton(selected = chosen, onClick = null, enabled = !state.busy,
                                        modifier = Modifier.padding(end = 12.dp))
                                }
                            }
                        }
                    }
                }
                MyDestination.APPEARANCE -> WallpaperSettingsPanel(wallpaper)
                MyDestination.UPDATES -> UpdatePanel(updateActions)
                MyDestination.ABOUT -> GlassCard(padding = PaddingValues(24.dp), spacing = 10.dp) {
                    // The mark builds itself once from a week of classes each time the page opens.
                    BrandLoader(Modifier.size(84.dp).padding(bottom = 6.dp), MarkMotion.WEEK)
                    Text("工小助", style = MaterialTheme.typography.displaySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("版本 ${BuildConfig.VERSION_NAME}")
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("北京工业大学校园助手 · Android 12 及以上", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = .7.dp, color = palette.hairline)
                    Text("账号与课表加密保存在本机。卸载或清除应用数据会删除本机资料。", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                    Text("本机加密保护存储，不代表校园网认证的网络传输已加密。", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                    Text("课表视觉参考矿小助开源项目，以 Kotlin / Compose 独立实现。", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun StorageRecovery(state: AppUiState, model: AppViewModel, onReset: () -> Unit) {
    GlassCard {
        Text(if (state.loading) "正在读取账号…" else "账号存储暂不可用", style = MaterialTheme.typography.titleMedium)
        if (!state.loading) {
            Text("可以重试读取，或确认清除后重新录入。课表和应用更新可独立使用。", style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            CtaButton("重试读取", model::reload, enabled = !state.busy)
            TextButton(onClick = onReset, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("清除本机账号并重新录入") }
        }
    }
}
