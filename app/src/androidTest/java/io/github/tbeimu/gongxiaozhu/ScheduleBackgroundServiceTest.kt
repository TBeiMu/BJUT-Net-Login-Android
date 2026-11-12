package io.github.tbeimu.gongxiaozhu

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.schedule.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Real Android services and Activity lifecycle, with fictional school responses only. */
class ScheduleBackgroundServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var controller: ScheduleController

    private class Session : ScheduleSession {
        val opens = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        @Volatile var closed = false
        private val term = ScheduleTerm("2026-2027", "1", 2, 1)
        override suspend fun open(): ScheduleGate {
            opens.incrementAndGet()
            return ScheduleGate.Ready(ScheduleIdentity("fictional-background", "测试同学"), term)
        }
        override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate = error("Unused")
        override suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek {
            if (number == 2) finish.await()
            return ScheduleWeek(number, (0L..6L).map {
                LocalDate.of(2026, 11, 2).plusWeeks(number - 1L).plusDays(it).toString()
            }, emptyList())
        }
        override suspend fun resource(token: String, url: String): ScheduleResource? = null
        override fun close() { closed = true }
    }

    @Before fun prepare() {
        check(context.packageName.endsWith(".debug"))
        createAccountRepository(context).apply { reset(); load(); settings(AppSettings(autoLogin = false)) }
        instrumentation.runOnMainSync { controller = ScheduleImportRuntime.get(context).controller }
        waitFor { !controller.state.value.loading && !controller.state.value.busy }
        instrumentation.runOnMainSync { controller.clear() }
        waitFor { !controller.state.value.busy }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor { controller.state.value.networkKind != null }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) {
            instrumentation.runOnMainSync { controller.pause() }
            waitFor { !controller.state.value.busy }
        }
        if (::scenario.isInitialized) scenario.close()
        if (::controller.isInitialized) {
            instrumentation.runOnMainSync { controller.clear() }
            waitFor { !controller.state.value.busy }
        }
        createAccountRepository(context).reset()
    }

    private fun start(session: Session) {
        instrumentation.runOnMainSync {
            controller.networkChanged(ScheduleConnection("fictional-service-network", ScheduleNetworkKind.WIFI) { session })
            controller.start()
        }
        waitFor { controller.state.value.imported == 1 && controller.state.value.busy }
        waitFor { serviceRunning() }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == ScheduleImportService::class.java.name && it.foreground }

    private fun waitFor(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue("等待课表任务状态超时", condition())
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }

    @Test fun backgroundImportCompletesAndReleasesServiceWithoutAnotherLogin() {
        val session = Session(); start(session)
        val task = controller.state.value.taskId
        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(serviceRunning()); assertFalse(session.closed)
        assertEquals(task, controller.state.value.taskId)
        session.finish.complete(Unit)
        waitFor { !controller.state.value.busy && controller.state.value.data.complete != null }
        waitFor { !serviceRunning() }
        assertTrue(session.closed)
        assertEquals(2, createScheduleRepository(context).load().complete!!.weeks.size)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, session.opens.get())
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        assertNull(controller.state.value.taskId)
    }

    @Test fun recreationAndRepeatedBackgroundDoNotRestartImport() {
        val session = Session(); start(session)
        val task = controller.state.value.taskId
        scenario.recreate()
        repeat(2) {
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        assertEquals(task, controller.state.value.taskId); assertFalse(session.closed)
        assertEquals(1, session.opens.get()); assertTrue(serviceRunning())
        session.finish.complete(Unit)
        waitFor { !controller.state.value.busy }
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
    }

    @Test fun backgroundNetworkLossPausesAndRetainsProgress() {
        val session = Session(); start(session)
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { controller.networkChanged(null) }
        waitFor { !controller.state.value.busy && !serviceRunning() }
        assertTrue(session.closed)
        assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        session.finish.complete(Unit)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, session.opens.get()); assertNull(controller.state.value.data.complete)
    }

    @Test fun notificationPausesAndOldNotificationCannotStopNewImport() {
        val first = Session(); start(first)
        val manager = context.getSystemService(NotificationManager::class.java)
        waitFor { manager.activeNotifications.any { it.id == 501 } }
        val notice = manager.activeNotifications.first { it.id == 501 }.notification
        assertFalse(notice.extras.toString().contains("fictional-background"))
        val pause = notice.actions.single().actionIntent
        scenario.moveToState(Lifecycle.State.CREATED)
        pause.send()
        waitFor { !controller.state.value.busy && !serviceRunning() }
        assertTrue(first.closed); assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        scenario.moveToState(Lifecycle.State.RESUMED)
        val next = Session(); start(next)
        val task = controller.state.value.taskId
        pause.send()
        // Deliver a second old stop callback as well as the actual PendingIntent.
        instrumentation.runOnMainSync { controller.serviceStopped("fictional-expired-task") }
        assertEquals(task, controller.state.value.taskId); assertTrue(controller.state.value.busy)
        next.finish.complete(Unit)
        waitFor { !controller.state.value.busy }
        assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
        waitFor { manager.activeNotifications.none { it.id == 501 } }
    }

    @Test fun removingRecentTaskStopsServiceAndRetainsImportedWeeks() {
        val session = Session(); start(session)
        scenario.moveToState(Lifecycle.State.CREATED)
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        waitFor { !controller.state.value.busy && !serviceRunning() }
        assertTrue(session.closed); assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
        assertEquals(1, createScheduleRepository(context).load().pending!!.weeks.size)
        assertNull(controller.state.value.data.complete)
    }

    @Test fun screenOffImportCompletesBeforeUnlocking() {
        val session = Session(); start(session)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        scenario.moveToState(Lifecycle.State.CREATED)
        try {
            shell("input keyevent 223")
            waitFor { !power.isInteractive }
            assertTrue(serviceRunning()); assertFalse(session.closed)
            session.finish.complete(Unit)
            waitFor { !controller.state.value.busy && !serviceRunning() }
            assertEquals(ScheduleNotice.SUCCESS, controller.state.value.notice)
            assertNotNull(createScheduleRepository(context).load().complete)
            assertFalse(power.isInteractive)
        } finally {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
        }
    }

    @Test fun systemDataSyncTimeoutPausesAndReleasesService() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 35)
        val key = "data_sync_fgs_timeout_duration"
        val previous = shell("device_config get activity_manager $key")
        check(previous == "null" || previous.matches(Regex("[0-9]+")))
        try {
            shell("device_config put activity_manager $key 2000")
            val session = Session(); start(session)
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { !controller.state.value.busy && !serviceRunning() }
            assertTrue(session.closed); assertEquals(ScheduleNotice.PAUSED, controller.state.value.notice)
            assertTrue(controller.state.value.message.contains("运行时限"))
            assertEquals(1, createScheduleRepository(context).load().pending!!.weeks.size)
        } finally {
            if (previous == "null") shell("device_config delete activity_manager $key")
            else shell("device_config put activity_manager $key $previous")
        }
    }
}
