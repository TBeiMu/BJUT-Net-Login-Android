package io.github.tbeimu.gongxiaozhu.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.NetworkMode

private enum class Tab(val title: String, val glyph: String) {
    HOME("首页", "⌂"), BILLING("资费", "◎"), SETTINGS("设置", "⚙")
}

@Composable
fun GongXiaoZhuApp(model: AppViewModel, openUpdatesRequest: Int = 0, openLoginRequest: Int = 0) = GongXiaoZhuTheme {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) { tab = Tab.SETTINGS; managing = false }
    }
    LaunchedEffect(openLoginRequest) {
        if (openLoginRequest > 0) { tab = Tab.HOME; managing = false }
    }
    BackHandler(enabled = managing) { managing = false }

    LaunchedEffect(tab, managing, state.loading, state.storageBlocked) {
        model.billingVisible(tab == Tab.BILLING && !managing && !state.loading && !state.storageBlocked)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item; managing = false },
                        icon = { Text(item.glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(item.title) })
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            state.storageBlocked && tab != Tab.SETTINGS -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Heading("账号存储暂不可用", "请检查本机账号存储")
                Text("账号文件可能损坏，或加密密钥不可用。可以先重试；清除本机数据后，需要重新录入全部账号。")
                Button(onClick = model::reload, enabled = !state.busy) { Text("重试读取") }
                OutlinedButton(onClick = { resetting = true }, enabled = !state.busy) { Text("清除本机账号并重新录入") }
            }
            managing -> Column(Modifier.padding(padding).fillMaxSize()) {
                TextButton(onClick = { managing = false }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("返回") }
                Accounts(state, Modifier.weight(1f), model::addAccount, model::editAccount, model::setDefault, onDelete = { deleting = it })
            }
            else -> when (tab) {
                Tab.HOME -> Home(state, Modifier.padding(padding), onAdd = model::addAccount,
                    onAccounts = { managing = true }, onBilling = { tab = Tab.BILLING },
                    onSelect = model::setDefault, onLogin = model::login, onCancelLogin = model::cancelLogin,
                    onMode = { model.updateSettings(state.data.settings.copy(networkMode = it)) })
                Tab.BILLING -> BillingScreen(state, Modifier.padding(padding), onAccounts = { managing = true },
                    onQuery = model::queryBilling, onOffline = model::requestOffline)
                Tab.SETTINGS -> Settings(state, Modifier.padding(padding), model)
            }
        }
    }

    state.editor?.let { draft ->
        AccountEditor(draft, state.busy, state.editorError, onDismiss = model::closeEditor, onSave = model::saveAccount)
    }
    if (state.billing.offerWeb) {
        AlertDialog(onDismissRequest = model::dismissWebOffer,
            title = { Text("自动查询登录未完成") },
            text = { Text("是否改用内置网页登录？将预填当前所选账号和密码，由你在学校页面点击登录。") },
            confirmButton = { TextButton(onClick = model::openBillingWeb) { Text("网页登录") } },
            dismissButton = { TextButton(onClick = model::dismissWebOffer) { Text("暂不使用") } })
    }
    state.billing.webPage?.let { page ->
        BillingWebDialog(page, state.billing.running, state.billing.message, model::submitBillingWeb,
            model::refreshBillingWebCaptcha, model::closeBillingWeb, model::unsupportedBillingWeb)
    }
    state.billing.confirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = model::dismissOffline,
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("注销这台在线设备？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("账号：${state.data.defaultAccount?.label.orEmpty()}")
                Text("IPv4：${confirmation.device.ip.ifBlank { "—" }}")
                Text("MAC：${confirmation.device.mac.ifBlank { "—" }}")
                Text("这会让选定设备断开校园网。若是本机，当前连接也会中断；工小助本次前台期间不会自动重新认证。")
                Text(confirmation.tip, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = model::confirmOffline, enabled = !state.busy && !state.billing.running) { Text("确认注销设备") } },
            dismissButton = { TextButton(onClick = model::dismissOffline) { Text("取消") } })
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
private fun Home(state: AppUiState, modifier: Modifier, onAdd: () -> Unit, onAccounts: () -> Unit,
    onBilling: () -> Unit, onSelect: (String) -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onMode: (NetworkMode) -> Unit) {
    val data = state.data
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Heading("工小助", "让每一次校园连接更简单")
        Panel {
            Text("默认登录账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            AccountSelector(state, onSelect, onAdd, onAccounts)
            HorizontalDivider()
            Text("网络模式：${data.settings.networkMode.title}")
            state.login.mode?.let { Text("本次识别：${it.title}") }
            Text("打开 App 自动登录：${if (data.settings.autoLogin) "已开启" else "已关闭"}")
        }
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("校园网助手", style = MaterialTheme.typography.labelLarge)
                Text(if (data.accounts.isEmpty()) "先保存你的账号" else "账号已准备就绪", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("连接校园 WiFi 后，使用默认账号检查并完成认证。")
                Button(onClick = if (data.accounts.isEmpty()) onAdd else onBilling) {
                    Text(if (data.accounts.isEmpty()) "添加校园网账号" else "查看校园网资费")
                }
            }
        }
        Panel {
            Text(if (state.login.wifiConnected) "WiFi 已连接" else "等待连接 WiFi", style = MaterialTheme.typography.titleMedium)
            Text(state.login.message, modifier = Modifier.testTag("login_status"))
            if (state.login.running) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("可切换到后台或锁屏；划掉 App 会停止本次认证。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancelLogin, modifier = Modifier.testTag("cancel_login")) { Text("取消认证") }
            }
            LoginDiagnostics(state.login)
            LoginNotificationPermission()
            if (state.login.needsChoice) {
                Text("选择后会保存网络模式，再点击登录。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMode(NetworkMode.PUBLIC_WIFI) }, enabled = !state.busy) { Text("公共 WiFi") }
                    OutlinedButton(onClick = { onMode(NetworkMode.DORM_WIFI) }, enabled = !state.busy) { Text("宿舍 WiFi") }
                }
            }
            Button(onClick = onLogin, enabled = !state.busy && !state.login.running,
                modifier = Modifier.fillMaxWidth().testTag("login_button")) {
                Text(when {
                    state.login.running -> "正在认证…"
                    state.login.completed -> "检查联网状态"
                    else -> "登录 / 重试"
                })
            }
        }
        Text("账号密码仅加密保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountSelector(state: AppUiState, onSelect: (String) -> Unit, onAdd: () -> Unit, onAccounts: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = !state.busy,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("account_selector")) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.data.defaultAccount?.label ?: "选择 / 添加校园网账号", style = MaterialTheme.typography.titleMedium)
                state.data.defaultAccount?.let { Text(maskUsername(it.username), style = MaterialTheme.typography.bodySmall) }
            }
            Text("⌄", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.data.accounts.forEach { account ->
                DropdownMenuItem(text = { Column {
                    Text(account.label + if (account.id == state.data.defaultAccountId) " · 当前" else "")
                    Text(maskUsername(account.username), style = MaterialTheme.typography.bodySmall)
                } }, onClick = { expanded = false; if (account.id != state.data.defaultAccountId) onSelect(account.id) },
                    modifier = Modifier.testTag("select_account_${account.id}"))
            }
            if (state.data.accounts.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(text = { Text("添加账号") }, onClick = { expanded = false; onAdd() })
            DropdownMenuItem(text = { Text("管理账号") }, onClick = { expanded = false; onAccounts() })
        }
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
        UpdatePanel()
        if (state.storageBlocked) {
            Panel { Text("账号设置暂不可用，可回到首页重试读取。应用更新仍可使用。") }
        } else {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("打开 App 自动登录", style = MaterialTheme.typography.titleMedium)
                        Text("每次进入前台最多尝试一次；失败后手动重试", style = MaterialTheme.typography.bodySmall)
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
                Text("识别有歧义时，请指定当前 WiFi 类型；更改模式后手动登录。", style = MaterialTheme.typography.bodySmall)
            }
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
                Text("账号资料加密保存在本机；登录时仅向学校认证地址提交默认账号。", style = MaterialTheme.typography.bodySmall)
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
