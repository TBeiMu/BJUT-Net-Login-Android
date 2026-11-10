package io.github.tbeimu.gongxiaozhu

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.LoginNotificationPermission
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Revoke notification permission before instrumentation; use user-fixed to exercise permanent denial. */
@RunWith(AndroidJUnit4::class)
class LoginNotificationPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun settingsRecoveryWorksAfterDenialAndRefreshesUiWhenReturning() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeFalse(NotificationManagerCompat.from(context).areNotificationsEnabled())
        compose.setContent { GongXiaoZhuTheme { LoginNotificationPermission() } }
        // Permanent denial must not remove the alternate route to settings.
        compose.onNodeWithText("允许通知以查看后台认证进度（可选）").assertExists()
        compose.onNodeWithText("打开系统通知设置").performClick()
        try {
            waitFor {
                shell("dumpsys activity activities").lineSequence().any {
                    (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("com.android.settings")
                }
            }
            shell("pm clear-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        } finally { shell("input keyevent 4") }
        waitFor { compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        compose.onNodeWithText("打开系统通知设置").assertDoesNotExist()
        compose.onNodeWithText("允许通知以查看后台认证进度（可选）").assertDoesNotExist()
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!predicate() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue("等待通知设置状态超时", predicate())
    }
}
