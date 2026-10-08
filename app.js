// ── Data ──────────────────────────────────────────────
const APP_NAME = "StudyCoPi";
const COLORS = [
  "#3b6d11",
  "#185fa5",
  "#a32d2d",
  "#ba7517",
  "#534ab7",
  "#0f6e56",
  "#993556",
  "#5f5e5a",
  "#639922",
  "#993c1d",
];

function load(key, def) {
  try {
    const v = localStorage.getItem(key);
    return v ? JSON.parse(v) : def;
  } catch {
    return def;
  }
}
function save(key, val) {
  const serialized = JSON.stringify(val);
  const changed = localStorage.getItem(key) !== serialized;
  localStorage.setItem(key, serialized);
  if (changed && typeof markBackupDirty === "function") markBackupDirty(key);
}

let subjects = load("sl_subjects", [
  { id: "s1", name: "数学", color: "#185fa5" },
  { id: "s2", name: "英語", color: "#3b6d11" },
  { id: "s3", name: "国語", color: "#a32d2d" },
]);
let schedules = load("sl_schedules", []);
let exams = load("sl_exams", []);
let examGroups = load("sl_exam_groups", []);
let selectedDay = null;
let lastAction = null;
let toastTimer = null;
let modalTrigger = null;
let focusExamGroupId = "";
let focusSessionStart = null;
let focusFinishing = false;
let weekOffset = 0;
let hoursMode = "both"; // 'actual' | 'planned' | 'both'
let showExamsInSchedule = true;

// ── Utilities ─────────────────────────────────────────
function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}
function fmt(dateStr) {
  if (!dateStr) return "";
  const d = new Date(dateStr);
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
function fmtDate(dateStr) {
  if (!dateStr) return "";
  const d = new Date(dateStr);
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}
function statusBadge(status) {
  const map = {
    done: "badge-done",
    miss: "badge-miss",
    partial: "badge-partial",
    pending: "badge-pending",
  };
  const label = {
    done: "完了",
    miss: "未実施",
    partial: "一部完了",
    pending: "未記録",
  };
  return `<span class="badge ${map[status] || "badge-pending"}">${label[status] || "未記録"}</span>`;
}
function subjectById(id) {
  return subjects.find((s) => s.id === id) || { name: "不明", color: "#888" };
}

// Countdown helpers
function daysUntil(dateStr) {
  if (!dateStr) return null;
  const now = new Date();
  now.setHours(0, 0, 0, 0);
  const d = new Date(dateStr);
  d.setHours(0, 0, 0, 0);
  return Math.ceil((d - now) / 86400000);
}
function countdownText(days) {
  if (days < 0) return "終了";
  if (days === 0) return "今日！";
  if (days === 1) return "明日！";
  return `あと ${days} 日`;
}
function countdownClass(days) {
  if (days <= 3) return "soon";
  if (days <= 7) return "near";
  return "ok";
}

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;"}[c]));
}
function durationText(hours) {
  const m = Math.round((Number(hours) || 0) * 60);
  return m < 60 ? `${m}分` : `${Math.floor(m / 60)}時間${m % 60 ? `${m % 60}分` : ""}`;
}
function groupName(id) { return examGroups.find((g) => g.id === id)?.name || "試験未指定"; }
function groupOptions(value = "", all = false) {
  return `<option value="">${all ? "すべての試験・学習" : "試験未指定 / 日常の学習"}</option>`
    + (all ? '<option value="unassigned"' + (value === "unassigned" ? ' selected' : '') + '>試験未指定のみ</option>' : "")
    + [...examGroups].sort((a, b) => b.startDate.localeCompare(a.startDate)).map((g) =>
      `<option value="${g.id}" ${value === g.id ? "selected" : ""}>${esc(g.startDate.slice(0, 4))}年 · ${esc(g.name)} (${esc(g.type)})</option>`).join("");
}
function populateExamFilters() {
  for (const id of ["filter-exam", "hours-exam-filter"]) {
    const el = document.getElementById(id);
    el.innerHTML = groupOptions(el.value, true);
  }
}
function getPeriodRange() {
  const preset = document.getElementById("hours-period-preset").value;
  const now = new Date();
  if (preset === 'week') now.setDate(now.getDate() + hoursPeriodOffset * 7);
  if (preset === 'month') { now.setDate(1); now.setMonth(now.getMonth() + hoursPeriodOffset); }
  if (preset === 'year') now.setFullYear(now.getFullYear() + hoursPeriodOffset);
  if (preset === "week") {
    const from = StudyModel.weekStart(now), to = new Date(from);
    to.setDate(to.getDate() + 7);
    return { from, to };
  }
  if (preset === "month") return { from: new Date(now.getFullYear(), now.getMonth(), 1), to: new Date(now.getFullYear(), now.getMonth() + 1, 1) };
  if (preset === 'year') return { from: new Date(now.getFullYear(), 0, 1), to: new Date(now.getFullYear() + 1, 0, 1) };
  if (preset === "custom") {
    const f = document.getElementById("hours-from").value, t = document.getElementById("hours-to").value;
    const to = t ? new Date(t + "T00:00:00") : null;
    if (to) to.setDate(to.getDate() + 1);
    return { from: f ? new Date(f + "T00:00:00") : null, to };
  }
  return { from: null, to: null };
}
function filterByPeriod(list, range) {
  return list.filter((s) => {
    const d = new Date(s.datetime);
    return (!range.from || d >= range.from) && (!range.to || d < range.to);
  });
}
function toast(message, undo = null) {
  clearTimeout(toastTimer);
  lastAction = undo;
  document.getElementById("toast-message").textContent = message;
  document.getElementById("toast-undo").hidden = !undo;
  document.getElementById("toast").hidden = false;
  toastTimer = setTimeout(() => { document.getElementById("toast").hidden = true; lastAction = null; }, 7000);
}
function undoLastAction() {
  if (!lastAction) return;
  const action = lastAction;
  lastAction = null;
  action();
  render();
  toast("元に戻しました");
}

// ── Navigation ────────────────────────────────────────
function showView(name) {
  if (typeof stopSoundPreview === "function") stopSoundPreview();
  if (!document.getElementById("view-" + name)) return;
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("active", v.id === "view-" + name));
  document.querySelectorAll(".nav-item").forEach((n) => {
    const active = n.id === "nav-" + name;
    n.classList.toggle("active", active);
    if (active) n.setAttribute("aria-current", "page"); else n.removeAttribute("aria-current");
  });
  const mainViews = ["dashboard", "schedule", "examgroups", "hours"];
  document.body.dataset.subpage = String(![...mainViews, "more", "timetable"].includes(name));
  document.querySelectorAll(".mobile-bottom-nav button").forEach((b) => {
    const active = b.dataset.view === (mainViews.includes(name) ? name : name === "timetable" ? "schedule" : name === "focus" ? "dashboard" : "more");
    b.classList.toggle("active", active);
    if (active) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
  document.body.dataset.view = name;
  if (name === 'schedule' || name === 'timetable') document.getElementById(name === 'schedule' ? 'schedule-filter-slot' : 'week-filter-slot').append(document.getElementById('schedule-filters'));
  if (name === "settings") loadNotifSettingsUI();
  render();
  if (typeof updatePlanTabs === "function") updatePlanTabs();
  window.scrollTo({ top: 0, behavior: "instant" });
}
function render() {
  const view = document.querySelector(".view.active")?.id.replace("view-", "");
  if (view === "schedule") { populateSubjectFilter(); populateExamFilters(); renderSchedule(); }
  else if (view === "dashboard") renderDashboard();
  else if (view === "timetable") { populateSubjectFilter(); populateExamFilters(); renderTimetable(); }
  else if (view === "examgroups") renderExamGroups();
  else if (view === "hours") { populateExamFilters(); renderHours(); }
  else if (view === "focus") renderFocusSetup();
  else if (view === "settings" || view === "subjects") { renderSubjects(); renderExams(); }
  else if (view === "exams") renderExams();
  else if (view === "data") renderData();
}
function quickAdd() {
  openModal(document.body.dataset.view === "examgroups" ? "examgroup" : "schedule");
}

// ── Shared: スケジュールカード HTML（モバイル用）────────
function appIcon(name) { return `<svg class="app-icon" aria-hidden="true" focusable="false"><use href="assets/material-icons.svg#${name}" /></svg>`; }
function buildScheduleCard(s) {
  const subject = subjectById(s.subjectId);
  const time = new Date(s.datetime).toLocaleTimeString("ja-JP", {hour:"2-digit", minute:"2-digit"});
  const done = s.status === "done", finished = done || s.status === "miss";
  const actual = StudyModel.actualHours(s);
  return `<article class="study-card ${finished ? "study-finished" : ""}">
    <div class="study-row-head"><button class="study-title-button" onclick="editSchedule('${s.id}')" aria-label="${esc(subject.name)}の予定を編集"><span class="study-time"><span class="subject-dot" style="background:${subject.color}"></span>${time} · ${durationText(s.duration)}</span><span class="study-subject">${esc(subject.name)}</span></button>
    <button class="study-check" aria-label="${esc(subject.name)}の完了を切り替え" aria-pressed="${done}" onclick="quickComplete('${s.id}')">${done ? "✓" : ""}</button></div>
    ${s.content ? `<button class="study-title-button study-content" onclick="editSchedule('${s.id}')">${esc(s.content)}</button>` : ""}
    ${s.examGroupId ? `<div class="exam-link-label">${esc(groupName(s.examGroupId))}</div>` : ""}
    ${actual > 0 || s.actualDuration !== undefined || s.status !== 'pending' ? `<div class="study-meta">${s.status === 'done' ? '完了' : s.status === 'partial' ? '一部完了' : s.status === 'miss' ? '未実施' : '計測済み'} · 実績 ${durationText(actual)}</div>` : ''}
    <div class="study-actions"><button class="icon-button" onclick="editSchedule('${s.id}')" aria-label="${esc(subject.name)}の予定を編集">${appIcon('edit')}</button><button class="btn btn-quiet" onclick="openRecord('${s.id}')">記録</button>${!finished ? `<button class="btn btn-tonal start-button" onclick="startScheduleFocus('${s.id}')" aria-label="${esc(subject.name)}のタイマーを開始">${appIcon('play')}開始</button>` : ""}</div>
  </article>`;
}
function quickComplete(id) {
  const item = schedules.find((s) => s.id === id);
  if (!item) return;
  if (focusSchedId === id && document.getElementById("focus-active").style.display !== "none") { toast("タイマーを終了してから記録してください"); return; }
  const previous = {...item};
  if (item.status === "done") item.status = "pending";
  else {
    item.status = "done";
    // Preserve measured time; only estimate when there is no previous record.
    if (item.actualDuration === undefined || item.actualDuration === "") item.actualDuration = Number(item.duration) || 0;
  }
  save("sl_schedules", schedules); render();
  toast(item.status === "done" ? "完了を記録しました" : "未記録に戻しました", () => {
    const index = schedules.findIndex((s) => s.id === id);
    if (index >= 0) schedules[index] = previous;
    save("sl_schedules", schedules);
  });
}

function emptyState(message, button = "予定を追加", action = "openModal('schedule')") {
  return `<div class="empty-state"><p>${message}</p><button class="btn btn-primary" onclick="${action}">＋ ${button}</button></div>`;
}
function renderDashboard() {
  const now = new Date(), today = StudyModel.localDate(now);
  const items = schedules.filter((s) => StudyModel.localDate(s.datetime) === today).sort((a,b) => new Date(a.datetime)-new Date(b.datetime));
  const pending = items.filter((s) => !["done", "miss"].includes(s.status));
  const finished = items.filter((s) => ["done", "miss"].includes(s.status));
  document.getElementById("today-label").textContent = now.toLocaleDateString("ja-JP", {month:"long", day:"numeric", weekday:"long"});
  document.getElementById("dash-next").innerHTML = "";
  document.getElementById("dash-mobile-list").innerHTML = pending.map(buildScheduleCard).join("")
    + (!items.length ? emptyState("今日の予定はありません。", "予定を追加") : !pending.length ? '<p class="all-done">今日の予定は完了しました。</p>' : "")
    + (finished.length ? `<details class="completed-list"><summary>完了・未実施 ${finished.length}件</summary>${finished.map(buildScheduleCard).join("")}</details>` : "");
  const total = StudyModel.totals(items);
  document.getElementById("dash-stats").innerHTML = `<div><span>今日の勉強</span><strong>${durationText(total.actual)}</strong></div><div><span>予定時間</span><strong>${durationText(total.planned)}</strong></div><div class="today-progress" style="grid-column:1/-1;flex-direction:row;align-items:center;width:100%"><progress max="${total.count || 1}" value="${total.done}" aria-label="今日の完了率"></progress><span>${total.done} / ${total.count}件 完了</span></div>`;
  const upcoming = [...examGroups].filter((g) => g.endDate >= today).sort((a,b) => a.startDate.localeCompare(b.startDate))[0];
  document.getElementById("dash-exam-countdown").innerHTML = `<h2 class="section-title exam-prep-title">試験の準備</h2><button class="exam-home-card" onclick="${upcoming ? `viewExamHours('${upcoming.id}')` : "openModal('examgroup')"}"><small>${upcoming ? upcoming.startDate <= today ? "試験期間中" : countdownText(daysUntil(upcoming.startDate)) : "次の試験"}</small><span class="exam-home-name"><strong>${esc(upcoming?.name || "考査・模試を登録")}</strong>${appIcon('chevron')}</span>${upcoming ? `<span class="muted">${fmtDate(upcoming.startDate)}</span>` : ""}</button>`;
  const from = StudyModel.weekStart(now), to = new Date(from); to.setDate(to.getDate()+7);
  const week = StudyModel.totals(filterByPeriod(schedules, {from,to}));
  document.getElementById("dash-week").innerHTML = `<button class="week-summary" onclick="showView('hours')"><span>今週の勉強 ${durationText(week.actual)}</span>${appIcon('chevron')}</button>`;
}

// ── Schedule filter ───────────────────────────────────
function populateSubjectFilter() {
  const sel = document.getElementById("filter-subject");
  const cur = sel.value;
  sel.innerHTML =
    '<option value="">すべての教科</option>' +
    subjects
      .map(
        (s) =>
          `<option value="${s.id}" ${cur === s.id ? "selected" : ""}>${esc(s.name)}</option>`,
      )
      .join("");
}

function toggleExamVisibility() {
  showExamsInSchedule = !showExamsInSchedule;
  const btn = document.getElementById("toggle-exam-btn");
  if (btn) {
    btn.textContent = showExamsInSchedule ? "試験日程を非表示" : "試験日程を表示";
  }
  renderSchedule();
}

function renderSchedule() {
  const sf = document.getElementById("filter-subject").value;
  const status = document.getElementById("filter-status").value;
  const gf = document.getElementById("filter-exam").value;
  let list = StudyModel.forExam(schedules, gf).filter((s) => (!sf || s.subjectId === sf) && (!status || (s.status || "pending") === status));
  const total = StudyModel.totals(list);
  const filterCount = [sf, status, gf].filter(Boolean).length;
  document.getElementById("filter-count").textContent = filterCount ? `${filterCount}件の条件` : "";
  const context = gf ? `${gf === "unassigned" ? "試験未指定" : groupName(gf)} · ` : "";
  document.getElementById("schedule-summary").textContent = `${context}${list.length}件の学習予定 · 予定 ${durationText(total.planned)} · 実績 ${durationText(total.actual)}`;
  if (showExamsInSchedule && !status) {
    list = list.concat(StudyModel.forExam(exams, gf).filter((e) => !sf || subjects.find((s) => s.id === sf)?.name === e.subject).map((e) => ({...e, isExam:true, datetime:e.date + "T" + (e.startTime || "00:00")})));
  }
  list.sort((a, b) => new Date(a.datetime) - new Date(b.datetime));
  const grouped = new Map();
  for (const item of list) {
    const date = StudyModel.localDate(item.datetime);
    if (!grouped.has(date)) grouped.set(date, []);
    grouped.get(date).push(item);
  }
  const today = StudyModel.localDate();
  const groupHtml = (date, items) => {
    const body = items.map((s) => s.isExam ? examSessionCard(s) : buildScheduleCard(s)).join("");
    const label = new Date(date + "T00:00").toLocaleDateString("ja-JP", {month:"long", day:"numeric", weekday:"short"});
    return `<section class="schedule-day"><h2 class="day-heading">${esc(label)}${date === today ? ' <span class="day-today">今日</span>' : ""}<span>${items.length}件</span></h2><div class="day-items">${body}</div></section>`;
  };
  const past = [...grouped].filter(([date]) => date < today), future = [...grouped].filter(([date]) => date >= today);
  document.getElementById("schedule-mobile-list").innerHTML = list.length ? future.map(([date, items]) => groupHtml(date, items)).join("")
    + (past.length ? `<details class="past-archive"><summary>過去の予定 · ${past.reduce((n, [,items]) => n + items.length, 0)}件</summary>${past.reverse().map(([date, items]) => groupHtml(date, items)).join("")}</details>` : "") : emptyState("この条件の予定はありません。");
  if (document.body.dataset.view === 'timetable') renderTimetable();
}
function timetableData() {
  const group = document.getElementById('filter-exam').value;
  const subject = document.getElementById('filter-subject').value;
  const status = document.getElementById('filter-status').value;
  return {
    plans: StudyModel.forExam(schedules,group).filter(s=>(!subject || s.subjectId===subject) && (!status || (s.status || 'pending')===status)),
    exams: showExamsInSchedule && !status ? StudyModel.forExam(exams,group).filter(e=>!subject || subjectById(subject).name===e.subject) : []
  };
}
let ttMode = "list";
function setTTMode(mode) {
  if (typeof stopSoundPreview === "function") stopSoundPreview(); ttMode = mode; applyTTMode(); layoutTimetableBlocks(); }
function applyTTMode() {
  const grid = ttMode === "grid";
  document.getElementById("timetable-grid").style.display = grid ? "" : "none";
  document.getElementById("mobile-day-list").style.display = grid ? "none" : "";
  for (const mode of ["list", "grid"]) document.getElementById("tt-mode-" + mode)?.setAttribute("aria-pressed", String(ttMode === mode));
  if (typeof updatePlanTabs === "function") updatePlanTabs();
}

// ── Timetable ─────────────────────────────────────────
function renderTimetable() {
  const {plans: weekPlans, exams: weekExams} = timetableData();
  const now = new Date();
  const base = new Date(now);
  base.setDate(now.getDate() - (now.getDay() + 6) % 7 + weekOffset * 7);
  base.setHours(0, 0, 0, 0);
  const days = ["月", "火", "水", "木", "金", "土", "日"];
  const dates = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(base);
    d.setDate(base.getDate() + i);
    return d;
  });
  const previousWrap = document.querySelector("#timetable-grid .tt2-wrap");
  const sameWeek = previousWrap?.dataset.week === StudyModel.localDate(base);
  const scrollPosition = sameWeek ? { top: previousWrap.scrollTop, left: previousWrap.scrollLeft } : null;

  document.getElementById("week-label").textContent =
    `${dates[0].getMonth() + 1}/${dates[0].getDate()} 〜 ${dates[6].getMonth() + 1}/${dates[6].getDate()}`;

  // ── 表示時間範囲：最低6〜22、予定が範囲外なら自動拡張 ─
  let minHour = 6,
    maxHour = 22;

  weekPlans.forEach((s) => {
    if (
      !dates.some(
        (d) => d.toDateString() === new Date(s.datetime).toDateString(),
      )
    )
      return;
    const t = new Date(s.datetime);
    minHour = Math.min(minHour, t.getHours());
    maxHour = Math.max(
      maxHour,
      Math.ceil(t.getHours() + (parseFloat(s.duration) || 1)),
    );
  });
  weekExams.forEach((e) => {
    if (
      !dates.some(
        (d) => d.toDateString() === new Date(e.date + "T00:00").toDateString(),
      )
    )
      return;
    if (e.startTime)
      minHour = Math.min(minHour, parseInt(e.startTime.split(":")[0]));
    if (e.endTime)
      maxHour = Math.max(maxHour, Math.ceil(parseInt(e.endTime.split(":")[0]) + parseInt(e.endTime.split(":")[1]) / 60));
  });

  const START_HOUR = Math.max(0, minHour);
  const END_HOUR = Math.min(24, maxHour);
  const TOTAL_HOURS = END_HOUR - START_HOUR;
  const ROW_H = 52;
  const HEADER_H = 40;
  const GRID_H = TOTAL_HOURS * ROW_H;

  function toY(datetimeStr) {
    const d = new Date(datetimeStr);
    return (
      ((d.getHours() * 60 + d.getMinutes() - START_HOUR * 60) / 60) * ROW_H
    );
  }
  function durToH(v) {
    return (parseFloat(v) || 1) * ROW_H;
  }

  // 時間軸ラベル
  let axisHtml = "";
  for (let h = START_HOUR; h <= END_HOUR; h++) {
    axisHtml += `<div class="tt2-tick" style="top:${(h - START_HOUR) * ROW_H}px">${h}:00</div>`;
  }

  // 横線
  let linesHtml = "";
  for (let i = 0; i <= TOTAL_HOURS; i++) {
    linesHtml += `<div class="tt2-hour-line" style="top:${i * ROW_H}px"></div>`;
  }

  // 各曜日カラム
  let colsHtml = "";
  dates.forEach((d, i) => {
    const isToday = d.toDateString() === now.toDateString();

    const daySchedules = weekPlans
      .filter((s) => new Date(s.datetime).toDateString() === d.toDateString())
      .sort((a, b) => new Date(a.datetime) - new Date(b.datetime));

    const dayExams = weekExams.filter(
      (e) => new Date(e.date + "T00:00").toDateString() === d.toDateString(),
    );

    let blocksHtml = linesHtml;

    dayExams.forEach((e) => {
      const matchedSubj = subjects.find((s) => s.name === e.subject);
      const color = matchedSubj ? matchedSubj.color : "#657078";
      const startMinutes = e.startTime ? e.startTime.split(":").reduce((h, m) => Number(h) * 60 + Number(m)) : START_HOUR * 60;
      const endMinutes = e.endTime ? e.endTime.split(":").reduce((h, m) => Number(h) * 60 + Number(m)) : startMinutes + 60;
      const top = Math.max(0, (startMinutes - START_HOUR * 60) / 60 * ROW_H);
      const height = Math.max(56, (endMinutes - startMinutes) / 60 * ROW_H);
      blocksHtml += `<button type="button" class="tt2-block tt2-exam-block" data-exam-id="${esc(e.id)}" data-top="${top}" style="top:${top}px;min-height:${height}px;--subject-color:${color}">
        <span class="tt2-block-name">${esc(e.subject)}</span>
        <span class="tt2-block-time">試験 ${e.startTime || "時刻未指定"}${e.endTime ? "〜" + e.endTime : ""}</span>
      </button>`;
    });

    daySchedules.forEach((s) => {
      const subj = subjectById(s.subjectId);
      const top = Math.max(0, toY(s.datetime));
      const height = Math.max(56, durToH(s.duration));
      const sd = new Date(s.datetime);
      const timeStr = `${String(sd.getHours()).padStart(2, "0")}:${String(sd.getMinutes()).padStart(2, "0")}`;
      blocksHtml += `<button type="button" class="tt2-block" data-schedule-id="${esc(s.id)}" data-top="${top}" style="top:${top}px;min-height:${height}px;--subject-color:${subj.color}" title="${esc(s.content || "")}">
        <span class="tt2-block-name">${esc(subj.name)}</span>
        <span class="tt2-block-time">${timeStr} · ${durationText(s.duration)}${s.status === "done" ? " · 完了" : s.status === "miss" ? " · 未実施" : ""}</span>
      </button>`;
    });

    colsHtml += `<div class="tt2-col${isToday ? " tt2-today" : ""}">
      <div class="tt2-col-header${isToday ? " tt2-col-header-today" : ""}" style="height:${HEADER_H}px">${days[i]}<br><span>${d.getDate()}</span></div>
      <div class="tt2-col-body" style="height:${GRID_H}px">${blocksHtml}</div>
    </div>`;
  });

  document.getElementById("timetable-grid").innerHTML = `
    <div class="tt2-wrap" data-week="${StudyModel.localDate(base)}">
      <div class="tt2-axis" style="padding-top:${HEADER_H}px">
        <div class="tt2-axis-inner" style="position:relative;height:${GRID_H}px">${axisHtml}</div>
      </div>
      <div class="tt2-cols">${colsHtml}</div>
    </div>`;

  renderMobileDayList(base, now);
  applyTTMode();
  layoutTimetableBlocks();
  if (scrollPosition) {
    const wrap = document.querySelector("#timetable-grid .tt2-wrap");
    wrap.scrollTop = scrollPosition.top;
    wrap.scrollLeft = scrollPosition.left;
  }
}

// Keep each label readable without moving its start time. Expanded short events get
// separate horizontal lanes when they collide, including events of different types.
function layoutTimetableBlocks() {
  const grid = document.getElementById("timetable-grid");
  if (!grid || !grid.getClientRects().length) return;
  const columns = [...grid.querySelectorAll(".tt2-col")];
  columns.forEach((column) => {
    column.style.minWidth = "160px";
    const body = column.querySelector(".tt2-col-body");
    const blocks = [...body.querySelectorAll(".tt2-block")].sort((a, b) => Number(a.dataset.top) - Number(b.dataset.top));
    const lanes = [];
    blocks.forEach((block) => {
      block.style.width = "156px";
      block.style.right = "auto";
      const top = Number(block.dataset.top);
      const height = block.getBoundingClientRect().height;
      let lane = lanes.findIndex((end) => end + 4 <= top);
      if (lane < 0) lane = lanes.length;
      lanes[lane] = top + height;
      block.dataset.lane = String(lane);
      block.style.left = `${2 + lane * 160}px`;
      block.onclick = () => {
        if (block.dataset.examId) editExam(block.dataset.examId);
        else {
          const item = schedules.find((s) => s.id === block.dataset.scheduleId);
          if (item) openModal("schedule", item);
        }
      };
    });
    column.style.minWidth = `${Math.max(1, lanes.length) * 160}px`;
  });
  // Events at 23:59 still need space for the complete label below the last tick.
  const bodies = [...grid.querySelectorAll(".tt2-col-body")];
  const baseHeight = grid.querySelector(".tt2-axis-inner").offsetHeight;
  const bottom = Math.max(baseHeight, ...bodies.flatMap((body) => [...body.querySelectorAll(".tt2-block")].map((block) => Number(block.dataset.top) + block.offsetHeight + 8)));
  bodies.forEach((body) => body.style.height = `${bottom}px`);
}
document.fonts.ready.then(() => layoutTimetableBlocks());

function renderMobileDayList(base, now) {
  const {plans: weekPlans, exams: weekExams} = timetableData();
  const container = document.getElementById("mobile-day-list");
  const dates = Array.from({length:7}, (_, i) => { const d = new Date(base); d.setDate(base.getDate() + i); return StudyModel.localDate(d); });
  if (!dates.includes(selectedDay)) selectedDay = dates.includes(StudyModel.localDate(now)) ? StudyModel.localDate(now) : dates[0];
  const items = weekPlans.filter((s) => StudyModel.localDate(s.datetime) === selectedDay).sort((a, b) => new Date(a.datetime) - new Date(b.datetime));
  const sessions = weekExams.filter((e) => e.date === selectedDay);
  container.innerHTML = `<div class="day-selector" aria-label="表示する日">${dates.map((date) => {
    const d = new Date(date + "T00:00");
    const hasPlans = weekPlans.some(s=>StudyModel.localDate(s.datetime)===date) || weekExams.some(e=>e.date===date);
    return `<button class="${date === selectedDay ? "selected" : ""}" aria-pressed="${date === selectedDay}" onclick="selectDay('${date}')"><span>${d.toLocaleDateString("ja-JP", {weekday:"short"})}</span><strong>${d.getDate()}</strong><i class="day-mark ${hasPlans ? 'has-plans' : ''}" aria-hidden="true"></i></button>`;
  }).join("")}</div><div class="section-header"><h2 class="section-title">${fmtDate(selectedDay)}の予定</h2><button class="btn btn-sm" onclick="openModal('schedule',{datetime:'${selectedDay}T17:00'})">＋ 追加</button></div>${sessions.map(examSessionCard).join("")}${items.map(buildScheduleCard).join("")}${!items.length && !sessions.length ? '<p class="empty-state">この日の予定はありません。</p>' : ""}`;
}
function selectDay(date) { selectedDay = date; renderTimetable(); }
function examSessionCard(e) {
  const checklist = StudyModel.checklistTotals(e.checklist);
  return `<article class="exam-session"><div><span class="muted">${esc(groupName(e.examGroupId))}</span><h3>${esc(e.subject)}</h3><p>${fmtDate(e.date)} ${esc(e.startTime || "")}${e.endTime ? "〜" + esc(e.endTime) : ""}</p>${e.note ? `<p class="muted">${esc(e.note)}</p>` : ""}<button type="button" class="btn" data-checklist-summary="${e.id}" onclick="openChecklist('${e.id}')">範囲チェックリスト · ${checklist.done} / ${checklist.count}件 完了</button></div><button class="btn btn-sm" onclick="editExam('${e.id}')">編集</button></article>`;
}
let checklistExamId = '';
let checklistFilter = 'all';
let checklistEditingId = '';
let checklistDraftTitle = '';
function openChecklist(id) {
  const exam = exams.find(item => item.id === id);
  if (!exam) return;
  checklistExamId = id; checklistFilter = 'all'; checklistEditingId = '';
  modalTrigger = document.activeElement;
  document.getElementById('modal-content').classList.add('checklist-modal');
  document.getElementById('modal-body').innerHTML = `<div class="modal-header checklist-header"><h2 id="modal-heading">試験範囲チェックリスト</h2><button type="button" class="btn" onclick="closeModal()">閉じる</button></div><div class="checklist-overview"><p class="checklist-context">${esc(groupName(exam.examGroupId))} · ${esc(exam.subject)}</p><p id="checklist-summary" aria-live="polite"></p><progress id="checklist-progress" aria-label="試験範囲の完了率"></progress><div class="checklist-filters" role="group" aria-label="表示する範囲">${[['all','すべて'],['remaining','未完了'],['review','要復習']].map(([key,label]) => `<button type="button" data-checklist-filter="${key}" onclick="setChecklistFilter('${key}')">${label}</button>`).join('')}</div><p id="checklist-error" role="alert" hidden></p></div><div class="checklist-scroll" id="checklist-scroll">${exam.note ? `<details class="checklist-range-note"><summary>範囲メモ</summary><p>${esc(exam.note)}</p></details>` : ''}<details id="checklist-add-panel" class="checklist-add-panel" ${exam.checklist?.length ? '' : 'open'}><summary>範囲を追加</summary><form onsubmit="event.preventDefault();addChecklistItems()"><label class="form-group">範囲を追加（1行1項目）<textarea id="checklist-add" rows="3" placeholder="問題集 p.20〜25&#10;問題集 p.26〜30"></textarea></label><p class="muted">単元・ページを1行ずつ入力すると、まとめて追加できます。</p><button type="submit" class="btn btn-primary">追加する</button></form></details><div id="checklist-items"></div></div>`;
  renderChecklistItems(); activateModal();
}
function checklistError(message) {
  const error = document.getElementById('checklist-error');
  if (error) { error.textContent = message; error.hidden = !message; }
}
function saveChecklist(exam) {
  save('sl_exams', exams);
  const total = StudyModel.checklistTotals(exam.checklist);
  document.querySelectorAll(`[data-checklist-summary="${exam.id}"]`).forEach(button => { button.textContent = `範囲チェックリスト · ${total.done} / ${total.count}件 完了`; });
}
function setChecklistFilter(filter) {
  if (!['all','remaining','review'].includes(filter)) return;
  checklistFilter = filter; checklistEditingId = ''; checklistError(''); renderChecklistItems();
  document.getElementById('checklist-scroll').scrollTop = 0;
}
function renderChecklistItems() {
  const exam = exams.find(item => item.id === checklistExamId);
  if (!exam || !document.getElementById('checklist-items')) return;
  const items = exam.checklist || [], total = StudyModel.checklistTotals(items), remaining = total.count - total.done;
  document.getElementById('checklist-summary').textContent = `全${total.count}件 · 完了${total.done}件 · 未完了${remaining}件 · 要復習${total.review}件`;
  document.getElementById('checklist-progress').max = total.count || 1;
  document.getElementById('checklist-progress').value = total.done;
  document.querySelectorAll('[data-checklist-filter]').forEach(button => {
    const key=button.dataset.checklistFilter;
    button.setAttribute('aria-pressed',String(checklistFilter===key));
    button.textContent = `${key==='all'?'すべて':key==='remaining'?'未完了':'要復習'}（${key==='all'?total.count:key==='remaining'?remaining:total.review}）`;
  });
  const visible = items.filter(item => checklistFilter === 'all' || checklistFilter === 'remaining' && item.status !== 'done' || checklistFilter === 'review' && item.status === 'review');
  document.getElementById('checklist-items').innerHTML = visible.length ? visible.map(item => `<article class="checklist-item" data-item-id="${item.id}"><div class="checklist-line"><label class="checklist-toggle"><input type="checkbox" aria-label="${esc(item.title)}を完了にする" ${item.status==='done'?'checked':''} onchange="setChecklistStatus('${item.id}',this.checked?'done':'pending')"></label><button type="button" class="checklist-title" aria-expanded="${checklistEditingId===item.id}" aria-controls="checklist-detail-${item.id}" onclick="openChecklistItem('${item.id}')">${esc(item.title)}</button><select class="checklist-status" aria-label="${esc(item.title)}の状況" onchange="setChecklistStatus('${item.id}',this.value)">${StudyModel.checklistStatuses.map(([key,label]) => `<option value="${key}" ${item.status===key?'selected':''}>${label}</option>`).join('')}</select></div>${checklistEditingId===item.id ? `<div class="checklist-detail" id="checklist-detail-${item.id}"><label class="form-group">範囲の名前<input type="text" value="${esc(checklistDraftTitle)}" maxlength="300" oninput="checklistDraftTitle=this.value"></label><div class="checklist-detail-actions"><button type="button" class="btn btn-primary" onclick="saveChecklistItemName('${item.id}')">保存</button><button type="button" class="btn" onclick="openChecklistItem('${item.id}')">キャンセル</button><button type="button" class="btn btn-quiet" onclick="deleteChecklistItem('${item.id}')">削除</button></div></div>` : ''}</article>`).join('') : `<p class="empty-state">${items.length ? 'この状況の項目はありません。' : 'まだ範囲がありません。問題集のページや単元を追加してください。'}</p>`;
}
function openChecklistItem(id) {
  const item = exams.find(exam=>exam.id===checklistExamId)?.checklist?.find(item=>item.id===id);
  if (!item) return;
  if (checklistEditingId===id) checklistEditingId='';
  else { checklistEditingId=id; checklistDraftTitle=item.title; }
  checklistError(''); renderChecklistItems();
  document.querySelector(`[data-item-id="${id}"] ${checklistEditingId ? 'input[type=text]' : '.checklist-title'}`)?.focus({preventScroll:true});
}
function saveChecklistItemName(id) {
  if (!renameChecklistItem(id,checklistDraftTitle)) return;
  checklistEditingId=''; renderChecklistItems();
  document.querySelector(`[data-item-id="${id}"] .checklist-title`)?.focus({preventScroll:true});
}
function addChecklistItems() {
  const exam=exams.find(item=>item.id===checklistExamId); if(!exam) return;
  const input=document.getElementById('checklist-add'),titles=input.value.split(/\r?\n/).map(title=>title.trim()).filter(Boolean);
  if(!titles.length || titles.some(title=>title.length>300)) return checklistError('範囲を1行ずつ入力してください（1行300文字まで）。');
  if((exam.checklist?.length || 0)+titles.length>200) return checklistError('チェックリストは200件までです。');
  exam.checklist=[...(exam.checklist || []),...titles.map(title=>({id:uid(),title,status:'pending'}))];
  saveChecklist(exam);input.value='';checklistError('');renderChecklistItems();document.getElementById('checklist-add-panel').open=false;
  document.querySelector('[data-checklist-filter="all"]').focus({preventScroll:true});
}
function setChecklistStatus(id,status) {
  if(!StudyModel.checklistStatuses.some(item=>item[0]===status)) return;
  const exam=exams.find(item=>item.id===checklistExamId),item=exam?.checklist?.find(item=>item.id===id);if(!item) return;
  const checkbox=document.activeElement?.type==='checkbox';
  item.status=status;saveChecklist(exam);renderChecklistItems();
  const target=document.querySelector(`[data-item-id="${id}"] ${checkbox?'input[type=checkbox]':'select'}`) || document.querySelector(`[data-checklist-filter="${checklistFilter}"]`);
  target?.focus({preventScroll:true});
}
function renameChecklistItem(id,title) {
  const exam=exams.find(item=>item.id===checklistExamId),item=exam?.checklist?.find(item=>item.id===id);if(!item) return false;
  if(!title.trim() || title.trim().length>300){checklistError('範囲の名前を確認してください。');return false;}
  item.title=title.trim();saveChecklist(exam);checklistError('');return true;
}
function deleteChecklistItem(id) {
  const exam=exams.find(item=>item.id===checklistExamId);if(!exam) return;
  const index=exam.checklist?.findIndex(item=>item.id===id) ?? -1;if(index<0) return;
  const [previous]=exam.checklist.splice(index,1);checklistEditingId='';saveChecklist(exam);renderChecklistItems();
  document.querySelector(`[data-checklist-filter="${checklistFilter}"]`)?.focus({preventScroll:true});
  toast('範囲を削除しました',()=>{
    const current=exams.find(item=>item.id===exam.id);if(!current || current.checklist?.some(item=>item.id===id)) return;
    current.checklist ||= [];if(current.checklist.length>=200) return;
    current.checklist.splice(Math.min(index,current.checklist.length),0,previous);saveChecklist(current);
    if(checklistExamId===current.id) renderChecklistItems();
  });
}
function renderExams() {
  const html = [...exams].sort((a,b) => a.date.localeCompare(b.date)).map(examSessionCard).join("") || '<p class="muted">教科別の日程はまだありません。試験ごとの管理から登録できます。</p>';
  for (const id of ["exam-grid", "exam-grid-settings"]) { const el = document.getElementById(id); if (el) el.innerHTML = html; }
}
function renderExamGroups() {
  const yearEl = document.getElementById("exam-year-filter"), current = yearEl.value;
  const years = [...new Set(examGroups.map((g) => g.startDate.slice(0,4)))].sort().reverse();
  yearEl.innerHTML = '<option value="">すべての年</option>' + years.map((y) => `<option ${current === y ? "selected" : ""}>${y}</option>`).join("");
  const today = StudyModel.localDate();
  const groups = [...examGroups].filter((g) => !yearEl.value || g.startDate.startsWith(yearEl.value)).sort((a,b) => (a.endDate < today) - (b.endDate < today) || (a.endDate < today ? b.startDate.localeCompare(a.startDate) : a.startDate.localeCompare(b.startDate)));
  document.getElementById("exam-groups-list").innerHTML = groups.length ? groups.map((g) => {
    const total = StudyModel.totals(StudyModel.forExam(schedules, g.id));
    const sessions = exams.filter((e) => e.examGroupId === g.id).sort((a,b) => (a.date + a.startTime).localeCompare(b.date + b.startTime));
    return `<article class="exam-group-card"><div class="exam-group-top"><span class="muted">${esc(g.type)} · ${g.startDate.slice(0,4)}年</span><span class="badge ${g.endDate < today ? "badge-pending" : "badge-done"}">${g.endDate < today ? "終了" : g.startDate <= today ? "実施中" : countdownText(daysUntil(g.startDate))}</span></div><h2>${esc(g.name)}</h2><p class="muted">${fmtDate(g.startDate)}${g.startDate !== g.endDate ? "〜" + fmtDate(g.endDate) : ""}</p><div class="exam-metrics"><div><span>準備の実績</span><strong>${durationText(total.actual)}</strong></div><div><span>予定時間</span><strong>${durationText(total.planned)}</strong></div><div><span>学習予定</span><strong>${total.done} / ${total.count}件</strong></div></div><div class="exam-group-actions"><button class="btn btn-tonal" onclick="viewExamSchedules('${g.id}')">学習予定</button><button class="btn" onclick="viewExamHours('${g.id}')">統計</button><button class="btn btn-quiet" onclick="openModal('examgroup',examGroups.find(g=>g.id==='${g.id}'))">編集</button></div><details class="exam-sessions"><summary>教科別の日程 (${sessions.length}件)</summary>${sessions.map(examSessionCard).join("")}<button class="btn" onclick="openModal('exam',{examGroupId:'${g.id}',date:'${g.startDate}'})">＋ 教科の日程</button></details></article>`;
  }).join("") : emptyState("考査・模試を1回ずつ登録して、準備を分けて管理しよう。", "試験を登録", "openModal('examgroup')");
  const unassigned = exams.filter((e) => !e.examGroupId), studyCount = schedules.filter((s) => !s.examGroupId).length;
  document.getElementById("unassigned-exams").innerHTML = `<h2 class="section-title">試験未指定の記録</h2><p class="muted">学習 ${studyCount}件 / 教科別日程 ${unassigned.length}件。以前の記録はそのまま残しています。編集画面で対象の試験を選べます。</p><button class="btn" onclick="viewExamSchedules('unassigned')">未指定の学習を見る</button>${unassigned.length ? `<details class="exam-sessions"><summary>未指定の日程を見る</summary>${unassigned.map(examSessionCard).join("")}</details>` : ""}`;
}
function viewExamHours(id) {
  populateExamFilters();
  document.getElementById("hours-exam-filter").value = id;
  document.getElementById("hours-period-preset").value = "all";
  document.getElementById("hours-custom-range").style.display = "none";
  showView("hours");
}
function viewExamSchedules(id) {
  populateExamFilters();
  document.getElementById("filter-exam").value = id;
  document.getElementById("filter-subject").value = "";
  document.getElementById("filter-status").value = "";
  showView("schedule");
}

// ── Subjects (with drag reorder) ──────────────────────
let dragSrcIdx = null;

function renderSubjects() {
  function buildRows() {
    return subjects.length
      ? subjects
          .map((s, i) => {
            const planned = schedules
              .filter((sc) => sc.subjectId === s.id)
              .reduce((a, b) => a + (parseFloat(b.duration) || 0), 0);
            const actual = StudyModel.totals(schedules.filter((sc) => sc.subjectId === s.id)).actual;
            return `<tr draggable="true" data-idx="${i}" ondragstart="onDragStart(event,${i})" ondragover="onDragOver(event,${i})" ondrop="onDrop(event,${i})" ondragleave="onDragLeave(event)" ondragend="onDragEnd(event)">
      <td data-label="並び順"><div class="subject-reorder"><button class="btn btn-sm" aria-label="${esc(s.name)}を上へ" onclick="moveSubject(${i},-1)" ${i === 0 ? "disabled" : ""}>↑</button><button class="btn btn-sm" aria-label="${esc(s.name)}を下へ" onclick="moveSubject(${i},1)" ${i === subjects.length-1 ? "disabled" : ""}>↓</button></div></td>
      <td data-label="カラー"><div style="width:20px;height:20px;border-radius:50%;background:${s.color};display:inline-block"></div></td>
      <td data-label="教科名"><strong>${esc(s.name)}</strong></td>
      <td data-label="予定時間">${durationText(planned)}</td>
      <td data-label="実績時間">${durationText(actual)}</td>
      <td data-label="操作">
        <button class="btn btn-sm" onclick="editSubject('${s.id}')">編集</button>
        <button class="btn btn-sm btn-danger" onclick="deleteSubject('${s.id}')">削除</button>
      </td>
    </tr>`;
          })
          .join("")
      : `<tr class="empty-row"><td colspan="6" class="empty-cell"><div class="empty"><p>教科がありません</p></div></td></tr>`;
  }
  const cards = document.getElementById("subjects-list");
  if (cards) cards.innerHTML = subjects.length ? subjects.map((s,i) => `<article class="subject-row"><span class="subject-dot" style="background:${s.color}"></span><strong>${esc(s.name)}</strong><div class="subject-row-actions"><button class="btn btn-quiet" aria-label="${esc(s.name)}を上へ" onclick="moveSubject(${i},-1)" ${i===0?'disabled':''}>上</button><button class="btn btn-quiet" aria-label="${esc(s.name)}を下へ" onclick="moveSubject(${i},1)" ${i===subjects.length-1?'disabled':''}>下</button><button class="icon-button" aria-label="${esc(s.name)}を編集" onclick="editSubject('${s.id}')">${appIcon('edit')}</button></div></article>`).join('') : '<p class="muted">教科がありません。追加から登録してください。</p>';
  const rows = buildRows();
  const t1 = document.getElementById("subjects-table");
  if (t1) t1.innerHTML = rows;
  const t2 = document.getElementById("subjects-table-settings");
  if (t2) t2.innerHTML = rows;
}

function onDragStart(e, idx) {
  dragSrcIdx = idx;
  e.currentTarget.classList.add("dragging");
}
function onDragOver(e, idx) {
  e.preventDefault();
  if (idx !== dragSrcIdx) e.currentTarget.classList.add("drag-over");
}
function onDragLeave(e) {
  e.currentTarget.classList.remove("drag-over");
}
function onDragEnd(e) {
  e.currentTarget.classList.remove("dragging");
  document
    .querySelectorAll("tr.drag-over")
    .forEach((r) => r.classList.remove("drag-over"));
}
function onDrop(e, toIdx) {
  e.preventDefault();
  if (dragSrcIdx === null || dragSrcIdx === toIdx) return;
  const moved = subjects.splice(dragSrcIdx, 1)[0];
  subjects.splice(toIdx, 0, moved);
  save("sl_subjects", subjects);
  dragSrcIdx = null;
  render();
}

// ── Hours ─────────────────────────────────────────────
let hoursPeriodOffset = 0;
let heatmapYear = new Date().getFullYear();
function shiftHoursPeriod(offset) { hoursPeriodOffset += offset; renderHours(); }
function shiftHeatmapYear(offset) { heatmapYear += offset; renderHours(); }
function setHoursMode(mode) {
  hoursMode = mode;
  document
    .querySelectorAll(".hours-toggle-btn")
    .forEach((b) => b.classList.remove("active"));
  document.getElementById("hours-btn-" + mode).classList.add("active");
  renderHours();
}

function onPeriodPresetChange() {
  hoursPeriodOffset = 0;
  const val = document.getElementById("hours-period-preset").value;
  const wrap = document.getElementById("hours-custom-range");
  wrap.style.display = val === "custom" ? "flex" : "none";
  renderHours();
}

function renderHours() {
  const groupId = document.getElementById("hours-exam-filter").value;
  const range = getPeriodRange();
  const invalidRange = range.from && range.to && range.from >= range.to;
  const inRange = invalidRange ? [] : filterByPeriod(StudyModel.forExam(schedules, groupId), range);
  const total = StudyModel.totals(inRange);
  const navigable = ['week', 'month', 'year'].includes(document.getElementById('hours-period-preset').value);
  document.getElementById('hours-period-nav').hidden = !navigable;
  document.getElementById('hours-period-label').textContent = navigable ? `${StudyModel.localDate(range.from)} 〜 ${StudyModel.localDate(new Date(range.to.getTime() - 1))}` : '';
  document.getElementById("hours-context").textContent = invalidRange ? "終了日は開始日以降を選んでください。" : `${groupId ? groupId === "unassigned" ? "試験未指定の学習" : groupName(groupId) : "すべての学習"} · ${inRange.length}件の記録（対象の試験と期間の両方で絞り込み）`;
  document.getElementById("hours-stats").innerHTML = `<div class="stat-card"><div class="stat-label">勉強した時間</div><div class="stat-value">${durationText(total.actual)}</div></div><div class="stat-card"><div class="stat-label">予定時間</div><div class="stat-value">${durationText(total.planned)}</div></div><div class="stat-card"><div class="stat-label">完了した予定</div><div class="stat-value">${total.done}<span class="stat-unit"> / ${total.count}件</span></div></div>`;
  const rows = subjects.map((subject) => ({ subject, ...StudyModel.totals(inRange.filter((s) => s.subjectId === subject.id)) }));
  const unknown = inRange.filter((s) => !subjects.some((subject) => subject.id === s.subjectId));
  if (unknown.length) rows.push({ subject: {name:"削除された教科", color:"#68736c"}, ...StudyModel.totals(unknown) });
  const max = Math.max(...rows.map((r) => Math.max(r.actual, r.planned)), 0.1);
  document.getElementById("hours-list").innerHTML = inRange.length ? rows.map((r) => `<div class="hours-row"><div class="hours-row-title"><strong>${esc(r.subject.name)}</strong><span>${r.count}件</span></div>${hoursMode !== "planned" ? `<div class="hours-measure"><span>実績</span><div class="hours-track"><div style="width:${r.actual/max*100}%;background:var(--accent)"></div></div><strong>${durationText(r.actual)}</strong></div>` : ""}${hoursMode !== "actual" ? `<div class="hours-measure"><span>予定</span><div class="hours-track"><div style="width:${r.planned/max*100}%;background:var(--border2)"></div></div><strong>${durationText(r.planned)}</strong></div>` : ""}</div>`).join("") : '<p class="empty-state">この条件の学習記録はまだありません。</p>';
  renderTimeHistory(inRange, range, invalidRange);
}

function renderTimeHistory(items, range, invalid) {
  const unit = document.getElementById('hours-bucket').value;
  const earliest = items.reduce((date, s) => Math.min(date, new Date(s.datetime).getTime()), Infinity);
  const latest = items.reduce((date, s) => Math.max(date, new Date(s.datetime).getTime()), Date.now());
  const from = range.from || (items.length ? new Date(earliest) : new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  from.setHours(0, 0, 0, 0);
  const to = range.to || new Date(latest);
  if (!range.to) { to.setHours(0, 0, 0, 0); to.setDate(to.getDate() + 1); }
  const allBuckets = invalid ? [] : StudyModel.timeBuckets(items, from, to, unit);
  const buckets = allBuckets.slice(-366);
  const max = Math.max(1, ...buckets.map(b => Math.max(b.actual, b.planned)));
  const label = b => unit === 'month' ? b.date.slice(0, 7) : b.date.slice(5).replace('-', '/') + (unit === 'week' ? '週' : '');
  document.getElementById('hours-chart-range').textContent = buckets.length ? `${buckets[0].date} 〜 ${StudyModel.localDate(new Date(to.getTime() - 1))}（最大366区間）` : '表示できる期間がありません';
  document.getElementById('hours-chart').innerHTML = buckets.map(b => `<button type="button" class="history-column" title="${b.date} 実績 ${durationText(b.actual)}・予定 ${durationText(b.planned)}" aria-label="${b.date} 実績 ${durationText(b.actual)}・予定 ${durationText(b.planned)}" onclick="showHistoryDetail('${b.date}',${b.actual},${b.planned})"><span class="history-bars"><i class="history-actual" style="height:${b.actual / max * 100}%"></i><i class="history-planned" style="height:${b.planned / max * 100}%"></i></span><span>${label(b)}</span></button>`).join('');
  document.getElementById('hours-history-detail').textContent = '棒や日付のマスを選ぶと、勉強時間を確認できます。';
  document.getElementById('hours-chart-table').innerHTML = `<table><caption>時間の推移（単位：時間）</caption><thead><tr><th scope="col">期間の開始日</th><th scope="col">実績</th><th scope="col">予定</th></tr></thead><tbody>${buckets.map(b => `<tr><th scope="row">${b.date}</th><td>${durationText(b.actual)}</td><td>${durationText(b.planned)}</td></tr>`).join('')}</tbody></table>`;
  const days = StudyModel.dailyTotals(items);
  const rolling = document.getElementById('hours-heat-mode').value === 'rolling';
  const {from: first, to: last, start, end} = StudyModel.activityRange(new Date(), rolling ? null : heatmapYear);
  let cells = '', months = '', active = 0, hours = 0, index = 0;
  for (let date = new Date(start); date < end; date.setDate(date.getDate() + 1)) {
    const column = Math.floor(index++ / 7) + 1;
    if (date >= first && date < last && (date.getDate() === 1 || date.getTime() === first.getTime())) months += `<span style="grid-column:${column}">${date.getMonth() + 1}月</span>`;
    const key = StudyModel.localDate(date), total = days.get(key) || {actual: 0, planned: 0};
    if (date < first || date >= last) { cells += '<span class="heat-cell outside"></span>'; continue; }
    const excluded = invalid || range.from && date < range.from || range.to && date >= range.to;
    if (!excluded) { if (total.actual > 0) active++; hours += total.actual; }
    cells += `<button type="button" class="heat-cell level-${StudyModel.heatLevel(total.actual)}${excluded ? ' excluded' : ''}" ${excluded ? 'disabled' : ''} title="${key} ${excluded ? '集計期間外' : durationText(total.actual)}" aria-label="${key} ${excluded ? '集計期間外' : '実績 ' + durationText(total.actual)}" onclick="showHistoryDetail('${key}',${total.actual},${total.planned})"></button>`;
  }
  document.getElementById('hours-heat-year').textContent = `${heatmapYear}年`;
  document.getElementById('hours-heat-nav').hidden = rolling;
  document.getElementById('hours-heatmap').innerHTML = cells;
  const heatScroll = document.querySelector('.heat-scroll');
  const weeks = index / 7;
  const available = heatScroll.clientWidth - 36;
  const cellSize = available >= weeks * 10 + (weeks - 1) * 4 ? Math.min(16, Math.floor((available - (weeks - 1) * 4) / weeks)) : 14;
  heatScroll.style.setProperty('--heat-size', `${cellSize}px`);
  document.getElementById('hours-heat-months').style.gridTemplateColumns = `repeat(${weeks},var(--heat-size,14px))`;
  document.getElementById('hours-heat-months').innerHTML = months;
  heatScroll.scrollLeft = rolling ? heatScroll.scrollWidth : 0;
  document.getElementById('hours-heat-summary').textContent = `${rolling ? '直近1年' : heatmapYear + '年'} · 勉強した日 ${active}日 · 実績 ${durationText(hours)}（選択中の試験・期間）`;
}
function showHistoryDetail(date, actual, planned) {
  document.getElementById('hours-history-detail').textContent = `${date} · 実績 ${durationText(actual)} / 予定 ${durationText(planned)}`;
}

// ── Focus mode ────────────────────────────────────────
let focusTimer = null;
let focusStart = null;
let focusPaused = false;
let focusPauseAt = null;
let focusElapsed = 0; // seconds already elapsed (before current segment)
let focusTargetSec = 25 * 60;
let focusSubjectId = "";
let focusSchedId = "";

function renderFocusSetup() {
  const subject = document.getElementById("focus-subject"), previous = subject.value;
  subject.innerHTML = subjects.map((s) => `<option value="${s.id}" ${s.id === previous ? "selected" : ""}>${esc(s.name)}</option>`).join("");
  const link = document.getElementById("focus-schedule-link"), cur = link.value;
  link.innerHTML = '<option value="">予定に紐付けずに記録</option>' + [...schedules].filter((s) => (!s.status || ["pending", "partial"].includes(s.status)) || s.id === cur).sort((a,b) => new Date(a.datetime) - new Date(b.datetime)).map((s) => `<option value="${s.id}" ${cur === s.id ? "selected" : ""}>${fmt(s.datetime)} ${esc(subjectById(s.subjectId).name)} ${esc(s.content || "")}</option>`).join("");
  const group = document.getElementById("focus-exam-group");
  group.innerHTML = groupOptions(group.value);
  syncFocusLink();
}
function syncFocusLink() {
  const s = schedules.find((s) => s.id === document.getElementById("focus-schedule-link").value);
  const subject = document.getElementById("focus-subject"), group = document.getElementById("focus-exam-group");
  subject.disabled = group.disabled = !!s;
  if (s) { subject.value = s.subjectId; group.value = s.examGroupId || ""; }
}
function startScheduleFocus(id) {
  if (document.getElementById("focus-active").style.display !== "none") { showView("focus"); toast("実行中のタイマーを終了してから次の勉強を始めてください"); return; }
  const s = schedules.find((s) => s.id === id);
  if (!s) return;
  showView("focus");
  document.getElementById("focus-schedule-link").value = id;
  document.getElementById("focus-minutes").value = Math.min(1440, Math.max(1, Math.round(Number(s.duration) * 60)));
  syncFocusLink();
  startFocus();
}
function setFocusTimer(min) {
  document.getElementById("focus-minutes").value = min;
  document.querySelectorAll(".focus-preset-btn").forEach((b) => b.classList.toggle("active", b.textContent === `${min}分`));
}

function updateFocusMinutes() {
  document
    .querySelectorAll(".focus-preset-btn")
    .forEach((b) => b.classList.remove("active"));
}

function startFocus() {
  if (typeof unlockTimerSound === "function") unlockTimerSound();
  if (document.getElementById("focus-active").style.display !== "none") return;
  const minutes = Number(document.getElementById("focus-minutes").value);
  if (!subjects.some((s) => s.id === document.getElementById("focus-subject").value)) { toast("先に「その他 → 教科」で教科を登録してください"); return; }
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) { toast("タイマーは1〜1440分で設定してください"); return; }
  syncFocusLink();
  focusExamGroupId = document.getElementById("focus-exam-group").value;
  focusSessionStart = Date.now();
  focusFinishing = false;
  focusSubjectId = document.getElementById("focus-subject").value;
  focusSchedId = document.getElementById("focus-schedule-link").value;
  focusTargetSec =
    (parseInt(document.getElementById("focus-minutes").value) || 25) * 60;
  focusElapsed = 0;
  focusPaused = false;
  focusStart = performance.now();

  document.getElementById("focus-setup").style.display = "none";
  document.getElementById("focus-active").style.display = "flex";
  const subj = subjectById(focusSubjectId);
  document.getElementById("focus-active-subject").textContent = subj.name;

  clearInterval(focusTimer);
  focusTimer = setInterval(tickFocus, 100);
  tickFocus();

  document.body.classList.add("timer-running");

  // Try to prevent screen sleep
  if ("wakeLock" in navigator) {
    navigator.wakeLock.request("screen").catch(() => {});
  }
}

function tickFocus() {
  const nowElapsed = focusPaused
    ? focusElapsed
    : focusElapsed + (performance.now() - focusStart) / 1000;
  const remainingExact = Math.max(0, focusTargetSec - nowElapsed);
  const remaining = Math.ceil(remainingExact);
  const mm = String(Math.floor(remaining / 60)).padStart(2, "0");
  const ss = String(remaining % 60).padStart(2, "0");
  const display = `${mm}:${ss}`;
  const progress = 1 - remainingExact / focusTargetSec;
  const circumference = 339.3;
  const offset = circumference * (1 - progress);

  const text = (id, value) => { const element = document.getElementById(id); if (element && element.textContent !== value) element.textContent = value; };
  text("focus-timer-display", display);
  document.getElementById("running-focus").hidden = false;
  text("running-focus-time", display);
  text("running-focus-label", focusPaused ? "一時停止中" : subjectById(focusSubjectId).name);
  text("focus-elapsed", `経過: ${Math.floor(nowElapsed / 60)}分`);
  const ring = document.getElementById("focus-ring");
  if (ring) ring.setAttribute("stroke-dashoffset", offset);

  // Fullscreen display
  text("focus-fs-timer", display);
  text("focus-fs-elapsed", `経過: ${Math.floor(nowElapsed / 60)}分`);

  if (remainingExact <= 0) {
    clearInterval(focusTimer);
    finishFocusAuto(nowElapsed);
  }
}

function pauseFocus() {
  if (document.getElementById("focus-active").style.display === "none") return;
  const btn = document.getElementById("focus-pause-btn");
  const btnFs = document.getElementById("focus-fs-pause-btn");

  if (focusPaused) {
    // 再開
    focusStart = performance.now();
    focusPaused = false;
    focusTimer = setInterval(tickFocus, 100);
    [btn, btnFs].forEach((b) => {
      if (!b) return;
      b.textContent = "一時停止";
      b.classList.remove("btn-resuming");
    });
  } else {
    // 一時停止
    focusElapsed += (performance.now() - focusStart) / 1000;
    focusPaused = true;
    clearInterval(focusTimer);
    [btn, btnFs].forEach((b) => {
      if (!b) return;
      b.textContent = "再開";
      b.classList.add("btn-resuming");
    });
  }
  tickFocus();
}

function stopFocus() {
  if (document.getElementById("focus-active").style.display === "none") return;
  const nowElapsed = focusPaused
    ? focusElapsed
    : focusElapsed + (performance.now() - focusStart) / 1000;
  clearInterval(focusTimer);
  exitFullscreenOverlay();
  recordFocusSession(nowElapsed);
}

function finishFocusAuto(elapsed) {
  if (focusFinishing) return;
  focusFinishing = true;
  notifyTimerEnd(subjectById(focusSubjectId).name);
  exitFullscreenOverlay();
  recordFocusSession(Math.min(elapsed, focusTargetSec));
}

function recordFocusSession(elapsedSec) {
  const hrs = Math.max(0, Math.min(elapsedSec, focusTargetSec)) / 3600;
  const s = focusSchedId ? schedules.find((item) => item.id === focusSchedId) : null;
  if (s) {
      s.actualDuration = Math.round((StudyModel.actualHours(s) + hrs) * 100) / 100;
      s.status = s.actualDuration + 0.001 >= Number(s.duration) ? "done" : "partial";
  } else schedules.push({ id:uid(), subjectId:focusSubjectId, examGroupId:focusExamGroupId || undefined, datetime:new Date(focusSessionStart).toISOString(), duration:hrs, actualDuration:hrs, content:"集中モードで記録", note:"", status:"done" });
  save("sl_schedules", schedules);
  focusTimer = null;
  document.body.classList.remove("timer-running");
  document.getElementById("running-focus").hidden = true;
  document.getElementById("focus-setup").style.display = "";
  document.getElementById("focus-active").style.display = "none";
  for (const id of ["focus-pause-btn", "focus-fs-pause-btn"]) {
    document.getElementById(id).textContent = "一時停止";
    document.getElementById(id).classList.remove("btn-resuming");
  }
  document.getElementById("focus-schedule-link").value = "";
  render();
  toast(`勉強を記録しました · ${durationText(hrs)}`);
}

function enterFullscreen() {
  const subj = subjectById(focusSubjectId);
  document.getElementById("focus-fs-subject").textContent = subj.name;
  document.getElementById("focus-fullscreen-overlay").style.display = "flex";
  // Request browser fullscreen if available
  const el = document.getElementById("focus-fullscreen-overlay");
  if (el.requestFullscreen) el.requestFullscreen().catch(() => {});
}

function exitFullscreenOverlay(e) {
  if (e && e.target !== document.getElementById("focus-fullscreen-overlay"))
    return;
  document.getElementById("focus-fullscreen-overlay").style.display = "none";
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

// ── Data ──────────────────────────────────────────────
function renderData() {
  const el = document.getElementById("data-stats");
  if (!el) return;
  el.innerHTML = `
    <div class="stat-card"><div class="stat-label">教科</div><div class="stat-value">${subjects.length}<span class="stat-unit"> 件</span></div></div>
    <div class="stat-card"><div class="stat-label">スケジュール</div><div class="stat-value">${schedules.length}<span class="stat-unit"> 件</span></div></div>
    <div class="stat-card"><div class="stat-label">考査・模試</div><div class="stat-value">${examGroups.length}<span class="stat-unit"> 件</span></div></div>
  `;
}

function exportData() {
  const payload = {
    version: exams.some(exam => exam.checklist?.length) ? 4 : 3,
    app: APP_NAME,
    exportedAt: new Date().toISOString(),
    web: typeof webSound !== "undefined" ? {sound: webSound, scheduledFocus: typeof scheduledFocusEnabled !== "undefined" && scheduledFocusEnabled} : {},
    android: load("sl_android_backup", undefined),
    subjects,
    schedules,
    exams,
    examGroups,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `studycopi-backup-${StudyModel.localDate()}.json`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  if (typeof markBackupExported === "function") markBackupExported();
}

function importData(input) {
  const file = input.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = (event) => {
    try {
      const raw = JSON.parse(event.target.result);
      const data = StudyModel.validateBackup(raw);
      const sound = typeof validateWebSound === "function" ? validateWebSound(raw.web?.sound) : null;
      const focusMode = typeof validateScheduledFocus === "function" ? validateScheduledFocus(raw.web?.scheduledFocus) : false;
      if (!confirm("現在のデータを上書きして復元します。よろしいですか？")) return;
      if (document.getElementById("focus-active").style.display !== "none") { toast("タイマーを終了してから復元してください"); return; }
      subjects = data.subjects; schedules = data.schedules; exams = data.exams; examGroups = data.examGroups;
      save("sl_subjects", subjects); save("sl_schedules", schedules); save("sl_exams", exams); save("sl_exam_groups", examGroups);
      if (typeof setScheduledFocus === "function") { closeScheduledFocus(); setScheduledFocus(focusMode); }
      if (sound) { webSound = sound; save("sl_sound", sound); loadWebSoundUI(); }
      if (raw.android && typeof raw.android === "object" && !Array.isArray(raw.android)) save("sl_android_backup", raw.android); else localStorage.removeItem("sl_android_backup");
      render(); toast("データを復元しました");
    } catch (error) { toast(`復元できませんでした: ${error.message}`); }
    finally { input.value = ""; }
  };
  reader.onerror = () => { input.value = ""; toast("ファイルを読み込めませんでした"); };
  reader.readAsText(file);
}

// ── Actions ───────────────────────────────────────────
function setStatus(id, status) {
  const s = schedules.find((sc) => sc.id === id);
  if (s) {
    s.status = s.status === status ? "pending" : status;
    save("sl_schedules", schedules);
    render();
  }
}
function saveActual(id, val) {
  const s = schedules.find((sc) => sc.id === id);
  if (s) {
    s.actualDuration = val === "" ? undefined : parseFloat(val);
    save("sl_schedules", schedules);
    render();
  }
}
function deleteSchedule(id) {
  if (focusSchedId === id && document.getElementById("focus-active").style.display !== "none") { toast("実行中のタイマーを終了してから削除してください"); return; }
  const index = schedules.findIndex((s) => s.id === id);
  if (index < 0) return;
  const [item] = schedules.splice(index, 1);
  save("sl_schedules", schedules); closeModal(); render();
  toast("予定を削除しました", () => { schedules.splice(index, 0, item); save("sl_schedules", schedules); });
}

function deleteExam(id) {
  if (!confirm("削除しますか？")) return;
  exams = exams.filter((e) => e.id !== id);
  save("sl_exams", exams);
  closeModal();
  render();
}
function deleteSubject(id) {
  if (focusSubjectId === id && document.getElementById("focus-active").style.display !== "none") { toast("タイマーを終了してから教科を削除してください"); return; }
  if (schedules.some(s => s.subjectId === id)) { toast("学習記録に使われている教科は削除できません"); return; }
  if (subjects.length <= 1) { toast("教科を1つ以上残してください"); return; }
  if (!confirm("この教科を削除しますか？")) return;
  subjects = subjects.filter((s) => s.id !== id);
  save("sl_subjects", subjects);
  closeModal();
  render();
}
function changeWeek(d) {
  weekOffset += d;
  renderTimetable();
}
function goToday() {
  weekOffset = 0;
  renderTimetable();
}

// ── Modals ────────────────────────────────────────────

function formField(id, label, input, full = false) {
  return `<div class="form-group ${full ? "form-full" : ""}"><label for="${id}">${label}</label>${input}</div>`;
}
function groupField(id, value) { return formField(id, "対象の試験", `<select id="${id}">${groupOptions(value)}</select>`, true); }
function modalLayout(title, fields, submit, extra = "") {
  return `<div class="modal-header"><h2 id="modal-heading">${title}</h2><button type="button" class="btn btn-quiet" onclick="closeModal()" aria-label="入力画面を閉じる">閉じる</button></div><form id="modal-form" onsubmit="event.preventDefault();${submit}"><div class="form-grid">${fields}</div><p id="form-error" role="alert" class="form-error" hidden></p><div class="modal-footer">${extra}<button type="button" class="btn" onclick="closeModal()">キャンセル</button><button type="submit" class="btn btn-primary">保存</button></div></form>`;
}
function openModal(type, data = {}) {
  document.getElementById("modal-content").classList.remove("checklist-modal");
  modalTrigger = document.activeElement;
  const body = document.getElementById("modal-body");
  if (type === "schedule") {
    const now = new Date(); now.setSeconds(0, 0);
    const value = data.datetime ? new Date(data.datetime) : now;
    const local = new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0,16);
    body.innerHTML = modalLayout(data.id ? "学習予定を編集" : "学習予定を追加",
      formField("m-subjectId", "教科", `<select id="m-subjectId" required>${subjects.map((s) => `<option value="${s.id}" ${data.subjectId === s.id ? "selected" : ""}>${esc(s.name)}</option>`).join("")}</select>`, true)
      + groupField("m-group", data.examGroupId || (document.querySelector(".view.active")?.id === "view-schedule" && document.getElementById("filter-exam").value !== "unassigned" ? document.getElementById("filter-exam").value : ""))
      + formField("m-datetime", "日時", `<input type="datetime-local" id="m-datetime" value="${local}" required>`, true)
      + formField("m-duration", "予定時間（分）", `<input type="number" id="m-duration" value="${Math.round(Number(data.duration ?? 1) * 60)}" min="1" max="1440" required><div class="duration-presets">${[15,30,45,60].map((n) => `<button type="button" class="btn btn-sm" onclick="document.getElementById('m-duration').value=${n}">${n}分</button>`).join("")}</div>`, true)
      + formField("m-content", "学習内容（任意）", `<input id="m-content" value="${esc(data.content)}" placeholder="例：問題集 p.24〜28" maxlength="500">`, true)
      + `<details class="form-full optional-fields"><summary>メモ・実績時間</summary>${formField("m-actual", "実績時間（分・任意）", `<input type="number" id="m-actual" min="0" step="any" value="${data.actualDuration !== undefined && data.actualDuration !== "" ? Math.round(Number(data.actualDuration) * 60) : ""}" placeholder="未記録">`)}${formField("m-note", "メモ", `<textarea id="m-note" maxlength="2000">${esc(data.note)}</textarea>`)}</details>`,
      `saveSchedule('${data.id || ""}')`, data.id ? `<button type="button" class="btn btn-danger" onclick="deleteSchedule('${data.id}')">削除</button>` : "");
  } else if (type === "examgroup") {
    body.innerHTML = modalLayout(data.id ? "試験を編集" : "試験を登録",
      formField("m-group-name", "試験名", `<input id="m-group-name" required maxlength="100" value="${esc(data.name)}" placeholder="例：1学期中間考査 / 第2回模試">`, true)
      + formField("m-group-type", "種類", `<select id="m-group-type">${["定期考査","模試","その他"].map((type) => `<option ${data.type === type ? "selected" : ""}>${type}</option>`).join("")}</select>`, true)
      + formField("m-group-start", "試験の開始日", `<input type="date" id="m-group-start" required value="${esc(data.startDate || StudyModel.localDate())}">`)
      + formField("m-group-end", "試験の終了日", `<input type="date" id="m-group-end" required value="${esc(data.endDate || data.startDate || StudyModel.localDate())}">`)
      + '<p class="muted form-full">1日だけの模試は同じ日を指定。準備の学習は、日付にかかわらずこの試験に紐付けられます。</p>',
      `saveExamGroup('${data.id || ""}')`, data.id ? `<button type="button" class="btn btn-danger" onclick="deleteExamGroup('${data.id}')">削除</button>` : "");
  } else if (type === "exam") {
    body.innerHTML = modalLayout(data.id ? "教科別の日程を編集" : "教科別の日程を追加",
      groupField("m-exam-group", data.examGroupId)
      + formField("m-esubject", "教科名", `<input id="m-esubject" list="exam-subjects" required maxlength="100" value="${esc(data.subject)}" placeholder="例：数学"><datalist id="exam-subjects">${subjects.map((s) => `<option value="${esc(s.name)}">`).join("")}</datalist>`, true)
      + formField("m-edate", "日付", `<input type="date" id="m-edate" value="${esc(data.date || StudyModel.localDate())}" required>`, true)
      + formField("m-estart", "開始時刻（任意）", `<input type="time" id="m-estart" value="${esc(data.startTime)}">`)
      + formField("m-eend", "終了時刻（任意）", `<input type="time" id="m-eend" value="${esc(data.endTime)}">`)
      + formField("m-enote", "範囲・メモ（任意）", `<textarea id="m-enote" maxlength="2000">${esc(data.note)}</textarea>`, true),
      `saveExam('${data.id || ""}')`, data.id ? `<button type="button" class="btn btn-danger" onclick="deleteExam('${data.id}')">削除</button>` : "");
  } else if (type === "subject") {
    const color = data.color || COLORS[subjects.length % COLORS.length];
    body.innerHTML = modalLayout(data.id ? "教科を編集" : "教科を追加",
      formField("m-sname", "教科名", `<input id="m-sname" value="${esc(data.name)}" required maxlength="100" placeholder="例：数学I・A">`, true)
      + formField("m-scolor", "教科の色", `<div class="subject-colors">${COLORS.map((c) => `<button type="button" class="color-pick ${c === color ? "selected" : ""}" aria-label="色 ${c}" aria-pressed="${c === color}" style="background:${c}" onclick="pickColor(this,'${c}')"></button>`).join("")}</div><input type="color" id="m-scolor" value="${color}" aria-label="カスタム色">`, true), `saveSubject('${data.id || ""}')`, data.id ? `<button type="button" class="btn btn-danger" onclick="deleteSubject('${data.id}')">削除</button>` : "");
  }
  activateModal();
}
function activateModal() {
  document.getElementById("modal-backdrop").classList.add("open");
  document.body.classList.add("modal-open");
  document.querySelectorAll(".app, .mobile-header, .mobile-bottom-nav, .running-focus").forEach((el) => el.inert = true);
  // Focus the header so a mobile keyboard does not immediately cover the form.
  document.getElementById("modal-content").focus();
  updateVisualViewport();
}
function updateVisualViewport() {
  if (!window.visualViewport) return;
  document.documentElement.style.setProperty("--visual-height", `${visualViewport.height}px`);
  document.documentElement.style.setProperty("--visual-top", `${visualViewport.offsetTop}px`);
}
if (window.visualViewport) {
  visualViewport.addEventListener("resize", updateVisualViewport);
  visualViewport.addEventListener("scroll", updateVisualViewport);
}
function formError(message) {
  const el = document.getElementById("form-error");
  el.textContent = message; el.hidden = false; el.scrollIntoView({block:"nearest"});
}
function pickColor(el, color) {
  document.querySelectorAll(".color-pick").forEach((e) => { e.classList.toggle("selected", e === el); e.setAttribute("aria-pressed", String(e === el)); });
  document.getElementById("m-scolor").value = color;
}
function closeModal(e) {
  if (e && e.target !== document.getElementById("modal-backdrop")) return;
  document.getElementById("modal-backdrop").classList.remove("open");
  document.getElementById("modal-content").classList.remove("checklist-modal");
  document.body.classList.remove("modal-open");
  document.querySelectorAll(".app, .mobile-header, .mobile-bottom-nav, .running-focus").forEach((el) => el.inert = false);
  if (modalTrigger?.isConnected) modalTrigger.focus();
}
window.addEventListener("keydown", (event) => {
  if (!document.getElementById("modal-backdrop").classList.contains("open")) return;
  if (event.key === "Escape") { event.preventDefault(); closeModal(); }
  if (event.key === "Tab") {
    const elements = [...document.querySelectorAll('#modal-content button, #modal-content input, #modal-content select, #modal-content textarea, #modal-content summary')].filter((el) => !el.disabled && el.getClientRects().length);
    const first = elements[0], last = elements.at(-1), active = document.activeElement;
    if (event.shiftKey && (active === first || active.id === "modal-content")) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && active === last) { event.preventDefault(); first?.focus(); }
  }
});
function editSchedule(id) { openModal("schedule", schedules.find((s) => s.id === id) || {}); }
function editExam(id) { openModal("exam", exams.find((e) => e.id === id) || {}); }
function editSubject(id) { openModal("subject", subjects.find((s) => s.id === id) || {}); }
function saveSchedule(id) {
  const minutes = Number(document.getElementById("m-duration").value), actualVal = document.getElementById("m-actual").value;
  const old = schedules.find((s) => s.id === id);
  if (focusSchedId === id && document.getElementById("focus-active").style.display !== "none") return formError("タイマーを終了してから予定を編集してください");
  if (!subjects.some((s) => s.id === document.getElementById("m-subjectId").value)) return formError("先に「その他 → 教科」で教科を登録してください");
  if (!Number.isFinite(minutes) || minutes < 1 || minutes > 1440) return formError("予定時間は1〜1440分で入力してください");
  if (actualVal !== "" && (!Number.isFinite(Number(actualVal)) || Number(actualVal) < 0)) return formError("実績時間は0分以上で入力してください");
  const datetime = document.getElementById("m-datetime").value;
  if (!datetime || Number.isNaN(Date.parse(datetime))) return formError("日時を入力してください");
  const obj = {...old, id:id || uid(), subjectId:document.getElementById("m-subjectId").value, examGroupId:document.getElementById("m-group").value || undefined, datetime, duration:minutes/60, actualDuration:actualVal === "" ? undefined : Number(actualVal)/60, content:document.getElementById("m-content").value.trim(), note:document.getElementById("m-note").value.trim(), status:old?.status || "pending"};
  if (old) schedules[schedules.indexOf(old)] = obj; else schedules.push(obj);
  save("sl_schedules", schedules); closeModal(); render(); toast("学習予定を保存しました");
}
function saveExamGroup(id) {
  const name = document.getElementById("m-group-name").value.trim(), startDate = document.getElementById("m-group-start").value, endDate = document.getElementById("m-group-end").value;
  if (!name || !startDate || !endDate) return formError("試験名と日付を入力してください");
  if (endDate < startDate) return formError("終了日は開始日以降を選んでください");
  if (exams.some((e) => e.examGroupId === id && (e.date < startDate || e.date > endDate))) return formError("登録済みの教科別日程が試験期間外になります。期間か教科の日程を確認してください");
  const obj = {id:id || uid(), name, type:document.getElementById("m-group-type").value, startDate, endDate};
  const index = examGroups.findIndex((g) => g.id === id);
  if (index >= 0) examGroups[index] = obj; else examGroups.push(obj);
  save("sl_exam_groups", examGroups); closeModal(); render(); toast("試験を保存しました");
}
function deleteExamGroup(id) {
  if (!confirm("試験を削除しますか？ 学習記録と教科別日程は残り、試験未指定になります。")) return;
  if (focusExamGroupId === id && document.getElementById("focus-active").style.display !== "none") { formError("タイマーを終了してから試験を削除してください"); return; }
  examGroups = examGroups.filter((g) => g.id !== id);
  for (const s of [...schedules, ...exams]) if (s.examGroupId === id) delete s.examGroupId;
  save("sl_exam_groups", examGroups); save("sl_schedules", schedules); save("sl_exams", exams);
  closeModal(); render(); toast("試験を削除しました。学習記録は残しています");
}
function saveExam(id) {
  const subject = document.getElementById("m-esubject").value.trim(), date = document.getElementById("m-edate").value;
  const startTime = document.getElementById("m-estart").value, endTime = document.getElementById("m-eend").value, examGroupId = document.getElementById("m-exam-group").value;
  if (!subject || !date) return formError("教科名と日付を入力してください");
  if (endTime && (!startTime || endTime <= startTime)) return formError("終了時刻は開始時刻より後にしてください");
  const group = examGroups.find((g) => g.id === examGroupId);
  if (group && (date < group.startDate || date > group.endDate)) return formError("日付は対象の試験期間内を選んでください");
  const previous = exams.find(exam => exam.id === id);
  const obj = {...previous, id:id || uid(), subject, date, startTime, endTime, examGroupId:examGroupId || undefined, note:document.getElementById("m-enote").value.trim()};
  const index = exams.findIndex((e) => e.id === id);
  if (index >= 0) exams[index] = obj; else exams.push(obj);
  save("sl_exams", exams); closeModal(); render(); toast("教科別の日程を保存しました");
}
function saveSubject(id) {
  const name = document.getElementById("m-sname").value.trim();
  if (!name) return formError("教科名を入力してください");
  const old = subjects.find((s) => s.id === id);
  if (old && focusSubjectId === id && document.getElementById("focus-active").style.display !== "none") return formError("タイマーを終了してから教科を編集してください");
  const obj = {id:id || uid(), name, color:document.getElementById("m-scolor").value};
  if (old) { for (const e of exams) if (e.subject === old.name) e.subject = name; subjects[subjects.indexOf(old)] = obj; save("sl_exams", exams); }
  else subjects.push(obj);
  save("sl_subjects", subjects); closeModal(); render(); toast("教科を保存しました");
}
function openRecord(id) {
  const s = schedules.find((s) => s.id === id); if (!s) return;
  modalTrigger = document.activeElement;
  document.getElementById("modal-body").innerHTML = modalLayout("勉強を記録",
    `<p class="form-full record-description">${esc(subjectById(s.subjectId).name)} · ${esc(s.content || "学習")}</p>`
    + `<fieldset class="form-full record-status"><legend>学習の状況</legend>${[["done","完了"],["partial","一部"],["miss","未実施"]].map(([v,label]) => `<label><input type="radio" name="record-status" value="${v}" ${s.status === v || v === "done" && (!s.status || s.status === "pending") ? "checked" : ""} onchange="onRecordStatus('${id}')"><span>${label}</span></label>`).join("")}</fieldset>`
    + formField("record-minutes", "実績時間（分）", `<input type="number" id="record-minutes" min="0" step="any" required value="${Math.round((s.actualDuration !== undefined && s.actualDuration !== "" ? Number(s.actualDuration) : s.status === "miss" ? 0 : Number(s.duration)) * 60)}">`, true)
    + '<p class="muted form-full">タイマーで記録済みの時間も含めた合計を入力します。</p>', `saveRecord('${id}')`);
  activateModal();
}
function onRecordStatus(id) {
  const status = document.querySelector('input[name="record-status"]:checked').value;
  const input = document.getElementById("record-minutes");
  if (status === "miss") input.value = 0;
  else if (Number(input.value) === 0) input.value = Math.round(Number(schedules.find((s) => s.id === id)?.duration || 0) * 60);
}
function saveRecord(id) {
  const s = schedules.find((s) => s.id === id); if (!s) return;
  if (focusSchedId === id && document.getElementById("focus-active").style.display !== "none") return formError("タイマーを終了してから記録してください");
  const input = document.getElementById("record-minutes"), minutes = Number(input.value);
  const status = document.querySelector('input[name="record-status"]:checked')?.value;
  if (!status || input.value === "" || !Number.isFinite(minutes) || minutes < 0) return formError("状況と0分以上の実績時間を入力してください");
  if (status === "miss" && minutes !== 0) return formError("未実施の実績時間は0分にしてください");
  const previous = {...s};
  s.actualDuration = minutes / 60; s.status = status;
  save("sl_schedules", schedules); closeModal(); render();
  toast("勉強を記録しました", () => { const current = schedules.findIndex((item) => item.id === id); if (current >= 0) schedules[current] = previous; save("sl_schedules", schedules); });
}
function moveSubject(index, direction) {
  const target = index + direction; if (target < 0 || target >= subjects.length) return;
  [subjects[index], subjects[target]] = [subjects[target], subjects[index]];
  save("sl_subjects", subjects); render();
}
// ── Notifications ─────────────────────────────────────
let notifSettings = load("sl_notif", {
  timer: true,
  scheduleStart: true,
  schedulePre: true,
  preMin: 10,
  examPrev: true,
  examPrevTime: "20:00",
  examDay: true,
  examDayTime: "07:00",
});

let _scheduleCheckTimer = null;
let _examCheckTimer = null;
let _firedNotifs = new Set(load("sl_notif_fired", []));

function saveNotifSettings() {
  notifSettings = {
    timer:
      document.getElementById("notif-timer")?.checked ?? notifSettings.timer,
    scheduleStart:
      document.getElementById("notif-schedule-start")?.checked ??
      notifSettings.scheduleStart,
    schedulePre:
      document.getElementById("notif-schedule-pre")?.checked ??
      notifSettings.schedulePre,
    preMin:
      parseInt(document.getElementById("notif-pre-min")?.value) ||
      notifSettings.preMin,
    examPrev:
      document.getElementById("notif-exam-prev")?.checked ??
      notifSettings.examPrev,
    examPrevTime:
      document.getElementById("notif-exam-prev-time")?.value ||
      notifSettings.examPrevTime,
    examDay:
      document.getElementById("notif-exam-day")?.checked ??
      notifSettings.examDay,
    examDayTime:
      document.getElementById("notif-exam-day-time")?.value ||
      notifSettings.examDayTime,
  };
  save("sl_notif", notifSettings);
  // サブ設定の表示切替
  const wrap = document.getElementById("notif-pre-min-wrap");
  if (wrap) wrap.style.display = notifSettings.schedulePre ? "flex" : "none";
  const wrapPrev = document.getElementById("notif-exam-prev-time-wrap");
  if (wrapPrev)
    wrapPrev.style.display = notifSettings.examPrev ? "flex" : "none";
  const wrapDay = document.getElementById("notif-exam-day-time-wrap");
  if (wrapDay) wrapDay.style.display = notifSettings.examDay ? "flex" : "none";
  restartNotifPolling();
}

function loadNotifSettingsUI() {
  const map = {
    "notif-timer": "timer",
    "notif-schedule-start": "scheduleStart",
    "notif-schedule-pre": "schedulePre",
    "notif-exam-prev": "examPrev",
    "notif-exam-day": "examDay",
  };
  Object.entries(map).forEach(([id, key]) => {
    const el = document.getElementById(id);
    if (el) el.checked = notifSettings[key];
  });
  const preMin = document.getElementById("notif-pre-min");
  if (preMin) preMin.value = notifSettings.preMin;
  const prevTime = document.getElementById("notif-exam-prev-time");
  if (prevTime) prevTime.value = notifSettings.examPrevTime || "20:00";
  const dayTime = document.getElementById("notif-exam-day-time");
  if (dayTime) dayTime.value = notifSettings.examDayTime || "07:00";

  const wrap = document.getElementById("notif-pre-min-wrap");
  if (wrap) wrap.style.display = notifSettings.schedulePre ? "flex" : "none";
  const wrapPrev = document.getElementById("notif-exam-prev-time-wrap");
  if (wrapPrev)
    wrapPrev.style.display = notifSettings.examPrev ? "flex" : "none";
  const wrapDay = document.getElementById("notif-exam-day-time-wrap");
  if (wrapDay) wrapDay.style.display = notifSettings.examDay ? "flex" : "none";
  updateNotifPermissionUI();
}

function updateNotifPermissionUI() {
  const statusEl = document.getElementById("notif-status-text");
  const btnEl = document.getElementById("notif-request-btn");
  const gridEl = document.getElementById("notif-settings-grid");
  if (!statusEl) return;

  if (!("Notification" in window)) {
    statusEl.textContent = "このブラウザでは通知を利用できません";
    if (btnEl) btnEl.style.display = "none";
    return;
  }
  const perm = Notification.permission;
  if (perm === "granted") {
    statusEl.textContent = "通知は許可されています";
    statusEl.style.color = "var(--accent)";
    if (btnEl) btnEl.style.display = "none";
    if (gridEl) gridEl.style.opacity = "1";
  } else if (perm === "denied") {
    statusEl.textContent =
      "通知がブロックされています。ブラウザの設定から許可してください。";
    statusEl.style.color = "var(--danger)";
    if (btnEl) btnEl.style.display = "none";
    if (gridEl) gridEl.style.opacity = "0.4";
  } else {
    statusEl.textContent = "通知が許可されていません";
    statusEl.style.color = "var(--warn)";
    if (btnEl) btnEl.style.display = "inline-flex";
    if (gridEl) gridEl.style.opacity = "0.6";
  }
}

async function requestNotifPermission() {
  if (!("Notification" in window)) return;
  const result = await Notification.requestPermission();
  updateNotifPermissionUI();
  if (result === "granted") restartNotifPolling();
}

function sendNotif(title, body, tag) {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  // タグで重複送信防止
  if (tag && _firedNotifs.has(tag)) return;
  if (tag) {
    _firedNotifs.add(tag);
    // fired セットは当日のみ保持（midnight にクリア）
    save("sl_notif_fired", [..._firedNotifs]);
  }

  // アイコンは絶対URLで指定（Android Chrome が相対パスを正しく解決しない場合があるため）
  const iconUrl = new URL("icons/app-icon.png", window.location.href).href;
  const badgeUrl = new URL("icons/app-icon.png", window.location.href).href;

  const options = {
    body,
    icon: iconUrl,
    badge: badgeUrl,
    tag: tag || undefined,
    requireInteraction: false,
    data: { url: window.location.href },
  };

  // Service Worker 経由（Android Chrome 推奨ルート：アプリ名・アイコンが正しく表示される）
  const reg =
    window.swRegistration ||
    (navigator.serviceWorker && navigator.serviceWorker.controller && null);
  if (window.swRegistration) {
    window.swRegistration.showNotification(title, options).catch((err) => {
      console.warn("showNotification failed, falling back:", err);
      try {
        new Notification(title, options);
      } catch (e) {}
    });
  } else {
    // SW未登録時のフォールバック（iOSフォアグラウンド等）
    try {
      const n = new Notification(title, options);
      n.onclick = () => {
        window.focus();
        n.close();
      };
    } catch (e) {
      console.warn("Notification failed:", e);
    }
  }
}

// ─ タイマー終了通知（finishFocusAuto から呼ぶ）
function notifyTimerEnd(subjectName) {
  if (typeof playTimerSound === "function") playTimerSound();
  if (!notifSettings.timer) return;
  sendNotif(
    "タイマー終了！",
    `${subjectName} の集中タイマーが終わりました。お疲れ様でした！`,
    null,
  );
}

// ─ スケジュール通知ポーリング（1分ごと）
function pollScheduleNotifs() {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();

  schedules.forEach((s) => {
    if (s.status === "done" || s.status === "miss") return;
    const t = new Date(s.datetime);
    // 今日のスケジュールのみ
    if (t.toDateString() !== now.toDateString()) return;
    const tMin = t.getHours() * 60 + t.getMinutes();
    const subj = subjectById(s.subjectId);

    // 予定開始通知（±1分の誤差を許容）
    if (notifSettings.scheduleStart) {
      const tag = `sched-start-${s.id}-${t.toDateString()}`;
      if (Math.abs(tMin - nowMin) <= 1) {
        sendNotif(
          `${subj.name} の時間です`,
          `${s.content || "勉強"} を始めましょう！（${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}〜）`,
          tag,
        );
      }
    }

    // X分前リマインダー
    if (notifSettings.schedulePre) {
      const pre = notifSettings.preMin;
      const tag = `sched-pre-${s.id}-${t.toDateString()}`;
      if (Math.abs(tMin - pre - nowMin) <= 1) {
        sendNotif(
          `${pre}分後に ${esc(subj.name)} があります`,
          `${s.content || "勉強"} の準備をしましょう！`,
          tag,
        );
      }
    }
  });
}

// ─ 考査通知ポーリング（1分ごと）
function pollExamNotifs() {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  const now = new Date();
  const hm = now.getHours() * 60 + now.getMinutes();

  exams.forEach((e) => {
    const examDate = new Date(e.date + "T00:00");
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diffDays = Math.round((examDate - today) / 86400000);

    // 前日：設定時刻
    if (notifSettings.examPrev && diffDays === 1) {
      const [ph, pm] = (notifSettings.examPrevTime || "20:00")
        .split(":")
        .map(Number);
      const tag = `exam-prev-${e.id}-${e.date}`;
      if (Math.abs(hm - (ph * 60 + pm)) <= 1) {
        sendNotif(
          `${groupName(e.examGroupId)}: 明日は ${e.subject} の試験です`,
          `${e.date} に考査があります。最終確認を忘れずに！`,
          tag,
        );
      }
    }

    // 当日：設定時刻
    if (notifSettings.examDay && diffDays === 0) {
      const [dh, dm] = (notifSettings.examDayTime || "07:00")
        .split(":")
        .map(Number);
      const tag = `exam-day-${e.id}-${e.date}`;
      if (Math.abs(hm - (dh * 60 + dm)) <= 1) {
        sendNotif(
          `${groupName(e.examGroupId)}: 今日は ${e.subject} の試験です`,
          `${e.startTime ? e.startTime + " 開始" : ""}  頑張ってください！`,
          tag,
        );
      }
    }
  });
}

function restartNotifPolling() {
  clearInterval(_scheduleCheckTimer);
  clearInterval(_examCheckTimer);
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  pollScheduleNotifs();
  pollExamNotifs();
  _scheduleCheckTimer = setInterval(pollScheduleNotifs, 60 * 1000);
  _examCheckTimer = setInterval(pollExamNotifs, 60 * 1000);
}

// 日付が変わったら fired セットをリセット
function scheduleMidnightReset() {
  const now = new Date();
  const msToMidnight =
    new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1) - now;
  setTimeout(() => {
    _firedNotifs.clear();
    save("sl_notif_fired", []);
    restartNotifPolling();
    scheduleMidnightReset();
  }, msToMidnight + 1000);
}

// ── Help ──────────────────────────────────────────────
function switchHelpTab(tab) {
  document
    .querySelectorAll(".help-tab")
    .forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  document
    .querySelectorAll(".help-panel")
    .forEach((p) => p.classList.toggle("active", p.id === "help-" + tab));
}

function switchPwaTab(os) {
  document.querySelectorAll(".pwa-os-btn").forEach((b, i) => {
    const ids = ["ios", "android", "pc"];
    b.classList.toggle("active", ids[i] === os);
  });
  ["pwa-ios", "pwa-android", "pwa-pc"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.style.display = id === "pwa-" + os ? "" : "none";
  });
}

// ── Init ──────────────────────────────────────────────
showView("dashboard");
loadNotifSettingsUI();
restartNotifPolling();
scheduleMidnightReset();

window.addEventListener("resize", () => {
  const active = document.querySelector(".view.active");
  if (active && active.id === "view-timetable") {
    renderTimetable();
    applyTTMode();
  }
});
