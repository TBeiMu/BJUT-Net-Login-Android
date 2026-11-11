package io.github.tbeimu.gongxiaozhu.ui

import android.app.WallpaperManager
import android.app.WallpaperColors
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class WallpaperState {
    var preferences by mutableStateOf(WallpaperPreferences())
    var image by mutableStateOf<ImageBitmap?>(null)
    var systemColors by mutableStateOf<List<Color>?>(null)
    var systemColorsUnavailable by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var choose: () -> Unit = {}
    var clear: () -> Unit = {}
    var selectMode: (WallpaperMode) -> Unit = {}
    var selectPreset: (WallpaperPreset) -> Unit = {}
    var previewBlur: (Float) -> Unit = {}
    var saveBlur: () -> Unit = {}

    fun acceptSystemColors(colors: List<Color>?) {
        if (!colors.isNullOrEmpty()) { systemColors = colors.take(3); systemColorsUnavailable = false }
        else systemColorsUnavailable = true
    }
}

@Composable
internal fun rememberWallpaper(): WallpaperState {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { WallpaperStore(context) }
    val state = remember { WallpaperState().apply { busy = true } }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(WallpaperPreferences()) }
    var operation by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }

    fun commit(next: WallpaperPreferences) {
        if (state.busy) return
        state.busy = true
        val token = ++operation
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(next) }
                if (token == operation) { saved = next; state.preferences = next; state.message = null }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (token == operation) { state.preferences = saved; state.message = "背景设置保存失败，已保留原设置" } }
            finally { if (token == operation) state.busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) state.busy = !loaded
        else { state.busy = true; pendingPhoto = uri }
    }
    // Restored picker results may arrive before the first read after Activity recreation.
    // Finish that read first, then import using its snapshot; initialization never supersedes it.
    LaunchedEffect(pendingPhoto, loaded) {
        val uri = pendingPhoto
        if (!loaded || uri == null) return@LaunchedEffect
        val token = ++operation
        state.busy = true
        try {
            val (preferences, bitmap) = withContext(Dispatchers.IO) { store.importPhoto(uri, saved) }
            if (token == operation) {
                saved = preferences; state.preferences = preferences
                state.image = bitmap.asImageBitmap(); state.message = null
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (token == operation) state.message = "图片无法读取或保存，已保留原背景" }
        finally { if (token == operation) { pendingPhoto = null; state.busy = false } }
    }
    state.choose = {
        if (!state.busy) {
            state.busy = true
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { state.busy = false; state.message = "暂时无法打开照片选择器" }
        }
    }
    state.clear = { commit(saved.restoredDefault()) }
    state.selectMode = { mode ->
        if (mode == WallpaperMode.PHOTO && state.image == null) state.choose()
        else commit(saved.copy(mode = mode))
    }
    state.selectPreset = { commit(saved.copy(mode = WallpaperMode.PRESET, preset = it)) }
    state.previewBlur = { if (!state.busy) state.preferences = state.preferences.copy(photoBlurDp = it.roundToInt().coerceIn(0, 24)) }
    state.saveBlur = { commit(state.preferences) }

    LaunchedEffect(store) {
        val token = ++operation
        try {
            val preferences = withContext(Dispatchers.IO) { store.loadPreferences() }
            val image = try { withContext(Dispatchers.IO) { store.loadImage(preferences)?.asImageBitmap() } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { if (token == operation) state.message = "原壁纸无法读取，暂时显示默认背景"; null }
            if (token == operation) { saved = preferences; state.preferences = preferences; state.image = image }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (token == operation) state.message = "背景设置无法读取，暂时显示默认背景；可重新选择" }
        finally { if (token == operation) { loaded = true; state.busy = pendingPhoto != null } }
    }
    ObserveWallpaperColors(state)
    return state
}

@Composable
private fun ObserveWallpaperColors(state: WallpaperState) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner, context, state.preferences.mode) {
        val manager = WallpaperManager.getInstance(context)
        var active = false
        var generation = 0
        var read: Job? = null
        var registered = false
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (active && which and WallpaperManager.FLAG_SYSTEM != 0) {
                generation++
                read?.cancel()
                state.acceptSystemColors(colors.toPalette())
            }
        }
        fun stop() {
            active = false; generation++; read?.cancel()
            if (registered) {
                runCatching { manager.removeOnColorsChangedListener(listener) }
                registered = false
            }
        }
        fun start() {
            if (active || state.preferences.mode != WallpaperMode.SYSTEM_COLORS) return
            active = true
            val token = ++generation
            registered = runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess
            read = scope.launch {
                val colors = withContext(Dispatchers.IO) {
                    runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
                }
                if (active && token == generation) state.acceptSystemColors(colors.toPalette())
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { stop(); owner.lifecycle.removeObserver(observer) }
    }
}

private fun WallpaperColors?.toPalette(): List<Color>? = this?.let {
    listOfNotNull(primaryColor, secondaryColor, tertiaryColor).map { color -> Color(color.toArgb()) }
}

internal fun wallpaperPresetColors(preset: WallpaperPreset, dark: Boolean): List<Color> {
    val light = when (preset) {
        WallpaperPreset.BLUE_CYAN -> listOf(0xFFA9CDF4, 0xFFD0EAF0, 0xFFE5E8FA)
        WallpaperPreset.MINT -> listOf(0xFFA8DCCB, 0xFFD3EDE2, 0xFFE6F0ED)
        WallpaperPreset.LAVENDER -> listOf(0xFFC8B7E7, 0xFFE4DAF2, 0xFFE8E7F5)
        WallpaperPreset.PINK -> listOf(0xFFE8B9CF, 0xFFF4DFE7, 0xFFF0E8F1)
        WallpaperPreset.APRICOT -> listOf(0xFFE8C29E, 0xFFF3E2CD, 0xFFF5EBDF)
        WallpaperPreset.GRAY -> listOf(0xFFC5CFD8, 0xFFE2E7EB, 0xFFF0F2F5)
    }.map { Color(it) }
    if (!dark) return light
    if (preset == WallpaperPreset.BLUE_CYAN) return listOf(Color(0xFF122A43), Color(0xFF173C4D), Color(0xFF182839))
    return light.map { lerp(it, Color(0xFF081523), .82f) }
}

@Composable
internal fun WallpaperBackground(wallpaper: WallpaperState, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val preferences = wallpaper.preferences
    val colors = if (preferences.mode == WallpaperMode.SYSTEM_COLORS && !wallpaper.systemColors.isNullOrEmpty()) {
        val source = wallpaper.systemColors!!
        List(3) { index -> lerp(source.getOrElse(index) { source.last() },
            if (dark) Color(0xFF081523) else Color.White, if (dark) .78f else .55f) }
    } else wallpaperPresetColors(if (preferences.mode == WallpaperMode.PRESET) preferences.preset else WallpaperPreset.BLUE_CYAN, dark)
    val radius = with(LocalDensity.current) { preferences.photoBlurDp.dp.toPx() }
    Box(modifier.background(Brush.verticalGradient(colors))) {
        val photo = wallpaper.image.takeIf { preferences.mode == WallpaperMode.PHOTO }
        if (photo != null) Image(photo, contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
            }, contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) {
            val wave = Path().apply {
                moveTo(0f, size.height * .7f)
                cubicTo(size.width * .35f, size.height * .44f, size.width * .8f, size.height * .95f, size.width, size.height * .66f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(wave, Color.White.copy(alpha = if (dark) .03f else .18f))
        }
        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF081523).copy(alpha = .65f) else Color.White.copy(alpha = .48f)))
    }
}

@Composable
internal fun AppBackdrop(wallpaper: WallpaperState, content: @Composable () -> Unit) {
    val backdrop = rememberScheduleBackdrop()
    Box(Modifier.fillMaxSize()) {
        WallpaperBackground(wallpaper, Modifier.fillMaxSize().recordScheduleBackdrop(backdrop))
        CompositionLocalProvider(LocalWallpaperBackdrop provides backdrop) { content() }
    }
}
