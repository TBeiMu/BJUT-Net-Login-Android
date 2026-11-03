package io.github.tbeimu.gongxiaozhu.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn

class WifiConnection(val key: String, val transport: PortalTransport) {
    override fun toString() = "WifiConnection(network=<bound>)"
}

/** Observes physical WiFi, including unvalidated captive networks; never requests/connects WiFi. */
class WifiMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val connections = callbackFlow {
        // Snapshot once, then use ordered callback data on the same main thread.
        val networks = linkedMapOf<Network, LinkProperties?>()
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                networks[network] = manager.getLinkProperties(network)
            }
        }
        fun publish() {
            val selected = manager.activeNetwork?.takeIf { networks.containsKey(it) }
                ?: networks.keys.minByOrNull { it.networkHandle }
            val link = selected?.let { networks[it] }
            if (selected == null || link == null) {
                trySend(null)
            } else {
                val fingerprint = "${selected.networkHandle}:${link.linkAddresses}:${link.routes}"
                val client = portalClientBuilder().socketFactory(selected.socketFactory)
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = selected.getAllByName(hostname).toList()
                    }).build()
                trySend(WifiConnection(fingerprint, HttpPortalTransport(client)))
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networks.containsKey(network)) networks[network] = null
            }
            override fun onLost(network: Network) { networks.remove(network); publish() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    networks.remove(network)
                    publish()
                }
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                networks[network] = linkProperties
                publish()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        manager.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.flowOn(Dispatchers.Main.immediate).distinctUntilChangedBy { it?.key }
}
