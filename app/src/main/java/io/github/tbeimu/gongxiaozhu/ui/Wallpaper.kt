package io.github.tbeimu.gongxiaozhu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.tbeimu.gongxiaozhu.data.readBounded
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WallpaperStore(context: Context) {
    private val resolver = context.contentResolver
    private val file = AtomicFile(File(context.noBackupFilesDir, "wallpaper.jpg"))
    fun load(): Bitmap? = if (!file.baseFile.exists()) null else file.openRead().use { decode(it.readBounded(20 * 1024 * 1024)) }
    fun import(uri: Uri): Bitmap {
        val bitmap = resolver.openInputStream(uri)?.use { decode(it.readBounded(20 * 1024 * 1024)) } ?: error("无法打开图片")
        val stream = file.startWrite()
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        return bitmap
    }
    fun clear() { file.delete(); check(!file.baseFile.exists()) }
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
}

internal class WallpaperState {
    var image by mutableStateOf<ImageBitmap?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var choose: () -> Unit = {}
    var clear: () -> Unit = {}
}

@Composable
internal fun rememberWallpaper(): WallpaperState {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { WallpaperStore(context) }
    val state = remember { WallpaperState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            state.busy = true
            try { state.image = withContext(Dispatchers.IO) { store.import(uri).asImageBitmap() }; state.message = "背景已更新，仅保存在本机" }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { state.message = "图片无法读取或过大，已保留原背景" }
            finally { state.busy = false }
        }
    }
    state.choose = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    state.clear = { scope.launch {
        state.busy = true
        try { withContext(Dispatchers.IO) { store.clear() }; state.image = null; state.message = "已恢复默认背景" }
        catch (_: Exception) { state.message = "恢复背景失败，请重试" }
        finally { state.busy = false }
    } }
    LaunchedEffect(store) {
        try { state.image = withContext(Dispatchers.IO) { store.load()?.asImageBitmap() } }
        catch (_: Exception) { state.message = "原背景无法读取，暂时显示默认背景" }
    }
    return state
}

@Composable
internal fun AppBackdrop(wallpaper: WallpaperState, content: @Composable () -> Unit) {
    val backdrop = rememberScheduleBackdrop()
    val dark = isSystemInDarkTheme()
    val colors = if (dark) listOf(Color(0xFF122A43), Color(0xFF173C4D), Color(0xFF182839))
        else listOf(Color(0xFFA9CDF4), Color(0xFFD0EAF0), Color(0xFFE5E8FA))
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().recordScheduleBackdrop(backdrop).background(Brush.verticalGradient(colors))) {
            wallpaper.image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Canvas(Modifier.fillMaxSize()) {
                    val wave = Path().apply {
                        moveTo(0f, size.height * .7f)
                        cubicTo(size.width * .35f, size.height * .44f, size.width * .8f, size.height * .95f, size.width, size.height * .66f)
                        lineTo(size.width, size.height); lineTo(0f, size.height); close()
                    }
                    drawPath(wave, Color.White.copy(alpha = if (dark) .03f else .18f))
                }
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF081523).copy(alpha = .65f) else Color.White.copy(alpha = .48f)))
        }
        CompositionLocalProvider(LocalWallpaperBackdrop provides backdrop) { content() }
    }
}
