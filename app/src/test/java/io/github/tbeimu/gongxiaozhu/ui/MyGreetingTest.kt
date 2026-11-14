package io.github.tbeimu.gongxiaozhu.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class MyGreetingTest {
    private val date = LocalDate.of(1970, 1, 1)

    @Test fun everyPeriodUsesItsExactStartAndExclusiveEnd() {
        val periods = listOf(
            Triple("00:00", "04:59:59", "世界安静下来，梦还在赶路。"),
            Triple("05:00", "07:59:59", "新的一天，从第一缕光开始。"),
            Triple("08:00", "10:59:59", "把热爱放进日程，把行动交给现在。"),
            Triple("11:00", "12:59:59", "忙里偷闲，也要好好吃饭。"),
            Triple("13:00", "16:59:59", "保持热爱，奔赴山河。"),
            Triple("17:00", "18:59:59", "落日归山海，烟火向星辰。"),
            Triple("19:00", "22:59:59", "把烦恼交给夜色，把期待留给明天。"),
            Triple("23:00", "23:59:59", "别急，月亮也在慢慢变圆。"),
        )
        periods.forEach { (start, end, expected) ->
            assertEquals(start, expected, myGreeting(date.atTime(LocalTime.parse(start))))
            assertEquals(end, expected, myGreeting(date.atTime(LocalTime.parse(end))))
        }
    }

    @Test fun phrasesRotateDailyAndRemainStableInsideTheSamePeriod() {
        val expected = listOf("保持热爱，奔赴山河。", "一半清醒，一半温柔。", "慢慢来，下午的光也在替你加油。")
        (0L..5L).forEach { day ->
            val phrase = expected[(day % 3).toInt()]
            val moment = date.plusDays(day)
            assertEquals(phrase, myGreeting(moment.atTime(13, 0)))
            assertEquals(phrase, myGreeting(moment.atTime(15, 30)))
            assertEquals(phrase, myGreeting(moment.atTime(16, 59)))
        }
    }

    @Test fun midnightChangesBothThePeriodAndDailyPhrase() {
        assertEquals("今天到此为止，明天重新开始。", myGreeting(LocalDateTime.of(1970, 1, 3, 23, 59)))
        assertEquals("世界安静下来，梦还在赶路。", myGreeting(LocalDateTime.of(1970, 1, 4, 0, 0)))
        assertEquals("夜很深了，先把心事放一放，好好休息。", myGreeting(LocalDateTime.of(1969, 12, 31, 0, 0)))
    }
}
