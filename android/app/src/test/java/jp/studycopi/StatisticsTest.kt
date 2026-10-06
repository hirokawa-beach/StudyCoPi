package jp.studycopi

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StatisticsTest {
    private val records = listOf(
        Schedule("a", "s1", "2026-12-31T18:00", 1.0, status = "done", examGroupId = "g1"),
        Schedule("b", "s1", "2027-01-01T18:00", 2.0, status = "partial", actualDuration = .5, examGroupId = "g1"),
        Schedule("c", "s1", "2027-01-03T18:00", 3.0, status = "done", actualDuration = 0.0, examGroupId = "g2"))
    @Test fun weeksCrossTheYearAndIncludeEmptyWeeksWithoutMixingExams() {
        val buckets = statisticsBuckets(StudyData(schedules = records).forExam("g1"), LocalDate.parse("2026-12-28"), LocalDate.parse("2027-01-11"), "week")
        assertEquals(listOf("2026-12-28", "2027-01-04", "2027-01-11"), buckets.map { it.date.toString() })
        assertEquals(listOf(1.5, 0.0, 0.0), buckets.map { it.actual })
        assertEquals(3.0, buckets.first().planned, 0.0)
    }
    @Test fun monthlyTotalsRespectRangeAndZeroActual() {
        val buckets = statisticsBuckets(records, LocalDate.parse("2027-01-01"), LocalDate.parse("2027-01-03"), "month")
        assertEquals(.5, buckets.single().actual, 0.0); assertEquals(5.0, buckets.single().planned, 0.0)
        assertTrue(statisticsBuckets(records, LocalDate.parse("2027-02-01"), LocalDate.parse("2027-01-01"), "day").isEmpty())
    }
    @Test fun leapDaysIntensityAndLongImports() {
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01"), statisticsBuckets(emptyList(), LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01"), "day").map { it.date.toString() })
        assertEquals(listOf(0, 1, 2, 3, 4, 4), listOf(0.0, .1, .5, 1.0, 2.0, 20.0).map(::heatLevel))
        assertEquals(366, statisticsBuckets(records, LocalDate.parse("2000-01-01"), LocalDate.parse("2026-12-31"), "day").size)
    }
}
