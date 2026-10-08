package jp.studycopi

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class WidgetKind(val title: String, val sizes: String, val route: String) {
    TODAY("今日の学習", "4×2・4×4", "today"),
    PLANS("今日の予定", "4×2・4×4", "plans"),
    HOURS("今日の勉強時間", "2×1・2×2", "stats"),
    TOTAL("予定時間と累積勉強時間", "2×2・4×2", "stats"),
    ALARM("次の目覚まし", "2×1・2×2", "wake"),
    NEXT("次の学習予定", "4×1・4×2", "plans")
}
data class WidgetEntry(val time: String, val subject: String, val detail: String)
data class WidgetPresentation(val title: String, val label: String, val value: String, val rows: List<String>, val empty: String, val more: String, val visual: WidgetVisual? = null, val entries: List<WidgetEntry> = emptyList())
fun widgetPresentation(data: StudyData, kind: WidgetKind, now: LocalDateTime = LocalDateTime.now()): WidgetPresentation {
    val content = widgetContent(data, now.toLocalDate())
    val todayPlans = data.schedules.filter { it.date == now.toLocalDate() && !it.finished }
    val todayExams = data.exams.count { it.date == now.toLocalDate().toString() }
    val entries = (todayPlans.map { WidgetEntry(dateTime(it.datetime).toLocalTime().toString().take(5), data.subject(it.subjectId).name,
        listOf(hoursText(it.duration), it.content).filter { text -> text.isNotBlank() }.joinToString(" · ")) } +
        data.exams.filter { it.date == now.toLocalDate().toString() }.map { WidgetEntry(it.startTime.ifBlank { "時刻未定" }, "${it.subject}（試験）", it.range) }).sortedBy { it.time }
    val presentation = when (kind) {
        WidgetKind.TODAY -> WidgetPresentation(content.date, "今日の勉強時間 · 記録済み", content.studied, content.rows,
            "今日の予定はありません", if (content.remaining > 0) "ほか${content.remaining}件 · 今日を開く ›" else "今日の学習を開く ›")
        WidgetKind.PLANS -> WidgetPresentation(kind.title, content.date, "${todayPlans.size + todayExams}件", content.rows,
            "今日の未完了の予定はありません", if (content.remaining > 0) "ほか${content.remaining}件 · 予定を開く ›" else "予定を開く ›")
        WidgetKind.HOURS -> WidgetPresentation(kind.title, "今日 · 記録済み", content.studied, emptyList(), "", "学習の記録を開く ›")
        WidgetKind.TOTAL -> WidgetPresentation("累積勉強時間", "全期間 · 記録済み", hoursText(data.schedules.sumOf { it.actual }),
            listOf("累積予定時間  ${hoursText(data.schedules.sumOf { it.duration })}"), "", "実績を開く ›")
        WidgetKind.ALARM -> {
            val wall = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val alarm = data.wakeAlarms.filter { it.enabled && it.nextAt >= wall }.minByOrNull { it.nextAt }
            WidgetPresentation(kind.title, alarm?.let { Instant.ofEpochMilli(it.nextAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: "設定から追加できます",
                alarm?.time ?: "オフ", alarm?.let { listOf(it.label, wakeDaysText(it.days)) } ?: emptyList(), "", "目覚ましを開く ›")
        }
        WidgetKind.NEXT -> {
            val plan = data.schedules.filter { !it.finished && dateTime(it.datetime) >= now }.minByOrNull { dateTime(it.datetime) }
            WidgetPresentation(kind.title, plan?.let { "${it.date.monthValue}/${it.date.dayOfMonth} · ${data.subject(it.subjectId).name}" } ?: "これからの予定",
                plan?.let { dateTime(it.datetime).toLocalTime().toString().take(5) } ?: "予定なし",
                plan?.let { listOf("${hoursText(it.duration)}  ${it.content}") } ?: emptyList(), "", "予定を開く ›")
        }
    }
    return presentation.copy(visual = widgetVisual(data, kind, now.toLocalDate()), entries = if (kind in listOf(WidgetKind.TODAY, WidgetKind.PLANS)) entries else emptyList())
}

class TodayPlansWidget : StudyWidget() { override val kind = WidgetKind.PLANS }
class TodayHoursWidget : StudyWidget() { override val kind = WidgetKind.HOURS }
class TotalHoursWidget : StudyWidget() { override val kind = WidgetKind.TOTAL }
class NextAlarmWidget : StudyWidget() { override val kind = WidgetKind.ALARM }
class NextPlanWidget : StudyWidget() { override val kind = WidgetKind.NEXT }
