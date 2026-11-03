package io.github.tbeimu.gongxiaozhu

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.network.WifiMonitor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiMonitorTest {
    @Test fun cleartextIsRestrictedToKnownCampusHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        listOf("10.21.251.3", "10.21.221.98", "172.30.201.2").forEach {
            assertTrue(policy.isCleartextTrafficPermitted(it))
        }
        listOf("example.org", "10.21.251.4", "172.30.201.10", "127.0.0.1").forEach {
            assertFalse(policy.isCleartextTrafficPermitted(it))
        }
        assertFalse(policy.isCleartextTrafficPermitted)
    }

    @Test fun wifiSnapshotMatchesAndroidNetworkWithoutLocationPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val snapshot = withTimeout(5000) { WifiMonitor(context).connections.first() }
        if (wifi.isEmpty()) assertNull(snapshot) else {
            assertNotNull(snapshot)
            assertTrue(wifi.any { snapshot!!.key.startsWith("${it.networkHandle}:") })
        }
    }
}
