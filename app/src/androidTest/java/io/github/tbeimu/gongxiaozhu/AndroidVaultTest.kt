package io.github.tbeimu.gongxiaozhu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "instrumentation-test.vault"
    private val alias = "gongxiaozhu.instrumentation.test"
    private fun repository() = AccountRepository(AndroidVaultStore(context, filename), AesGcmVaultCipher(AndroidVaultKeySource(alias)))

    @Before fun setup() { repository().reset() }
    @After fun cleanup() { repository().reset() }

    @Test fun androidKeystoreAndAtomicFilePreserveAccountsAndSettings() {
        val repo = repository()
        repo.load()
        val state = repo.save(AccountDraft(label = "虚构测试", username = "android-demo-only", password = " demo&+%?=密码 "))
        repo.settings(AppSettings(false, NetworkMode.DORM_WIFI))
        val restored = repository()
        val loaded = restored.load()
        assertEquals(state.accounts, loaded.accounts)
        assertEquals(AppSettings(false, NetworkMode.DORM_WIFI), loaded.settings)
        assertEquals(" demo&+%?=密码 ", restored.edit(state.defaultAccountId!!).password)
        val bytes = File(context.noBackupFilesDir, filename).readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("android-demo-only"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("demo&+%"))
    }

    @Test fun lostAndroidKeystoreKeyRequiresExplicitReset() {
        val repo = repository()
        repo.load()
        repo.save(AccountDraft(label = "虚构测试", username = "android-demo-only", password = "demo-password"))
        val original = File(context.noBackupFilesDir, filename).readBytes()
        AndroidVaultKeySource(alias).clear()
        assertThrows(VaultUnavailableException::class.java) { repository().load() }
        assertArrayEquals(original, File(context.noBackupFilesDir, filename).readBytes())
        assertTrue(repository().reset().accounts.isEmpty())
    }

    @Test fun corruptAndroidFileIsPreservedAndWritesBlocked() {
        val file = File(context.noBackupFilesDir, filename)
        file.writeBytes(byteArrayOf(7, 8, 9))
        val repo = repository()
        assertThrows(VaultUnavailableException::class.java) { repo.load() }
        assertThrows(VaultUnavailableException::class.java) { repo.settings(AppSettings()) }
        assertArrayEquals(byteArrayOf(7, 8, 9), file.readBytes())
    }
}
