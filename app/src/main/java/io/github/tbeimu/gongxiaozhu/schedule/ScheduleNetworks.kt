package io.github.tbeimu.gongxiaozhu.schedule

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.github.tbeimu.gongxiaozhu.network.BoundNetworkDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient

enum class ScheduleNetworkKind(val title: String) { WIFI("WiFi"), CELLULAR("移动数据") }

class ScheduleConnection(val key: String, val kind: ScheduleNetworkKind, val session: () -> ScheduleSession) {
    val identity: String get() = "$key:${kind.name}"
    override fun toString() = "ScheduleConnection(kind=$kind, network=<bound>)"
}

internal data class ScheduleRoute<T>(val id: T, val kind: ScheduleNetworkKind, val validated: Boolean)

internal fun <T> selectScheduleRoute(routes: List<ScheduleRoute<T>>, active: T?): T? = routes.minWithOrNull(
    compareBy<ScheduleRoute<T>> { if (it.validated) 0 else 1 }
        .thenBy { if (it.kind == ScheduleNetworkKind.WIFI) 0 else 1 }
        .thenBy { if (it.id == active) 0 else 1 }
)?.id

internal fun scheduleNetworkKind(hasTransport: (Int) -> Boolean, hasCapability: (Int) -> Boolean): ScheduleNetworkKind? {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ScheduleNetworkKind.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ScheduleNetworkKind.WIFI
        else -> null
    }
}

private data class ScheduleNetworkSnapshot(val capabilities: NetworkCapabilities? = null, val link: LinkProperties? = null)

/** Observes available physical networks without connecting WiFi or sharing another module's session. */
internal fun scheduleNetworks(context: Context) = callbackFlow {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    val networks = linkedMapOf<Network, ScheduleNetworkSnapshot>()
    var active = manager.activeNetwork
    @Suppress("DEPRECATION")
    manager.allNetworks.forEach { network ->
        networks[network] = ScheduleNetworkSnapshot(manager.getNetworkCapabilities(network), manager.getLinkProperties(network))
    }
    fun publish() {
        val routes = networks.entries.sortedBy { it.key.networkHandle }.mapNotNull { (network, snapshot) ->
            val caps = snapshot.capabilities ?: return@mapNotNull null
            val kind = scheduleNetworkKind(caps::hasTransport, caps::hasCapability) ?: return@mapNotNull null
            if (snapshot.link == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ||
                (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND) && network != active)) return@mapNotNull null
            ScheduleRoute(network, kind, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        val selected = selectScheduleRoute(routes, active)
        val link = networks[selected]?.link
        if (selected == null || link == null) { trySend(null); return }
        val kind = routes.first { it.id == selected }.kind
        val key = "${selected.networkHandle}:${link.linkAddresses}:${link.routes}:${link.dnsServers}"
        val client = OkHttpClient.Builder().socketFactory(selected.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    throw java.net.UnknownHostException("课表请求必须先完成绑定网络的异步解析")
            }).build()
        trySend(ScheduleConnection(key, kind) { HttpScheduleSession(client, BoundNetworkDns.onNetwork(selected)) })
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!networks.containsKey(network)) networks[network] = ScheduleNetworkSnapshot() }
        override fun onLost(network: Network) { networks.remove(network); publish() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networks[network] = (networks[network] ?: ScheduleNetworkSnapshot()).copy(capabilities = capabilities)
            publish()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            networks[network] = (networks[network] ?: ScheduleNetworkSnapshot()).copy(link = properties)
            publish()
        }
    }
    val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { active = network; publish() }
        override fun onLost(network: Network) { if (active == network) active = null; publish() }
    }
    val handler = Handler(Looper.getMainLooper())
    var listening = false
    var listeningDefault = false
    try {
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build(), callback, handler)
        listening = true
        manager.registerDefaultNetworkCallback(defaultCallback, handler)
        listeningDefault = true
        publish()
        awaitClose { }
    } finally {
        if (listeningDefault) manager.unregisterNetworkCallback(defaultCallback)
        if (listening) manager.unregisterNetworkCallback(callback)
    }
}.flowOn(Dispatchers.Main.immediate).distinctUntilChangedBy { it?.identity }
