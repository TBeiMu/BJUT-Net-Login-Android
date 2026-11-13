package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.NameSaveException
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Optional name completion cannot block account saving, authentication or timetable import. */
class AccountNameController(private val scope: CoroutineScope, private val accounts: AccountRepository,
    private val storageFailure: () -> Unit,
    private val networkAuthenticated: (String) -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private var foreground = false
    private var wifi: WifiConnection? = null
    private var identity: ScheduleIdentity? = null
    private var scheduleReady = false
    private var blocked = false
    private var editing: String? = null
    private val changingAccounts = mutableSetOf<String>()
    private val failedWrites = mutableSetOf<String>()
    private val mutableWriteFailure = MutableStateFlow(false)
    val writeFailure = mutableWriteFailure.asStateFlow()
    private var job: Job? = null
    private var activeAccount: String? = null
    private var activeNetwork: String? = null
    @Volatile private var generation = 0L

    init { scope.launch { accounts.state.collect { changed() } } }

    fun enterForeground() { foreground = true; changed() }
    fun leaveForeground() { foreground = false; changed() }
    fun wifiObserved(value: WifiObservation) {
        wifi = (value as? WifiObservation.Current)?.connection
        changed()
    }
    fun scheduleIdentity(value: ScheduleIdentity?, ready: Boolean = true) {
        // A fresh verified/restored identity permits another local save; ordinary progress does not.
        if (value != null && value !== identity) failedWrites.removeAll(
            accounts.state.value?.accounts?.filter { it.username == value.studentId }?.map { it.id }.orEmpty().toSet())
        identity = value; scheduleReady = ready; changed()
    }
    fun storageAvailable(value: Boolean) {
        blocked = !value
        if (blocked) mutableWriteFailure.value = false
        changed()
    }
    fun editingAccount(id: String?) {
        editing?.let { changingAccounts.remove(it); if (it != id) failedWrites.remove(it) }
        editing = id
        if (id != null) accountWillChange(id)
        changed()
    }
    fun accountWillChange(id: String?) {
        if (id == null) changingAccounts += accounts.state.value?.accounts?.map { it.id }.orEmpty()
        else changingAccounts += id
        if (job != null && (id == null || activeAccount == id)) { generation++; job?.cancel() }
    }
    fun authenticationChanged() = changed()
    fun accountChangeFinished() {
        failedWrites.removeAll(changingAccounts.filter { it != editing }.toSet())
        changingAccounts.retainAll(listOfNotNull(editing).toSet())
        changed()
    }
    fun consumeWriteFailure(): Boolean = mutableWriteFailure.value.also { mutableWriteFailure.value = false }

    private fun changed() {
        val data = accounts.state.value
        if (data != null) {
            changingAccounts.retainAll(data.accounts.map { it.id }.toSet())
            failedWrites.retainAll(data.accounts.filter { it.canFillName }.map { it.id }.toSet())
            if (failedWrites.isEmpty()) mutableWriteFailure.value = false
        }
        val current = data?.accounts?.find { it.id == activeAccount }
        if (job != null && (blocked || current == null || !current.canFillName || editing == activeAccount ||
                (activeNetwork != null && (!foreground || wifi?.key != activeNetwork)))) {
            generation++; job?.cancel()
        }
        if (job != null || blocked || data == null) return
        val known = identity?.takeIf { it.name.trim().let { name ->
            name.isNotEmpty() && name != it.studentId && name.length <= 80 && name.none(Char::isISOControl)
        } }
        val local = data.accounts.firstOrNull { it.canFillName && it.id !in changingAccounts &&
            it.id !in failedWrites && it.username == known?.studentId }
        if (local != null && known != null) {
            launch(local.id, null) { isCurrent -> withContext(io) {
                accounts.fillNameFromSchedule(known.studentId, known.name, local.id, isCurrent)
            } }
            return
        }
        if (!foreground || !scheduleReady) return
        val selected = wifi ?: return
        if (!selected.internetValidated && !networkAuthenticated(selected.key)) return
        val lookup = selected.nameLookup ?: return
        val next = data.accounts.firstOrNull { it.nameLookupPending && it.canFillName &&
            it.id !in changingAccounts && it.id !in failedWrites } ?: return
        launch(next.id, selected.key) { isCurrent ->
            val account = withContext(io) { accounts.beginNameLookup(next.id, isCurrent) } ?: return@launch
            currentCoroutineContext().ensureActive()
            val result = withTimeoutOrNull(30_000) { lookup.lookup(account) } ?: return@launch
            currentCoroutineContext().ensureActive()
            withContext(io) { accounts.completeNameLookup(account, result.studentId, result.name, isCurrent) }
        }
    }

    private fun launch(id: String, network: String?, action: suspend (() -> Boolean) -> Unit) {
        activeAccount = id; activeNetwork = network
        val token = ++generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            val ownedJob = currentCoroutineContext()[Job]!!
            val isCurrent = { generation == token && ownedJob.isActive }
            try { action(isCurrent) }
            catch (error: CancellationException) { throw error }
            catch (_: NameSaveException) {
                if (isCurrent()) { failedWrites += id; mutableWriteFailure.value = true }
            }
            catch (_: VaultUnavailableException) {
                if (isCurrent()) { blocked = true; mutableWriteFailure.value = false; storageFailure() }
            }
            catch (_: Exception) { /* Leave the saved account intact; timetable import can fill its name later. */ }
        }.also { owned -> owned.invokeOnCompletion {
            scope.launch {
                if (job === owned) { job = null; activeAccount = null; activeNetwork = null; changed() }
            }
        } }
        job?.start()
    }
}
