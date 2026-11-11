package io.github.tbeimu.gongxiaozhu

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.time.LocalDate

/** Fictional transport is injected only into the isolated debug instrumentation process. */
class UpdatePromptIntegrationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val manifest = UpdateManifest(1, "0.5.0", 20, "io.github.tbeimu.gongxiaozhu", 31,
        3_670_016, "0".repeat(64), "更新内容（测试示例）\n\n1. 改善课表和个人页的使用体验。\n\n2. 打开应用后检查新版，可选择忽略当前版本。\n\n3. 完善校园网连接状态的短暂提示。\n\n4. 下载前确认流量使用，保留账号与设置。\n\n以上为虚构测试说明。")
    private class Source(private val manifest: UpdateManifest) : UpdateSource {
        val gate = CompletableDeferred<Unit>()
        var checks = 0; var downloads = 0
        override suspend fun check(): UpdateManifest { checks++; gate.await(); return manifest }
        override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
            saveEtag: (String?) -> Unit, progress: (Long) -> Unit) { downloads++; awaitCancellation() }
    }
    private lateinit var source: Source
    private lateinit var repository: UpdateRepository
    private lateinit var original: UpdateRepository
    private lateinit var runtime: UpdateRuntime
    private lateinit var scope: CoroutineScope
    private lateinit var store: UpdateStore
    private var notificationAsked = false
    private val fixture = object : ExternalResource() {
        override fun before() {
            val inst = InstrumentationRegistry.getInstrumentation()
            val context = inst.targetContext
            check(context.packageName.endsWith(".debug"))
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
            createAccountRepository(context).apply { reset(); load(); settings(AppSettings(autoLogin = false)) }
            val identity = ScheduleIdentity("fictional-update-student", "测试同学")
            val term = ScheduleTerm("2026-2027", "1", 1, 1)
            val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
            val week = ScheduleWeek(1, (0L..6L).map { monday.plusDays(it).toString() }, emptyList())
            createScheduleRepository(context).apply {
                clear(); begin(identity, term); append(week); finish(1); begin(identity, term)
            }
            val preferences = context.getSharedPreferences("update_ui", Context.MODE_PRIVATE)
            notificationAsked = preferences.getBoolean("notification_asked", false)
            preferences.edit().putBoolean("notification_asked", true).commit()
            source = Source(manifest)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            store = UpdateStore(File(context.cacheDir, "prompt-fixture-${java.util.UUID.randomUUID()}"))
            inst.runOnMainSync {
                runtime = UpdateRuntime.get(context)
                original = runtime.repository
                repository = UpdateRepository(scope, store, ApkVerifier(19, manifest.packageName, 36, "1".repeat(64)) { null }, 19, 36)
                UpdateRuntime::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(runtime, repository)
                repository.networkChanged(UpdateNetwork("fictional-wifi", source))
            }
        }
        override fun after() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.runOnMainSync { repository.cancel() }
            runBlocking { withTimeout(5000) { repository.state.first { it.phase == UpdatePhase.IDLE } } }
            inst.runOnMainSync {
                UpdateRuntime::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(runtime, original)
                scope.cancel()
            }
            val context = inst.targetContext
            context.getSharedPreferences("update_ui", Context.MODE_PRIVATE).edit().putBoolean("notification_asked", notificationAsked).commit()
            createAccountRepository(context).reset(); createScheduleRepository(context).clear()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    private fun showPrompt() {
        compose.waitUntil(5000) { source.checks == 1 }
        compose.runOnUiThread { source.gate.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("update_prompt")).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openManualCheck() {
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_UPDATES").performScrollTo().performClick()
        compose.onNodeWithTag("check_update").performScrollTo().performClick()
    }

    @Test fun startupButtonsHaveRequestedOrderAndCancelDoesNotDownloadOrRepeatOnRotation() {
        showPrompt()
        screenshot("normal")
        val cancel = compose.onNodeWithTag("cancel_update_prompt").fetchSemanticsNode().boundsInRoot
        val ignore = compose.onNodeWithTag("ignore_update_prompt").fetchSemanticsNode().boundsInRoot
        val update = compose.onNodeWithTag("accept_update_prompt").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.left < ignore.left && ignore.left < update.left)
        compose.onNodeWithText("3.5 MB", substring = true).assertExists()
        compose.onNodeWithTag("cancel_update_prompt").performClick()
        compose.onNodeWithTag("update_prompt").assertDoesNotExist()
        assertEquals(0, source.downloads); assertNull(store.load())
        compose.activityRule.scenario.recreate()
        assertEquals(1, source.checks)
        compose.onNodeWithTag("accept_update_prompt").assertDoesNotExist()
        openManualCheck()
        compose.onNodeWithTag("accept_update_prompt").assertIsDisplayed()
        assertEquals(2, source.checks)
    }

    @Test fun ignoredVersionIsQuietAfterReturningButManualCheckingStillOffersIt() {
        showPrompt(); compose.onNodeWithTag("ignore_update_prompt").performClick()
        assertEquals(20L, store.ignoredVersion())
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { source.checks == 2 && repository.state.value.phase != UpdatePhase.CHECKING }
        compose.onNodeWithTag("accept_update_prompt").assertDoesNotExist()
        openManualCheck()
        compose.onNodeWithTag("accept_update_prompt").assertIsDisplayed()
        assertEquals(3, source.checks); assertEquals(0, source.downloads)
    }

    @Test fun immediateUpdateOpensUpdatePageAndStartsOnlyOneWifiDownload() {
        showPrompt(); compose.onNodeWithTag("accept_update_prompt").performClick()
        compose.waitUntil(5000) { source.downloads == 1 }
        compose.onNodeWithTag("update_card").assertExists()
        compose.onNodeWithTag("accept_update_prompt").assertDoesNotExist()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
        assertEquals(1, source.checks)
    }

    @Test fun immediateMobileUpdateWaitsForSeparateConsentAndDecliningSendsNoApkRequest() {
        showPrompt()
        compose.runOnUiThread { repository.networkChanged(UpdateNetwork("fictional-cell", source, UpdateNetworkKind.CELLULAR)) }
        compose.onNodeWithTag("accept_update_prompt").performClick()
        compose.onNodeWithText("使用移动数据下载更新？").assertIsDisplayed()
        assertEquals(0, source.downloads)
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("使用移动数据下载更新？").assertDoesNotExist()
        assertEquals(0, source.downloads); assertEquals(false, store.load()?.downloadStarted)
        compose.onNodeWithTag("update_card").assertExists()
    }

    @Test fun automaticPromptWaitsForImportCancellationDialogToClose() {
        compose.waitUntil(5000) { source.checks == 1 }
        compose.onNodeWithTag("cancel_schedule_import").performClick()
        compose.onNodeWithText("取消本次课表导入？").assertIsDisplayed()
        compose.runOnUiThread { source.gate.complete(Unit) }
        compose.waitUntil(5000) { repository.state.value.prompt != null }
        compose.onNodeWithTag("update_prompt").assertDoesNotExist()
        compose.onNodeWithText("保留进度").performClick()
        compose.onNodeWithTag("accept_update_prompt").assertIsDisplayed()
    }

    @Test fun decliningFirstNotificationPermissionStillContinuesTheRequestedWifiDownload() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val info = inst.uiAutomation.serviceInfo
        val oldFlags = info.flags
        try {
            shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
            shell("pm clear-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
            context.getSharedPreferences("update_ui", Context.MODE_PRIVATE).edit().putBoolean("notification_asked", false).commit()
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            inst.uiAutomation.serviceInfo = info
            showPrompt(); compose.onNodeWithTag("accept_update_prompt").performClick()
            compose.waitUntil(5000) { findDeny(inst.uiAutomation.rootInActiveWindow) != null }
            assertEquals(0, source.downloads)
            val deny = findDeny(inst.uiAutomation.rootInActiveWindow)!!
            assertTrue(deny.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(5000) { source.downloads == 1 }
            assertEquals(1, source.checks)
            compose.onNodeWithTag("update_card").assertExists()
        } finally {
            info.flags = oldFlags; inst.uiAutomation.serviceInfo = info
            if (granted) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
    }

    private fun findDeny(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true) return node
        for (i in 0 until node.childCount) findDeny(node.getChild(i))?.let { return it }
        return null
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun screenshot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val output = File(inst.targetContext.getExternalFilesDir(null), "update-popup-$name.png")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "cp ${output.absolutePath} /sdcard/Download/gxz-update-popup-$name.png")).use { it.readBytes() }
    }
}
