package io.github.tbeimu.gongxiaozhu

import android.net.DnsResolver
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tbeimu.gongxiaozhu.update.BoundUpdateDns
import java.net.InetAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundUpdateDnsTest {
    private val answer = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    @Test fun cancellationSignalsPlatformAndIgnoresLateAnswer() = runBlocking {
        lateinit var signal: CancellationSignal
        lateinit var callback: DnsResolver.Callback<List<InetAddress>>
        var returned = false
        val resolver = BoundUpdateDns { host, cancel, result ->
            assertEquals("update.example.invalid", host); signal = cancel; callback = result
        }
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.lookup("update.example.invalid"); returned = true
        }
        request.cancelAndJoin()
        assertTrue(signal.isCanceled)
        callback.onAnswer(answer, 0)
        assertFalse(returned)
    }

    @Test fun timeoutCancelsPlatformQueryWithoutWaitingForAnAnswer() = runBlocking {
        lateinit var signal: CancellationSignal
        val resolver = BoundUpdateDns { _, cancel, _ -> signal = cancel }
        assertNull(withTimeoutOrNull(100) { resolver.lookup("update.example.invalid") })
        assertTrue(signal.isCanceled)
    }

    @Test fun cancellingOneQueryDoesNotCancelTheNext() = runBlocking {
        val signals = mutableListOf<CancellationSignal>()
        val callbacks = mutableListOf<DnsResolver.Callback<List<InetAddress>>>()
        val resolver = BoundUpdateDns { _, cancel, result -> signals += cancel; callbacks += result }
        val first = async(start = CoroutineStart.UNDISPATCHED) { resolver.lookup("first.example.invalid") }
        first.cancelAndJoin()
        val second = async(start = CoroutineStart.UNDISPATCHED) { resolver.lookup("second.example.invalid") }
        callbacks[0].onAnswer(answer, 0)
        assertFalse(second.isCompleted)
        callbacks[1].onAnswer(answer, 0)
        assertEquals(answer, second.await())
        assertTrue(signals[0].isCanceled); assertFalse(signals[1].isCanceled)
    }
}
