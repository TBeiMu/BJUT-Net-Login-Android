package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.*
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

internal val demoIdentity = ScheduleIdentity("graduate-demo", "虚构同学")
internal val demoTerm = ScheduleTerm("2026-2027", "1", 2, 2)
internal fun demoWeek(number: Int, empty: Boolean = false): ScheduleWeek {
    val monday = LocalDate.of(2026, 11, 2).plusWeeks(number - 1L)
    val dates = (0L..6L).map { monday.plusDays(it).toString() }
    return ScheduleWeek(number, dates, if (empty) emptyList() else listOf(ScheduleCourse("demo-course", "虚构课程", dates[0], listOf(1, 2), "模拟教室", "测试教师")))
}
internal class ScheduleMemoryStore : VaultStore {
    var bytes: ByteArray? = null
    var failWrite = false
    override fun read() = bytes?.copyOf()
    override fun write(bytes: ByteArray) { if (failWrite) error("fake write failure"); this.bytes = bytes.copyOf() }
    override fun clear() { bytes = null }
}
internal class ScheduleMemoryKeys : VaultKeySource {
    var value: SecretKey? = null
    override fun encryptionKey(): SecretKey = value ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { value = it }
    override fun decryptionKey(): SecretKey = value ?: error("fake missing key")
    override fun clear() { value = null }
}
internal fun repository(store: ScheduleMemoryStore = ScheduleMemoryStore(), keys: ScheduleMemoryKeys = ScheduleMemoryKeys()) =
    ScheduleRepository(store, AesGcmVaultCipher(keys)).also { it.load() }

class ScheduleRepositoryTest {
    @Test fun cancellingPendingImportPreservesCompleteScheduleIdentityAndKey() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        repo.begin(demoIdentity, demoTerm);repo.append(demoWeek(1));repo.append(demoWeek(2))
        val complete = repo.finish(100).complete
        repo.begin(demoIdentity, demoTerm);repo.append(demoWeek(1, true))
        val key = keys.value
        val result = repo.discardPending()
        assertNull(result.pending);assertEquals(complete, result.complete);assertEquals(demoIdentity, result.identity)
        assertSame(key,keys.value)
        assertEquals(result,ScheduleRepository(store,AesGcmVaultCipher(keys)).load())
        repo.begin(demoIdentity,demoTerm)
        assertTrue(repo.snapshot().pending!!.weeks.isEmpty())
    }
    @Test fun cancellationWriteFailureKeepsProgressAndDoesNotSilentlyReplaceData() {
        val store=ScheduleMemoryStore(); val repo=repository(store)
        repo.begin(demoIdentity,demoTerm);repo.append(demoWeek(1))
        val bytes=store.bytes!!.copyOf();store.failWrite=true
        assertThrows(ScheduleException::class.java) { repo.discardPending() }
        assertArrayEquals(bytes,store.bytes);assertEquals(1,repo.snapshot().pending!!.weeks.size)
        store.failWrite=false
        val cleared=repo.discardPending()
        assertNull(cleared.complete);assertNull(cleared.pending);assertEquals(demoIdentity,cleared.identity)
    }
    @Test fun incompleteRefreshRetainsCompleteSemesterAndRestartResumes() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
        val first = repo.finish(100)
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1, true))
        val restored = ScheduleRepository(store, AesGcmVaultCipher(keys)).load()
        assertEquals(first.complete, restored.complete); assertEquals(1, restored.pending!!.weeks.size)
        assertFalse(store.bytes!!.decodeToString().contains(demoIdentity.studentId))
        assertThrows(ScheduleException::class.java) { repo.finish(200) }
        repo.append(demoWeek(2)); val second = repo.finish(200)
        assertNull(second.pending); assertTrue(second.complete!!.weeks[0].courses.isEmpty())
    }

    @Test fun corruptionAndLostKeyAreNotSilentlyOverwritten() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        repo.begin(demoIdentity, demoTerm)
        val good = store.bytes!!.copyOf()
        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(ScheduleException::class.java) { repo.append(demoWeek(1)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), store.bytes)
        assertThrows(ScheduleException::class.java) { repo.load() }
        assertThrows(ScheduleException::class.java) { repo.begin(demoIdentity, demoTerm) }
        assertArrayEquals(byteArrayOf(1, 2, 3), store.bytes)
        store.bytes = good; keys.value = null
        assertThrows(ScheduleException::class.java) { repo.load() }
        assertArrayEquals(good, store.bytes)
    }

    @Test fun mismatchInvalidatesProgressWithoutReplacingBoundSchedule() {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1))
        val error = assertThrows(ScheduleException::class.java) { repo.begin(ScheduleIdentity("other-demo"), demoTerm) }
        assertEquals(ScheduleProblem.ACCOUNT_MISMATCH, error.problem)
        val data = repo.load(); assertEquals(demoIdentity, data.identity); assertNull(data.pending)
    }

    @Test fun changedTermDiscardsOldProgressAndFailedWriteKeepsState() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1))
        val next = repo.begin(demoIdentity, demoTerm.copy(term = "2"))
        assertTrue(next.pending!!.weeks.isEmpty())
        val before = store.bytes!!.copyOf(); store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.append(demoWeek(1)) }
        assertArrayEquals(before, store.bytes)
    }

    @Test fun conflictingClassesAreGroupedButNonconsecutiveLessonsKeepSeparateBlocks() {
        val week = demoWeek(1)
        val courses = week.courses + week.courses[0].copy(id = "other", lessons = listOf(2, 3)) + week.courses[0].copy(id = "split", lessons = listOf(5, 7))
        val blocks = courseBlocks(week.copy(courses = courses))
        assertEquals(listOf(1, 5, 7), blocks.map { it.first })
        assertEquals(3, blocks[0].last); assertEquals(2, blocks[0].courses.size)
        assertEquals(2, currentScheduleWeek(SemesterSchedule(demoIdentity, demoTerm, listOf(week, demoWeek(2)), 0), LocalDate.of(2026, 11, 10)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleControllerTest {
    private class Session(private val term: ScheduleTerm = demoTerm, private val timeoutAt: String? = null) : ScheduleSession {
        var closed = false; var requests = mutableListOf<Int>(); var gate: CompletableDeferred<Unit>? = null
        var submits = 0
        private suspend fun deadline(step: String) {
            if (timeoutAt == step) withTimeout(20_000) { awaitCancellation() }
        }
        override suspend fun open(): ScheduleGate {
            deadline("open")
            return ScheduleGate.Login(ScheduleLoginPage("demo-token", "https://cas.bjut.edu.cn/login", "fake page"))
        }
        override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate {
            submits++; deadline("submit")
            return ScheduleGate.Ready(demoIdentity, term)
        }
        override suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek {
            requests += number
            if (number == 2) gate?.await()
            if (number == 2) deadline("week")
            return demoWeek(number)
        }
        override suspend fun resource(token: String, url: String): ScheduleResource? = null
        override fun close() { closed = true }
    }
    private fun connection(session: Session, key: String = "demo-wifi", kind: ScheduleNetworkKind = ScheduleNetworkKind.WIFI) = ScheduleConnection(key, kind) { session }

    @Test fun noAutoImportAndUserLoginPrecedesAllWeekRequests() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val session = Session(); controller.networkChanged(connection(session)); controller.enterForeground(); runCurrent()
        assertNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
        controller.start(); runCurrent(); assertNotNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
        controller.submit("demo-token", mapOf("username" to "fake", "password" to "fake")); runCurrent()
        assertEquals(listOf(1, 2), session.requests); assertTrue(session.closed)
        assertNotNull(controller.state.value.data.complete); assertNull(controller.state.value.page)
    }

    @Test fun backgroundOrNetworkChangeClearsLoginPageAndRejectsStaleSubmission() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val session = Session(); controller.networkChanged(connection(session)); controller.enterForeground()
        controller.start(); runCurrent(); controller.leaveForeground(); runCurrent()
        controller.submit("demo-token", emptyMap()); runCurrent()
        assertTrue(session.closed); assertNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
    }

    @Test fun pausingMidImportRetainsParsedWeeksAndFreshSessionOnlyFetchesMissingWeeks() = runTest {
        val repo = repository()
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        val first = Session().apply { gate = CompletableDeferred() }
        controller.networkChanged(connection(first)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        controller.pause(); runCurrent()
        assertTrue(first.closed); assertNull(controller.state.value.page); assertNull(controller.state.value.data.complete)
        val next = Session()
        controller.networkChanged(connection(next)); controller.enterForeground(); runCurrent()
        assertTrue(next.requests.isEmpty())
        controller.start(); runCurrent(); controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(listOf(2), next.requests)
        assertEquals(2, controller.state.value.data.complete!!.weeks.size)
    }

    @Test fun reloadIsIndependentOfCampusWifiAndDoesNotLoseCachedCourses() = runTest {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2)); repo.finish(100)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.networkObservationPaused()
        runCurrent()
        assertNotNull(controller.state.value.data.complete)
        assertFalse(controller.state.value.loading)
    }

    @Test fun oneLoginAutomaticallyImportsAllTwentyWeeks() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val session = Session(demoTerm.copy(weekCount = 20))
        controller.networkChanged(connection(session)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("demo-token", mapOf("username" to "fake", "password" to "fake")); runCurrent()
        assertEquals(1, session.submits)
        assertEquals((1..20).toList(), session.requests)
        assertEquals(20, controller.state.value.data.complete!!.weeks.size)
        assertEquals(20, controller.state.value.imported)
        assertNull(controller.state.value.data.pending)
        assertNull(controller.state.value.page)
        assertTrue(session.closed)
    }

    @Test fun operationDeadlinesCloseSessionRetainDataAndNeverRetryCredentials() = runTest {
        for (step in listOf("open", "submit", "week")) {
            val repo = repository()
            repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
            val previous = repo.finish(100).complete
            val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
            val session = Session(timeoutAt = step)
            controller.networkChanged(connection(session)); controller.enterForeground(); controller.start(); runCurrent()
            if (step != "open") { controller.submit("demo-token", emptyMap()); runCurrent() }
            advanceTimeBy(20_001); runCurrent()
            val state = controller.state.value
            assertFalse(state.busy); assertFalse(state.blocked)
            assertTrue(session.closed); assertNull(state.page)
            assertEquals(ScheduleProblem.CONNECTION.hint, state.message)
            assertEquals(previous, state.data.complete)
            assertEquals(if (step == "week") 1 else 0, state.data.pending?.weeks?.size ?: 0)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(if (step == "open") 0 else 1, session.submits)
            controller.submit("demo-token", emptyMap()); runCurrent()
            assertEquals(if (step == "open") 0 else 1, session.submits)
        }
    }

    @Test fun mobileImportAndSwitchingNetworksRequireExplicitFreshLogin() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val mobile = Session().apply { gate = CompletableDeferred() }
        controller.enterForeground()
        controller.networkChanged(connection(mobile, "mobile-1", ScheduleNetworkKind.CELLULAR))
        controller.start(); runCurrent(); controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(ScheduleNetworkKind.CELLULAR, controller.state.value.networkKind)
        assertEquals(listOf(1, 2), mobile.requests)
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)

        val wifi = Session()
        controller.networkChanged(connection(wifi, "wifi-2")); runCurrent()
        assertTrue(mobile.closed)
        assertNull(controller.state.value.page)
        assertTrue(controller.state.value.message.contains("网络已变化"))
        assertTrue(wifi.requests.isEmpty()); assertEquals(0, wifi.submits)
        controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(0, wifi.submits)
        controller.start(); runCurrent(); controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(listOf(2), wifi.requests)
        assertEquals(1, mobile.submits); assertEquals(1, wifi.submits)
        assertNotNull(controller.state.value.data.complete)
    }

    @Test fun noNetworkAndPausedObservationCannotStartAnImport() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        controller.enterForeground(); controller.networkChanged(null); controller.start(); runCurrent()
        assertNull(controller.state.value.page)
        assertTrue(controller.state.value.message.contains("移动数据"))
        val mobile = Session()
        controller.networkChanged(connection(mobile, "mobile-1", ScheduleNetworkKind.CELLULAR))
        controller.leaveForeground(); controller.networkObservationPaused(); controller.enterForeground(); controller.start(); runCurrent()
        assertNull(controller.state.value.page)
        assertTrue(mobile.requests.isEmpty()); assertEquals(0, mobile.submits)
    }

    @Test fun cancelPausedImportDiscardsOnlyProgressAndNextImportStartsFresh() = runTest {
        val repo=repository()
        repo.begin(demoIdentity,demoTerm);repo.append(demoWeek(1));repo.append(demoWeek(2))
        val complete=repo.finish(10).complete
        val controller=ScheduleController(backgroundScope,repo,StandardTestDispatcher(testScheduler));runCurrent()
        val first=Session().apply { gate=CompletableDeferred() }
        controller.networkChanged(connection(first));controller.enterForeground();controller.start();runCurrent()
        controller.submit("demo-token",emptyMap());runCurrent()
        controller.pause();runCurrent()
        assertTrue(controller.state.value.canCancelImport)
        controller.cancelImport();controller.cancelImport();runCurrent()
        assertTrue(first.closed);assertNull(controller.state.value.data.pending)
        assertEquals(complete,controller.state.value.data.complete)
        assertEquals(ScheduleNotice.IDLE,controller.state.value.notice)
        assertEquals(0,controller.state.value.imported);assertEquals(0,controller.state.value.total)
        val next=Session()
        controller.networkChanged(connection(next));controller.enterForeground();controller.start();runCurrent()
        controller.submit("demo-token",emptyMap());runCurrent()
        assertEquals(listOf(1,2),next.requests)
    }

    @Test fun cancellationWaitsForOldRequestCleanupAndRejectsItsLateWeek() = runTest {
        val release=CompletableDeferred<Unit>()
        var cancelled=false
        var closed=false
        val session=object:ScheduleSession {
            override suspend fun open() = ScheduleGate.Ready(demoIdentity,demoTerm)
            override suspend fun submit(token:String, values:Map<String,String>) = error("Unused")
            override suspend fun week(term:ScheduleTerm,number:Int):ScheduleWeek {
                if(number==2)try { awaitCancellation() } catch (_:CancellationException) {
                    cancelled=true
                    withContext(NonCancellable) { release.await() }
                }
                return demoWeek(number)
            }
            override suspend fun resource(token:String,url:String):ScheduleResource?=null
            override fun close(){closed=true}
        }
        val repo=repository()
        val controller=ScheduleController(backgroundScope,repo,StandardTestDispatcher(testScheduler));runCurrent()
        controller.networkChanged(ScheduleConnection("fake",ScheduleNetworkKind.WIFI){session});controller.enterForeground();controller.start();runCurrent()
        controller.cancelImport();runCurrent()
        assertTrue(cancelled);assertTrue(closed);assertTrue(controller.state.value.busy)
        controller.start();runCurrent();assertEquals(1,repo.snapshot().pending!!.weeks.size)
        release.complete(Unit);runCurrent()
        assertFalse(controller.state.value.busy);assertNull(repo.snapshot().pending);assertNull(repo.snapshot().complete)
        assertEquals(ScheduleNotice.IDLE,controller.state.value.notice)
    }

    @Test fun cancellationAfterLoginPagePauseClearsRetryMessageWithoutUnbinding() = runTest {
        val controller=ScheduleController(backgroundScope,repository(),StandardTestDispatcher(testScheduler));runCurrent()
        val session=Session()
        controller.networkChanged(connection(session));controller.enterForeground();controller.start();runCurrent()
        controller.pause();runCurrent()
        assertTrue(controller.state.value.canCancelImport);assertNull(controller.state.value.data.pending)
        controller.cancelImport();runCurrent()
        assertEquals(ScheduleNotice.IDLE,controller.state.value.notice);assertFalse(controller.state.value.canCancelImport)
        assertNull(controller.state.value.page);assertTrue(session.closed)
    }

    @Test fun networkSelectionPrefersWorkingWifiAndCanUseMobileWithoutWifi() {
        val wifi = ScheduleRoute("wifi", ScheduleNetworkKind.WIFI, true)
        val mobile = ScheduleRoute("mobile", ScheduleNetworkKind.CELLULAR, true)
        assertEquals("wifi", selectScheduleRoute(listOf(mobile, wifi), "mobile"))
        assertEquals("mobile", selectScheduleRoute(listOf(mobile, wifi.copy(validated = false)), "wifi"))
        assertEquals("mobile", selectScheduleRoute(listOf(mobile), null))
        assertEquals("wifi", selectScheduleRoute(listOf(wifi.copy(validated = false)), "wifi"))
        assertNull(selectScheduleRoute(emptyList<ScheduleRoute<String>>(), null))
    }
}
