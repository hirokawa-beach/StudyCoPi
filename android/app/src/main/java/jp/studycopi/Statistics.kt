package jp.studycopi

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class StudyBucket(val date: LocalDate, val planned: Double = 0.0, val actual: Double = 0.0)
fun heatLevel(hours: Double): Int = when { hours <= 0 -> 0; hours < .5 -> 1; hours < 1 -> 2; hours < 2 -> 3; else -> 4 }
fun statisticsBuckets(records: List<Schedule>, from: LocalDate, to: LocalDate, unit: String): List<StudyBucket> {
    if (from > to) return emptyList()
    fun start(date: LocalDate) = when (unit) { "week" -> weekStart(date); "month" -> date.withDayOfMonth(1); else -> date }
    fun advance(date: LocalDate, count: Long) = when (unit) { "week" -> date.plusWeeks(count); "month" -> date.plusMonths(count); else -> date.plusDays(count) }
    val first = start(from); val last = start(to)
    val intervals = when (unit) { "week" -> ChronoUnit.WEEKS; "month" -> ChronoUnit.MONTHS; else -> ChronoUnit.DAYS }.between(first, last)
    val totals = records.filter { it.date in from..to }.groupBy { start(it.date) }
    // Keep the interactive chart bounded even for imported records spanning decades.
    var cursor = advance(first, (intervals - 365).coerceAtLeast(0))
    return buildList {
        while (cursor <= last) {
            val entries = totals[cursor].orEmpty()
            add(StudyBucket(cursor, entries.sumOf { it.duration }, entries.sumOf { it.actual }))
            cursor = advance(cursor, 1)
        }
    }
}
