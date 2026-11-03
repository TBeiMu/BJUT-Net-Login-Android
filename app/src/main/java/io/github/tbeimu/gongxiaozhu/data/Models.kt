package io.github.tbeimu.gongxiaozhu.data

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkMode(val title: String) {
    AUTO("自动识别"), PUBLIC_WIFI("公共 WiFi"), DORM_WIFI("宿舍 WiFi")
}

@Serializable
data class AppSettings(
    val autoLogin: Boolean = true,
    val networkMode: NetworkMode = NetworkMode.AUTO,
)

data class AccountSummary(val id: String, val label: String, val username: String)

data class AccountDraft(val id: String? = null, val label: String, val username: String, val password: String) {
    override fun toString(): String = "AccountDraft(id=$id, credentials=<redacted>)"
}

data class AccountState(
    val accounts: List<AccountSummary> = emptyList(),
    val defaultAccountId: String? = null,
    val settings: AppSettings = AppSettings(),
) {
    val defaultAccount: AccountSummary? get() = accounts.find { it.id == defaultAccountId }
}

@Serializable
internal data class SavedAccount(val id: String, val label: String, val username: String, val password: String) {
    override fun toString(): String = "SavedAccount(id=$id, credentials=<redacted>)"
}

@Serializable
internal data class VaultData(
    val version: Int = 1,
    val accounts: List<SavedAccount> = emptyList(),
    val defaultAccountId: String? = null,
    val settings: AppSettings = AppSettings(),
)

class VaultUnavailableException : Exception("账号存储不可用，请重试或清除本机账号后重新录入。")
class AccountValidationException(message: String) : Exception(message)
