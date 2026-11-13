package io.github.tbeimu.gongxiaozhu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountValidationException
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import io.github.tbeimu.gongxiaozhu.billing.BillingController
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState

data class AppUiState(
    val data: AccountState = AccountState(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val storageBlocked: Boolean = false,
    val message: String? = null,
    val editor: AccountDraft? = null,
    val editorError: String? = null,
    val login: LoginUiState = LoginUiState(),
    val billing: BillingUiState = BillingUiState(),
    val schedule: ScheduleUiState = ScheduleUiState(),
) {
    override fun toString() = "AppUiState(loading=$loading, busy=$busy, storageBlocked=$storageBlocked)"
}

class AppViewModel(private val runtime: CampusLoginRuntime) : ViewModel() {
    private val repository: AccountRepository = runtime.accounts
    private val mutable = MutableStateFlow(AppUiState())
    val state = mutable.asStateFlow()

    private val loginController = runtime.controller
    val connectionNotice = runtime.connectionNotice
    val nameWriteFailure = runtime.accountNames.writeFailure
    fun consumeNameWriteFailure() = runtime.accountNames.consumeWriteFailure()
    val scheduleController = runtime.scheduleImport.controller

    private val billingController = BillingController(viewModelScope,
        credentials = { id -> withContext(Dispatchers.IO) { repository.credentialsForLogin(id) } },
        publish = { billing -> mutable.update { it.copy(billing = billing) } },
        storageFailure = {
            runtime.storageUnavailable()
            mutable.update { it.copy(storageBlocked = true, editor = null, editorError = null) }
        },
        beforeOffline = loginController::deviceOfflineRequested,
        awaitLoginStopped = loginController::awaitStopped)

    init {
        loginController.awaitAccountReadiness()
        reload()
        viewModelScope.launch { repository.state.collect { data ->
            if (data != null) mutable.update { it.copy(data = repository.state.value ?: data) }
        } }
        viewModelScope.launch { scheduleController.state.collect { schedule -> mutable.update { it.copy(schedule = schedule) } } }
        viewModelScope.launch { loginController.state.collect { login -> mutable.update { it.copy(login = login) } } }
        viewModelScope.launch { runtime.storageBlocked.collect { blocked ->
            if (blocked) mutable.update { it.copy(storageBlocked = true, editor = null, editorError = null) }
        } }
        viewModelScope.launch { runtime.connections.collect(::billingWifiObserved) }
        viewModelScope.launch {
            state.collect {
                billingController.accountChanged(if (it.loading || it.storageBlocked) null else it.data.defaultAccountId)
                if (!it.loading) loginController.configure(ready = !it.storageBlocked,
                    automatic = it.data.settings.autoLogin && it.data.defaultAccount != null,
                    mode = it.data.settings.networkMode, accountId = it.data.defaultAccountId)
            }
        }
    }

    private fun billingWifiObserved(observation: WifiObservation) {
        when (observation) {
            WifiObservation.Suspended -> billingController.wifiObservationPaused()
            is WifiObservation.Current -> billingController.wifiChanged(observation.connection)
        }
    }

    fun enterForeground() {
        runtime.enterForeground()
        billingWifiObserved(runtime.connections.value)
        billingController.enterForeground()
        runtime.scheduleImport.enterForeground()
    }
    fun leaveForeground() {
        runtime.scheduleImport.leaveForeground()
        runtime.leaveForeground(); billingController.leaveForeground()
    }
    fun login() { if (!state.value.busy && state.value.editor == null) loginController.login() }
    fun cancelLogin() = loginController.cancel()
    fun billingVisible(visible: Boolean) = billingController.setVisible(visible)
    fun queryBilling(code: String? = null) {
        if (!state.value.busy && !state.value.storageBlocked && state.value.editor == null) billingController.query(code)
    }
    fun requestOffline(id: String) { if (!state.value.busy) billingController.requestOffline(id) }
    fun confirmOffline() { if (!state.value.busy) billingController.confirmOffline() }
    fun dismissOffline() = billingController.dismissOffline()
    fun dismissWebOffer() = billingController.dismissWebOffer()
    fun openBillingWeb() { if (!state.value.busy && !state.value.storageBlocked) billingController.openWeb() }
    fun submitBillingWeb(token: String, password: String, code: String) = billingController.submitWeb(token, password, code)
    fun refreshBillingWebCaptcha(token: String, display: (ByteArray) -> Unit) = billingController.refreshWebCaptcha(token, display)
    fun closeBillingWeb() = billingController.closeWeb()
    fun unsupportedBillingWeb() = billingController.unsupportedWeb()

    fun reload() {
        if (state.value.busy) return
        runtime.accountNames.storageAvailable(false)
        runtime.accountNames.editingAccount(null)
        mutable.update { it.copy(loading = true, busy = true, editor = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.load() }
                runtime.storageReloaded()
                mutable.update { it.copy(data = repository.state.value ?: data, loading = false, busy = false, storageBlocked = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                runtime.storageUnavailable()
                mutable.update { it.copy(loading = false, busy = false, storageBlocked = true) }
            }
        }
    }

    fun addAccount() {
        if (!state.value.busy && !state.value.storageBlocked) {
            mutable.update { it.copy(editor = AccountDraft(label = "", username = "", password = ""), editorError = null) }
        }
    }

    fun editAccount(id: String) {
        if (state.value.busy) return
        loginController.accountWillChange(id)
        runtime.accountNames.editingAccount(id)
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { repository.edit(id) }
                mutable.update { it.copy(busy = false, editor = draft, editorError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: VaultUnavailableException) {
                runtime.storageUnavailable()
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorError = null, message = null) }
            } catch (_: Exception) {
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, message = "账号暂时无法读取，请重试") }
            }
        }
    }

    fun closeEditor() { if (!state.value.busy) {
        mutable.update { it.copy(editor = null, editorError = null) }
        runtime.accountNames.editingAccount(null)
    } }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun saveAccount(draft: AccountDraft) {
        draft.id?.let(loginController::accountWillChange)
        draft.id?.let(runtime.accountNames::accountWillChange)
        write("账号已加密保存", closeEditor = true, accountChange = true) { repository.save(draft) }
    }
    fun deleteAccount(id: String) {
        loginController.accountWillChange(id)
        runtime.accountNames.accountWillChange(id)
        write("账号已删除", accountChange = true) { repository.delete(id) }
    }
    fun setDefault(id: String) = write("默认账号已更新，将用于后续登录", accountChange = true) { repository.setDefault(id) }
    fun updateSettings(settings: AppSettings) = write("设置已保存") { repository.settings(settings) }
    fun resetStorage() {
        loginController.accountWillChange(null)
        runtime.accountNames.accountWillChange(null)
        write("本机账号已清除，请重新添加", recovery = true, accountChange = true) { repository.reset() }
    }

    private fun write(
        success: String,
        closeEditor: Boolean = false,
        recovery: Boolean = false,
        accountChange: Boolean = false,
        action: () -> AccountState,
    ) {
        if (state.value.busy || (state.value.storageBlocked && !recovery)) return
        if (accountChange) billingController.accountChanged(state.value.data.defaultAccountId, force = true)
        mutable.update { it.copy(busy = true, editorError = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { action() }
                runtime.storageReloaded()
                mutable.update { it.copy(data = repository.state.value ?: data, busy = false, storageBlocked = false,
                    editor = if (closeEditor) null else it.editor, editorError = null, message = success) }
                if (closeEditor) runtime.accountNames.editingAccount(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountValidationException) {
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = error.message)
                    else it.copy(busy = false, message = error.message) }
            } catch (_: VaultUnavailableException) {
                runtime.storageUnavailable()
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorError = null, message = null) }
            } catch (_: Exception) {
                val message = if (recovery) "清除未完成，请重试读取并检查存储状态" else "保存失败，请重试；未显示保存成功"
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = message)
                    else it.copy(busy = false, message = message) }
            } finally {
                runtime.accountNames.accountChangeFinished()
            }
        }
    }
}
