package io.github.tbeimu.gongxiaozhu

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.network.WifiMonitor
import io.github.tbeimu.gongxiaozhu.network.isPhysicalWifi
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
        listOf("10.21.251.3", "10.21.221.98").forEach {
            assertTrue(policy.isCleartextTrafficPermitted(it))
        }
        listOf("example.org", "connectivitycheck.platform.hicloud.com", "10.21.251.4", "172.30.201.2", "172.30.201.10", "127.0.0.1").forEach {
            assertFalse(policy.isCleartextTrafficPermitted(it))
        }
        assertFalse(policy.isCleartextTrafficPermitted)
    }

    @Test fun wifiSnapshotMatchesAndroidNetworkWithoutLocationPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = manager.allNetworks.filter {
            isPhysicalWifi(manager.getNetworkCapabilities(it))
        }
        val snapshot = withTimeout(5000) { WifiMonitor(context).connections.first() }
        if (wifi.isEmpty()) assertNull(snapshot) else {
            assertNotNull(snapshot)
            assertTrue(wifi.any { snapshot!!.key.startsWith("${it.networkHandle}:") })
        }
    }

    @Test fun wifiBackedVpnIsExcludedWithoutRequiringValidatedInternet() {
        // Public NetworkRequest builders provide Android transport flags for this policy test;
        // NetworkCapabilities mutators are hidden platform APIs.
        val physical = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        assertFalse(physical.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertTrue(isPhysicalWifi(physical::hasTransport))
        val vpn = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build()
        assertFalse(isPhysicalWifi(vpn::hasTransport))
        val cellular = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build()
        assertFalse(isPhysicalWifi(cellular::hasTransport))
        assertFalse(isPhysicalWifi(null as NetworkCapabilities?))
    }
}
