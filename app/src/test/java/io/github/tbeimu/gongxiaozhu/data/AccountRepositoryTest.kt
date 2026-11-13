package io.github.tbeimu.gongxiaozhu.data

import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccountRepositoryTest {
    private lateinit var store: MemoryStore
    private lateinit var keys: MemoryKeys
    private lateinit var cipher: AesGcmVaultCipher
    private lateinit var repository: AccountRepository

    @Before fun setup() {
        store = MemoryStore()
        keys = MemoryKeys()
        cipher = AesGcmVaultCipher(keys)
        repository = AccountRepository(store, cipher)
        repository.load()
    }

    @Test fun firstAccountBecomesDefaultAndSurvivesNewRepository() {
        val state = repository.save(draft("student-demo-01"))
        assertEquals(state.accounts.single().id, state.defaultAccountId)
        assertEquals(state, AccountRepository(store, cipher).load())
    }

    @Test fun switchingDefaultDoesNotDeleteAccounts() {
        repository.save(draft("student-demo-01"))
        val second = repository.save(draft("student-demo-02")).accounts.last()
        val selected = repository.setDefault(second.id)
        assertEquals(2, selected.accounts.size)
        assertEquals(second.id, selected.defaultAccountId)
        assertEquals(selected, AccountRepository(store, cipher).load())
    }

    @Test fun deletingDefaultSelectsFirstRemainingAndLastDeletionClearsDefault() {
        val first = repository.save(draft("student-demo-01")).accounts.single()
        val second = repository.save(draft("student-demo-02")).accounts.last()
        assertEquals(second.id, repository.delete(first.id).defaultAccountId)
        assertNull(repository.delete(second.id).defaultAccountId)
        assertTrue(AccountRepository(store, cipher).load().accounts.isEmpty())
    }

    @Test fun editingPreservesIdAndExactPasswordIncludingSpacesAndSpecialCharacters() {
        val account = repository.save(draft("student-demo-01")).accounts.single()
        val special = "  demo&+%?=中文密码  "
        repository.save(AccountDraft(account.id, "宿舍", "student-demo-edited", special))
        val restored = AccountRepository(store, cipher)
        restored.load()
        assertEquals(account.id, restored.edit(account.id).id)
        assertEquals(special, restored.edit(account.id).password)
        assertEquals("宿舍", restored.edit(account.id).label)
    }

    @Test fun settingsPersistWithoutAccounts() {
        val settings = AppSettings(false, NetworkMode.DORM_WIFI)
        repository.settings(settings)
        assertEquals(settings, AccountRepository(store, cipher).load().settings)
    }

    @Test fun ciphertextContainsNeitherUsernameNorPassword() {
        repository.save(draft("student-demo-01"))
        val raw = store.bytes!!.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("student-demo-01"))
        assertFalse(raw.contains("demo-only-password"))
    }

    @Test fun eachSaveUsesADifferentIv() {
        val state = repository.save(draft("student-demo-01"))
        val first = store.bytes!!.copyOf()
        repository.setDefault(state.defaultAccountId!!)
        assertFalse(first.contentEquals(store.bytes!!))
        assertFalse(first.copyOfRange(4, 16).contentEquals(store.bytes!!.copyOfRange(4, 16)))
    }

    @Test fun tamperedCiphertextBlocksReadsAndWritesWithoutOverwritingFile() {
        repository.save(draft("student-demo-01"))
        store.bytes = store.bytes!!.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val damaged = store.bytes!!.copyOf()
        val restored = AccountRepository(store, cipher)
        assertThrows(VaultUnavailableException::class.java) { restored.load() }
        assertThrows(VaultUnavailableException::class.java) { restored.save(draft("student-demo-02")) }
        assertArrayEquals(damaged, store.bytes)
    }

    @Test fun malformedFileBlocksReadsWithoutChangingFile() {
        store.bytes = "not-a-vault".encodeToByteArray()
        assertThrows(VaultUnavailableException::class.java) { repository.load() }
        assertEquals("not-a-vault", store.bytes!!.decodeToString())
    }

    @Test fun missingKeyDoesNotGenerateReplacementOrOverwriteVault() {
        repository.save(draft("student-demo-01"))
        val original = store.bytes!!.copyOf()
        keys.key = null
        assertThrows(VaultUnavailableException::class.java) { repository.load() }
        assertNull(keys.key)
        assertArrayEquals(original, store.bytes)
    }

    @Test fun explicitResetAllowsFreshAccountsAfterCorruption() {
        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(VaultUnavailableException::class.java) { repository.load() }
        assertTrue(repository.reset().accounts.isEmpty())
        assertNull(store.bytes)
        assertNull(keys.key)
        assertEquals(1, repository.save(draft("student-demo-02")).accounts.size)
    }

    @Test fun losingKeyWhileAppIsOpenDoesNotGenerateReplacementOrOverwrite() {
        repository.save(draft("student-demo-01"))
        val original = store.bytes!!.copyOf()
        keys.key = null
        assertThrows(VaultUnavailableException::class.java) { repository.save(draft("student-demo-02")) }
        assertNull(keys.key)
        assertArrayEquals(original, store.bytes)
    }

    @Test fun corruptingFileWhileAppIsOpenBlocksFurtherWrites() {
        repository.save(draft("student-demo-01"))
        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(VaultUnavailableException::class.java) { repository.settings(AppSettings(false)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), store.bytes)
    }

    @Test fun failedWriteKeepsPreviouslySavedAccountAndDefaults() {
        val original = repository.save(draft("student-demo-01"))
        val originalBytes = store.bytes!!.copyOf()
        store.failWrite = true
        assertThrows(VaultUnavailableException::class.java) { repository.save(draft("student-demo-02")) }
        assertEquals("student-demo-01", repository.edit(original.accounts.single().id).username)
        assertArrayEquals(originalBytes, store.bytes)
        assertEquals(original, AccountRepository(store, cipher).load())
    }

    @Test fun duplicateUsernamesAreRejectedAfterTrimming() {
        repository.save(draft("student-demo-01"))
        assertThrows(AccountValidationException::class.java) { repository.save(draft(" student-demo-01 ")) }
        assertEquals(1, AccountRepository(store, cipher).load().accounts.size)
    }

    @Test fun emptyFieldsAndInvalidDefaultAreRejected() {
        assertThrows(AccountValidationException::class.java) { repository.save(draft("   ")) }
        assertThrows(AccountValidationException::class.java) { repository.save(AccountDraft(label = "", username = "demo", password = "")) }
        assertThrows(AccountValidationException::class.java) { repository.setDefault("missing") }
        assertNull(store.bytes)
    }

    @Test fun unsupportedVaultVersionBlocksSaving() {
        store.bytes = cipher.encrypt("""{"version":2,"accounts":[],"defaultAccountId":null}""".encodeToByteArray())
        assertThrows(VaultUnavailableException::class.java) { repository.load() }
        assertThrows(VaultUnavailableException::class.java) { repository.settings(AppSettings()) }
    }

    @Test fun credentialObjectsDoNotExposePasswordInToString() {
        val draft = draft("student-demo-01")
        assertFalse(draft.toString().contains(draft.password))
        assertFalse(SavedAccount("demo-id", "demo", "demo", draft.password).toString().contains(draft.password))
    }

    @Test fun editingAfterCorruptionOrKeyLossDoesNotExposeCachedCredentials() {
        val id = repository.save(draft("student-demo-01")).defaultAccountId!!
        val original = store.bytes!!.copyOf()
        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(VaultUnavailableException::class.java) { repository.edit(id) }
        store.bytes = original
        keys.key = null
        assertThrows(VaultUnavailableException::class.java) { repository.edit(id) }
        assertArrayEquals(original, store.bytes)
    }

    @Test fun staleRepositoryCannotOverwriteNewerValidAccounts() {
        val original = repository.save(draft("student-demo-01"))
        val other = AccountRepository(store, cipher)
        other.load()
        val updated = other.save(draft("student-demo-02"))
        assertThrows(VaultUnavailableException::class.java) { repository.settings(AppSettings(false)) }
        assertThrows(VaultUnavailableException::class.java) { repository.edit(original.defaultAccountId!!) }
        assertEquals(updated, repository.load())
    }

    @Test fun credentialSnapshotContainsOnlyDefaultAndRequiresValidStorage() {
        assertNull(repository.credentialsForLogin(null))
        val first = repository.save(draft("student-demo-01")).defaultAccountId!!
        val second = repository.save(draft("student-demo-02")).accounts.last().id
        assertEquals(first, repository.credentialsForLogin(first)!!.id)
        repository.setDefault(second)
        assertEquals(second, repository.credentialsForLogin(second)!!.id)
        assertEquals(first, repository.credentialsForLogin(first)!!.id)
        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(VaultUnavailableException::class.java) { repository.credentialsForLogin(second) }
    }

    @Test fun newUnnamedAccountGetsOnePersistedNameAttempt() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        assertTrue(account.canFillName); assertTrue(account.nameLookupPending)
        val restored = AccountRepository(store, cipher)
        restored.load()
        assertNotNull(restored.beginNameLookup(account.id))
        assertNull(restored.beginNameLookup(account.id))
        assertFalse(AccountRepository(store, cipher).load().accounts.single().nameLookupPending)
    }

    @Test fun verifiedNamePersistsWithoutChangingPasswordDefaultOrSettings() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        repository.settings(AppSettings(false, NetworkMode.DORM_WIFI))
        val request = repository.beginNameLookup(account.id)!!
        assertTrue(repository.completeNameLookup(request, account.username, " 测试同学 "))
        val restored = AccountRepository(store, cipher)
        val state = restored.load()
        assertEquals("测试同学", state.accounts.single().label)
        assertEquals(account.id, state.defaultAccountId)
        assertEquals(request.password, restored.edit(account.id).password)
        assertEquals(AppSettings(false, NetworkMode.DORM_WIFI), state.settings)
        assertFalse(store.bytes!!.toString(Charsets.UTF_8).contains("测试同学"))
        assertEquals(state, repository.state.value)
    }

    @Test fun customLabelsIncludingExplicitAccountTextArePreserved() {
        for (label in listOf("我的校园网", "student-demo-01")) {
            repository.reset()
            val account = repository.save(draft("student-demo-01").copy(label = label)).accounts.single()
            assertFalse(account.canFillName); assertFalse(account.nameLookupPending)
            assertNull(repository.beginNameLookup(account.id))
            assertFalse(repository.fillNameFromSchedule(account.username, "测试同学"))
            assertEquals(label, repository.state.value!!.accounts.single().label)
        }
    }

    @Test fun lateNameCannotOverwriteEditedOrRecreatedAccount() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        val request = repository.beginNameLookup(account.id)!!
        repository.save(request.copy(password = "changed-fictional-password"))
        assertFalse(repository.completeNameLookup(request, account.username, "测试同学"))
        repository.delete(account.id)
        repository.save(draft(account.username).copy(label = ""))
        assertFalse(repository.completeNameLookup(request, account.username, "测试同学"))
        assertEquals(account.username, repository.state.value!!.accounts.single().label)
    }

    @Test fun namesOnlyFillTheMatchingAccountAndRejectInvalidNames() {
        val first = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        val second = repository.save(draft("student-demo-02").copy(label = "")).accounts.last()
        val request = repository.beginNameLookup(first.id)!!
        assertFalse(repository.completeNameLookup(request, second.username, "测试同学"))
        for (name in listOf("", " ", first.username, "x".repeat(81), "测试\n姓名")) {
            assertFalse(repository.fillNameFromSchedule(first.username, name))
        }
        assertTrue(repository.fillNameFromSchedule(second.username, "第二同学"))
        assertEquals(first.username, repository.state.value!!.accounts.first().label)
        assertEquals("第二同学", repository.state.value!!.accounts.last().label)
        assertEquals(first.id, repository.state.value!!.defaultAccountId)
    }

    @Test fun olderVaultDoesNotStartOnlineLookupsButCanUseImportedName() {
        val old = """{"version":1,"accounts":[{"id":"legacy","label":"student-demo-01","username":"student-demo-01","password":"fictional-password"}],"defaultAccountId":"legacy"}"""
        store.bytes = cipher.encrypt(old.encodeToByteArray())
        val state = repository.load()
        assertFalse(state.accounts.single().nameLookupPending)
        assertTrue(state.accounts.single().canFillName)
        assertTrue(repository.fillNameFromSchedule("student-demo-01", "测试同学"))
    }

    @Test fun failedNameWritePreservesOldEncryptedFileAndPublishedState() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        val previous = repository.state.value
        val bytes = store.bytes!!.copyOf()
        store.failWrite = true
        assertThrows(NameSaveException::class.java) { repository.fillNameFromSchedule(account.username, "测试同学") }
        assertEquals(previous, repository.state.value)
        assertArrayEquals(bytes, store.bytes)
        assertEquals("demo-only-password", repository.credentialsForLogin(account.id)!!.password)
    }

    @Test fun failedAttemptMarkerWriteDoesNotReleaseCredentialsOrConsumeTheAttempt() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        store.failWrite = true
        assertThrows(NameSaveException::class.java) { repository.beginNameLookup(account.id) }
        assertTrue(repository.state.value!!.accounts.single().nameLookupPending)
        assertEquals("demo-only-password", repository.credentialsForLogin(account.id)!!.password)
    }

    @Test fun failedNameWriteWithDamagedFileOrLostKeyStillBlocksAccounts() {
        for (damageFile in listOf(true, false)) {
            repository.reset()
            val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
            store.onWrite = {
                if (damageFile) store.bytes = byteArrayOf(1, 2, 3) else keys.key = null
                throw IOException("Fictional uncertain write")
            }
            assertThrows(VaultUnavailableException::class.java) { repository.fillNameFromSchedule(account.username, "测试同学") }
            assertThrows(VaultUnavailableException::class.java) { repository.credentialsForLogin(account.id) }
            store.onWrite = {}
        }
    }

    @Test fun cancelledNameOperationsDoNotReadOrWriteTheVault() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        val saved = repository.edit(account.id)
        val bytes = store.bytes!!.copyOf()
        keys.key = null // A cancelled task should not even attempt to decrypt.
        assertNull(repository.beginNameLookup(account.id) { false })
        assertFalse(repository.fillNameFromSchedule(account.username, "测试同学", account.id) { false })
        assertFalse(repository.completeNameLookup(saved, account.username, "测试同学") { false })
        assertArrayEquals(bytes, store.bytes)
    }

    @Test fun cancellationDuringEncryptionSkipsTheCommit() {
        val account = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        val bytes = store.bytes!!.copyOf()
        var valid = true
        val cancellingCipher = object : VaultCipher by cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray =
                cipher.encrypt(plaintext, requireExistingKey).also { valid = false }
        }
        val other = AccountRepository(store, cancellingCipher)
        other.load()
        assertFalse(other.fillNameFromSchedule(account.username, "测试同学", account.id) { valid })
        assertArrayEquals(bytes, store.bytes)
        assertEquals(account.username, other.state.value!!.accounts.single().label)
    }

    @Test fun cachedNameIsBoundToTheOriginallySelectedAccountId() {
        val original = repository.save(draft("student-demo-01").copy(label = "")).accounts.single()
        repository.delete(original.id)
        repository.save(draft(original.username).copy(label = ""))
        assertFalse(repository.fillNameFromSchedule(original.username, "测试同学", original.id))
        assertEquals(original.username, repository.state.value!!.accounts.single().label)
    }

    private fun draft(username: String) = AccountDraft(label = "测试账号", username = username, password = "demo-only-password")

    private class MemoryStore : VaultStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var onWrite: () -> Unit = {}
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            onWrite()
            if (failWrite) throw IOException("Simulated write failure")
            this.bytes = bytes.copyOf()
        }
        override fun clear() { bytes = null }
    }

    private class MemoryKeys : VaultKeySource {
        var key: SecretKey? = null
        override fun encryptionKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun decryptionKey(): SecretKey = key ?: throw IllegalStateException("Missing key")
        override fun clear() { key = null }
    }
}
