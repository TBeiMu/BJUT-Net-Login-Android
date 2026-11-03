package io.github.tbeimu.gongxiaozhu.data

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface VaultKeySource {
    fun encryptionKey(): SecretKey
    fun decryptionKey(): SecretKey
    fun clear()
}

interface VaultCipher {
    fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean = false): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
    fun clearKey()
}

class AesGcmVaultCipher(private val keys: VaultKeySource) : VaultCipher {
    override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, if (requireExistingKey) keys.decryptionKey() else keys.encryptionKey())
        require(cipher.iv.size == IV_SIZE)
        cipher.updateAAD(HEADER)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER.size + IV_SIZE + ciphertext.size)
            .put(HEADER).put(cipher.iv).put(ciphertext).array()
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= HEADER.size + IV_SIZE + TAG_SIZE)
        require(envelope.copyOfRange(0, HEADER.size).contentEquals(HEADER))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = envelope.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, keys.decryptionKey(), GCMParameterSpec(TAG_SIZE * 8, iv))
        cipher.updateAAD(HEADER)
        return cipher.doFinal(envelope, HEADER.size + IV_SIZE, envelope.size - HEADER.size - IV_SIZE)
    }

    override fun clearKey() = keys.clear()

    private companion object {
        val HEADER = byteArrayOf(0x47, 0x58, 0x5A, 0x01)
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
    }
}
