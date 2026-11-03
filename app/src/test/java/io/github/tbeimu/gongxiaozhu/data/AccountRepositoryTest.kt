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

    private fun draft(username: String) = AccountDraft(label = "测试账号", username = username, password = "demo-only-password")

    private class MemoryStore : VaultStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
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
