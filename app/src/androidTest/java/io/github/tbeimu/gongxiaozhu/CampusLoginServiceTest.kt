package io.github.tbeimu.gongxiaozhu

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.CampusAuthenticator
import io.github.tbeimu.gongxiaozhu.network.CampusLogin
import io.github.tbeimu.gongxiaozhu.network.CampusLoginController
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.CampusLoginService
import io.github.tbeimu.gongxiaozhu.network.LoginResult
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.update.UpdateRuntime
import io.github.tbeimu.gongxiaozhu.update.UpdateRepository
import io.github.tbeimu.gongxiaozhu.update.UpdateStore
import io.github.tbeimu.gongxiaozhu.update.UpdateManifest
import io.github.tbeimu.gongxiaozhu.update.UpdateNetwork
import io.github.tbeimu.gongxiaozhu.update.UpdateSource
import io.github.tbeimu.gongxiaozhu.update.ApkVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import java.io.File

/** Only this test APK replaces the authenticator. Production contains no bypass or test credential. */
@RunWith(AndroidJUnit4::class)
class CampusLoginServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: CampusLoginRuntime
    private val result = CompletableDeferred<LoginResult>()
    private val calls = AtomicInteger()

    @Before fun setup() {
        check(context.packageName.endsWith(".debug"))
        createAccountRepository(context).apply {
            reset(); load()
            save(AccountDraft(label = "服务测试", username = "fictional-service-user", password = "fictional-service-password"))
            settings(AppSettings(autoLogin = false, networkMode = NetworkMode.PUBLIC_WIFI))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        runtime = CampusLoginRuntime.get(context)
        waitFor { runtime.controller.state.value.wifiConnected }
        main {
            CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }
                .set(runtime.controller, CampusAuthenticator { _, _, _, progress ->
                    calls.incrementAndGet(); progress("虚构认证等待中")
                    result.await()
                })
        }
    }

    @After fun cleanup() {
        if (::runtime.isInitialized) {
            main { runtime.controller.cancel() }
            waitFor { !runtime.controller.state.value.running && !serviceRunning() }
            main { CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }
                .set(runtime.controller, CampusLogin()) }
        }
        if (::scenario.isInitialized) scenario.close()
        createAccountRepository(context).reset()
    }

    @Test fun backgroundCompletionRemovesNotificationAndReturnDoesNotRestart() {
        start()
        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(serviceRunning())
        assertTrue(runtime.controller.state.value.running)
        result.complete(LoginResult("虚构后台成功", completed = true))
        waitFor { !runtime.controller.state.value.running && !serviceRunning() }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 401 })
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, calls.get())
        assertEquals("虚构后台成功", runtime.controller.state.value.message)
    }

    @Test fun activityRecreationAndReturnKeepSameTaskAndCancellationStopsService() {
        start()
        val id = runtime.controller.state.value.taskId
        scenario.recreate()
        assertEquals(id, runtime.controller.state.value.taskId)
        assertEquals(1, calls.get())
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, calls.get())
        main { runtime.controller.cancel(id) }
        waitFor { !serviceRunning() && !runtime.controller.state.value.running }
    }

    // Run with POST_NOTIFICATIONS denied before instrumentation (revocation kills the process).
    @Test fun deniedNotificationsDoNotBlockBackgroundAuthentication() {
        org.junit.Assume.assumeFalse(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        start()
        scenario.moveToState(Lifecycle.State.CREATED)
        result.complete(LoginResult("虚构失败"))
        waitFor { !serviceRunning() && !runtime.controller.state.value.running }
        assertEquals("虚构失败", runtime.controller.state.value.message)
    }

    @Test fun replayedStartCannotCreateAnotherAuthentication() {
        start()
        val id = runtime.controller.state.value.taskId
        scenario.onActivity { it.startService(Intent(it, CampusLoginService::class.java).putExtra(CampusLoginService.TASK_ID, id)) }
        instrumentation.waitForIdleSync()
        assertEquals(1, calls.get())
    }

    @Test fun removingRecentTaskStopsAuthentication() {
        start()
        scenario.onActivity { activity ->
            activity.getSystemService(ActivityManager::class.java).appTasks
                .first { it.taskInfo.taskId == activity.taskId }.finishAndRemoveTask()
        }
        waitFor { !runtime.controller.state.value.running && !serviceRunning() }
        assertFalse(runtime.controller.state.value.completed)
    }

    @Test fun screenOffKeepsTaskAndWakeLockUntilCompletion() {
        start()
        try {
            shell("input keyevent 223")
            waitFor { !context.getSystemService(android.os.PowerManager::class.java).isInteractive }
            assertTrue(runtime.controller.state.value.running)
            result.complete(LoginResult("虚构锁屏完成", completed = true))
            waitFor { !runtime.controller.state.value.running && !serviceRunning() }
        } finally { shell("input keyevent 224") }
    }

    @Test fun progressNotificationCancelStopsOnlyItsOwnAttempt() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        start()
        val manager = context.getSystemService(NotificationManager::class.java)
        waitFor { manager.activeNotifications.any { it.id == 401 } }
        val cancel = manager.activeNotifications.first { it.id == 401 }.notification.actions.single().actionIntent
        cancel.send()
        waitFor { !runtime.controller.state.value.running && !serviceRunning() }
        waitFor { manager.activeNotifications.none { it.id == 401 } }
    }

    @Test fun platformTimeoutCallbackStopsServiceWithoutRestart() {
        start()
        main {
            // Invoke the platform callback on the actual running service, not a mock Service.
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
            val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
            val service = services.values.filterIsInstance<CampusLoginService>().single()
            if (Build.VERSION.SDK_INT >= 35) service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else if (Build.VERSION.SDK_INT >= 34) service.onTimeout(1)
            else service.onTaskRemoved(null)
        }
        waitFor { !runtime.controller.state.value.running && !serviceRunning() }
        assertEquals(1, calls.get())
    }

    @Test fun wifiLossInBackgroundStopsWithoutAutomaticResume() {
        start()
        scenario.moveToState(Lifecycle.State.CREATED)
        try {
            shell("svc wifi disable")
            waitFor { !runtime.controller.state.value.running && !serviceRunning() }
            assertFalse(runtime.controller.state.value.completed)
        } finally { shell("svc wifi enable") }
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { runtime.controller.state.value.wifiConnected }
        assertEquals(1, calls.get())
    }

    @Test fun updateDownloadAndAuthenticationHaveIndependentServiceLifetimes() {
        val updates = UpdateRuntime.get(context)
        val field = UpdateRuntime::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val original = field.get(updates)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val directory = File(context.cacheDir, "fictional-update-service-test")
        val store = UpdateStore(directory)
        val manifest = UpdateManifest(1, "99.0.0", 9999, "io.github.tbeimu.gongxiaozhu", 31, 1024, "1".repeat(64), "虚构说明")
        val source = object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = awaitCancellation()
        }
        val repository = UpdateRepository(scope, store, ApkVerifier(14, manifest.packageName, 36, "2".repeat(64)) { null }, 14, 36)
        try {
            main {
                field.set(updates, repository)
                repository.networkChanged(UpdateNetwork("fictional-wifi", source))
                repository.check()
            }
            waitFor { repository.state.value.manifest != null }
            scenario.onActivity { activity ->
                activity.startForegroundService(Intent(activity, UpdateDownloadService::class.java)
                    .putExtra(UpdateDownloadService.DOWNLOAD_PERMIT, repository.requestDownload()))
            }
            waitFor { repository.state.value.phase == io.github.tbeimu.gongxiaozhu.update.UpdatePhase.DOWNLOADING }
            start()
            result.complete(LoginResult("虚构完成"))
            waitFor { !serviceRunning() }
            assertEquals(io.github.tbeimu.gongxiaozhu.update.UpdatePhase.DOWNLOADING, repository.state.value.phase)
        } finally {
            main { repository.cancel() }
            waitFor { repository.state.value.phase == io.github.tbeimu.gongxiaozhu.update.UpdatePhase.IDLE }
            context.stopService(Intent(context, UpdateDownloadService::class.java))
            main { field.set(updates, original); scope.cancel() }
            store.clear()
        }
    }

    private fun start() {
        waitFor {
            var ready = false
            scenario.onActivity { activity -> ready = !androidx.lifecycle.ViewModelProvider(activity)
                .get(io.github.tbeimu.gongxiaozhu.ui.AppViewModel::class.java).state.value.loading &&
                (runtime.connections.value as? WifiObservation.Current)?.connection != null }
            ready
        }
        scenario.onActivity { runtime.controller.login() }
        waitFor { calls.get() == 1 && serviceRunning() }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == CampusLoginService::class.java.name }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue("等待认证状态超时", condition())
    }
}
