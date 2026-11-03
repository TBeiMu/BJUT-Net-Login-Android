package io.github.tbeimu.gongxiaozhu.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tbeimu.gongxiaozhu.BuildConfig
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.NetworkMode

private enum class Tab(val title: String, val glyph: String) {
    HOME("首页", "⌂"), ACCOUNTS("账号", "◎"), SETTINGS("设置", "⚙")
}

@Composable
fun GongXiaoZhuApp(model: AppViewModel) = GongXiaoZhuTheme {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }
    var resetting by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { Text(item.glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(item.title) })
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            state.storageBlocked -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Heading("账号存储暂不可用", "请检查本机账号存储")
                Text("账号文件可能损坏，或加密密钥不可用。可以先重试；清除本机数据后，需要重新录入全部账号。")
                Button(onClick = model::reload, enabled = !state.busy) { Text("重试读取") }
                OutlinedButton(onClick = { resetting = true }, enabled = !state.busy) { Text("清除本机账号并重新录入") }
            }
            else -> when (tab) {
                Tab.HOME -> Home(state.data, Modifier.padding(padding), onAdd = model::addAccount,
                    onAccounts = { tab = Tab.ACCOUNTS })
                Tab.ACCOUNTS -> Accounts(state, Modifier.padding(padding), model::addAccount,
                    model::editAccount, model::setDefault, onDelete = { deleting = it })
                Tab.SETTINGS -> Settings(state, Modifier.padding(padding), model)
            }
        }
    }

    state.editor?.let { draft ->
        AccountEditor(draft, state.busy, state.editorError, onDismiss = model::closeEditor, onSave = model::saveAccount)
    }
    deleting?.let { account ->
        AlertDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text("删除账号？") },
            text = { Text("将从本机删除“${account.label}”。删除默认账号后，会使用剩余的第一个账号作为默认账号。") },
            confirmButton = { TextButton(onClick = { deleting = null; model.deleteAccount(account.id) }, enabled = !state.busy) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (resetting) {
        AlertDialog(onDismissRequest = { resetting = false }, title = { Text("清除全部本机账号？") },
            text = { Text("所有保存的账号、密码和设置将被清除。此操作无法撤销，之后需要重新录入。") },
            confirmButton = { TextButton(onClick = { resetting = false; model.resetStorage() }, enabled = !state.busy) { Text("清除并重新录入") } },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("取消") } })
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Home(data: AccountState, modifier: Modifier, onAdd: () -> Unit, onAccounts: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Heading("工小助", "让每一次校园连接更简单")
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("校园网助手", style = MaterialTheme.typography.labelLarge)
                Text(if (data.accounts.isEmpty()) "先保存你的账号" else "账号已准备就绪", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("第二阶段已支持账号管理。校园网自动登录将在下一阶段接入。")
                Button(onClick = if (data.accounts.isEmpty()) onAdd else onAccounts) {
                    Text(if (data.accounts.isEmpty()) "添加校园网账号" else "管理账号")
                }
            }
        }
        Panel {
            Text("默认登录账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            data.defaultAccount?.let { account ->
                Text(account.label, style = MaterialTheme.typography.titleLarge)
                Text(maskUsername(account.username), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("尚未设置 · 添加首个账号后将自动设为默认")
            HorizontalDivider()
            Text("网络模式：${data.settings.networkMode.title}")
            Text("打开 App 自动登录：${if (data.settings.autoLogin) "已开启，待接入" else "已关闭"}")
        }
        Panel {
            Text("登录功能尚未接入", style = MaterialTheme.typography.titleMedium)
            Text("此阶段版本不会发送账号密码，也不会发起校园网认证请求。")
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("立即登录 · 下一阶段开放") }
        }
        Text("账号密码仅加密保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Accounts(state: AppUiState, modifier: Modifier, onAdd: () -> Unit, onEdit: (String) -> Unit,
    onDefault: (String) -> Unit, onDelete: (AccountSummary) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading("我的账号", "已保存 ${state.data.accounts.size} 个账号 · 密码不会在列表显示") }
        item { Button(onClick = onAdd, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("添加账号") } }
        if (state.data.accounts.isEmpty()) item {
            Panel { Text("还没有账号", style = MaterialTheme.typography.titleLarge); Text("添加校园网账号和密码，后续自动登录将使用默认账号。") }
        }
        items(state.data.accounts, key = { it.id }) { account ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(account.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (account.id == state.data.defaultAccountId) {
                        Text("默认", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                Text(maskUsername(account.username), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("密码已加密保存", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onEdit(account.id) }, enabled = !state.busy) { Text("编辑") }
                    TextButton(onClick = { onDefault(account.id) }, enabled = !state.busy && account.id != state.data.defaultAccountId) { Text("设为默认") }
                    TextButton(onClick = { onDelete(account) }, enabled = !state.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Text("切换默认账号不会退出当前网络会话。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Settings(state: AppUiState, modifier: Modifier, model: AppViewModel) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Heading("设置", "按你的习惯连接校园")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("打开 App 自动登录", style = MaterialTheme.typography.titleMedium)
                    Text("设置已可保存，登录功能待接入", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.data.settings.autoLogin,
                    onCheckedChange = { model.updateSettings(state.data.settings.copy(autoLogin = it)) }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "打开 App 自动登录" })
            }
        }
        Panel {
            Text("网络模式", style = MaterialTheme.typography.titleMedium)
            NetworkMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.data.settings.networkMode == mode, enabled = !state.busy,
                        onClick = { model.updateSettings(state.data.settings.copy(networkMode = mode)) },
                        modifier = Modifier.semantics { contentDescription = "网络模式：${mode.title}" })
                    Text(mode.title)
                }
            }
            Text("自动识别将在下一阶段接入，也可预先指定公共或宿舍 WiFi。", style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Text("关于工小助", style = MaterialTheme.typography.titleMedium)
            Text("版本 ${BuildConfig.VERSION_NAME}")
            Text("支持 Android 12 及以上 · 北工大校园网助手")
            Text("本机加密保护账号存储，不代表校园认证过程的网络传输已加密。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccountEditor(draft: AccountDraft, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (AccountDraft) -> Unit) {
    // Secrets stay in memory; never use rememberSaveable or SavedStateHandle for these fields.
    var label by remember(draft.id) { mutableStateOf(draft.label) }
    var username by remember(draft.id) { mutableStateOf(draft.username) }
    var password by remember(draft.id) { mutableStateOf(draft.password) }
    var visible by remember { mutableStateOf(false) }
    val activityWindow = (LocalView.current.context as? android.app.Activity)?.window
    DisposableEffect(activityWindow) {
        val wasSecure = activityWindow?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (draft.id == null) "添加账号" else "编辑账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("备注（可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("account_label"), enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("校园网账号") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("account_username"), enabled = !busy,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("account_password"), enabled = !busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } })
                Text("仅加密保存在本机，不上传账号。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            onClick = { onSave(AccountDraft(draft.id, label, username, password)) }) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("保存")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

internal fun maskUsername(value: String): String = when {
    value.length <= 2 -> "••"
    value.length <= 5 -> value.take(1) + "••" + value.takeLast(1)
    else -> value.take(2) + "••••" + value.takeLast(2)
}
