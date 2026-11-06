package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDnsTest {
    @get:Rule val temp = TemporaryFolder()
    private val manifest = UpdateManifest(1, "0.3.1", 13, "io.github.tbeimu.gongxiaozhu", 31, 4, "1".repeat(64), "虚构更新")
    private val verifier = ApkVerifier(12, manifest.packageName, 31, "2".repeat(64)) { null }

    private class PendingDns : UpdateDns {
        lateinit var callback: CancellableContinuation<List<InetAddress>>
        var cancelled = false
        override suspend fun lookup(hostname: String): List<InetAddress> = suspendCancellableCoroutine {
            callback = it
            it.invokeOnCancellation { cancelled = true }
        }
        fun lateAnswer() = callback.resume(listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
    }

    @Test fun cancellingPendingDnsUnlocksUiAndRejectsLateAnswer() = runTest {
        val dns = PendingDns()
        var requests = 0
        val client = updateClientBuilder().addInterceptor { requests++; error("DNS 未完成时不应发出 HTTP 请求") }.build()
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier, 12, 31)
        repo.networkChanged(UpdateNetwork("wifi", HttpUpdateSource(client, dns)))
        repo.check(); runCurrent()
        assertEquals(UpdatePhase.CHECKING, repo.state.value.phase)
        repo.cancel(); runCurrent()
        assertTrue(dns.cancelled)
        assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        dns.lateAnswer(); runCurrent()
        assertEquals(0, requests)
        assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        repo.networkChanged(UpdateNetwork("wifi", object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = Unit
        }))
        repo.check(); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, repo.state.value.phase)
    }

    @Test fun dnsHasItsOwnTimeoutAndCancelsThePendingQuery() = runTest {
        val dns = PendingDns()
        var requests = 0
        val client = updateClientBuilder().addInterceptor { requests++; error("不应连接") }.build()
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier, 12, 31)
        repo.networkChanged(UpdateNetwork("wifi", HttpUpdateSource(client, dns)))
        repo.check(); runCurrent()
        testScheduler.advanceTimeBy(15_000); runCurrent()
        assertTrue(dns.cancelled)
        assertEquals(UpdatePhase.ERROR, repo.state.value.phase)
        assertTrue(repo.state.value.message.contains("DNS 查询超时"))
        assertFalse(repo.state.value.busy)
        dns.lateAnswer(); runCurrent()
        assertEquals(0, requests)
    }

    @Test fun switchingWifiDuringDownloadDnsPausesBeforeAnyFileWrite() = runTest {
        val dns = PendingDns()
        var requests = 0
        val client = updateClientBuilder().addInterceptor { requests++; error("不应连接") }.build()
        val http = HttpUpdateSource(client, dns)
        val store = UpdateStore(temp.newFolder())
        val repo = UpdateRepository(this, store, verifier, 12, 31)
        repo.networkChanged(UpdateNetwork("wifi", object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) =
                http.download(manifest, file, etag, saveEtag, progress)
        }))
        repo.check(); runCurrent(); repo.download(); runCurrent()
        repo.networkChanged(null); runCurrent()
        assertTrue(dns.cancelled)
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
        dns.lateAnswer(); runCurrent()
        assertEquals(0, requests)
        assertFalse(store.partial.exists())
        assertEquals(UpdatePhase.PAUSED, UpdateRepository(this, store, verifier, 12, 31).state.value.phase)
    }
}
