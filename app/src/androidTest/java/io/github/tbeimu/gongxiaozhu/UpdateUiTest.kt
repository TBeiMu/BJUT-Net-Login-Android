package io.github.tbeimu.gongxiaozhu

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.UpdateCard
import io.github.tbeimu.gongxiaozhu.ui.MobileDownloadDialog
import io.github.tbeimu.gongxiaozhu.ui.UpdateVersionDialog
import io.github.tbeimu.gongxiaozhu.update.UpdatePrompt
import io.github.tbeimu.gongxiaozhu.update.MobileDownloadConfirmation
import io.github.tbeimu.gongxiaozhu.update.UpdateManifest
import io.github.tbeimu.gongxiaozhu.update.UpdatePhase
import io.github.tbeimu.gongxiaozhu.update.UpdateUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val manifest = UpdateManifest(1, "0.3.0", 12, "io.github.tbeimu.gongxiaozhu", 31, 1024, "0".repeat(64), "虚构更新说明")

    @Test fun checkIsManualAndDownloadWaitsForUser() {
        val state = mutableStateOf(UpdateUiState())
        var checks = 0; var downloads = 0
        compose.setContent { GongXiaoZhuTheme { UpdateCard(state.value,
            onCheck = { checks++; state.value = UpdateUiState(UpdatePhase.AVAILABLE, manifest, message = "发现新版本") },
            onDownload = { downloads++ }, onCancel = {}, onInstall = {}, onReleasePage = {}) } }
        compose.runOnIdle { assertEquals(0, checks); assertEquals(0, downloads) }
        compose.onNodeWithText("检查更新").performClick()
        compose.onNodeWithText("虚构更新说明").assertExists()
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithText("下载更新").performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }
    @Test fun pausedDownloadAndCancelRemainActionable() {
        val state = mutableStateOf(UpdateUiState(UpdatePhase.PAUSED, manifest, 512, "WiFi 已断开或切换，请手动继续"))
        var resumes = 0
        compose.setContent { GongXiaoZhuTheme { UpdateCard(state.value, {}, { resumes++ },
            { state.value = UpdateUiState() }, {}, {}) } }
        compose.onNodeWithText("继续下载").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.onNodeWithText("取消更新").performClick()
        compose.onNodeWithText("检查更新").assertExists()
    }
    @Test fun readyApkDoesNotAutomaticallyInvokeInstaller() {
        var installs = 0
        compose.setContent { GongXiaoZhuTheme { UpdateCard(UpdateUiState(UpdatePhase.READY, manifest), {}, {}, {}, { installs++ }, {}) } }
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithTag("install_update").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }
    @Test fun debugOnlyOffersReleasePage() {
        compose.setContent { GongXiaoZhuTheme { UpdateCard(UpdateUiState(enabled = false), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        compose.onNodeWithText("打开发布页").assertExists()
    }
    @Test fun fileProviderCannotExposeAccountFilesOrPartialDownloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.updates"
        for (file in listOf(File(context.filesDir, "accounts.vault"), File(context.filesDir, "updates/download.part"))) {
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, file) }
        }
        assertEquals("content", FileProvider.getUriForFile(context, authority, File(context.filesDir, "updates/verified/update.apk")).scheme)
    }
    @Test fun mobileDownloadDialogDisclosesSizeAndWaitsForAgreement() {
        var confirmed = 0
        compose.setContent { GongXiaoZhuTheme {
            MobileDownloadDialog(MobileDownloadConfirmation("fictional", "0.3.2", 3_670_016), { confirmed++ }, {})
        } }
        compose.onNodeWithText("使用移动数据下载更新？").assertExists()
        compose.onNodeWithText("3.5 MB", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("同意并下载").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }
    @Test fun cancellingMobileDialogDoesNotApproveDownload() {
        var confirmed = 0
        val showing = mutableStateOf(true)
        compose.setContent { GongXiaoZhuTheme {
            if (showing.value) MobileDownloadDialog(MobileDownloadConfirmation("fictional", "0.3.2", 1024),
                { confirmed++ }, { showing.value = false })
        } }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("使用移动数据下载更新？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmed) }
    }

    @Test fun versionDialogButtonsStayLeftToRightWithLargeTextAndDoNotActUntilClicked() {
        var cancellations = 0; var ignores = 0; var downloads = 0
        compose.setContent { GongXiaoZhuTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 2f)) {
                UpdateVersionDialog(UpdatePrompt("fake-prompt", manifest.copy(versionName = "0.5.0", versionCode = 20, apkSize = 3_670_016)),
                    { cancellations++ }, { ignores++ }, { downloads++ })
            }
        } }
        val cancel = compose.onNodeWithTag("cancel_update_prompt").fetchSemanticsNode().boundsInRoot
        val ignore = compose.onNodeWithTag("ignore_update_prompt").fetchSemanticsNode().boundsInRoot
        val update = compose.onNodeWithTag("accept_update_prompt").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.left < ignore.left && ignore.left < update.left)
        compose.onNodeWithTag("cancel_update_prompt").assertIsDisplayed()
        compose.onNodeWithTag("ignore_update_prompt").assertIsDisplayed()
        compose.onNodeWithTag("accept_update_prompt").assertIsDisplayed()
        val inst = InstrumentationRegistry.getInstrumentation()
        val screenshot = inst.uiAutomation.takeScreenshot()
        if (screenshot != null) {
            val file = File(inst.targetContext.getExternalFilesDir(null), "update-popup-large.png")
            file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "cp ${file.absolutePath} /sdcard/Download/gxz-update-popup-large.png")).use { it.readBytes() }
        }
        compose.runOnIdle { assertEquals(0, cancellations + ignores + downloads) }
        compose.onNodeWithTag("ignore_update_prompt").performClick()
        compose.runOnIdle { assertEquals(1, ignores); assertEquals(0, downloads + cancellations) }
    }
}
