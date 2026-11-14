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
}
