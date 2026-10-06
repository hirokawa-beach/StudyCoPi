@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package jp.studycopi

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.flow.first

@Composable fun StatisticsScreen(data: StudyData, examFilter: String, filter: (String) -> Unit) {
    val today = LocalDate.now()
    var period by rememberSaveable { mutableStateOf("all") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var unit by rememberSaveable { mutableStateOf("month") }
    var from by rememberSaveable { mutableStateOf(weekStart(today).toString()) }
    var to by rememberSaveable { mutableStateOf(today.toString()) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var heatMode by rememberSaveable { mutableStateOf("rolling") }
    var detail by rememberSaveable { mutableStateOf("棒や日付のマスを選ぶと、勉強時間を確認できます。") }
    val examRecords = remember(data.schedules, examFilter) { data.forExam(examFilter) }
    val range = when (period) {
        "week" -> weekStart(today).plusWeeks(offset.toLong()).let { it to it.plusDays(6) }
        "month" -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.plusMonths(1).minusDays(1) }
        "year" -> today.withDayOfYear(1).plusYears(offset.toLong()).let { it to it.plusYears(1).minusDays(1) }
        "custom" -> LocalDate.parse(from) to LocalDate.parse(to)
        else -> (examRecords.minOfOrNull { it.date } ?: today.withDayOfMonth(1)) to maxOf(today, examRecords.maxOfOrNull { it.date } ?: today)
    }
    val records = remember(examRecords, range) { examRecords.filter { it.date in range.first..range.second } }
    val buckets = remember(records, range, unit) { statisticsBuckets(records, range.first, range.second, unit) }
    val daily = remember(records) { records.groupBy { it.date }.mapValues { (date, entries) -> StudyBucket(date, entries.sumOf { it.duration }, entries.sumOf { it.actual }) } }
    LaunchedEffect(examFilter, range, unit, year, heatMode) { detail = "棒や日付のマスを選ぶと、勉強時間を確認できます。" }
    fun select(bucket: StudyBucket) { detail = "${bucket.date} · 実績 ${hoursText(bucket.actual)} / 予定 ${hoursText(bucket.planned)}" }
    val choices = listOf("" to "すべての試験・学習", "unassigned" to "試験未指定のみ") + data.examGroups.sortedByDescending { it.startDate }.map { it.id to "${it.startDate.take(4)}年 · ${it.name}" }
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("学習の統計", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.guideTarget("stats-filter")) { ChoiceField("対象の試験", examFilter, choices, filter) }
            Spacer(Modifier.height(12.dp))
            ChoiceField("期間", period, listOf("all" to "すべての期間", "week" to "今週", "month" to "今月", "year" to "今年", "custom" to "期間を指定")) { period = it; offset = 0 }
            if (period == "custom") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("開始日", from, Modifier.weight(1f)) { from = it }; DateField("終了日", to, Modifier.weight(1f)) { to = it }
            }
            if (range.first > range.second) Text("開始日と終了日を確認してください", color = MaterialTheme.colorScheme.error)
            if (period in listOf("week", "month", "year")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { offset-- }) { Text("前へ") }; TextButton(onClick = { offset++ }) { Text("次へ") }
                }
                Text("${range.first} 〜 ${range.second}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatMetric("勉強した時間", hoursText(records.sumOf { it.actual }), Modifier.weight(1f))
                StatMetric("予定時間", hoursText(records.sumOf { it.duration }), Modifier.weight(1f))
            }
            Text("${records.size}件の学習記録 · ${records.count { it.status == "done" }}件 完了", style = MaterialTheme.typography.bodySmall)
            if (records.isEmpty()) Text("この試験・期間の学習記録はありません", modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Text("勉強時間の推移", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ChoiceField("集計単位", unit, listOf("day" to "日ごと", "week" to "週ごと", "month" to "月ごと")) { unit = it }
            Text(if (buckets.isEmpty()) "表示できる期間がありません" else "${buckets.first().date} 〜 ${range.second}（最大366区間）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            StatLegend()
            val maximum = buckets.maxOfOrNull { maxOf(it.actual, it.planned) }?.coerceAtLeast(1.0) ?: 1.0
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buckets.forEach { bucket ->
                    Column(Modifier.width(72.dp).clickable(role = Role.Button) { select(bucket) }.semantics(mergeDescendants = true) {
                        contentDescription = "${bucket.date} 実績 ${hoursText(bucket.actual)}、予定 ${hoursText(bucket.planned)}"
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.height(160.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.width(18.dp).height((160 * bucket.actual / maximum).dp).background(MaterialTheme.colorScheme.primary))
                            Box(Modifier.width(18.dp).height((160 * bucket.planned / maximum).dp).background(MaterialTheme.colorScheme.outline))
                        }
                        HorizontalDivider()
                        Text(if (unit == "month") bucket.date.toString().take(7) else "${bucket.date.monthValue}/${bucket.date.dayOfMonth}" + if (unit == "week") "週" else "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
        item {
            Text("学習のヒートマップ", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ChoiceField("表示期間", heatMode, listOf("rolling" to "直近1年", "year" to "年ごと")) { heatMode = it }
            if (heatMode == "year") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { year-- }) { Text("前年") }; Text("${year}年", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { year++ }) { Text("翌年") }
            }
            val first = if (heatMode == "rolling") today.minusYears(1).plusDays(1) else LocalDate.of(year, 1, 1)
            val last = if (heatMode == "rolling") today else LocalDate.of(year, 12, 31)
            val yearDays = daily.filterKeys { it in first..last }
            Text("勉強した日 ${yearDays.count { it.value.actual > 0 }}日 · 実績 ${hoursText(yearDays.values.sumOf { it.actual })}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
            val end = last.minusDays((last.dayOfWeek.value % 7).toLong()).plusDays(7)
            val dark = isSystemInDarkTheme()
            val colors = if (dark) listOf(Color(0xFF161B22), Color(0xFF0E4429), Color(0xFF006D32), Color(0xFF26A641), Color(0xFF39D353))
                else listOf(Color(0xFFEFF2F5), Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF30A14E), Color(0xFF216E39))
            val heatScroll = rememberScrollState()
            LaunchedEffect(heatMode, year) {
                if (heatMode == "rolling") { snapshotFlow { heatScroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }; heatScroll.scrollTo(heatScroll.maxValue) }
                else heatScroll.scrollTo(0)
            }
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Spacer(Modifier.height(18.dp)); listOf("", "月", "", "水", "", "金", "").forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(18.dp)) } }
                Row(Modifier.weight(1f).horizontalScroll(heatScroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    var week = start
                    while (week < end) {
                        val thisWeek = week
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val month = (0..6).map { thisWeek.plusDays(it.toLong()) }.firstOrNull { it in first..last && (it.dayOfMonth == 1 || it == first) }
                            Box(Modifier.size(18.dp)) { if (month != null) Text("${month.monthValue}月", style = MaterialTheme.typography.labelSmall, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible) }
                            repeat(7) { day ->
                                val date = thisWeek.plusDays(day.toLong())
                                val bucket = daily[date] ?: StudyBucket(date)
                                val inYear = date in first..last
                                val included = inYear && (period == "all" || date in range.first..range.second)
                                val color = if (!inYear) Color.Transparent else colors[heatLevel(bucket.actual)].copy(alpha = if (included) 1f else .25f)
                                Box(Modifier.size(18.dp).background(color, RoundedCornerShape(2.dp))
                                    .then(if (inYear) Modifier.border(.5.dp, (if (dark) Color.White else Color.Black).copy(alpha = .06f), RoundedCornerShape(2.dp)) else Modifier)
                                    .clickable(enabled = included, role = Role.Button) { select(bucket) }
                                    .semantics { if (inYear) contentDescription = "$date ${if (included) "実績 ${hoursText(bucket.actual)}" else "集計期間外"}" })
                            }
                        }
                        week = week.plusWeeks(1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().semantics { contentDescription = "濃さは0分、30分未満、1時間未満、2時間未満、2時間以上の5段階" }, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text("少ない", style = MaterialTheme.typography.labelSmall)
                colors.forEach { color -> Box(Modifier.size(14.dp).background(color, RoundedCornerShape(2.dp))) }
                Text("多い", style = MaterialTheme.typography.labelSmall)
            }
            Text(detail, modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Text("実績は予定の開始日に集計します。薄いマスは選択した期間の対象外です。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("教科ごとの時間", style = MaterialTheme.typography.titleLarge)
            val maximum = data.subjects.maxOfOrNull { subject -> records.filter { it.subjectId == subject.id }.let { maxOf(it.sumOf { it.actual }, it.sumOf { it.duration }) } }?.coerceAtLeast(1.0) ?: 1.0
            data.subjects.forEach { subject ->
                val entries = records.filter { it.subjectId == subject.id }
                Text(subject.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
                StatMeasure("実績", entries.sumOf { it.actual }, maximum, MaterialTheme.colorScheme.primary)
                StatMeasure("予定", entries.sumOf { it.duration }, maximum, MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable private fun StatMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 12.dp)) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.headlineSmall) }
}
@Composable private fun StatLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("実績" to MaterialTheme.colorScheme.primary, "予定" to MaterialTheme.colorScheme.outline).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Box(Modifier.size(12.dp).background(color)); Text(label, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun StatMeasure(label: String, hours: Double, maximum: Double, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { (hours / maximum).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(5.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Text(hoursText(hours), style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 80.dp))
    }
}
