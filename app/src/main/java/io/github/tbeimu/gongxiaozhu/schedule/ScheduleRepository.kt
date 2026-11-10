package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import kotlinx.serialization.json.Json

/** Only normalized course data is serialized. No login page, credential or cookie can enter this type. */
class ScheduleRepository(private val store: VaultStore, private val cipher: VaultCipher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var current: ScheduleData? = null
    private var exists = false

    @Synchronized fun load(): ScheduleData = storage {
        current = null
        val bytes = store.read()
        exists = bytes != null
        val data = if (bytes == null) ScheduleData() else cipher.decrypt(bytes).let { plaintext ->
            try { json.decodeFromString<ScheduleData>(plaintext.decodeToString(throwOnInvalidSequence = true)) }
            finally { plaintext.fill(0) }
        }
        validate(data); current = data; data
    }

    @Synchronized fun begin(identity: ScheduleIdentity, term: ScheduleTerm): ScheduleData {
        validateIdentity(identity); validateTerm(term)
        val data = requireLoaded()
        if (data.identity != null && data.identity.studentId != identity.studentId) {
            if (data.pending != null) persist(data.copy(pending = null))
            throw ScheduleException(ScheduleProblem.ACCOUNT_MISMATCH)
        }
        val pending = data.pending?.takeIf { it.identity.studentId == identity.studentId && it.term.key == term.key && it.term.weekCount == term.weekCount }
            ?.copy(identity = identity, term = term) ?: PendingSchedule(identity, term)
        return persist(data.copy(identity = identity, pending = pending))
    }

    @Synchronized fun append(week: ScheduleWeek): ScheduleData {
        val data = requireLoaded(); val pending = data.pending ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        validateWeek(week)
        if (week.number > pending.term.weekCount) throw ScheduleException(ScheduleProblem.RESPONSE)
        val weeks = (pending.weeks.filterNot { it.number == week.number } + week).sortedBy { it.number }
        return persist(data.copy(pending = pending.copy(weeks = weeks)))
    }

    @Synchronized fun finish(now: Long): ScheduleData {
        val data = requireLoaded(); val p = data.pending ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        requireComplete(p.weeks, p.term)
        return persist(data.copy(complete = SemesterSchedule(p.identity, p.term, p.weeks.sortedBy { it.number }, now), pending = null))
    }

    /** Discard only the unfinished attempt; keep the bound identity, complete semester and key. */
    @Synchronized fun discardPending(): ScheduleData {
        val data = requireLoaded()
        return if (data.pending == null) data else persist(data.copy(pending = null))
    }

    @Synchronized fun clear(): ScheduleData = storage {
        store.clear(); cipher.clearKey()
        exists = false
        ScheduleData().also { current = it }
    }
    @Synchronized fun snapshot(): ScheduleData = requireLoaded()
    private fun requireLoaded() = current ?: throw ScheduleException(ScheduleProblem.STORAGE)
    private fun persist(value: ScheduleData): ScheduleData = storage {
        val stored = store.read()
        require(exists == (stored != null))
        if (stored != null) {
            val previous = cipher.decrypt(stored)
            try { require(json.decodeFromString<ScheduleData>(previous.decodeToString(throwOnInvalidSequence = true)) == requireLoaded()) }
            finally { previous.fill(0) }
        }
        validate(value)
        val plain = json.encodeToString(value).encodeToByteArray()
        try {
            require(plain.size <= 4 * 1024 * 1024)
            store.write(cipher.encrypt(plain, requireExistingKey = exists))
        } finally { plain.fill(0) }
        exists = true; current = value; value
    }
    private fun validate(data: ScheduleData) {
        require(data.schema == 1)
        data.identity?.let(::validateIdentity)
        data.complete?.let {
            require(it.identity.studentId == data.identity?.studentId)
            validateTerm(it.term); requireComplete(it.weeks, it.term)
        }
        data.pending?.let {
            require(it.identity.studentId == data.identity?.studentId)
            validateTerm(it.term); require(it.weeks.map { w -> w.number }.distinct().size == it.weeks.size)
            it.weeks.forEach { w -> validateWeek(w); require(w.number <= it.term.weekCount) }
        }
    }
    private fun requireComplete(weeks: List<ScheduleWeek>, term: ScheduleTerm) {
        if (weeks.map { it.number }.sorted() != (1..term.weekCount).toList()) throw ScheduleException(ScheduleProblem.RESPONSE)
        weeks.forEach(::validateWeek)
        if (!weeks.sortedBy { it.number }.zipWithNext().all { (a, b) -> java.time.LocalDate.parse(a.dates.first()).plusWeeks(1).toString() == b.dates.first() })
            throw ScheduleException(ScheduleProblem.RESPONSE)
    }
    private fun validateIdentity(value: ScheduleIdentity) {
        if (value.studentId.isBlank() || value.studentId.length > 128 || value.name.length > 100) throw ScheduleException(ScheduleProblem.IDENTITY)
    }
    private inline fun <T> storage(block: () -> T): T = try { block() }
    catch (_: Exception) { throw ScheduleException(ScheduleProblem.STORAGE) }
}
