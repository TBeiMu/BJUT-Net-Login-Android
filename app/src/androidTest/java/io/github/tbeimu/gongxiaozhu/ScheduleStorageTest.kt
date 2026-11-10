package io.github.tbeimu.gongxiaozhu

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.WallpaperStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScheduleStorageTest {
    @Test fun independentKeystoreAndUnbindingNeverModifyCampusAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val accounts = createAccountRepository(context)
        accounts.reset(); accounts.load()
        accounts.save(AccountDraft(label = "虚构账号", username = "campus-demo", password = "fake-password"))
        val before = AndroidVaultStore(context).read()
        val schedules = createScheduleRepository(context)
        try {
            schedules.clear()
            schedules.begin(ScheduleIdentity("graduate-demo", "虚构学生"), ScheduleTerm("2026-2027", "1", 1, 1))
            schedules.append(ScheduleWeek(1, (0L..6L).map { java.time.LocalDate.of(2026, 11, 2).plusDays(it).toString() }, emptyList()))
            schedules.finish(1234)
            val data = createScheduleRepository(context).load()
            assertEquals("graduate-demo", data.identity?.studentId)
            assertEquals(1, data.complete!!.weeks.size)
            assertFalse(AndroidScheduleStore(context).read()!!.decodeToString().contains("graduate-demo"))
            schedules.clear()
            assertArrayEquals(before, AndroidVaultStore(context).read())
            assertNull(AndroidScheduleStore(context).read())
        } finally { schedules.clear(); accounts.reset() }
    }

    @Test fun wallpaperImportReencodesImageAndFailedImportKeepsPreviousBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val file = File(context.cacheDir, "fictional-wallpaper.png")
        val bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val store = WallpaperStore(context)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(30, store.import(Uri.fromFile(file)).width)
            assertEquals(40, store.load()!!.height)
            file.writeText("fictional invalid image")
            assertThrows(Exception::class.java) { store.import(Uri.fromFile(file)) }
            assertEquals(30, store.load()!!.width)
            store.clear(); assertNull(store.load())
        } finally { store.clear(); file.delete(); bitmap.recycle() }
    }
}
