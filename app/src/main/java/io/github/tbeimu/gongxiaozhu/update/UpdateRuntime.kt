package io.github.tbeimu.gongxiaozhu.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.tbeimu.gongxiaozhu.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class UpdateRuntime private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = UpdateRepository(scope, UpdateStore(File(context.filesDir, "updates")),
        androidApkVerifier(context), BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, !BuildConfig.DEBUG)

    init { scope.launch { updateNetworks(context).collect(repository::networkChanged) } }

    companion object {
        @Volatile private var instance: UpdateRuntime? = null
        fun get(context: Context): UpdateRuntime = instance ?: synchronized(this) {
            instance ?: UpdateRuntime(context.applicationContext).also { instance = it }
        }
    }
}

internal fun androidApkVerifier(context: Context) = ApkVerifier(BuildConfig.VERSION_CODE.toLong(),
    "io.github.tbeimu.gongxiaozhu", Build.VERSION.SDK_INT, BuildConfig.RELEASE_CERT_SHA256) { file ->
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    info?.let { ApkIdentity(it.packageName, it.longVersionCode, it.versionName,
        it.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
        it.signingInfo?.apkContentsSigners?.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).hex()
        }?.toSet().orEmpty()) }
}

internal fun updateNetworkKind(capabilities: NetworkCapabilities?): UpdateNetworkKind? =
    capabilities?.let { updateNetworkKind(it::hasTransport, it::hasCapability) }

internal fun updateNetworkKind(hasTransport: (Int) -> Boolean, hasCapability: (Int) -> Boolean): UpdateNetworkKind? {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
    // A hybrid transport must never evade mobile-data consent by also advertising WiFi.
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpdateNetworkKind.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpdateNetworkKind.WIFI
        else -> null
    }
}

private data class NetworkSnapshot(val capabilities: NetworkCapabilities? = null, val link: LinkProperties? = null)

/** Independent from campus authentication. Every source remains pinned to its selected network. */
internal fun updateNetworks(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networks = linkedMapOf<Network, NetworkSnapshot>()
    var active = manager.activeNetwork
    @Suppress("DEPRECATION")
    manager.allNetworks.forEach {
        networks[it] = NetworkSnapshot(manager.getNetworkCapabilities(it), manager.getLinkProperties(it))
    }
    fun publish() {
        val routes = networks.entries.sortedBy { it.key.networkHandle }.mapNotNull { (network, snapshot) ->
            val caps = snapshot.capabilities ?: return@mapNotNull null
            val kind = updateNetworkKind(caps) ?: return@mapNotNull null
            if (snapshot.link == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ||
                (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND) && network != active)) return@mapNotNull null
            UpdateRoute(network, kind, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        val selected = selectUpdateRoute(routes, active)
        val link = networks[selected]?.link
        if (selected == null || link == null) { trySend(null); return }
        val kind = routes.first { it.id == selected }.kind
        val key = "${selected.networkHandle}:${link.linkAddresses}:${link.routes}:${link.dnsServers}"
        val client = updateClientBuilder().socketFactory(selected.socketFactory).build()
        trySend(UpdateNetwork(key, HttpUpdateSource(client, BoundUpdateDns.onNetwork(selected)), kind))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!networks.containsKey(network)) networks[network] = NetworkSnapshot() }
        override fun onLost(network: Network) { networks.remove(network); publish() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networks[network] = (networks[network] ?: NetworkSnapshot()).copy(capabilities = capabilities)
            publish()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            networks[network] = (networks[network] ?: NetworkSnapshot()).copy(link = properties)
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
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback, handler)
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
