package jp.studycopi

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class TimeEntry(val id: String, val date: LocalDate, val minute: Int, val duration: Int,
    val name: String, val time: String, val color: String, val exam: Boolean = false)
data class TimePlacement(val entry: TimeEntry, val top: Float, val height: Float, val lane: Int)
/** Allocate lanes using displayed bounds, so even one-minute plans keep readable labels. */
fun placeTimeEntries(entries: List<TimeEntry>, start: Int, heights: Map<String, Float>, scale: Float = 1.2f): List<TimePlacement> {
    val ends = mutableListOf<Float>()
    return entries.sortedWith(compareBy<TimeEntry> { it.minute }.thenBy { it.id }).map {
        val top = (it.minute - start) * scale
        val height = max(it.duration * scale, heights[it.id] ?: 64f)
        var lane = ends.indexOfFirst { end -> end <= top - 4f }
        if (lane == -1) { lane = ends.size; ends.add(0f) }
        ends[lane] = top + height
        TimePlacement(it, top, height, lane)
    }
}
@Composable fun WeekTimeGrid(data: StudyData, plans: List<Schedule>, exams: List<ExamSession>, week: LocalDate,
    edit: (String) -> Unit, editExam: (String) -> Unit) {
    val entries = plans.filter { it.date in week..week.plusDays(6) }.map {
        val time = dateTime(it.datetime).toLocalTime()
        TimeEntry("plan-${it.id}", it.date, time.hour * 60 + time.minute, (it.duration * 60).toInt().coerceAtLeast(1),
            data.subject(it.subjectId).name, time.toString().take(5), data.subject(it.subjectId).color)
    } + exams.filter { LocalDate.parse(it.date) in week..week.plusDays(6) && it.startTime.isNotBlank() }.map {
        val time = LocalTime.parse(it.startTime)
        val end = it.endTime.takeIf(String::isNotBlank)?.let(LocalTime::parse)
        TimeEntry("exam-${it.id}", LocalDate.parse(it.date), time.hour * 60 + time.minute,
            if (end != null) (end.toSecondOfDay() - time.toSecondOfDay()).div(60).coerceAtLeast(1) else 30,
            it.subject + "（試験）", it.startTime, "#806D48", true)
    }
    val start = (minOf(360, entries.minOfOrNull { it.minute } ?: 360) / 60) * 60
    val end = minOf(1440, ceil(maxOf(1320, entries.maxOfOrNull { it.minute + it.duration } ?: 1320) / 60.0).toInt() * 60)
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val heightById = entries.associate { entry ->
        val name = measure.measure(entry.name, style, constraints = Constraints(maxWidth = with(density) { 124.dp.roundToPx() }))
        val time = measure.measure(entry.time, MaterialTheme.typography.labelSmall)
        entry.id to with(density) { (name.size.height + time.size.height).toDp().value + 28f }.coerceAtLeast(64f)
    }
    val days = (0..6).map { index -> placeTimeEntries(entries.filter { it.date == week.plusDays(index.toLong()) }, start, heightById) }
    val height = max((end - start) * 1.2f, days.flatten().maxOfOrNull { it.top + it.height + 8 } ?: 0f)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("横にスワイプして曜日を確認。予定をタップして編集", style = MaterialTheme.typography.bodySmall)
        exams.filter { LocalDate.parse(it.date) in week..week.plusDays(6) && it.startTime.isBlank() }.forEach {
            TextButton(onClick = { editExam(it.id) }) { Text("${shortDate(it.date)} · ${it.subject}（試験・時刻未定）") }
        }
        Row(Modifier.fillMaxWidth().height(540.dp).verticalScroll(rememberScrollState()).testTag("week-time-grid")) {
            Column(Modifier.width(42.dp)) {
                Spacer(Modifier.height(48.dp))
                Box(Modifier.height(height.dp)) {
                    for (minute in start..end step 60) Text("%02d:00".format(minute / 60), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.offset(y = ((minute - start) * 1.2f).dp).padding(top = 2.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                days.forEachIndexed { index, placed ->
                    val lanes = (placed.maxOfOrNull { it.lane + 1 } ?: 1)
                    Column(Modifier.width((lanes * 144).dp)) {
                        Text(week.plusDays(index.toLong()).format(DateTimeFormatter.ofPattern("M/d（E）", Locale.JAPANESE)),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.height(48.dp).padding(10.dp))
                        Box(Modifier.height(height.dp).fillMaxWidth()) {
                            Canvas(Modifier.matchParentSize()) {
                                for (minute in start..end step 60) {
                                    val y = ((minute - start) * 1.2f).dp.toPx()
                                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
                                }
                                drawLine(lineColor, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(0f, size.height))
                            }
                            placed.forEach { block ->
                                val entry = block.entry
                                val subjectColor = Color(android.graphics.Color.parseColor(entry.color))
                                Surface(onClick = { if (entry.exam) editExam(entry.id.removePrefix("exam-")) else edit(entry.id.removePrefix("plan-")) },
                                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, subjectColor.copy(alpha = .6f)),
                                    modifier = Modifier.offset(x = (block.lane * 144 + 2).dp, y = block.top.dp)
                                        .width(140.dp).height(block.height.dp).testTag("time-${entry.id}")) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(entry.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(entry.name, style = style, modifier = Modifier.padding(top = 4.dp).testTag("time-name-${entry.id}"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
