package io.github.tbeimu.gongxiaozhu.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import io.github.tbeimu.gongxiaozhu.billing.BillingSession
import io.github.tbeimu.gongxiaozhu.billing.HttpBillingSession

class WifiConnection(val key: String, val transport: PortalTransport,
    val nameLookup: AccountNameLookup? = null, val internetValidated: Boolean = false,
    val billingSession: (() -> BillingSession)? = null) {
    override fun toString() = "WifiConnection(network=<bound>)"
}

// VPNs can advertise their underlying WiFi transport. Only bind physical WiFi.
internal fun isPhysicalWifi(capabilities: NetworkCapabilities?): Boolean =
    capabilities?.let { isPhysicalWifi(it::hasTransport) } ?: false

internal fun isPhysicalWifi(hasTransport: (Int) -> Boolean): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

/** Observes physical WiFi, including unvalidated captive networks; never requests/connects WiFi. */
class WifiMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val connections = callbackFlow {
        // Snapshot once, then use ordered callback data on the same main thread.
        val networks = linkedMapOf<Network, LinkProperties?>()
        val validated = mutableMapOf<Network, Boolean>()
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            if (isPhysicalWifi(manager.getNetworkCapabilities(network))) {
                networks[network] = manager.getLinkProperties(network)
                validated[network] = manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        }
        fun publish() {
            val selected = manager.activeNetwork?.takeIf { networks.containsKey(it) }
                ?: networks.keys.minByOrNull { it.networkHandle }
            val link = selected?.let { networks[it] }
            if (selected == null || link == null) {
                trySend(null)
            } else {
                val fingerprint = "${selected.networkHandle}:${link.linkAddresses}:${link.routes}:${link.dnsServers}"
                val client = portalClientBuilder().socketFactory(selected.socketFactory)
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = selected.getAllByName(hostname).toList()
                    }).build()
                val ipv4Client = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(4, TimeUnit.SECONDS).build()
                trySend(WifiConnection(fingerprint, HttpPortalTransport(client, ipv4Client, BoundNetworkDns.onNetwork(selected)),
                    nameLookup = HttpAccountNameLookup(client, BoundNetworkDns.onNetwork(selected)),
                    internetValidated = validated[selected] == true,
                    billingSession = { HttpBillingSession(client) }))
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networks.containsKey(network)) networks[network] = null
            }
            override fun onLost(network: Network) { networks.remove(network); validated.remove(network); publish() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!isPhysicalWifi(networkCapabilities)) {
                    networks.remove(network)
                    validated.remove(network)
                } else validated[network] = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                publish()
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                networks[network] = linkProperties
                publish()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        manager.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.flowOn(Dispatchers.Main.immediate).distinctUntilChangedBy { it?.let { wifi -> wifi.key to wifi.internetValidated } }
}
