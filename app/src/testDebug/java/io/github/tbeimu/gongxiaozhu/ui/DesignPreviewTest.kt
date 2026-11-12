package io.github.tbeimu.gongxiaozhu.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the debug showcase (fictional data) to PNG files on the JVM, for design review without
 * an emulator. Run with `-PgxzPreview=true`; images go to `app/build/design-preview` unless
 * `-PgxzPreviewDir` names another folder. Skipped in ordinary test runs.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DesignPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val out = File(System.getProperty("gxz.preview.dir") ?: "build/design-preview")

    @Before fun setUp() {
        assumeTrue("Design previews are rendered only on request", System.getProperty("gxz.preview") == "true")
        out.mkdirs()
        // Robolectric has no CJK system font; borrow one from the host when it is installed.
        val fonts = listOf(FontWeight.Normal to "Noto Sans SC (TrueType).otf", FontWeight.Medium to "Noto Sans SC Medium (TrueType).otf",
            FontWeight.Bold to "Noto Sans SC Bold (TrueType).otf").map { (weight, name) -> weight to File("C:/Windows/Fonts", name) }
        ShowcaseActivity.fontFamily = if (fonts.all { it.second.isFile }) FontFamily(fonts.map { (weight, file) -> Font(file, weight) }) else null
    }

    private fun open(tab: String? = null, page: String? = null, empty: Boolean = false, then: () -> Unit) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShowcaseActivity::class.java).putExtra("empty", empty)
        tab?.let { intent.putExtra("tab", it) }; page?.let { intent.putExtra("page", it) }
        ActivityScenario.launch<ShowcaseActivity>(intent).use { compose.waitForIdle(); then() }
    }

    private fun shot(name: String) { compose.waitForIdle(); captureScreenRoboImage(File(out, "$name.png").path) }

    private fun week() { compose.onNodeWithTag("schedule_mode").performClick(); compose.waitForIdle() }

    @Test fun schedule() = open { shot("01-schedule") }
    @Test fun otherDay() = open { compose.onNodeWithTag("agenda_day_3").performClick(); shot("20-agenda-thursday") }
    @Test fun emptyDay() = open { compose.onNodeWithTag("agenda_day_6").performClick(); shot("21-agenda-sunday") }
    @Test fun weekGrid() = open { week(); shot("22-week-grid") }
    @Test @Config(qualifiers = "+night") fun weekGridNight() = open { week(); shot("23-week-grid-night") }
    @Test @Config(qualifiers = "+night") fun scheduleNight() = open { shot("02-schedule-night") }
    @Test fun courseDetails() = open { compose.onNodeWithTag("agenda_course_2_5").performClick(); shot("03-course-details") }
    @Test fun weekList() = open { week(); compose.onNodeWithContentDescription("选择周次").performClick(); shot("04-week-list") }
    @Test fun otherWeek() = open {
        week(); compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("week_choice_12").performClick(); shot("05-other-week")
    }
    @Test fun billing() = open(tab = "BILLING") { shot("06-billing") }
    @Test @Config(qualifiers = "+night") fun billingNight() = open(tab = "BILLING") { shot("07-billing-night") }
    @Test fun my() = open(tab = "MY") { shot("08-my") }
    @Test @Config(qualifiers = "+night") fun myNight() = open(tab = "MY") { shot("09-my-night") }
    @Test fun network() = open(page = "NETWORK") { shot("10-network") }
    @Test fun accounts() = open(page = "ACCOUNTS") { shot("11-accounts") }
    @Test fun appearance() = open(page = "APPEARANCE") { shot("12-appearance") }
    @Test fun scheduleManagement() = open(page = "SCHEDULE") { shot("13-schedule-management") }
    @Test fun about() = open(page = "ABOUT") { shot("14-about") }
    @Test fun emptySchedule() = open(empty = true) { shot("16-empty-schedule") }
    @Test fun emptyBilling() = open(tab = "BILLING", empty = true) { shot("17-empty-billing") }
    @Test @Config(qualifiers = "w360dp-h760dp-xxhdpi") fun smallPhone() = open { shot("18-schedule-360dp") }
    @Test @Config(qualifiers = "+night") fun appearanceNight() = open(page = "APPEARANCE") { shot("19-appearance-night") }
    /** The real launcher, themed, notification and splash drawables, drawn the way the system draws them. */
    @Test fun brandAssets() = renderAssets("light")
    @Test @Config(qualifiers = "+night") fun brandAssetsNight() = renderAssets("night")

    private fun renderAssets(tag: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(out, "brand").apply { mkdirs() }
        fun save(name: String, size: Int, background: Int?, draw: (android.graphics.Canvas) -> Unit) {
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            background?.let { canvas.drawColor(it) }
            draw(canvas)
            File(dir, "$tag-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val icon = context.getDrawable(io.github.tbeimu.gongxiaozhu.R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        save("launcher", 432, null) { icon.setBounds(0, 0, 432, 432); icon.draw(it) }
        save("themed", 432, null) { canvas ->
            canvas.drawCircle(216f, 216f, 216f, android.graphics.Paint().apply { color = 0xFFD7E3F4.toInt(); isAntiAlias = true })
            icon.monochrome!!.mutate().apply { setTint(0xFF2E4A6E.toInt()); setBounds(0, 0, 432, 432) }.draw(canvas)
        }
        save("notification", 96, 0xFF1C1E23.toInt()) { canvas ->
            context.getDrawable(io.github.tbeimu.gongxiaozhu.R.drawable.ic_notification_mark)!!.apply { setBounds(12, 12, 84, 84) }.draw(canvas)
        }
        // The splash animation frame by frame, driven by the platform animator itself.
        val splash = context.getDrawable(io.github.tbeimu.gongxiaozhu.R.drawable.splash_mark_animated) as android.graphics.drawable.AnimatedVectorDrawable
        splash.setBounds(0, 0, 432, 432)
        splash.start()
        val paper = context.getColor(io.github.tbeimu.gongxiaozhu.R.color.logo_paper)
        for (frame in 0..26) {
            save("splash-%02d".format(frame), 432, paper) { splash.draw(it) }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        }
    }

    @Test fun presetPink() = open(page = "APPEARANCE") {
        compose.onNodeWithTag("wallpaper_preset_PINK").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("课表").performClick(); shot("15-schedule-pink")
    }
}
