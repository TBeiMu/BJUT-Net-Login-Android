package io.github.tbeimu.gongxiaozhu.schedule

internal data class LessonTime(val start: String, val end: String)

/** BJUT timetable supplied by the user on 2026-11-11; never substitute CUMT's times. */
private val bjutLessonTimes = listOf(
    LessonTime("08:00", "08:45"),
    LessonTime("08:50", "09:35"),
    LessonTime("09:55", "10:40"),
    LessonTime("10:45", "11:30"),
    LessonTime("13:30", "14:15"),
    LessonTime("14:20", "15:05"),
    LessonTime("15:25", "16:10"),
    LessonTime("16:15", "17:00"),
    LessonTime("18:00", "18:45"),
    LessonTime("18:50", "19:35"),
    LessonTime("19:55", "20:40"),
    LessonTime("20:45", "21:30"),
)

internal fun bjutLessonTime(lesson: Int): LessonTime? =
    if (lesson in 1..bjutLessonTimes.size) bjutLessonTimes[lesson - 1] else null
