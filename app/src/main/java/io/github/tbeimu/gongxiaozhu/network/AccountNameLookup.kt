package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.schedule.HttpScheduleSession
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

fun interface AccountNameLookup {
    suspend fun lookup(account: AccountDraft): ScheduleIdentity
}

/** Independent cookies, one verified CAS submission, and the same physical WiFi for DNS/HTTP. */
internal class HttpAccountNameLookup(private val client: OkHttpClient, private val dns: NetworkDns) : AccountNameLookup {
    override suspend fun lookup(account: AccountDraft): ScheduleIdentity {
        val session = HttpScheduleSession(client, dns)
        return try { session.accountIdentity(account) }
        finally { withContext(NonCancellable) { session.close(); session.awaitClosed() } }
    }
}
