package io.github.tbeimu.gongxiaozhu.data

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val mutable = MutableStateFlow<AccountState?>(null)
    val state = mutable.asStateFlow()

    @Synchronized
    fun load(): AccountState {
        vault = null
        hasPersistedVault = false
        mutable.value = null
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
            publish(loaded)
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
        val previous = current.accounts.find { it.id == draft.id }
        val custom = if (draft.label.isBlank()) false
            else if (previous != null && previous.label == label && previous.username == username) previous.customLabel
            else true
        val pending = if (previous == null) draft.label.isBlank() else previous.nameLookupPending &&
            previous.username == username && previous.password == draft.password && custom != true
        val saved = SavedAccount(draft.id ?: UUID.randomUUID().toString(), label, username, draft.password, custom, pending)
        val accounts = if (draft.id == null) current.accounts + saved else current.accounts.map {
            if (it.id == draft.id) saved else it
        }
        return persist(current.copy(accounts = accounts, defaultAccountId = current.defaultAccountId ?: saved.id))
    }

    @Synchronized
    fun edit(id: String): AccountDraft {
        checkPersistedState()
        val saved = requireLoaded().accounts.find { it.id == id }
            ?: throw AccountValidationException("账号不存在，请返回后重试")
        return AccountDraft(saved.id, saved.label, saved.username, saved.password)
    }

    /** A validated snapshot of just the selected account for one bounded login task. */
    @Synchronized
    fun credentialsForLogin(selectedDefaultId: String?): AccountDraft? {
        checkPersistedState()
        return selectedDefaultId?.let { edit(it) }
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

    /** Consume the one-time attempt before reading credentials or starting school HTTP. */
    @Synchronized
    fun beginNameLookup(id: String, isCurrent: () -> Boolean = { true }): AccountDraft? {
        if (!isCurrent()) return null
        checkPersistedState()
        val current = requireLoaded()
        val account = current.accounts.find { it.id == id && it.nameLookupPending && it.canFillName } ?: return null
        if (!persistName(current.copy(accounts = current.accounts.map {
            if (it.id == id) it.copy(nameLookupPending = false) else it
        }), isCurrent)) return null
        return AccountDraft(account.id, account.label, account.username, account.password)
    }

    /** A late result cannot rename a replaced account, changed password or custom label. */
    @Synchronized
    fun completeNameLookup(account: AccountDraft, studentId: String, name: String,
        isCurrent: () -> Boolean = { true }): Boolean {
        if (studentId != account.username) return false
        return fillName(studentId, name, isCurrent) { it.id == account.id && it.password == account.password }
    }

    @Synchronized
    fun fillNameFromSchedule(studentId: String, name: String, accountId: String? = null,
        isCurrent: () -> Boolean = { true }): Boolean =
        fillName(studentId, name, isCurrent) { accountId == null || it.id == accountId }

    private fun fillName(studentId: String, rawName: String, isCurrent: () -> Boolean,
        matches: (SavedAccount) -> Boolean): Boolean {
        if (!isCurrent()) return false
        val name = rawName.trim()
        if (name.isEmpty() || name == studentId || name.length > 80 || name.any(Char::isISOControl)) return false
        checkPersistedState()
        val current = requireLoaded()
        val account = current.accounts.find { it.username == studentId && it.canFillName && matches(it) } ?: return false
        return persistName(current.copy(accounts = current.accounts.map {
            if (it.id == account.id) it.copy(label = name, customLabel = false, nameLookupPending = false) else it
        }), isCurrent)
    }

    /** Only called after explicit destructive-action confirmation in the recovery UI. */
    @Synchronized
    fun reset(): AccountState = guarded {
        vault = null
        hasPersistedVault = false
        mutable.value = null
        store.clear()
        cipher.clearKey()
        vault = VaultData()
        publish(requireLoaded())
    }

    private fun persist(next: VaultData): AccountState = guarded { checkNotNull(writeVerified(next)) }

    private fun persistName(next: VaultData, isCurrent: () -> Boolean): Boolean = try {
        writeVerified(next, isCurrent) != null
    } catch (_: Exception) {
        // Only downgrade the error after rereading/decrypting the original, unchanged vault.
        // A damaged file, missing key or uncertain write still blocks account operations.
        checkPersistedState()
        throw NameSaveException()
    }

    /** Called under the repository monitor; queued/cancelled name work cannot cross this boundary. */
    private fun writeVerified(next: VaultData, isCurrent: () -> Boolean = { true }): AccountState? {
        if (!isCurrent()) return null
        validateVault(next)
        checkPersistedState()
        if (!isCurrent()) return null
        val plaintext = json.encodeToString(next).encodeToByteArray()
        try {
            val encrypted = cipher.encrypt(plaintext, requireExistingKey = hasPersistedVault)
            require(encrypted.size <= 1024 * 1024)
            if (!isCurrent()) return null
            store.write(encrypted)
        } finally {
            plaintext.fill(0)
        }
        vault = next
        hasPersistedVault = true
        return publish(next)
    }

    private fun checkPersistedState() = guarded {
        val previous = store.read()
        require(hasPersistedVault == (previous != null))
        if (previous != null) {
            val checked = cipher.decrypt(previous)
            try {
                val persisted = json.decodeFromString<VaultData>(checked.decodeToString(throwOnInvalidSequence = true))
                validateVault(persisted)
                // Refuse stale reads/writes if another repository changed valid data.
                require(persisted == requireLoaded())
            } finally {
                checked.fill(0)
            }
        }
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
        accounts.map { AccountSummary(it.id, it.label, it.username, it.canFillName, it.nameLookupPending) }, defaultAccountId, settings,
    )

    private fun publish(value: VaultData): AccountState = value.summary().also { mutable.value = it }

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        // No causes or payloads are exposed to UI/logs: errors can contain secret values.
        throw VaultUnavailableException()
    }
}
