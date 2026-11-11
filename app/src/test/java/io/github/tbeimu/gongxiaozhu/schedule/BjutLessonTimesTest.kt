package io.github.tbeimu.gongxiaozhu.schedule

import java.time.Duration
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class BjutLessonTimesTest {
    @Test fun twelveLessonsMatchSuppliedBjutTimesAndEachLasts45Minutes() {
        val expected = listOf("08:00-08:45", "08:50-09:35", "09:55-10:40", "10:45-11:30",
            "13:30-14:15", "14:20-15:05", "15:25-16:10", "16:15-17:00",
            "18:00-18:45", "18:50-19:35", "19:55-20:40", "20:45-21:30")
        expected.forEachIndexed { index, value ->
            val time = bjutLessonTime(index + 1)!!
            assertEquals(value, "${time.start}-${time.end}")
            assertEquals(45L, Duration.between(LocalTime.parse(time.start), LocalTime.parse(time.end)).toMinutes())
        }
    }

    @Test fun unknownLessonsHaveNoGuessedTime() {
        listOf(Int.MIN_VALUE, 0, 13, 20, Int.MAX_VALUE).forEach { assertNull(bjutLessonTime(it)) }
    }
}
