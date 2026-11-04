package io.github.tbeimu.gongxiaozhu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountValidationException
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.CampusLogin
import io.github.tbeimu.gongxiaozhu.network.ForegroundLoginController
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.network.WifiMonitor
import io.github.tbeimu.gongxiaozhu.billing.BillingController
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    override fun toString() = "AppUiState(loading=$loading, busy=$busy, storageBlocked=$storageBlocked)"
}

class AppViewModel(private val repository: AccountRepository, wifiMonitor: WifiMonitor) : ViewModel() {
    private val mutable = MutableStateFlow(AppUiState())
    val state = mutable.asStateFlow()

    private val loginController = ForegroundLoginController(viewModelScope, CampusLogin(),
        credentials = {
            val selectedDefaultId = state.value.data.defaultAccountId
            suspend { withContext(Dispatchers.IO) { repository.credentialsForLogin(selectedDefaultId) } }
        },
        publish = { login -> mutable.update { it.copy(login = login) } },
        storageFailure = { mutable.update { it.copy(storageBlocked = true, editor = null, editorError = null) } })

    private val billingController = BillingController(viewModelScope,
        credentials = { id -> withContext(Dispatchers.IO) { repository.credentialsForLogin(id) } },
        publish = { billing -> mutable.update { it.copy(billing = billing) } },
        storageFailure = { mutable.update { it.copy(storageBlocked = true, editor = null, editorError = null) } },
        beforeOffline = loginController::deviceOfflineRequested)

    init {
        reload()
        viewModelScope.launch { wifiMonitor.connections.collect {
            loginController.wifiChanged(it)
            billingController.wifiChanged(it)
        } }
        viewModelScope.launch {
            state.collect {
                billingController.accountChanged(if (it.loading || it.storageBlocked) null else it.data.defaultAccountId)
                loginController.configure(ready = !it.loading && !it.storageBlocked,
                    automatic = it.data.settings.autoLogin && it.data.defaultAccount != null,
                    mode = it.data.settings.networkMode)
            }
        }
    }

    fun enterForeground() { loginController.enterForeground(); billingController.enterForeground() }
    fun leaveForeground() { loginController.leaveForeground(); billingController.leaveForeground() }
    fun login() { if (!state.value.busy && state.value.editor == null) loginController.login() }
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
        mutable.update { it.copy(loading = true, busy = true, editor = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.load() }
                mutable.update { it.copy(data = data, loading = false, busy = false, storageBlocked = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { AppUiState(loading = false, storageBlocked = true) }
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
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { repository.edit(id) }
                mutable.update { it.copy(busy = false, editor = draft, editorError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: VaultUnavailableException) {
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorError = null, message = null) }
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "账号暂时无法读取，请重试") }
            }
        }
    }

    fun closeEditor() { if (!state.value.busy) mutable.update { it.copy(editor = null, editorError = null) } }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun saveAccount(draft: AccountDraft) = write("账号已加密保存", closeEditor = true, accountChange = true) { repository.save(draft) }
    fun deleteAccount(id: String) = write("账号已删除", accountChange = true) { repository.delete(id) }
    fun setDefault(id: String) = write("默认账号已更新，将用于后续登录", accountChange = true) { repository.setDefault(id) }
    fun updateSettings(settings: AppSettings) = write("设置已保存") { repository.settings(settings) }
    fun resetStorage() = write("本机账号已清除，请重新添加", recovery = true, accountChange = true) { repository.reset() }

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
                mutable.update { it.copy(data = data, busy = false, storageBlocked = false,
                    editor = if (closeEditor) null else it.editor, editorError = null, message = success) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountValidationException) {
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = error.message)
                    else it.copy(busy = false, message = error.message) }
            } catch (_: VaultUnavailableException) {
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorError = null, message = null) }
            } catch (_: Exception) {
                val message = if (recovery) "清除未完成，请重试读取并检查存储状态" else "保存失败，请重试；未显示保存成功"
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = message)
                    else it.copy(busy = false, message = message) }
            }
        }
    }
}
