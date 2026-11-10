package io.github.tbeimu.gongxiaozhu.network

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine

/** Each lookup has its own cancellation signal and uses the selected physical network. */
internal class BoundNetworkDns(
    private val query: (String, CancellationSignal, DnsResolver.Callback<List<InetAddress>>) -> Unit,
) : NetworkDns {
    override suspend fun lookup(hostname: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val completed = AtomicBoolean(false)
        fun finish(result: Result<List<InetAddress>>) {
            if (completed.compareAndSet(false, true)) continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation {
            completed.set(true)
            signal.cancel()
        }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            query(hostname, signal, object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    finish(if (rcode == 0 && answer.isNotEmpty()) Result.success(answer.toList())
                        else Result.failure(UnknownHostException("无法解析更新服务器")))
                }
                override fun onError(error: DnsResolver.DnsException) {
                    finish(Result.failure(UnknownHostException("无法解析更新服务器")))
                }
            })
        } catch (_: Exception) {
            signal.cancel()
            finish(Result.failure(UnknownHostException("无法启动网络解析")))
        }
    }

    companion object {
        fun onNetwork(network: Network): BoundNetworkDns = BoundNetworkDns { host, signal, callback ->
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY,
                Executor { it.run() }, signal, callback)
        }
    }
}

fun interface NetworkDns {
    suspend fun lookup(hostname: String): List<InetAddress>
}
