package io.github.tbeimu.gongxiaozhu.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidVaultKeySource(private val alias: String = "gongxiaozhu.accounts.v1") : VaultKeySource {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun encryptionKey(): SecretKey {
        val existing = keyStore().getKey(alias, null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    override fun decryptionKey(): SecretKey = keyStore().getKey(alias, null) as? SecretKey
        ?: throw VaultUnavailableException()

    override fun clear() = keyStore().deleteEntry(alias)
}

class AndroidVaultStore(context: Context, filename: String = "accounts.vault") : VaultStore {
    private val atomic = AtomicFile(File(context.noBackupFilesDir, filename))

    override fun read(): ByteArray? = try {
        atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (error: FileNotFoundException) {
        if (atomic.baseFile.exists()) throw error
        null
    }

    override fun write(bytes: ByteArray) {
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override fun clear() {
        atomic.delete()
        check(!atomic.baseFile.exists())
    }
}

fun createAccountRepository(context: Context): AccountRepository = AccountRepository(
    AndroidVaultStore(context.applicationContext), AesGcmVaultCipher(AndroidVaultKeySource()),
)
