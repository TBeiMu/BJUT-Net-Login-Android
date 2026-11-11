package io.github.tbeimu.gongxiaozhu

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class WallpaperTest {
    @get:Rule val compose = createAndroidComposeRule<WallpaperTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = WallpaperStore(context)
    private lateinit var state: WallpaperState
    private val settings get() = File(context.noBackupFilesDir, "wallpaper.json")
    private val blockedWrite get() = File(context.noBackupFilesDir, "wallpaper.json.new")

    @Before fun clean() {
        check(context.packageName.endsWith(".debug"))
        unblock()
        store.save(WallpaperPreferences())
        context.noBackupFilesDir.listFiles()?.filter {
            it.name == "wallpaper.jpg" || Regex("wallpaper-[a-f0-9-]+\\.jpg").matches(it.name)
        }?.forEach { it.delete() }
    }

    @After fun finish() {
        compose.runOnUiThread { WallpaperTestActivity.scene = null }
        unblock()
        store.clear()
        File(context.cacheDir, "wallpaper-test.png").delete()
    }

    private fun unblock() {
        if (blockedWrite.isDirectory) { File(blockedWrite, "blocked").delete(); blockedWrite.delete() }
    }

    private fun image(color: Int = android.graphics.Color.BLUE): File {
        val file = File(context.cacheDir, "wallpaper-test.png")
        Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        return file
    }

    private fun show(fontScale: Float = 1f) {
        compose.runOnUiThread { WallpaperTestActivity.scene = { GongXiaoZhuTheme {
            state = rememberWallpaper()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppBackdrop(state) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        WallpaperSettingsPanel(state)
                    }
                }
            }
        } } }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy }
    }

    @Test fun legacyPhotoMigratesUnblurredAndModeChangesRetainItsBytes() {
        settings.delete()
        val legacy = File(context.noBackupFilesDir, "wallpaper.jpg")
        image().copyTo(legacy, overwrite = true)
        val bytes = legacy.readBytes()
        show()
        assertEquals(WallpaperMode.PHOTO, state.preferences.mode)
        assertEquals(0, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_mode_PRESET").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        compose.onNodeWithTag("wallpaper_preset_PINK").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperPreset.PINK, store.loadPreferences().preset)
        compose.onNodeWithTag("wallpaper_mode_PHOTO").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(0, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_restore").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperMode.PRESET, store.loadPreferences().mode)
        assertArrayEquals(bytes, legacy.readBytes())
        assertNotNull(store.loadImage(store.loadPreferences()))
    }

    @Test fun sixPresetsPersistAndRemainUsableWithLargeText() {
        show(1.6f)
        for (preset in WallpaperPreset.entries) {
            compose.onNodeWithTag("wallpaper_preset_${preset.name}").performScrollTo().performClick()
            compose.waitUntil { !state.busy }
            compose.onNodeWithTag("wallpaper_preset_${preset.name}").assertIsSelected()
            assertEquals(preset, WallpaperStore(context).loadPreferences().preset)
        }
        preview("wallpaper-presets-large")
        val palettes = WallpaperPreset.entries.map { wallpaperPresetColors(it, false) }
        assertEquals(6, palettes.toSet().size)
    }

    @Test fun blurPreviewsImmediatelyPersistsOnReleaseAndRollsBackOnSaveFailure() {
        store.import(Uri.fromFile(image()))
        show()
        assertEquals(12, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_blur").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(24f) }
        compose.waitUntil { !state.busy }
        assertEquals(24, WallpaperStore(context).loadPreferences().photoBlurDp)
        val before = settings.readBytes()
        assertTrue(blockedWrite.mkdir()); File(blockedWrite, "blocked").writeText("fictional failure")
        compose.runOnIdle { state.previewBlur(0f) }
        assertEquals(0, state.preferences.photoBlurDp)
        compose.runOnIdle { state.saveBlur() }
        compose.waitUntil { !state.busy }
        assertEquals(24, state.preferences.photoBlurDp)
        assertNotNull(state.message)
        assertArrayEquals(before, settings.readBytes())
    }

    @Test fun failedPhotoCommitKeepsOldImageAndCorruptionIsNotSilentlyOverwritten() {
        val old = store.importPhoto(Uri.fromFile(image()), WallpaperPreferences()).first
        val oldImage = File(context.noBackupFilesDir, old.photoName!!).readBytes()
        val before = settings.readBytes()
        assertTrue(blockedWrite.mkdir()); File(blockedWrite, "blocked").writeText("fictional failure")
        assertThrows(Exception::class.java) { store.importPhoto(Uri.fromFile(image(android.graphics.Color.RED)), old) }
        assertArrayEquals(before, settings.readBytes())
        assertArrayEquals(oldImage, File(context.noBackupFilesDir, old.photoName).readBytes())
        unblock()
        settings.writeText("fictional invalid settings")
        show()
        assertEquals("fictional invalid settings", settings.readText())
        assertNotNull(state.message)
        assertArrayEquals(oldImage, File(context.noBackupFilesDir, old.photoName).readBytes())
        compose.onNodeWithTag("wallpaper_preset_MINT").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperPreset.MINT, store.loadPreferences().preset)
    }

    @Test fun unreadablePhotoDoesNotEraseItsReferenceOrFile() {
        val preferences = store.importPhoto(Uri.fromFile(image()), WallpaperPreferences()).first
        val photo = File(context.noBackupFilesDir, preferences.photoName!!)
        photo.writeText("fictional damaged image")
        val before = settings.readBytes()
        show()
        assertNull(state.image)
        assertNotNull(state.message)
        assertArrayEquals(before, settings.readBytes())
        assertEquals("fictional damaged image", photo.readText())
    }

    @Test fun systemColorsFollowChangesRefreshOnReturnAndNullKeepsPreviousPalette() {
        val manager = WallpaperManager.getInstance(InstrumentationRegistry.getInstrumentation().context)
        fun set(color: Int) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.SET_WALLPAPER")
            try { manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM) }
            finally { automation.dropShellPermissionIdentity(); bitmap.recycle() }
        }
        set(android.graphics.Color.RED)
        show()
        compose.onNodeWithTag("wallpaper_mode_SYSTEM_COLORS").performScrollTo().performClick()
        compose.waitUntil(10000) { !state.busy && state.systemColors?.firstOrNull()?.red?.let { it > .8f } == true }
        val red = state.systemColors
        compose.runOnIdle { state.acceptSystemColors(null) }
        assertEquals(red, state.systemColors)
        assertTrue(state.systemColorsUnavailable)
        set(android.graphics.Color.GREEN)
        compose.waitUntil(10000) { state.systemColors?.firstOrNull()?.green?.let { it > .8f } == true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val previous = state.systemColors
        set(android.graphics.Color.BLUE)
        assertEquals(previous, state.systemColors)
        // Wallpaper changes can recreate a stopped Activity. Bring its task to the front as a
        // real launcher return would, instead of asking ActivityScenario to resume a stale instance.
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, WallpaperTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        compose.waitUntil(10000) { state.systemColors?.firstOrNull()?.blue?.let { it > .8f } == true }
        preview("wallpaper-system-colors")
    }

    @Test fun renderedPhotoBlurSoftensBackgroundButNotForeground() {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) setPixel(x, y,
                if (x % 8 < 4) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        val local = WallpaperState().apply {
            image = bitmap.asImageBitmap()
            preferences = WallpaperPreferences(mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = "wallpaper.jpg")
        }
        compose.runOnUiThread { WallpaperTestActivity.scene = { GongXiaoZhuTheme {
            Box(Modifier.size(200.dp, 400.dp)) {
                WallpaperBackground(local, Modifier.fillMaxSize().testTag("blur_background"))
                Text("前景保持清晰", Modifier.align(Alignment.Center).background(Color.White).testTag("foreground"), color = Color.Black)
            }
        } } }
        fun contrast(): Float {
            val pixels = compose.onNodeWithTag("blur_background").captureToImage().asAndroidBitmap()
            val y = pixels.height / 4
            return (11 until pixels.width - 10).sumOf { x -> abs(android.graphics.Color.red(pixels.getPixel(x, y)) -
                android.graphics.Color.red(pixels.getPixel(x - 1, y))) }.toFloat() / (pixels.width - 21)
        }
        val original = contrast()
        val foreground = compose.onNodeWithTag("foreground").captureToImage().asAndroidBitmap()
        compose.runOnIdle { local.preferences = local.preferences.copy(photoBlurDp = 24) }
        assertTrue("Gaussian blur must visibly soften the image", contrast() < original * .25f)
        compose.onNodeWithTag("foreground").assertIsDisplayed()
        assertTrue("Foreground pixels must not be blurred", foreground.sameAs(
            compose.onNodeWithTag("foreground").captureToImage().asAndroidBitmap()))
        preview("wallpaper-photo-blur")
    }

    private fun preview(name: String) {
        context.getExternalFilesDir(null)?.resolve("$name.png")?.outputStream()?.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
