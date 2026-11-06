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
import io.github.tbeimu.gongxiaozhu.network.isPhysicalWifi
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

/** Independent observer: update traffic never uses a portal/billing client or default mobile route. */
internal fun updateNetworks(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networks = linkedMapOf<Network, LinkProperties?>()
    @Suppress("DEPRECATION")
    manager.allNetworks.forEach {
        if (isPhysicalWifi(manager.getNetworkCapabilities(it))) networks[it] = manager.getLinkProperties(it)
    }
    fun publish() {
        val selected = manager.activeNetwork?.takeIf(networks::containsKey)
            ?: networks.keys.minByOrNull { it.networkHandle }
        val link = networks[selected]
        if (selected == null || link == null) { trySend(null); return }
        val key = "${selected.networkHandle}:${link.linkAddresses}:${link.routes}:${link.dnsServers}"
        val client = updateClientBuilder().socketFactory(selected.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = selected.getAllByName(hostname).toList()
            }).build()
        trySend(UpdateNetwork(key, HttpUpdateSource(client)))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!networks.containsKey(network)) networks[network] = null }
        override fun onLost(network: Network) { networks.remove(network); publish() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!isPhysicalWifi(capabilities)) networks.remove(network)
            publish()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            networks[network] = properties; publish()
        }
    }
    manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback, Handler(Looper.getMainLooper()))
    publish()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.flowOn(Dispatchers.Main.immediate).distinctUntilChangedBy { it?.key }
