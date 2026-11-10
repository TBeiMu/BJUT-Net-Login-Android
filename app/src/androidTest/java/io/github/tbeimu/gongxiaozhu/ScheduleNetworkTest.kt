package io.github.tbeimu.gongxiaozhu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.network.WifiMonitor
import io.github.tbeimu.gongxiaozhu.schedule.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ScheduleNetworkTest {
    @Test fun supportsPhysicalMobileAndWifiButRejectsVpnAndOfflineTransports() {
        val transports = mutableSetOf(NetworkCapabilities.TRANSPORT_CELLULAR)
        var internet = true
        fun kind() = scheduleNetworkKind(transports::contains) { internet && it == NetworkCapabilities.NET_CAPABILITY_INTERNET }
        assertEquals(ScheduleNetworkKind.CELLULAR, kind())
        transports += NetworkCapabilities.TRANSPORT_WIFI
        assertEquals(ScheduleNetworkKind.CELLULAR, kind())
        transports -= NetworkCapabilities.TRANSPORT_CELLULAR
        assertEquals(ScheduleNetworkKind.WIFI, kind())
        transports += NetworkCapabilities.TRANSPORT_VPN
        assertNull(kind())
        transports -= NetworkCapabilities.TRANSPORT_VPN
        internet = false
        assertNull(kind())
    }

    @Test fun actualMobileRouteWorksWhileCampusMonitorStillRequiresWifi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val selected = withTimeout(5000) { scheduleNetworks(context).first() }
        assertNotNull("隔离模拟器应提供 WiFi 或移动数据", selected)
        @Suppress("DEPRECATION")
        val real = manager.allNetworks.single { selected!!.key.startsWith("${it.networkHandle}:") }
        val capabilities = manager.getNetworkCapabilities(real)!!
        assertEquals(selected!!.kind, scheduleNetworkKind(capabilities::hasTransport, capabilities::hasCapability))
        @Suppress("DEPRECATION")
        val hasWifi = manager.allNetworks.any { network -> manager.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true }
        assertEquals(hasWifi, withTimeout(5000) { WifiMonitor(context).connections.first() } != null)
    }
}
