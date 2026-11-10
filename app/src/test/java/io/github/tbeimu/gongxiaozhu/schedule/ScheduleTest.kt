package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.*
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
    private class Session : ScheduleSession {
        var closed = false; var requests = mutableListOf<Int>(); var gate: CompletableDeferred<Unit>? = null
        override suspend fun open(): ScheduleGate = ScheduleGate.Login(ScheduleLoginPage("demo-token", "https://cas.bjut.edu.cn/login", "fake page"))
        override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate = ScheduleGate.Ready(demoIdentity, demoTerm)
        override suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek {
            requests += number
            if (number == 2) gate?.await()
            return demoWeek(number)
        }
        override suspend fun resource(token: String, url: String): ScheduleResource? = null
        override fun close() { closed = true }
    }
    private fun wifi(session: Session, key: String = "demo-wifi") = WifiObservation.Current(WifiConnection(key, PortalTransport { "" }, scheduleSession = { session }))

    @Test fun noAutoImportAndUserLoginPrecedesAllWeekRequests() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val session = Session(); controller.wifiChanged(wifi(session)); controller.enterForeground(); runCurrent()
        assertNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
        controller.start(); runCurrent(); assertNotNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
        controller.submit("demo-token", mapOf("username" to "fake", "password" to "fake")); runCurrent()
        assertEquals(listOf(1, 2), session.requests); assertTrue(session.closed)
        assertNotNull(controller.state.value.data.complete); assertNull(controller.state.value.page)
    }

    @Test fun backgroundOrNetworkChangeClearsLoginPageAndRejectsStaleSubmission() = runTest {
        val controller = ScheduleController(backgroundScope, repository(), StandardTestDispatcher(testScheduler)); runCurrent()
        val session = Session(); controller.wifiChanged(wifi(session)); controller.enterForeground()
        controller.start(); runCurrent(); controller.leaveForeground(); runCurrent()
        controller.submit("demo-token", emptyMap()); runCurrent()
        assertTrue(session.closed); assertNull(controller.state.value.page); assertTrue(session.requests.isEmpty())
    }

    @Test fun pausingMidImportRetainsParsedWeeksAndFreshSessionOnlyFetchesMissingWeeks() = runTest {
        val repo = repository()
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        val first = Session().apply { gate = CompletableDeferred() }
        controller.wifiChanged(wifi(first)); controller.enterForeground(); controller.start(); runCurrent()
        controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(1, controller.state.value.data.pending!!.weeks.size)
        controller.leaveForeground(); runCurrent()
        assertTrue(first.closed); assertNull(controller.state.value.page); assertNull(controller.state.value.data.complete)
        val next = Session()
        controller.wifiChanged(wifi(next)); controller.enterForeground(); runCurrent()
        assertTrue(next.requests.isEmpty())
        controller.start(); runCurrent(); controller.submit("demo-token", emptyMap()); runCurrent()
        assertEquals(listOf(2), next.requests)
        assertEquals(2, controller.state.value.data.complete!!.weeks.size)
    }

    @Test fun reloadIsIndependentOfCampusWifiAndDoesNotLoseCachedCourses() = runTest {
        val repo = repository()
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2)); repo.finish(100)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.wifiChanged(WifiObservation.Suspended)
        runCurrent()
        assertNotNull(controller.state.value.data.complete)
        assertFalse(controller.state.value.loading)
    }
}
