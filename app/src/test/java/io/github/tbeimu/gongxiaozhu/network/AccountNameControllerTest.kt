package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountNameControllerTest {
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var failWrites = false
        var corruptOnFailure = false
        var writes = 0
        override fun read() = bytes
        override fun write(bytes: ByteArray) {
            writes++
            if (failWrites) {
                if (corruptOnFailure) this.bytes = byteArrayOf(1, 2, 3)
                throw IOException("Fictional write failure")
            }
            this.bytes = bytes
        }
        override fun clear() { bytes = null }
    }
    private fun repository(store: Store = Store()): AccountRepository = AccountRepository(store, object : VaultCipher {
        override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean) = plaintext.copyOf()
        override fun decrypt(envelope: ByteArray) = envelope.copyOf()
        override fun clearKey() {}
    }).apply { load() }
    private fun add(repo: AccountRepository, user: String = "fictional-student", label: String = "") =
        repo.save(AccountDraft(label = label, username = user, password = "fictional-password")).accounts.last()
    private fun route(key: String = "fictional-wifi", validated: Boolean = true, lookup: AccountNameLookup) =
        WifiObservation.Current(WifiConnection(key, PortalTransport { "" }, lookup, validated))

    @Test fun savesNameOnceAndKeepsExistingNetworkAccountSelection() = runTest {
        val repo = repository(); val first = add(repo); val second = add(repo, "other-student", "自定义备注")
        repo.setDefault(second.id)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { account -> calls++; assertEquals(first.id, account.id); ScheduleIdentity(account.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.first().label)
        assertEquals(second.id, repo.state.value!!.defaultAccountId)
        repeat(3) { controller.leaveForeground(); controller.enterForeground(); runCurrent() }
        assertEquals(1, calls)
    }

    @Test fun waitsForCurrentWifiAndScheduleCacheBeforeLookingUp() = runTest {
        val repo = repository(); add(repo)
        var authenticated = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.enterForeground()
        controller.wifiObserved(route(validated = false) { calls++; ScheduleIdentity(it.username, "测试同学") })
        runCurrent(); assertEquals(0, calls)
        controller.scheduleIdentity(null); runCurrent(); assertEquals(0, calls)
        authenticated = true; controller.authenticationChanged(); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun localImportedIdentityAvoidsCredentialLookupEvenInBackground() = runTest {
        val repo = repository(); val account = add(repo)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.wifiObserved(route { calls++; error("No school request expected") })
        controller.scheduleIdentity(ScheduleIdentity(account.username, "本地同学")); runCurrent()
        assertEquals("本地同学", repo.state.value!!.accounts.single().label)
        controller.enterForeground(); runCurrent(); assertEquals(0, calls)
    }

    @Test fun failedLookupDoesNotRetryAndLaterImportCanFillName() = runTest {
        val repo = repository(); val account = add(repo)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; error("Fictional server rejection") })
        controller.enterForeground(); runCurrent()
        controller.leaveForeground(); controller.enterForeground(); runCurrent()
        assertEquals(1, calls); assertEquals(account.username, repo.state.value!!.accounts.single().label)
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun backgroundCancellationWaitsForCleanupAndRejectsLateName() = runTest {
        val repo = repository(); val account = add(repo)
        val cleanup = CompletableDeferred<Unit>(); var cancelled = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            calls++
            try { awaitCancellation() } finally { cancelled = true; withContext(NonCancellable) { cleanup.await() } }
        })
        controller.enterForeground(); runCurrent(); controller.leaveForeground(); runCurrent()
        assertTrue(cancelled)
        controller.enterForeground(); runCurrent(); assertEquals(1, calls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(account.username, repo.state.value!!.accounts.single().label)
        assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun networkSwitchAndEditedAccountDiscardNonCooperativeResults() = runTest {
        val repo = repository(); val account = add(repo)
        val delayed = CompletableDeferred<Unit>()
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            withContext(NonCancellable) { delayed.await() }
            ScheduleIdentity(it.username, "旧结果")
        })
        controller.enterForeground(); runCurrent()
        controller.wifiObserved(WifiObservation.Suspended)
        controller.editingAccount(account.id)
        repo.save(repo.edit(account.id).copy(label = "我的备注", password = "new-fictional-password"))
        delayed.complete(Unit); runCurrent(); controller.editingAccount(null); runCurrent()
        assertEquals("我的备注", repo.state.value!!.accounts.single().label)
    }

    @Test fun deletedAccountIsNotRecreatedByAnOldLookup() = runTest {
        val repo = repository(); val account = add(repo)
        val delayed = CompletableDeferred<Unit>()
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            withContext(NonCancellable) { delayed.await() }; ScheduleIdentity(it.username, "旧结果")
        })
        controller.enterForeground(); runCurrent()
        controller.accountWillChange(account.id); repo.delete(account.id)
        delayed.complete(Unit); runCurrent()
        assertTrue(repo.state.value!!.accounts.isEmpty())
    }

    @Test fun editingPausesCachedNameCompletionUntilEditorCloses() = runTest {
        val repo = repository(); val account = add(repo)
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.editingAccount(account.id)
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertEquals(account.username, repo.state.value!!.accounts.single().label)
        controller.editingAccount(null); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun timeoutNeverRepeatsCredentialAttempt() = runTest {
        val repo = repository(); add(repo)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; delay(60_000); ScheduleIdentity(it.username, "迟到姓名") })
        controller.enterForeground(); runCurrent(); advanceTimeBy(30_001); runCurrent()
        controller.authenticationChanged(); runCurrent()
        assertEquals(1, calls)
        assertEquals("fictional-student", repo.state.value!!.accounts.single().label)
    }

    @Test fun healthyVaultNameWriteFailureIsNonBlockingAndReportedOnce() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        val identity = ScheduleIdentity(account.username, "测试同学")
        val oldBytes = store.bytes!!.copyOf()
        val writes = store.writes
        store.failWrites = true
        controller.scheduleIdentity(identity); runCurrent()
        assertFalse(blocked)
        assertArrayEquals(oldBytes, store.bytes)
        assertEquals("fictional-password", repo.credentialsForLogin(account.id)!!.password)
        assertTrue(controller.consumeWriteFailure()); assertFalse(controller.consumeWriteFailure())
        repeat(3) { controller.scheduleIdentity(identity); controller.storageAvailable(true); controller.authenticationChanged(); runCurrent() }
        assertEquals(writes + 1, store.writes)
        assertFalse(controller.writeFailure.value)
        store.failWrites = false
        controller.scheduleIdentity(identity.copy()); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun failedAttemptMarkerWriteNeverSendsCredentialsOrLoops() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var calls = 0; var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; ScheduleIdentity(it.username, "测试同学") })
        val writes = store.writes
        store.failWrites = true
        controller.enterForeground(); runCurrent()
        repeat(3) { controller.leaveForeground(); controller.enterForeground(); runCurrent() }
        assertEquals(0, calls); assertFalse(blocked); assertEquals(writes + 1, store.writes)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        store.failWrites = false
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
        assertEquals(0, calls)
    }

    @Test fun laterStorageFailureDiscardsTheEarlierHealthyVaultNotice() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        store.failWrites = true
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertTrue(controller.writeFailure.value)
        controller.storageAvailable(false)
        assertFalse(controller.consumeWriteFailure())
    }

    @Test fun completedLookupWhoseNameCannotBeSavedIsNeverResubmitted() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var calls = 0; var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; store.failWrites = true; ScheduleIdentity(it.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        assertFalse(blocked); assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        controller.leaveForeground(); controller.enterForeground(); runCurrent()
        assertEquals(1, calls)
        store.failWrites = false
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
        assertEquals(1, calls)
    }

    @Test fun uncertainWriteWithDamagedVaultStillReportsGlobalStorageFailure() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        store.failWrites = true; store.corruptOnFailure = true
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertTrue(blocked); assertFalse(controller.writeFailure.value)
        assertThrows(VaultUnavailableException::class.java) { repo.credentialsForLogin(account.id) }
    }

    private fun waitForRepositoryLock(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (thread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("Name IO must be waiting for the repository monitor", Thread.State.BLOCKED, thread.get()?.state)
    }

    @Test fun cancelledQueuedLocalWriteCannotChangeAnOpenEditorDraft() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameWriteTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, io = io)
        try {
            lateinit var editor: AccountDraft
            synchronized(repo) {
                controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学"))
                waitForRepositoryLock(thread)
                controller.editingAccount(account.id)
                editor = repo.edit(account.id)
            }
            runBlocking { withContext(io) {} } // Wait behind the cancelled IO on the same executor.
            assertEquals(editor.label, repo.state.value!!.accounts.single().label)
            repo.save(editor.copy(password = "edited-fictional-password"))
            controller.editingAccount(null); controller.accountChangeFinished()
            runBlocking { withContext(io) {} }
            assertEquals("测试同学", repo.state.value!!.accounts.single().label)
            assertEquals("edited-fictional-password", repo.edit(account.id).password)
        } finally { scope.cancel(); io.close() }
    }

    @Test fun cancelledQueuedAttemptMarkerIsNotWrittenAndSendsNoCredentials() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameAttemptTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, io = io)
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { calls++; ScheduleIdentity(it.username, "测试同学") })
            synchronized(repo) {
                controller.enterForeground()
                waitForRepositoryLock(thread)
                controller.leaveForeground()
            }
            runBlocking { withContext(io) {} }
            assertEquals(0, calls)
            assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            assertEquals(account.username, repo.state.value!!.accounts.single().label)
        } finally { scope.cancel(); io.close() }
    }

    @Test fun cancelledQueuedNetworkResultCannotWriteAfterWifiChanges() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameResultTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<ScheduleIdentity>()
        val requested = CompletableDeferred<Unit>()
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, io = io)
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { requested.complete(Unit); result.await() })
            controller.enterForeground()
            runBlocking { withTimeout(3_000) { requested.await() } }
            synchronized(repo) {
                result.complete(ScheduleIdentity(account.username, "测试同学"))
                waitForRepositoryLock(thread)
                controller.wifiObserved(WifiObservation.Suspended)
            }
            runBlocking { withContext(io) {} }
            assertEquals(account.username, repo.state.value!!.accounts.single().label)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        } finally { scope.cancel(); io.close() }
    }
}
