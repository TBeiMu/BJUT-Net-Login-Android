package io.github.tbeimu.gongxiaozhu.data

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface VaultStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun clear()
}

/** All state is published only after an encrypted atomic write succeeds. */
class AccountRepository(private val store: VaultStore, private val cipher: VaultCipher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var vault: VaultData? = null
    private var hasPersistedVault = false

    @Synchronized
    fun load(): AccountState {
        vault = null
        hasPersistedVault = false
        return guarded {
            val bytes = store.read()
            val loaded = if (bytes == null) VaultData() else {
                val plaintext = cipher.decrypt(bytes)
                try {
                    json.decodeFromString<VaultData>(plaintext.decodeToString(throwOnInvalidSequence = true))
                } finally {
                    plaintext.fill(0)
                }
            }
            validateVault(loaded)
            vault = loaded
            hasPersistedVault = bytes != null
            loaded.summary()
        }
    }

    @Synchronized
    fun save(draft: AccountDraft): AccountState {
        val current = requireLoaded()
        val username = draft.username.trim()
        val label = draft.label.trim().ifEmpty { username }
        if (username.isBlank()) throw AccountValidationException("请填写校园网账号")
        if (draft.password.isEmpty()) throw AccountValidationException("请填写密码")
        if (username.length > 128 || label.length > 80 || draft.password.length > 256) {
            throw AccountValidationException("账号、备注或密码过长，请检查输入")
        }
        if (current.accounts.any { it.username == username && it.id != draft.id }) {
            throw AccountValidationException("该账号已经保存，请编辑已有账号")
        }
        if (draft.id != null && current.accounts.none { it.id == draft.id }) {
            throw AccountValidationException("账号不存在，请返回后重试")
        }
        val saved = SavedAccount(draft.id ?: UUID.randomUUID().toString(), label, username, draft.password)
        val accounts = if (draft.id == null) current.accounts + saved else current.accounts.map {
            if (it.id == draft.id) saved else it
        }
        return persist(current.copy(accounts = accounts, defaultAccountId = current.defaultAccountId ?: saved.id))
    }

    @Synchronized
    fun edit(id: String): AccountDraft {
        val saved = requireLoaded().accounts.find { it.id == id }
            ?: throw AccountValidationException("账号不存在，请返回后重试")
        return AccountDraft(saved.id, saved.label, saved.username, saved.password)
    }

    @Synchronized
    fun delete(id: String): AccountState {
        val current = requireLoaded()
        val accounts = current.accounts.filterNot { it.id == id }
        val defaultId = if (current.defaultAccountId == id) accounts.firstOrNull()?.id else current.defaultAccountId
        return persist(current.copy(accounts = accounts, defaultAccountId = defaultId))
    }

    @Synchronized
    fun setDefault(id: String): AccountState {
        val current = requireLoaded()
        if (current.accounts.none { it.id == id }) throw AccountValidationException("账号不存在，请返回后重试")
        return persist(current.copy(defaultAccountId = id))
    }

    @Synchronized
    fun settings(settings: AppSettings): AccountState = persist(requireLoaded().copy(settings = settings))

    /** Only called after explicit destructive-action confirmation in the recovery UI. */
    @Synchronized
    fun reset(): AccountState = guarded {
        vault = null
        hasPersistedVault = false
        store.clear()
        cipher.clearKey()
        vault = VaultData()
        requireLoaded().summary()
    }

    private fun persist(next: VaultData): AccountState = guarded {
        validateVault(next)
        val previous = store.read()
        require(!hasPersistedVault || previous != null)
        if (previous != null) {
            // Detect corruption or a lost key even when the app already has accounts in memory.
            val checked = cipher.decrypt(previous)
            try {
                validateVault(json.decodeFromString<VaultData>(checked.decodeToString(throwOnInvalidSequence = true)))
            } finally {
                checked.fill(0)
            }
        }
        val plaintext = json.encodeToString(next).encodeToByteArray()
        try {
            val encrypted = cipher.encrypt(plaintext, requireExistingKey = previous != null)
            require(encrypted.size <= 1024 * 1024)
            store.write(encrypted)
        } finally {
            plaintext.fill(0)
        }
        vault = next
        hasPersistedVault = true
        next.summary()
    }

    private fun requireLoaded(): VaultData = vault ?: throw VaultUnavailableException()

    private fun validateVault(value: VaultData) {
        require(value.version == 1)
        require(value.accounts.map { it.id }.distinct().size == value.accounts.size)
        require(value.accounts.map { it.username }.distinct().size == value.accounts.size)
        require(value.accounts.all { it.id.isNotBlank() && it.username.isNotBlank() && it.password.isNotEmpty() })
        require(if (value.accounts.isEmpty()) value.defaultAccountId == null else {
            value.accounts.any { it.id == value.defaultAccountId }
        })
    }

    private fun VaultData.summary() = AccountState(
        accounts.map { AccountSummary(it.id, it.label, it.username) }, defaultAccountId, settings,
    )

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        // No causes or payloads are exposed to UI/logs: errors can contain secret values.
        throw VaultUnavailableException()
    }
}
