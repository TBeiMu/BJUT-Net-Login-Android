package io.github.tbeimu.gongxiaozhu

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleController
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleImportRuntime

/** Fixtures replace encrypted files between tests; the application-scoped runtime must reread them. */
internal fun reloadScheduleFixture(context: Context) {
    check(context.packageName.endsWith(".debug"))
    lateinit var controller: ScheduleController
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        controller = ScheduleImportRuntime.get(context).controller
        controller.reload()
    }
    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
    while (controller.state.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
    check(!controller.state.value.busy && !controller.state.value.blocked)
}
