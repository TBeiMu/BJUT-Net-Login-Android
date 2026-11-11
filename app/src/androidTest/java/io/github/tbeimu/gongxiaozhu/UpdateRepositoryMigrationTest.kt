package io.github.tbeimu.gongxiaozhu

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.update.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UpdateRepositoryMigrationTest {
    /** Explicit read-only check of public metadata on an isolated emulator; no account or APK request. */
    @Test fun publicMetadataLoadsThroughBoundNetworkFromRenamedRepository() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gxzLiveUpdate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val manifest = runBlocking { withTimeout(60_000) {
            val selected = updateNetworks(context).filterNotNull().first { it.validated }
            selected.source.check()
        } }
        assertEquals("io.github.tbeimu.gongxiaozhu", manifest.packageName)
        assertTrue(manifest.versionCode >= 24)
        assertTrue(manifest.apkUrl.startsWith("https://github.com/TBeiMu/BJUT_gxz/releases/download/"))
        assertEquals(64, manifest.apkSha256.length)
        println("Renamed repository public metadata verified: v${manifest.versionName}, code ${manifest.versionCode}")
    }
}
