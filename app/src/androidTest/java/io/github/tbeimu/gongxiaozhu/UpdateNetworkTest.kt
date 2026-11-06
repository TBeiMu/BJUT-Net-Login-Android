package io.github.tbeimu.gongxiaozhu

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tbeimu.gongxiaozhu.update.UpdateNetworkKind
import io.github.tbeimu.gongxiaozhu.update.updateNetworkKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateNetworkTest {
    @Test fun physicalMobileAndHybridNetworksAlwaysRequireMobileConsent() {
        val transports = mutableSetOf(NetworkCapabilities.TRANSPORT_WIFI)
        var internet = true
        fun kind() = updateNetworkKind(transports::contains, { internet && it == NetworkCapabilities.NET_CAPABILITY_INTERNET })
        assertEquals(UpdateNetworkKind.WIFI, kind())
        transports += NetworkCapabilities.TRANSPORT_CELLULAR
        assertEquals(UpdateNetworkKind.CELLULAR, kind())
        transports -= NetworkCapabilities.TRANSPORT_WIFI
        assertEquals(UpdateNetworkKind.CELLULAR, kind())
        transports += NetworkCapabilities.TRANSPORT_VPN
        assertNull(kind())
        transports -= NetworkCapabilities.TRANSPORT_VPN
        internet = false
        assertNull(kind())
    }
}
