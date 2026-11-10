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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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

internal enum class Tab(val title: String) {
    SCHEDULE("课表"), BILLING("资费"), MY("我的")
}

@Composable
fun GongXiaoZhuApp(model: AppViewModel, openUpdatesRequest: Int = 0, openLoginRequest: Int = 0) = GongXiaoZhuTheme {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.SCHEDULE) }
    val snackbar = remember { SnackbarHostState() }
    val wallpaper = rememberWallpaper()
    var destination by rememberSaveable { mutableStateOf<MyDestination?>(null) }
    var returnTab by rememberSaveable { mutableStateOf(Tab.MY) }
    val pages = rememberSaveableStateHolder()
    fun open(page: MyDestination, from: Tab = tab) { returnTab = from; tab = Tab.MY; destination = page }
    fun back() { destination = null; tab = returnTab }
    var resetSchedule by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }
    var resetting by remember { mutableStateOf(false) }
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) open(MyDestination.UPDATES, Tab.MY)
    }
    LaunchedEffect(openLoginRequest) {
        if (openLoginRequest > 0) open(MyDestination.NETWORK, Tab.MY)
    }
    BackHandler(enabled = destination != null) { back() }

    LaunchedEffect(tab, destination, state.loading, state.storageBlocked) {
        model.billingVisible(tab == Tab.BILLING && destination == null && !state.loading && !state.storageBlocked)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    AppBackdrop(wallpaper) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item; destination = null },
                        icon = { NavigationGlyph(item.ordinal) },
                        label = { Text(item.title) })
                }
            }
        },
    ) { padding ->
        val pageKey = destination?.let { "detail/${it.name}" } ?: "tab/${tab.name}"
        pages.SaveableStateProvider(pageKey) {
            val selected = destination
            if (selected != null) {
                MyDetailScreen(selected, state, Modifier.padding(padding), model, wallpaper,
                    onBack = ::back, onResetAccounts = { resetting = true },
                    onResetSchedule = { resetSchedule = true }, onDelete = { deleting = it })
            } else when (tab) {
                Tab.SCHEDULE -> Box(Modifier.padding(padding).fillMaxSize()) {
                    ScheduleScreen(state.schedule, Modifier, model.scheduleController::start,
                        { model.scheduleController.pause() }, model.scheduleController::reload,
                        { open(MyDestination.SCHEDULE, Tab.SCHEDULE) }, connectionNotice = model.connectionNotice,
                        titleUnobstructed = state.schedule.page == null && state.editor == null && !resetSchedule &&
                            !resetting && deleting == null && state.billing.webPage == null && !state.billing.offerWeb &&
                            state.billing.confirmation == null, onCancelImport = model.scheduleController::cancelImport)
                }
                Tab.BILLING -> if (state.storageBlocked) Column(Modifier.padding(padding).padding(24.dp)) {
                    Text("校园网账号存储暂不可用")
                    Button(onClick = { open(MyDestination.ACCOUNTS, Tab.BILLING) }) { Text("处理账号存储") }
                } else BillingScreen(state, Modifier.padding(padding),
                    onAccounts = { open(MyDestination.ACCOUNTS, Tab.BILLING) },
                    onQuery = model::queryBilling, onOffline = model::requestOffline)
                Tab.MY -> MyHomeScreen(state, Modifier.padding(padding)) { open(it, Tab.MY) }
            }
        }
    }
    }
    if (resetSchedule) AlertDialog(onDismissRequest = { resetSchedule = false },
        title = { Text("清除课表绑定和缓存？") },
        text = { Text("本机已导入课表和未完成进度会被清除，可重新登录其他课表账号。校园网账号、密码和设置会保留。") },
        confirmButton = { TextButton(onClick = { resetSchedule = false; model.scheduleController.clear() }) { Text("确认清除课表") } },
        dismissButton = { TextButton(onClick = { resetSchedule = false }) { Text("取消") } })
    state.schedule.page?.let { page -> ScheduleWebDialog(page, state.schedule.busy, state.schedule.message,
        model.scheduleController::submit, model.scheduleController::resource,
        { model.scheduleController.pause() }, model.scheduleController::unsupportedWebView) }

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
internal fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
internal fun NetworkPanel(state: AppUiState, model: AppViewModel) {
    Panel {
        Text("校园网连接", style = MaterialTheme.typography.titleMedium)
        Text(if (state.login.wifiConnected) "WiFi 已连接" else "等待连接 WiFi")
        Text(state.login.message, modifier = Modifier.testTag("login_status"))
        if (state.login.running) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("可切换到后台或锁屏；划掉 App 会停止本次认证。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::cancelLogin, modifier = Modifier.testTag("cancel_login")) { Text("取消认证") }
        }
        LoginDiagnostics(state.login)
        LoginNotificationPermission()
        if (state.login.needsChoice) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.updateSettings(state.data.settings.copy(networkMode = NetworkMode.PUBLIC_WIFI)) }, enabled = !state.busy) { Text("公共 WiFi") }
            OutlinedButton(onClick = { model.updateSettings(state.data.settings.copy(networkMode = NetworkMode.DORM_WIFI)) }, enabled = !state.busy) { Text("宿舍 WiFi") }
        }
        Button(onClick = model::login, enabled = !state.busy && !state.login.running,
            modifier = Modifier.fillMaxWidth().testTag("login_button")) {
            Text(when { state.login.running -> "正在认证…"; state.login.completed -> "检查联网状态"; else -> "登录 / 重试" })
        }
    }
}

@Composable
internal fun AccountSelector(state: AppUiState, onSelect: (String) -> Unit, onAdd: () -> Unit) {
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
        }
    }
}

@Composable
internal fun Accounts(state: AppUiState, modifier: Modifier, onAdd: () -> Unit, onEdit: (String) -> Unit,
    onDefault: (String) -> Unit, onDelete: (AccountSummary) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { AccountSelector(state, onDefault, onAdd) }
        item { Text("已保存 ${state.data.accounts.size} 个账号", style = MaterialTheme.typography.bodySmall) }
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
