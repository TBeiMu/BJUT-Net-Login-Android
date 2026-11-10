package io.github.tbeimu.gongxiaozhu.schedule

import android.content.Context
import android.util.AtomicFile
import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.AndroidVaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import java.io.File
import io.github.tbeimu.gongxiaozhu.data.readBounded

class AndroidScheduleStore(context: Context) : VaultStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "schedule.vault"))
    override fun read(): ByteArray? = try {
        file.openRead().use { stream ->
            stream.readBounded(4 * 1024 * 1024 + 128)
        }
    } catch (error: java.io.FileNotFoundException) { if (file.baseFile.exists()) throw error else null }
    override fun write(bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun clear() { file.delete(); check(!file.baseFile.exists()) }
}
fun createScheduleRepository(context: Context) = ScheduleRepository(AndroidScheduleStore(context.applicationContext),
    AesGcmVaultCipher(AndroidVaultKeySource("gongxiaozhu.schedule.v1")))
