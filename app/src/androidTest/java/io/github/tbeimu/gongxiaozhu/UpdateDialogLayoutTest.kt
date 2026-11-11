package io.github.tbeimu.gongxiaozhu

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.UpdateVersionDialog
import io.github.tbeimu.gongxiaozhu.update.UpdateManifest
import io.github.tbeimu.gongxiaozhu.update.UpdatePrompt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateDialogLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val manifest = UpdateManifest(1, "0.5.0", 21, "io.github.tbeimu.gongxiaozhu", 31,
        3_670_016, "0".repeat(64), "虚构横屏更新说明\n\n改善课表和界面。\n\n完善应用内更新。\n\n使用流量下载前需本次同意。")

    @Test fun landscapeWithTwoTimesSystemFontCanReadNotesAndUseEveryAction() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val oldScale = shell("settings get system font_scale").trim().ifBlank { "1.0" }
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(5000) { compose.activity.resources.configuration.fontScale >= 1.9f }
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.setContent { GongXiaoZhuTheme { UpdateVersionDialog(UpdatePrompt("fake-layout", manifest), {}, {}, {}) } }
            val notes = compose.onNodeWithTag("update_prompt_notes")
            notes.performScrollTo().assertIsDisplayed()
            val visibleHeight = notes.fetchSemanticsNode().boundsInRoot.height
            notes.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts))
                val lineHeight = layouts.single().getLineBottom(0) - layouts.single().getLineTop(0)
                assertTrue("The notes viewport must display a full line", visibleHeight >= lineHeight)
            }
            val cancel = compose.onNodeWithTag("cancel_update_prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val ignore = compose.onNodeWithTag("ignore_update_prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val update = compose.onNodeWithTag("accept_update_prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(cancel.left < ignore.left && ignore.left < update.left)
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                val file = File(inst.targetContext.getExternalFilesDir(null), "update-dialog-landscape.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                shell("cp ${file.absolutePath} /sdcard/Download/gxz-update-dialog-landscape.png")
            }
        } finally {
            shell("settings put system font_scale ${oldScale.takeIf { it.toFloatOrNull() != null } ?: "1.0"}")
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
