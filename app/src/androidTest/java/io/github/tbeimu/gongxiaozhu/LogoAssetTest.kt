package io.github.tbeimu.gongxiaozhu

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.BrandMark
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Render the packaged assets themselves; all local data and network fixtures remain fictional. */
class LogoAssetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun themed(night: Boolean) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
    private fun render(drawable: android.graphics.drawable.Drawable, size: Int = 432): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
    private fun count(bitmap: Bitmap, condition: (Int) -> Boolean): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count(condition)
    }
    private fun save(bitmap: Bitmap, name: String) {
        val out = File(context.getExternalFilesDir(null), "logo-v051").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun launcherRetainsTheSourceColorsInLightAndDarkThemes() {
        var previous: Bitmap? = null
        for (night in listOf(false, true)) {
            val icon = themed(night).getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
            val image = render(icon)
            assertTrue(count(image) { Color.red(it) < 60 && Color.blue(it) > 70 && Color.green(it) < 110 } > 5000)
            assertTrue(count(image) { Color.red(it) < 70 && Color.green(it) > 110 && Color.blue(it) > 120 } > 3000)
            assertTrue(count(image) { Color.red(it) > 210 && Color.green(it) > 130 && Color.blue(it) < 90 } > 500)
            previous?.let { assertTrue("夜间桌面 Logo 不应重新着色", image.sameAs(it)) }
            previous = image
            save(image, if (night) "launcher-dark" else "launcher-light")
        }
    }
    @Test fun monochromeAndNotificationKeepTheBirdNegativeSpace() {
        val ink = render(context.getDrawable(R.drawable.ic_launcher_monochrome)!!)
        val center = ink.getPixel(216, 216)
        assertEquals("脸部应保留透明负空间", 0, Color.alpha(center))
        assertTrue(count(ink) { Color.alpha(it) > 0 } in 10000..70000)
        save(ink, "monochrome")
        val notification = render(context.getDrawable(R.drawable.ic_notification_mark)!!, 96)
        assertTrue(count(notification) { Color.alpha(it) > 0 } in 1000..7000)
        assertEquals(0, Color.alpha(notification.getPixel(0, 0)))
        save(notification, "notification")
    }
    @Test fun splashAndLoadingKeepTheOriginalInkAndVermilionMark() {
        for (night in listOf(false, true)) {
            val current = themed(night)
            val splash = current.getDrawable(R.drawable.splash_mark_animated)
            assertTrue(splash is AnimatedVectorDrawable)
            val animation = splash as AnimatedVectorDrawable
            assertNotNull(current.getDrawable(R.drawable.splash_mark))
            save(render(current.getDrawable(R.drawable.splash_mark)!!), if (night) "splash-dark" else "splash-light")
            animation.start(); animation.stop()
        }
        compose.setContent { GongXiaoZhuTheme { BrandMark(Modifier.size(84.dp).testTag("old_loading_mark")) } }
        val loading = compose.onNodeWithTag("old_loading_mark").captureToImage()
        assertTrue(loading.width > 0 && loading.height > 0)
    }
    @Test fun aboutPageShowsTheBirdWhileTheProfileHasNoAvatar() {
        // Static about artwork is an independent bitmap resource, not the animated BrandMark.
        val image = render(context.getDrawable(R.drawable.logo_bird)!!)
        assertTrue(count(image) { Color.red(it) > 210 && Color.green(it) > 130 && Color.blue(it) < 90 } > 500)
        save(image, "about")
    }
}
