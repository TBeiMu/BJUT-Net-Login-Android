package io.github.tbeimu.gongxiaozhu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import io.github.tbeimu.gongxiaozhu.data.readBounded
import java.io.File
import java.util.UUID

/** Image generations are committed by the preferences pointer, never by overwriting the old photo. */
internal class WallpaperStore(context: Context) {
    private val resolver = context.contentResolver
    private val directory = context.noBackupFilesDir
    private val settings = AtomicFile(File(directory, "wallpaper.json"))

    fun loadPreferences(): WallpaperPreferences = synchronized(ioLock) {
        if (!settings.baseFile.exists() && !File(directory, "wallpaper.json.bak").exists()) {
            WallpaperPreferences.migrated(File(directory, WallpaperPreferences.LEGACY_PHOTO).exists())
        } else settings.openRead().use { WallpaperPreferences.decode(it.readBounded(16 * 1024)) }
    }

    fun loadImage(preferences: WallpaperPreferences): Bitmap? = synchronized(ioLock) {
        preferences.validate().photoName?.let { name ->
            File(directory, name).inputStream().use { decode(it.readBounded(MAX_IMAGE_BYTES)) }
        }
    }

    fun save(preferences: WallpaperPreferences) = synchronized(ioLock) {
        val bytes = WallpaperPreferences.encode(preferences)
        val output = settings.startWrite()
        try { output.write(bytes); settings.finishWrite(output) }
        catch (error: Exception) { settings.failWrite(output); throw error }
    }

    fun importPhoto(uri: Uri, previous: WallpaperPreferences): Pair<WallpaperPreferences, Bitmap> = synchronized(ioLock) {
        previous.validate()
        // The picker can return after Activity recreation. Read the committed pointer under the
        // shared lock rather than using the new Activity's not-yet-loaded snapshot.
        val current = try { loadPreferences() } catch (_: Exception) { previous }
        val bitmap = resolver.openInputStream(uri)?.use { decode(it.readBounded(MAX_IMAGE_BYTES)) }
            ?: error("无法打开图片")
        val name = "wallpaper-${UUID.randomUUID()}.jpg"
        val destination = AtomicFile(File(directory, name))
        try {
            val output = destination.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                destination.finishWrite(output)
            } catch (error: Exception) { destination.failWrite(output); throw error }
            val next = current.copy(mode = WallpaperMode.PHOTO, photoBlurDp = 12, photoName = name)
            save(next)
            current.photoName?.takeIf { it != name }?.let { File(directory, it).delete() }
            next to bitmap
        } catch (error: Exception) {
            destination.delete(); bitmap.recycle(); throw error
        }
    }

    // Restoring the UI default uses save(), retaining the photo.
    fun load(): Bitmap? = loadImage(loadPreferences())
    fun import(uri: Uri): Bitmap = importPhoto(uri, loadPreferences()).second
    fun clear() = synchronized(ioLock) {
        val preferences = loadPreferences()
        save(WallpaperPreferences())
        preferences.photoName?.let { check(!File(directory, it).exists() || File(directory, it).delete()) }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1920) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片无法读取")
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        // Shared across Activity recreation: old IO finishes before a new read/write.
        private val ioLock = Any()
    }
}
