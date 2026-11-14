package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalCoursesTest {
    private fun draft(context: CourseEditContext, name: String = "自定义实验", source: List<Int> = listOf(context.defaultWeek),
        target: List<Int> = listOf(1, 2), day: Int = 2, lessons: List<Int> = listOf(3, 4)) =
        CourseDraft(name, "", "", listOf(day), source, target, lessons, context.firstMonday, context.weekCount)
    private fun add(repo: ScheduleRepository): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, draft(context))
    }
    private fun addSixtyWeekCourse(repo: ScheduleRepository, weekdays: List<Int> = (1..7).toList()): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, draft(context, target = (1..60).toList(), lessons = listOf(1, 2))
            .copy(weekdays = weekdays, firstMonday = "2026-11-02", weekCount = 60))
    }
    private fun imported(repo: ScheduleRepository, term: ScheduleTerm = demoTerm): ScheduleData {
        repo.begin(demoIdentity, term)
        repo.append(demoWeek(1)); repo.append(demoWeek(2))
        return repo.finish(1)
    }
    @Test fun standaloneCalendarIsEncryptedAndRestoresWithoutSchoolIdentity() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val data = add(repo)
        assertNull(data.identity); assertNull(data.complete)
        assertFalse(store.bytes!!.decodeToString().contains("自定义实验"))
        assertEquals(data, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        val display = data.display()!!
        assertFalse(display.school); assertEquals(20, display.term.weekCount)
        assertEquals(2, display.weeks.sumOf { it.courses.size })
        assertEquals(CourseSource.CUSTOM, display.weeks[0].courses[0].source)
    }
    @Test fun firstImportAdoptsWeeksAndKeepsOutOfRangeOccurrences() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        repo.edit(context, draft(context, target = listOf(1, 20)))
        val oldId = repo.snapshot().localTerms.single().courses.single().id
        val data = imported(repo)
        assertEquals(demoWeek(1).dates.first(), data.localTerms.single().firstMonday)
        assertEquals(listOf(1, 20), data.localTerms.single().courses.single().occurrences.map { it.week })
        assertEquals(oldId, data.localTerms.single().courses.single().id)
        assertEquals(1, data.display()!!.weeks.sumOf { w -> w.courses.count { it.source == CourseSource.CUSTOM } })
    }
    @Test fun newSchoolSemesterNeverMovesArchivedCustomCourses() {
        val repo = repository(); add(repo); imported(repo)
        val old = repo.snapshot().localTerms.single()
        val result = imported(repo, demoTerm.copy(term = "2"))
        assertEquals(old, result.localTerms.single())
        assertTrue(result.display()!!.weeks.none { w -> w.courses.any { it.source == CourseSource.CUSTOM } })
        assertTrue(repo.selectLocalTerm(old.id).display()!!.weeks.any { w -> w.courses.any { it.source == CourseSource.CUSTOM } })
    }
    @Test fun failedRefreshAndCancellationPreserveSchoolEditsAndCustomCourses() {
        val repo = repository(); add(repo); imported(repo)
        val context = repo.snapshot().courseEditor(repo.snapshot().complete!!.weeks[0].courses[0])
        val edited = repo.edit(context, draft(context, "调课实验", target = listOf(2), day = 5)).complete
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1))
        assertThrows(ScheduleException::class.java) { repo.finish(2) }
        assertEquals(edited, repo.snapshot().complete)
        val cancelled = repo.discardPending()
        assertEquals(edited, cancelled.complete); assertEquals(1, cancelled.localTerms.single().courses.size)
        val refreshed = imported(repo)
        assertEquals("虚构课程", refreshed.complete!!.weeks[0].courses[0].name)
        assertEquals(1, refreshed.localTerms.single().courses.size)
    }
    @Test fun schoolEditingOnlyMatchesOriginalSeriesAndSelectedWeeks() {
        val repo = repository(); imported(repo)
        val c = repo.snapshot().complete!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c)
        assertEquals(listOf(1, 2), context.availableWeeks)
        val result = repo.edit(context, draft(context, target = listOf(2), day = 3)).complete!!
        assertTrue(result.weeks[0].courses.isEmpty())
        assertEquals(2, result.weeks[1].courses.size)
        assertTrue(result.weeks[1].courses.any { it.date == LocalDate.parse(demoWeek(2).dates[0]).plusDays(2).toString() })
    }
    @Test fun deletingOneCustomOccurrenceRetainsOtherWeeksAndStableId() {
        val repo = repository(); add(repo)
        val c = repo.snapshot().display()!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c, deleteOnly = true)
        val result = repo.edit(context, draft(context))
        val retained = result.localTerms.single().courses.single()
        assertEquals(c.id, retained.id); assertEquals(listOf(2), retained.occurrences.map { it.week })
    }
    @Test fun customPartialEditSplitsScheduleWithoutChangingRemainingMetadata() {
        val repo = repository(); add(repo)
        val c = repo.snapshot().display()!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c)
        val result = repo.edit(context, draft(context, "调课", target = listOf(3), day = 5))
        assertEquals(2, result.localTerms.single().courses.size)
        assertEquals(listOf(2), result.localTerms.single().courses.find { it.id == c.id }!!.occurrences.map { it.week })
        assertEquals("调课", result.display()!!.weeks[2].courses[0].name)
    }
    @Test fun invalidInputAndStaleDraftNeverOverwriteData() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        for (bad in listOf(draft(context, name = " "), draft(context, target = emptyList()),
            draft(context, lessons = listOf(21)), draft(context).copy(firstMonday = "2026-11-03"))) {
            assertThrows(CourseValidationException::class.java) { repo.edit(context, bad) }
            assertTrue(repo.snapshot().localTerms.isEmpty())
        }
        add(repo)
        assertThrows(CourseValidationException::class.java) { repo.edit(context, draft(context)) }
    }
    @Test fun failedLocalWritePreservesEncryptedVaultAndAllowsExplicitRetry() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        add(repo); val before = repo.snapshot(); val bytes = store.bytes!!.copyOf()
        val context = before.courseEditor(); store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.edit(context, draft(context)) }
        assertEquals(before, repo.snapshot()); assertArrayEquals(bytes, store.bytes)
        store.failWrite = false; repo.edit(context, draft(context))
        assertEquals(2, repo.snapshot().localTerms.single().courses.size)
    }
    @Test fun unbindingKeepsLocalRecordsButDifferentAccountDoesNotAdoptThem() {
        val repo = repository(); add(repo); imported(repo)
        val old = repo.snapshot().localTerms.single()
        assertNull(repo.unbind().identity)
        repo.begin(ScheduleIdentity("another-demo"), demoTerm)
        repo.append(demoWeek(1)); repo.append(demoWeek(2)); val result = repo.finish(2)
        assertEquals(old, result.localTerms.single())
        assertTrue(result.display()!!.weeks.all { w -> w.courses.none { it.source == CourseSource.CUSTOM } })
    }
    @Test fun overflowCustomOccurrenceCanBeManagedAndMovedBackIntoTerm() {
        val repo = repository(); val ctx = repo.snapshot().courseEditor()
        repo.edit(ctx, draft(ctx, target = listOf(20))); val data = imported(repo)
        val term = data.localTerms.single(); val c = term.courses.single()
        val record = ScheduleCourse(c.id, c.name, LocalDate.parse(term.firstMonday).plusWeeks(19).plusDays(1).toString(),
            listOf(3, 4), source = CourseSource.CUSTOM)
        val editor = data.courseEditor(record, weekday = 2, termId = term.id)
        assertEquals(20, editor.defaultWeek); assertEquals(listOf(20), editor.availableWeeks)
        val result = repo.edit(editor, draft(editor, target = listOf(1)))
        assertEquals(1, result.display()!!.weeks[0].courses.count { it.source == CourseSource.CUSTOM })
    }
    @Test fun nonconsecutiveLessonsAndOverlapsArePreserved() {
        val repo = repository(); val ctx = repo.snapshot().courseEditor()
        val data = repo.edit(ctx, draft(ctx, lessons = listOf(1, 3), target = listOf(1)))
        assertEquals(listOf(1, 3), data.display()!!.weeks[0].courses.single().lessons)
        assertEquals(2, courseBlocks(data.display()!!.weeks[0]).size)
    }
    @Test fun oldSerializedVaultDefaultsToNoCustomCalendar() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys()
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt("""{"schema":1,"identity":null,"complete":null,"pending":null}""".encodeToByteArray())
        val data = ScheduleRepository(store, cipher).load()
        assertTrue(data.localTerms.isEmpty()); assertNull(data.display())
    }
    @Test fun unrelatedSameNameOrOtherPeriodIsNotChanged() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val w = demoWeek(1)
        val original = w.courses.single()
        repo.append(w.copy(courses = listOf(original, original.copy(id = "unrelated"), original.copy(lessons = listOf(5, 6)))))
        repo.append(demoWeek(2)); repo.finish(1)
        val editor = repo.snapshot().courseEditor(original, deleteOnly = true)
        val result = repo.edit(editor, draft(editor))
        assertEquals(2, result.complete!!.weeks[0].courses.size)
        assertTrue(result.complete!!.weeks[0].courses.any { it.id == "unrelated" })
        assertTrue(result.complete!!.weeks[0].courses.any { it.lessons == listOf(5, 6) })
    }
    @Test fun manuallyCreatedCalendarAfterSchoolImportNeverAutoAdoptsOnRefresh() {
        val repo = repository(); imported(repo)
        val context = repo.snapshot().courseEditor(newLocalTerm = true)
        val created = repo.edit(context, draft(context)).localTerms.single()
        assertFalse(created.adoptOnImport)
        val refreshed = imported(repo)
        assertEquals(created, refreshed.localTerms.single())
        assertTrue(refreshed.display()!!.weeks.all { w -> w.courses.none { it.source == CourseSource.CUSTOM } })
    }
    @Test fun multipleWeekdaysPersistEverySelectedWeekDayWithoutDuplicates() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val context = repo.snapshot().courseEditor()
        val result = repo.edit(context, draft(context, target = listOf(1, 3)).copy(weekdays = listOf(1, 3, 7)))
        val course = result.localTerms.single().courses.single()
        assertEquals(listOf(1 to 1, 1 to 3, 1 to 7, 3 to 1, 3 to 3, 3 to 7),
            course.occurrences.map { it.week to it.weekday })
        val restored = ScheduleRepository(store, AesGcmVaultCipher(keys)).load()
        assertEquals(result, restored)
        assertEquals(6, restored.display()!!.weeks.sumOf { it.courses.size })
        assertEquals(3, restored.display()!!.weeks[0].courses.map { it.date }.distinct().size)
    }
    @Test fun editingOneWeekdayRetainsOtherDaysAndDeduplicatesExistingTargets() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        repo.edit(context, draft(context).copy(weekdays = listOf(1, 3)))
        val firstMonday = repo.snapshot().display()!!.weeks[0].courses.first()
        val edit = repo.snapshot().courseEditor(firstMonday)
        val changed = repo.edit(edit, draft(edit, target = listOf(1)).copy(weekdays = listOf(1, 3, 5)))
        assertEquals(firstMonday.id, changed.localTerms.single().courses.single().id)
        assertEquals(listOf(1 to 1, 1 to 3, 1 to 5, 2 to 1, 2 to 3),
            changed.localTerms.single().courses.single().occurrences.map { it.week to it.weekday })
        val delete = changed.courseEditor(changed.display()!!.weeks[0].courses.first(), deleteOnly = true)
        val deleted = repo.edit(delete, draft(delete))
        assertEquals(listOf(1 to 3, 1 to 5, 2 to 1, 2 to 3), deleted.localTerms.single().courses.single().occurrences.map { it.week to it.weekday })
    }
    @Test fun emptyInvalidOrDuplicateWeekdaysAreRejectedBeforeWriting() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        for (days in listOf(emptyList(), listOf(0), listOf(8), listOf(1, 1))) {
            assertThrows(CourseValidationException::class.java) { repo.edit(context, draft(context).copy(weekdays = days)) }
            assertTrue(repo.snapshot().localTerms.isEmpty())
        }
    }
    @Test fun partialEditAtOccurrenceLimitSplitsAndRestoresEveryArrangement() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo)
        val original = before.localTerms.single().courses.single()
        val course = before.display()!!.weeks[0].courses.first()
        val context = before.courseEditor(course)
        val result = repo.edit(context, draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3)))
        val courses = result.localTerms.single().courses
        assertEquals(2, courses.size)
        assertEquals(original.copy(occurrences = original.occurrences.filterNot { it.week == 1 && it.weekday == 1 }),
            courses.single { it.id == original.id })
        val adjusted = courses.single { it.id != original.id }
        assertEquals(listOf(CustomOccurrence(1, 1, listOf(3, 4)), CustomOccurrence(1, 3, listOf(3, 4))), adjusted.occurrences)
        assertEquals(original.name, adjusted.name)
        assertTrue(courses.all { it.occurrences.size <= 420 })
        val restored = ScheduleRepository(store, AesGcmVaultCipher(keys)).load()
        assertEquals(result, restored)
        assertEquals(421, restored.display()!!.weeks.sumOf { it.courses.size })
        val moved = restored.display()!!.weeks[0].courses.first { it.id == adjusted.id }
        val delete = restored.courseEditor(moved, deleteOnly = true)
        val deleted = repo.edit(delete, draft(delete))
        assertEquals(courses.single { it.id == original.id }, deleted.localTerms.single().courses.single { it.id == original.id })
        assertEquals(listOf(CustomOccurrence(1, 3, listOf(3, 4))),
            deleted.localTerms.single().courses.single { it.id == adjusted.id }.occurrences)
    }
    @Test fun sixtyWeekMultiDayAdjustmentRetainsOtherOriginalTimes() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo, listOf(1, 3, 5))
        val original = before.localTerms.single().courses.single()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val result = repo.edit(context, draft(context, target = (1..60).toList()).copy(weekdays = (1..7).toList()))
        val courses = result.localTerms.single().courses
        assertEquals(original.copy(occurrences = original.occurrences.drop(1)), courses.single { it.id == original.id })
        assertEquals((1..60).flatMap { week -> (1..7).map { day -> CustomOccurrence(week, day, listOf(3, 4)) } },
            courses.single { it.id != original.id }.occurrences)
        assertEquals(599, result.display()!!.weeks.sumOf { it.courses.size })
        assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
    }
    @Test fun capacitySplitDeduplicatesTargetsAlreadyInRetainedRecord() {
        val repo = repository(); val before = addSixtyWeekCourse(repo)
        val original = before.localTerms.single().courses.single()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val filled = repo.edit(context, draft(context, source = (1..60).toList(), target = (1..60).toList(), day = 1))
        assertEquals(original.id, filled.localTerms.single().courses.single().id)
        val monday = filled.display()!!.weeks[0].courses.single { it.lessons == listOf(3, 4) }
        val edit = filled.courseEditor(monday)
        val result = repo.edit(edit, draft(edit, target = (1..60).toList()).copy(weekdays = listOf(1, 2)))
        val courses = result.localTerms.single().courses
        val added = courses.single { it.id != original.id }
        assertEquals(listOf(CustomOccurrence(1, 1, listOf(3, 4))) + (1..60).map { CustomOccurrence(it, 2, listOf(3, 4)) },
            added.occurrences)
        val all = courses.flatMap { it.occurrences }
        assertEquals(480, all.size); assertEquals(all.size, all.distinct().size)
        assertEquals(419, courses.single { it.id == original.id }.occurrences.size)
    }
    @Test fun splitWriteFailurePreservesOriginalVaultAndAllowsExplicitRetry() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo); val bytes = store.bytes!!.copyOf()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val adjustment = draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3))
        store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.edit(context, adjustment) }
        assertEquals(before, repo.snapshot()); assertArrayEquals(bytes, store.bytes)
        assertEquals(before, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        store.failWrite = false
        val result = repo.edit(context, adjustment)
        assertEquals(421, result.localTerms.single().courses.sumOf { it.occurrences.size })
        assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
    }
    @Test fun splitAtCourseCountLimitReportsValidationAndKeepsOriginalData() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val initial = addSixtyWeekCourse(repo)
        val term = initial.localTerms.single(); val original = term.courses.single()
        val full = initial.copy(localTerms = listOf(term.copy(courses = listOf(original) + (1..999).map {
            CustomCourse("fictional-$it", "虚构占位课程", occurrences = listOf(CustomOccurrence(1, 2, listOf(5, 6))))
        })))
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt(kotlinx.serialization.json.Json.encodeToString(ScheduleData.serializer(), full).encodeToByteArray())
        val loadedRepo = ScheduleRepository(store, cipher); assertEquals(full, loadedRepo.load())
        val bytes = store.bytes!!.copyOf()
        val context = full.courseEditor(full.display()!!.weeks[0].courses.first { it.id == original.id })
        val error = assertThrows(CourseValidationException::class.java) {
            loadedRepo.edit(context, draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3)))
        }
        assertTrue(error.message!!.contains("本地课程数量已达上限"))
        assertEquals(full, loadedRepo.snapshot()); assertArrayEquals(bytes, store.bytes)
        // An edit that fits the existing record is still allowed at the course count limit.
        val unchanged = loadedRepo.edit(context, draft(context, target = listOf(1), day = 1, lessons = listOf(1, 2)))
        assertEquals(1000, unchanged.localTerms.single().courses.size)
        assertEquals(original, unchanged.localTerms.single().courses.single { it.id == original.id })
    }
    @Test fun oversizedStoredRecordIsStillRejectedWithoutOverwritingVault() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val initial = addSixtyWeekCourse(repo)
        val term = initial.localTerms.single(); val original = term.courses.single()
        val invalid = initial.copy(localTerms = listOf(term.copy(courses = listOf(original.copy(
            occurrences = original.occurrences + CustomOccurrence(1, 1, listOf(3, 4)))))))
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt(kotlinx.serialization.json.Json.encodeToString(ScheduleData.serializer(), invalid).encodeToByteArray())
        val bytes = store.bytes!!.copyOf()
        assertThrows(ScheduleException::class.java) { ScheduleRepository(store, cipher).load() }
        assertArrayEquals(bytes, store.bytes)
    }
}
