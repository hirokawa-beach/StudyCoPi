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
  localStorage.setItem(key, JSON.stringify(val));
}

let subjects = load("sl_subjects", [
  { id: "s1", name: "数学", color: "#185fa5" },
  { id: "s2", name: "英語", color: "#3b6d11" },
  { id: "s3", name: "国語", color: "#a32d2d" },
]);
let schedules = load("sl_schedules", []);
let exams = load("sl_exams", []);
let weekOffset = 0;
let hoursMode = "actual"; // 'actual' | 'planned' | 'both'
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

// Period filtering
function getPeriodRange() {
  const preset = document.getElementById("hours-period-preset").value;
  const now = new Date();
  if (preset === "all") return { from: null, to: null };
  if (preset === "week") {
    const ws = new Date(now);
    ws.setDate(now.getDate() - now.getDay() + 1);
    ws.setHours(0, 0, 0, 0);
    const we = new Date(ws);
    we.setDate(ws.getDate() + 7);
    return { from: ws, to: we };
  }
  if (preset === "month") {
    const ms = new Date(now.getFullYear(), now.getMonth(), 1);
    const me = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    return { from: ms, to: me };
  }
  if (preset === "custom") {
    const f = document.getElementById("hours-from").value;
    const t = document.getElementById("hours-to").value;
    return {
      from: f ? new Date(f) : null,
      to: t ? new Date(new Date(t).getTime() + 86399999) : null,
    };
  }
  // exam preset: value is exam id
  const ex = exams.find((e) => e.id === preset);
  if (ex) {
    const end = new Date(ex.date);
    end.setHours(23, 59, 59, 999);
    return { from: null, to: end };
  }
  return { from: null, to: null };
}

function filterByPeriod(list, range) {
  return list.filter((s) => {
    const d = new Date(s.datetime);
    if (range.from && d < range.from) return false;
    if (range.to && d > range.to) return false;
    return true;
  });
}

// ── Navigation ────────────────────────────────────────
function showView(name) {
  document
    .querySelectorAll(".view")
    .forEach((v) => v.classList.remove("active"));
  document
    .querySelectorAll(".nav-item")
    .forEach((n) => n.classList.remove("active"));
  document.getElementById("view-" + name).classList.add("active");
  const nav = document.getElementById("nav-" + name);
  if (nav) nav.classList.add("active");
  document.querySelectorAll(".mobile-bottom-nav button").forEach((b) => {
    b.classList.toggle("active", b.dataset.view === name);
  });
  if (name === "settings") loadNotifSettingsUI();
  render();
}

// ── Render ────────────────────────────────────────────
function render() {
  renderDashboard();
  renderSchedule();
  renderTimetable();
  renderExams();
  renderSubjects();
  renderHours();
  renderData();
  renderFocusSetup();
  populateSubjectFilter();
  populateHoursExamPresets();
}

// ── Shared: スケジュールカード HTML（モバイル用）────────
function buildScheduleCard(s) {
  const subj = subjectById(s.subjectId);
  const t = new Date(s.datetime);
  const timeStr = `${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;
  const endMin =
    t.getHours() * 60 +
    t.getMinutes() +
    Math.round((parseFloat(s.duration) || 1) * 60);
  const endStr = `${String(Math.floor(endMin / 60) % 24).padStart(2, "0")}:${String(endMin % 60).padStart(2, "0")}`;
  const actual =
    s.actualDuration !== undefined && s.actualDuration !== ""
      ? s.actualDuration
      : "";
  return `<div class="scm-card${s.status === "done" ? " scm-done" : s.status === "miss" ? " scm-miss" : ""}" style="border-left-color:${subj.color}">
    <div class="scm-left">
      <div class="scm-time">${timeStr}</div>
      <div class="scm-endtime">〜${endStr}</div>
      <div class="scm-dur">${s.duration || "-"}h</div>
    </div>
    <div class="scm-body">
      <div class="scm-subject" style="color:${subj.color}">${subj.name}</div>
      ${s.content ? `<div class="scm-content">${s.content}</div>` : ""}
      ${s.note ? `<div class="scm-note">${s.note}</div>` : ""}
      <div class="scm-actual-row">
        <span style="font-size:11px;color:var(--text3)">実績</span>
        <input type="number" class="scm-actual-input" value="${actual}" min="0" max="24" step="0.25"
          onchange="saveActual('${s.id}',this.value)" placeholder="-">
        <span style="font-size:11px;color:var(--text3)">h</span>
      </div>
    </div>
    <div class="scm-right">
      <div class="scm-status-btns">
        <button class="scm-btn-status${s.status === "done" ? " scm-s-done" : ""}" onclick="setStatus('${s.id}','done')" title="完了">✓</button>
        <button class="scm-btn-status${s.status === "partial" ? " scm-s-partial" : ""}" onclick="setStatus('${s.id}','partial')" title="一部">△</button>
        <button class="scm-btn-status${s.status === "miss" ? " scm-s-miss" : ""}" onclick="setStatus('${s.id}','miss')" title="未実施">✗</button>
      </div>
    </div>
  </div>`;
}

// ── Dashboard ─────────────────────────────────────────
function renderDashboard() {
  const now = new Date();
  const weekStart = new Date(now);
  weekStart.setDate(now.getDate() - now.getDay() + 1);
  weekStart.setHours(0, 0, 0, 0);
  const weekEnd = new Date(weekStart);
  weekEnd.setDate(weekStart.getDate() + 7);

  // 今週と今日のデータ
  const thisWeek = schedules.filter((s) => {
    const d = new Date(s.datetime);
    return d >= weekStart && d < weekEnd;
  });
  const todaySchedules = schedules.filter(
    (s) => new Date(s.datetime).toDateString() === now.toDateString(),
  );

  const todayTotal = todaySchedules.length;
  const todayDone = todaySchedules.filter((s) => s.status === "done").length;
  const todayPercent =
    todayTotal > 0 ? Math.round((todayDone / todayTotal) * 100) : 0;

  const weekDone = thisWeek.filter((s) => s.status === "done").length;
  const total = thisWeek.length;
  const weekPercent = total > 0 ? Math.round((weekDone / total) * 100) : 0;

  const todayHours = todaySchedules
    .filter((s) => s.status === "done")
    .reduce(
      (a, b) =>
        a +
        (parseFloat(
          b.actualDuration !== undefined && b.actualDuration !== ""
            ? b.actualDuration
            : b.duration,
        ) || 0),
      0,
    );

  const upcoming = exams
    .filter((e) => new Date(e.date) >= now)
    .sort((a, b) => new Date(a.date) - new Date(b.date))[0];

  // ダッシュボード上部にプログレスバーを挿入
  const progressHtml = `
    <div class="dash-progress-card">
      <div class="dash-progress-header">
        <div class="dash-progress-title"><span>🎯</span> 今日の目標達成率</div>
        <div class="dash-progress-text">${todayPercent}% (${todayDone}/${todayTotal}件)</div>
      </div>
      <div class="dash-progress-bar-bg">
        <div class="dash-progress-bar-fill" style="width: ${todayPercent}%"></div>
      </div>
    </div>
  `;

  document.getElementById("dash-stats").innerHTML = `
    <div class="stat-card"><div class="stat-label">今日の勉強時間</div><div class="stat-value">${todayHours.toFixed(1)}<span class="stat-unit"> h</span></div></div>
    <div class="stat-card"><div class="stat-label">今週のタスク</div><div class="stat-value">${weekDone} / ${total}<span class="stat-unit"> 件</span></div></div>
    <div class="stat-card"><div class="stat-label">今週の達成率</div><div class="stat-value">${weekPercent}<span class="stat-unit"> %</span></div></div>
    <div class="stat-card"><div class="stat-label">次の考査</div><div class="stat-value" style="font-size:14px;margin-top:4px">${upcoming ? upcoming.subject + '<br><span style="font-size:11px;color:var(--text2)">' + fmtDate(upcoming.date) + "</span>" : "なし"}</div></div>
  `;

  // Countdown banner
  const cEl = document.getElementById("dash-exam-countdown");
  if (upcoming) {
    const days = daysUntil(upcoming.date);
    cEl.innerHTML =
      progressHtml +
      `<div class="exam-countdown-banner">
      <span style="font-size:28px">📝</span>
      <div>
        <div class="ecd-name">${upcoming.subject}</div>
        <div class="ecd-meta">${fmtDate(upcoming.date)} ${upcoming.startTime || ""}</div>
      </div>
      <div class="ecd-days">${countdownText(days)}</div>
    </div>`;
  } else {
    cEl.innerHTML = progressHtml;
  }

  const recent = [...schedules]
    .filter(
      (s) => new Date(s.datetime) >= new Date(new Date().setHours(0, 0, 0, 0)),
    )
    .sort((a, b) => new Date(a.datetime) - new Date(b.datetime))
    .slice(0, 8);

  document.getElementById("dash-table").innerHTML = recent.length
    ? recent
        .map((s) => {
          const subj = subjectById(s.subjectId);
          const actual =
            s.actualDuration !== undefined && s.actualDuration !== ""
              ? s.actualDuration
              : "-";
          return `<tr>
      <td data-label="日時">${fmt(s.datetime)}</td>
      <td data-label="教科"><span class="subject-dot" style="background:${subj.color}"></span>${subj.name}</td>
      <td data-label="内容">${s.content || ""}</td>
      <td data-label="予定">${s.duration || "-"} h</td>
      <td data-label="実績">${actual !== "-" ? actual + " h" : "-"}</td>
      <td data-label="状況">${statusBadge(s.status || "pending")}</td>
      <td data-label="記録"><div class="status-toggle">
        <button class="status-btn ${s.status === "done" ? "done" : ""}" onclick="setStatus('${s.id}','done')">✓</button>
        <button class="status-btn ${s.status === "partial" ? "partial" : ""}" onclick="setStatus('${s.id}','partial')">△</button>
        <button class="status-btn ${s.status === "miss" ? "miss" : ""}" onclick="setStatus('${s.id}','miss')">✗</button>
      </div></td>
    </tr>`;
        })
        .join("")
    : `<tr class="empty-row"><td colspan="7" class="empty-cell"><div class="empty"><div class="empty-icon">📚</div><p>スケジュールがありません。追加してみましょう！</p></div></td></tr>`;

  // ── モバイル用カード表示 ──────────────────────────────
  const dashMobile = document.getElementById("dash-mobile-list");
  if (dashMobile) {
    if (!recent.length) {
      dashMobile.innerHTML = `<div class="empty"><div class="empty-icon">📚</div><p>スケジュールがありません</p></div>`;
    } else {
      const dayNames = ["日", "月", "火", "水", "木", "金", "土"];
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      // 日付グループ化
      const groups = {};
      recent.forEach((s) => {
        const d = new Date(s.datetime);
        const key = d.toDateString();
        if (!groups[key]) groups[key] = { date: d, items: [] };
        groups[key].items.push(s);
      });
      dashMobile.innerHTML = Object.values(groups)
        .map((g) => {
          const gDate = new Date(g.date);
          gDate.setHours(0, 0, 0, 0);
          const diff = Math.round((gDate - today) / 86400000);
          const dayLabel =
            diff === 0
              ? "今日"
              : diff === 1
                ? "明日"
                : diff === -1
                  ? "昨日"
                  : "";
          const dateStr = `${g.date.getMonth() + 1}/${g.date.getDate()}(${dayNames[g.date.getDay()]})`;
          const isToday = diff === 0;
          const itemsHtml = g.items.map((s) => buildScheduleCard(s)).join("");
          return `<div class="scm-group${isToday ? " scm-group-today" : ""}">
          <div class="scm-date-header">
            <span class="scm-date-str">${dateStr}</span>
            ${dayLabel ? `<span class="scm-day-label${isToday ? " scm-today-label" : ""}">${dayLabel}</span>` : ""}
          </div>
          ${itemsHtml}
        </div>`;
        })
        .join("");
    }
  }
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
          `<option value="${s.id}" ${cur === s.id ? "selected" : ""}>${s.name}</option>`,
      )
      .join("");
}

function toggleExamVisibility() {
  showExamsInSchedule = !showExamsInSchedule;
  const btn = document.getElementById("toggle-exam-btn");
  if (btn) {
    btn.textContent = showExamsInSchedule ? "考査を非表示" : "考査を表示";
  }
  renderSchedule();
}

function renderSchedule() {
  const sf = document.getElementById("filter-subject").value;
  const stf = document.getElementById("filter-status").value;

  // 考査データをスケジュールと同じフォーマットに変換
  const examItems = exams.map((e) => {
    const matchedSubj = subjects.find((subj) => subj.name === e.subject);
    return {
      isExam: true,
      id: e.id,
      subjectId: matchedSubj ? matchedSubj.id : null,
      subjectName: e.subject,
      datetime: e.date + "T" + (e.startTime || "00:00"),
      duration: "-",
      actualDuration: "-",
      content: "📝 考査",
      note: e.note || "",
      status: "exam",
      color: matchedSubj ? matchedSubj.color : "var(--purple)",
    };
  });

  // スケジュールと考査を結合してソート
  let list = [...schedules];
  if (showExamsInSchedule) {
    list = [...list, ...examItems];
  }
  list.sort((a, b) => new Date(a.datetime) - new Date(b.datetime));

  if (sf)
    list = list.filter(
      (s) => s.subjectId === sf || (s.isExam && s.subjectId === sf),
    );
  if (stf) list = list.filter((s) => (s.status || "pending") === stf);

  document.getElementById("schedule-table").innerHTML = list.length
    ? list
        .map((s) => {
          if (s.isExam) {
            // 考査用の行
            return `<tr style="background: var(--surface2);">
              <td data-label="日時">${fmt(s.datetime)}</td>
              <td data-label="教科"><span class="subject-dot" style="background:${s.color}"></span><strong>${s.subjectName}</strong></td>
              <td data-label="内容"><strong>${s.content}</strong></td>
              <td data-label="予定時間">-</td>
              <td data-label="実績時間">-</td>
              <td data-label="状況"><span class="badge badge-exam">考査予定</span></td>
              <td data-label="メモ" style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.note}</td>
              <td data-label="操作"><button class="btn btn-sm" onclick="editExam('${s.id}')">編集</button></td>
            </tr>`;
          }

          // 通常のスケジュール行
          const subj = subjectById(s.subjectId);
          const actual =
            s.actualDuration !== undefined && s.actualDuration !== ""
              ? s.actualDuration
              : "";
          return `<tr>
      <td data-label="日時">${fmt(s.datetime)}</td>
      <td data-label="教科"><span class="subject-dot" style="background:${subj.color}"></span>${subj.name}</td>
      <td data-label="内容">${s.content || ""}</td>
      <td data-label="予定時間">${s.duration || "-"} h</td>
      <td data-label="実績時間">
        <div class="actual-input-wrap">
          <input type="number" value="${actual}" min="0" max="24" step="0.25" style="width:64px;padding:4px 8px"
            onchange="saveActual('${s.id}', this.value)" placeholder="-">
          <span style="font-size:12px;color:var(--text2)">h</span>
        </div>
      </td>
      <td data-label="状況"><div class="status-toggle">
        <button class="status-btn ${s.status === "done" ? "done" : ""}"    onclick="setStatus('${s.id}','done')">完了</button>
        <button class="status-btn ${s.status === "partial" ? "partial" : ""}" onclick="setStatus('${s.id}','partial')">一部</button>
        <button class="status-btn ${s.status === "miss" ? "miss" : ""}"    onclick="setStatus('${s.id}','miss')">未</button>
      </div></td>
      <td data-label="メモ" style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.note || ""}</td>
      <td data-label="操作">
        <button class="btn btn-sm" onclick="editSchedule('${s.id}')">編集</button>
        <button class="btn btn-sm btn-danger" onclick="deleteSchedule('${s.id}')">削除</button>
      </td>
    </tr>`;
        })
        .join("")
    : `<tr class="empty-row"><td colspan="8" class="empty-cell"><div class="empty"><div class="empty-icon">📅</div><p>スケジュールがありません</p></div></td></tr>`;

  // ── モバイル用カード表示 ──────────────────────────────
  const mobileList = document.getElementById("schedule-mobile-list");
  if (!mobileList) return;
  if (!list.length) {
    mobileList.innerHTML = `<div class="empty"><div class="empty-icon">📅</div><p>スケジュールがありません</p></div>`;
    return;
  }

  // 日付でグループ化
  const groups = {};
  list.forEach((s) => {
    const d = new Date(s.datetime);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    if (!groups[key]) groups[key] = { date: d, items: [] };
    groups[key].items.push(s);
  });

  const dayNames = ["日", "月", "火", "水", "木", "金", "土"];
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  mobileList.innerHTML = Object.entries(groups)
    .map(([key, g]) => {
      const gDate = new Date(g.date);
      gDate.setHours(0, 0, 0, 0);
      const diff = Math.round((gDate - today) / 86400000);
      const dayLabel =
        diff === 0 ? "今日" : diff === 1 ? "明日" : diff === -1 ? "昨日" : "";
      const dateStr = `${g.date.getMonth() + 1}/${g.date.getDate()}(${dayNames[g.date.getDay()]})`;
      const isToday = diff === 0;

      const itemsHtml = g.items
        .map((s) => {
          if (s.isExam) {
            const color = s.color || "var(--purple)";
            return `<div class="scm-card scm-exam" style="border-left-color:${color}">
          <div class="scm-left">
            <div class="scm-time">${fmt(s.datetime).split(" ")[1] || ""}</div>
          </div>
          <div class="scm-body">
            <div class="scm-subject" style="color:${color}">📝 ${s.subjectName}</div>
            ${s.note ? `<div class="scm-note">${s.note}</div>` : ""}
          </div>
          <span class="badge badge-exam">考査</span>
        </div>`;
          }
          const subj = subjectById(s.subjectId);
          const t = new Date(s.datetime);
          const timeStr = `${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;
          const endMin =
            t.getHours() * 60 +
            t.getMinutes() +
            Math.round((parseFloat(s.duration) || 1) * 60);
          const endStr = `${String(Math.floor(endMin / 60) % 24).padStart(2, "0")}:${String(endMin % 60).padStart(2, "0")}`;
          const actual =
            s.actualDuration !== undefined && s.actualDuration !== ""
              ? s.actualDuration
              : "";
          return `<div class="scm-card${s.status === "done" ? " scm-done" : s.status === "miss" ? " scm-miss" : ""}" style="border-left-color:${subj.color}">
        <div class="scm-left">
          <div class="scm-time">${timeStr}</div>
          <div class="scm-endtime">〜${endStr}</div>
          <div class="scm-dur">${s.duration || "-"}h</div>
        </div>
        <div class="scm-body">
          <div class="scm-subject" style="color:${subj.color}">${subj.name}</div>
          ${s.content ? `<div class="scm-content">${s.content}</div>` : ""}
          ${s.note ? `<div class="scm-note">${s.note}</div>` : ""}
          <div class="scm-actual-row">
            <span style="font-size:11px;color:var(--text3)">実績</span>
            <input type="number" class="scm-actual-input" value="${actual}" min="0" max="24" step="0.25"
              onchange="saveActual('${s.id}',this.value)" placeholder="-">
            <span style="font-size:11px;color:var(--text3)">h</span>
          </div>
        </div>
        <div class="scm-right">
          <div class="scm-status-btns">
            <button class="scm-btn-status${s.status === "done" ? " scm-s-done" : ""}" onclick="setStatus('${s.id}','done')" title="完了">✓</button>
            <button class="scm-btn-status${s.status === "partial" ? " scm-s-partial" : ""}" onclick="setStatus('${s.id}','partial')" title="一部">△</button>
            <button class="scm-btn-status${s.status === "miss" ? " scm-s-miss" : ""}" onclick="setStatus('${s.id}','miss')" title="未実施">✗</button>
          </div>
          <div class="scm-ops">
            <button class="btn btn-sm" onclick="editSchedule('${s.id}')">編集</button>
            <button class="btn btn-sm btn-danger" onclick="deleteSchedule('${s.id}')">削除</button>
          </div>
        </div>
      </div>`;
        })
        .join("");

      return `<div class="scm-group${isToday ? " scm-group-today" : ""}">
      <div class="scm-date-header">
        <span class="scm-date-str">${dateStr}</span>
        ${dayLabel ? `<span class="scm-day-label${isToday ? " scm-today-label" : ""}">${dayLabel}</span>` : ""}
      </div>
      ${itemsHtml}
    </div>`;
    })
    .join("");
}

// ── Timetable mode toggle (mobile) ────────────────────
let ttMode = "list"; // 'list' | 'grid'

function setTTMode(mode) {
  ttMode = mode;
  applyTTMode();
}

function applyTTMode() {
  const grid = document.getElementById("timetable-grid");
  const list = document.getElementById("mobile-day-list");
  const thumb = document.getElementById("tt-switch-thumb");
  const lList = document.getElementById("tt-label-list");
  const lGrid = document.getElementById("tt-label-grid");
  if (!grid || !list) return;

  const isMobile = window.innerWidth <= 700;
  if (!isMobile) {
    grid.style.display = "";
    list.style.display = "none";
    if (thumb) thumb.style.transform = "";
  } else {
    const isGrid = ttMode === "grid";
    grid.style.display = isGrid ? "" : "none";
    list.style.display = isGrid ? "none" : "";
    // スライドスイッチの位置
    if (thumb)
      thumb.style.transform = isGrid
        ? "translateX(calc(100% + 2px))"
        : "translateX(0)";
    if (lList) lList.style.fontWeight = isGrid ? "400" : "700";
    if (lGrid) lGrid.style.fontWeight = isGrid ? "700" : "400";
  }
}

// ── Timetable ─────────────────────────────────────────
function renderTimetable() {
  const now = new Date();
  const base = new Date(now);
  base.setDate(now.getDate() - now.getDay() + 1 + weekOffset * 7);
  base.setHours(0, 0, 0, 0);
  const days = ["月", "火", "水", "木", "金", "土", "日"];
  const dates = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(base);
    d.setDate(base.getDate() + i);
    return d;
  });

  document.getElementById("week-label").textContent =
    `${dates[0].getMonth() + 1}/${dates[0].getDate()} 〜 ${dates[6].getMonth() + 1}/${dates[6].getDate()}`;

  // ── 表示時間範囲：最低6〜22、予定が範囲外なら自動拡張 ─
  let minHour = 6,
    maxHour = 22;

  schedules.forEach((s) => {
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
  exams.forEach((e) => {
    if (
      !dates.some(
        (d) => d.toDateString() === new Date(e.date + "T00:00").toDateString(),
      )
    )
      return;
    if (e.startTime)
      minHour = Math.min(minHour, parseInt(e.startTime.split(":")[0]));
    if (e.endTime)
      maxHour = Math.max(maxHour, Math.ceil(parseInt(e.endTime.split(":")[0])));
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

    const daySchedules = schedules
      .filter((s) => new Date(s.datetime).toDateString() === d.toDateString())
      .sort((a, b) => new Date(a.datetime) - new Date(b.datetime));

    const dayExams = exams.filter(
      (e) => new Date(e.date + "T00:00").toDateString() === d.toDateString(),
    );

    let blocksHtml = linesHtml;

    dayExams.forEach((e) => {
      const matchedSubj = subjects.find((s) => s.name === e.subject);
      const color = matchedSubj ? matchedSubj.color : "var(--purple)";
      const sMins = e.startTime
        ? parseInt(e.startTime.split(":")[0]) * 60 +
          parseInt(e.startTime.split(":")[1])
        : START_HOUR * 60;
      const eMins = e.endTime
        ? parseInt(e.endTime.split(":")[0]) * 60 +
          parseInt(e.endTime.split(":")[1])
        : sMins + 60;
      const top = Math.max(0, ((sMins - START_HOUR * 60) / 60) * ROW_H);
      const height = Math.max(ROW_H * 0.4, ((eMins - sMins) / 60) * ROW_H);
      blocksHtml += `<div class="tt2-block tt2-exam-block" style="top:${top}px;height:${height}px;border-color:${color};color:${color}" title="📝${e.subject}">
        <span class="tt2-block-name">📝${e.subject}</span>
        <span class="tt2-block-time">${e.startTime || ""}${e.endTime ? "〜" + e.endTime : ""}</span>
      </div>`;
    });

    daySchedules.forEach((s) => {
      const subj = subjectById(s.subjectId);
      const top = Math.max(0, toY(s.datetime));
      const height = Math.max(ROW_H * 0.4, durToH(s.duration));
      const alpha =
        s.status === "done" ? "cc" : s.status === "miss" ? "55" : "";
      const sd = new Date(s.datetime);
      const timeStr = `${String(sd.getHours()).padStart(2, "0")}:${String(sd.getMinutes()).padStart(2, "0")}`;
      blocksHtml += `<div class="tt2-block" style="top:${top}px;height:${height}px;background:${subj.color}${alpha}" title="${subj.name}: ${s.content || ""} (${timeStr}〜)">
        <span class="tt2-block-name">${subj.name}</span>
        <span class="tt2-block-time">${timeStr}</span>
        ${s.content ? `<span class="tt2-block-content">${s.content}</span>` : ""}
      </div>`;
    });

    colsHtml += `<div class="tt2-col${isToday ? " tt2-today" : ""}">
      <div class="tt2-col-header${isToday ? " tt2-col-header-today" : ""}" style="height:${HEADER_H}px">${days[i]}<br><span>${d.getDate()}</span></div>
      <div class="tt2-col-body" style="height:${GRID_H}px">${blocksHtml}</div>
    </div>`;
  });

  document.getElementById("timetable-grid").innerHTML = `
    <div class="tt2-wrap">
      <div class="tt2-axis" style="padding-top:${HEADER_H}px">
        <div class="tt2-axis-inner" style="position:relative;height:${GRID_H}px">${axisHtml}</div>
      </div>
      <div class="tt2-cols">${colsHtml}</div>
    </div>`;

  renderMobileDayList(base, now);
  applyTTMode();
}

function renderMobileDayList(base, now) {
  const container = document.getElementById("mobile-day-list");
  if (!container) return;
  if (window.innerWidth > 700) {
    container.innerHTML = "";
    return;
  }
  const days = ["月", "火", "水", "木", "金", "土", "日"];
  let html = "";
  for (let i = 0; i < 7; i++) {
    const d = new Date(base);
    d.setDate(base.getDate() + i);
    const isToday = d.toDateString() === now.toDateString();
    const daySchedules = schedules
      .filter((s) => new Date(s.datetime).toDateString() === d.toDateString())
      .sort((a, b) => new Date(a.datetime) - new Date(b.datetime));
    const dayExams = exams.filter(
      (e) => new Date(e.date + "T00:00").toDateString() === d.toDateString(),
    );

    html += `<div class="mobile-day-card${isToday ? " mobile-day-today" : ""}">
      <div class="mobile-day-header">
        <strong>${days[i]} ${d.getMonth() + 1}/${d.getDate()}${isToday ? " <span class='today-badge'>今日</span>" : ""}</strong>
        <span class="mobile-day-count">${daySchedules.length + dayExams.length}件</span>
      </div>`;

    // 考査
    dayExams.forEach((e) => {
      const matchedSubj = subjects.find((s) => s.name === e.subject);
      const color = matchedSubj ? matchedSubj.color : "var(--purple)";
      html += `<div class="mobile-schedule-item mobile-exam-item" style="border-left-color:${color}">
        <div class="msi-time">${e.startTime || ""}${e.endTime ? "〜" + e.endTime : ""}</div>
        <div class="msi-body">
          <span class="msi-subject" style="color:${color}">📝 ${e.subject}</span>
          ${e.note ? `<div class="msi-content">${e.note}</div>` : ""}
        </div>
        <span class="badge badge-exam" style="flex-shrink:0">考査</span>
      </div>`;
    });

    if (!daySchedules.length && !dayExams.length) {
      html += '<div class="mobile-day-empty">予定なし</div>';
    } else {
      daySchedules.forEach((s) => {
        const subj = subjectById(s.subjectId);
        const t = new Date(s.datetime);
        const startStr = `${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;
        const endMin =
          t.getHours() * 60 +
          t.getMinutes() +
          Math.round((parseFloat(s.duration) || 1) * 60);
        const endStr = `${String(Math.floor(endMin / 60) % 24).padStart(2, "0")}:${String(endMin % 60).padStart(2, "0")}`;
        const statusClass =
          s.status === "done"
            ? "done"
            : s.status === "miss"
              ? "miss"
              : s.status === "partial"
                ? "partial"
                : "";
        html += `<div class="mobile-schedule-item${statusClass ? " msi-" + statusClass : ""}" style="border-left-color:${subj.color}">
          <div class="msi-time">${startStr}<br><span class="msi-endtime">〜${endStr}</span></div>
          <div class="msi-body">
            <span class="msi-subject" style="color:${subj.color}">${subj.name}</span>
            ${s.content ? `<div class="msi-content">${s.content}</div>` : ""}
          </div>
          <div class="msi-actions">
            <button class="status-btn ${s.status === "done" ? "done" : ""}" onclick="setStatus('${s.id}','done')">✓</button>
            <button class="status-btn ${s.status === "miss" ? "miss" : ""}" onclick="setStatus('${s.id}','miss')">✗</button>
          </div>
        </div>`;
      });
    }
    html += "</div>";
  }
  container.innerHTML = html;
}

// ── Exams ─────────────────────────────────────────────
function renderExams() {
  const now = new Date();
  now.setHours(0, 0, 0, 0);
  const sorted = [...exams].sort((a, b) => new Date(a.date) - new Date(b.date));
  const html = sorted.length
    ? sorted
        .map((e) => {
          const days = daysUntil(e.date);
          const cls = days !== null ? countdownClass(days) : "";
          const matchedSubj = subjects.find((s) => s.name === e.subject);
          const bgColor = matchedSubj ? matchedSubj.color : "var(--purple)";
          return `<div class="exam-card" onclick="editExam('${e.id}')">
      <div class="exam-date">${fmtDate(e.date)}</div>
      <div class="exam-subj"><span class="badge" style="margin-bottom:4px; background:${bgColor}15; color:${bgColor}; border:1px solid ${bgColor}40;">${e.subject}</span></div>
      <div class="exam-time">${e.startTime || ""} ${e.endTime ? "– " + e.endTime : ""}</div>
      ${days !== null ? `<div class="exam-countdown ${cls}">${countdownText(days)}</div>` : ""}
      ${e.note ? `<div style="font-size:11px;color:var(--text3);margin-top:4px">${e.note}</div>` : ""}
      <div style="margin-top:8px"><button class="btn btn-sm btn-danger" onclick="event.stopPropagation();deleteExam('${e.id}')">削除</button></div>
    </div>`;
        })
        .join("")
    : `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">📝</div><p>考査が登録されていません</p></div>`;
  const g1 = document.getElementById("exam-grid");
  if (g1) g1.innerHTML = html;
  const g2 = document.getElementById("exam-grid-settings");
  if (g2) g2.innerHTML = html;
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
            const actual = schedules
              .filter((sc) => sc.subjectId === s.id && sc.status === "done")
              .reduce(
                (a, b) =>
                  a +
                  (parseFloat(
                    b.actualDuration !== undefined && b.actualDuration !== ""
                      ? b.actualDuration
                      : b.duration,
                  ) || 0),
                0,
              );
            return `<tr draggable="true" data-idx="${i}" ondragstart="onDragStart(event,${i})" ondragover="onDragOver(event,${i})" ondrop="onDrop(event,${i})" ondragleave="onDragLeave(event)" ondragend="onDragEnd(event)">
      <td data-label=""><span class="drag-handle" title="ドラッグで並び替え">⠿</span></td>
      <td data-label="カラー"><div style="width:20px;height:20px;border-radius:50%;background:${s.color};display:inline-block"></div></td>
      <td data-label="教科名"><strong>${s.name}</strong></td>
      <td data-label="予定時間">${planned.toFixed(1)} h</td>
      <td data-label="実績時間">${actual.toFixed(1)} h</td>
      <td data-label="操作">
        <button class="btn btn-sm" onclick="editSubject('${s.id}')">編集</button>
        <button class="btn btn-sm btn-danger" onclick="deleteSubject('${s.id}')">削除</button>
      </td>
    </tr>`;
          })
          .join("")
      : `<tr class="empty-row"><td colspan="6" class="empty-cell"><div class="empty"><p>教科がありません</p></div></td></tr>`;
  }
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
function setHoursMode(mode) {
  hoursMode = mode;
  document
    .querySelectorAll(".hours-toggle-btn")
    .forEach((b) => b.classList.remove("active"));
  document.getElementById("hours-btn-" + mode).classList.add("active");
  renderHours();
}

function onPeriodPresetChange() {
  const val = document.getElementById("hours-period-preset").value;
  const wrap = document.getElementById("hours-custom-range");
  wrap.style.display = val === "custom" ? "flex" : "none";
  renderHours();
}

function populateHoursExamPresets() {
  const grp = document.getElementById("exam-preset-group");
  const now = new Date();
  const upcoming = exams
    .filter((e) => new Date(e.date) >= now)
    .sort((a, b) => new Date(a.date) - new Date(b.date));
  if (!grp) return;
  grp.innerHTML = upcoming
    .map(
      (e) =>
        `<option value="${e.id}">${e.subject}まで（〜${fmtDate(e.date)}）</option>`,
    )
    .join("");
}

function renderHours() {
  const range = getPeriodRange();
  const inRange = filterByPeriod(schedules, range);
  const done = inRange.filter((s) => s.status === "done");
  const totalAct = done.reduce(
    (a, b) =>
      a +
      (parseFloat(
        b.actualDuration !== undefined && b.actualDuration !== ""
          ? b.actualDuration
          : b.duration,
      ) || 0),
    0,
  );
  const totalPln = inRange.reduce(
    (a, b) => a + (parseFloat(b.duration) || 0),
    0,
  );
  const week = filterByPeriod(
    done,
    (() => {
      const n = new Date(),
        ws = new Date(n);
      ws.setDate(n.getDate() - n.getDay() + 1);
      ws.setHours(0, 0, 0, 0);
      const we = new Date(ws);
      we.setDate(ws.getDate() + 7);
      return { from: ws, to: we };
    })(),
  ).reduce(
    (a, b) =>
      a +
      (parseFloat(
        b.actualDuration !== undefined && b.actualDuration !== ""
          ? b.actualDuration
          : b.duration,
      ) || 0),
    0,
  );

  document.getElementById("hours-stats").innerHTML = `
    <div class="stat-card"><div class="stat-label">実績時間（期間内）</div><div class="stat-value">${totalAct.toFixed(1)}<span class="stat-unit"> h</span></div></div>
    <div class="stat-card"><div class="stat-label">予定時間（期間内）</div><div class="stat-value">${totalPln.toFixed(1)}<span class="stat-unit"> h</span></div></div>
    <div class="stat-card"><div class="stat-label">今週の実績</div><div class="stat-value">${week.toFixed(1)}<span class="stat-unit"> h</span></div></div>
    <div class="stat-card"><div class="stat-label">達成率</div><div class="stat-value">${totalPln > 0 ? Math.round((totalAct / totalPln) * 100) : "-"}<span class="stat-unit">${totalPln > 0 ? " %" : ""}</span></div></div>
  `;

  const list = subjects.map((s) => {
    const subjDone = done.filter((sc) => sc.subjectId === s.id);
    const act = subjDone.reduce(
      (a, b) =>
        a +
        (parseFloat(
          b.actualDuration !== undefined && b.actualDuration !== ""
            ? b.actualDuration
            : b.duration,
        ) || 0),
      0,
    );
    const pln = inRange
      .filter((sc) => sc.subjectId === s.id)
      .reduce((a, b) => a + (parseFloat(b.duration) || 0), 0);
    return { s, act, pln };
  });
  const maxVal = Math.max(...list.map((x) => Math.max(x.act, x.pln)), 0.1);

  document.getElementById("hours-list").innerHTML =
    list
      .map(({ s, act, pln }) => {
        if (hoursMode === "actual") {
          return `<div class="subj-hour-row">
        <div style="display:flex;align-items:center;gap:6px;min-width:90px"><div style="width:10px;height:10px;border-radius:50%;background:${s.color}"></div><span class="subj-hour-name">${s.name}</span></div>
        <div class="hours-bar-wrap" style="flex:1"><div class="hours-bar-bg"><div class="hours-bar-fill" style="width:${Math.round((act / maxVal) * 100)}%;background:${s.color}"></div></div></div>
        <span class="subj-hour-val">${act.toFixed(1)} h</span>
      </div>`;
        }
        if (hoursMode === "planned") {
          return `<div class="subj-hour-row">
        <div style="display:flex;align-items:center;gap:6px;min-width:90px"><div style="width:10px;height:10px;border-radius:50%;background:${s.color}"></div><span class="subj-hour-name">${s.name}</span></div>
        <div class="hours-bar-wrap" style="flex:1"><div class="hours-bar-bg"><div class="hours-bar-fill" style="width:${Math.round((pln / maxVal) * 100)}%;background:${s.color}88"></div></div></div>
        <span class="subj-hour-val">${pln.toFixed(1)} h</span>
      </div>`;
        }
        // both
        return `<div class="subj-hour-row" style="align-items:flex-start">
      <div style="display:flex;align-items:center;gap:6px;min-width:90px;padding-top:4px"><div style="width:10px;height:10px;border-radius:50%;background:${s.color}"></div><span class="subj-hour-name">${s.name}</span></div>
      <div class="hours-bar-pair" style="flex:1">
        <div class="hours-bar-label-row"><span>実績</span><span>${act.toFixed(1)} h</span></div>
        <div class="hours-bar-bg"><div class="hours-bar-fill" style="width:${Math.round((act / maxVal) * 100)}%;background:${s.color}"></div></div>
        <div class="hours-bar-label-row" style="margin-top:4px"><span>予定</span><span>${pln.toFixed(1)} h</span></div>
        <div class="hours-bar-bg"><div class="hours-bar-fill" style="width:${Math.round((pln / maxVal) * 100)}%;background:${s.color}55"></div></div>
      </div>
    </div>`;
      })
      .join("") || '<p style="color:var(--text3)">記録がありません</p>';
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
  const fsSel = document.getElementById("focus-subject");
  if (!fsSel) return;
  fsSel.innerHTML = subjects
    .map((s) => `<option value="${s.id}">${s.name}</option>`)
    .join("");

  const lkSel = document.getElementById("focus-schedule-link");
  const cur = lkSel ? lkSel.value : "";
  if (!lkSel) return;
  const pending = schedules
    .filter((s) => !s.status || s.status === "pending")
    .sort((a, b) => new Date(a.datetime) - new Date(b.datetime));
  lkSel.innerHTML =
    '<option value="">紐付けなし</option>' +
    pending
      .map((s) => {
        const sb = subjectById(s.subjectId);
        return `<option value="${s.id}" ${cur === s.id ? "selected" : ""}>${fmt(s.datetime)} ${sb.name} ${s.content || ""}</option>`;
      })
      .join("");
}

function setFocusTimer(min) {
  document.getElementById("focus-minutes").value = min;
  document
    .querySelectorAll(".focus-preset-btn")
    .forEach((b) => b.classList.remove("active"));
  event.currentTarget.classList.add("active");
}

function updateFocusMinutes() {
  document
    .querySelectorAll(".focus-preset-btn")
    .forEach((b) => b.classList.remove("active"));
}

function startFocus() {
  focusSubjectId = document.getElementById("focus-subject").value;
  focusSchedId = document.getElementById("focus-schedule-link").value;
  focusTargetSec =
    (parseInt(document.getElementById("focus-minutes").value) || 25) * 60;
  focusElapsed = 0;
  focusPaused = false;
  focusStart = Date.now();

  document.getElementById("focus-setup").style.display = "none";
  document.getElementById("focus-active").style.display = "flex";
  const subj = subjectById(focusSubjectId);
  document.getElementById("focus-active-subject").textContent = subj.name;

  clearInterval(focusTimer);
  focusTimer = setInterval(tickFocus, 500);

  // Try to prevent screen sleep
  if ("wakeLock" in navigator) {
    navigator.wakeLock.request("screen").catch(() => {});
  }
}

function tickFocus() {
  const nowElapsed = focusPaused
    ? focusElapsed
    : focusElapsed + Math.floor((Date.now() - focusStart) / 1000);
  const remaining = Math.max(0, focusTargetSec - nowElapsed);
  const mm = String(Math.floor(remaining / 60)).padStart(2, "0");
  const ss = String(remaining % 60).padStart(2, "0");
  const display = `${mm}:${ss}`;
  const progress = 1 - remaining / focusTargetSec;
  const circumference = 339.3;
  const offset = circumference * (1 - progress);

  document.getElementById("focus-timer-display").textContent = display;
  document.getElementById("focus-elapsed").textContent =
    `経過: ${Math.floor(nowElapsed / 60)}分`;
  const ring = document.getElementById("focus-ring");
  if (ring) ring.setAttribute("stroke-dashoffset", offset);

  // Fullscreen display
  const fst = document.getElementById("focus-fs-timer");
  const fse = document.getElementById("focus-fs-elapsed");
  if (fst) fst.textContent = display;
  if (fse) fse.textContent = `経過: ${Math.floor(nowElapsed / 60)}分`;

  if (remaining <= 0) {
    clearInterval(focusTimer);
    finishFocusAuto(nowElapsed);
  }
}

function pauseFocus() {
  const btn = document.getElementById("focus-pause-btn");
  const btnFs = document.getElementById("focus-fs-pause-btn");

  if (focusPaused) {
    // 再開
    focusStart = Date.now();
    focusPaused = false;
    focusTimer = setInterval(tickFocus, 500);
    [btn, btnFs].forEach((b) => {
      if (!b) return;
      b.textContent = "⏸ 一時停止";
      b.classList.remove("btn-resuming");
    });
  } else {
    // 一時停止
    focusElapsed += Math.floor((Date.now() - focusStart) / 1000);
    focusPaused = true;
    clearInterval(focusTimer);
    [btn, btnFs].forEach((b) => {
      if (!b) return;
      b.textContent = "▶ 再開";
      b.classList.add("btn-resuming");
    });
  }
}

function stopFocus() {
  const nowElapsed = focusPaused
    ? focusElapsed
    : focusElapsed + Math.floor((Date.now() - focusStart) / 1000);
  clearInterval(focusTimer);
  exitFullscreenOverlay();
  recordFocusSession(nowElapsed);
}

function finishFocusAuto(elapsed) {
  const subj = subjectById(focusSubjectId);
  notifyTimerEnd(subj.name);
  setTimeout(() => {
    alert(`⏰ タイマー終了！ お疲れ様でした。`);
    recordFocusSession(elapsed);
  }, 100);
}

function recordFocusSession(elapsedSec) {
  const hrs = Math.round(elapsedSec / 36) / 100; // 2 decimal hours
  if (focusSchedId) {
    // Link to existing schedule
    const s = schedules.find((sc) => sc.id === focusSchedId);
    if (s) {
      s.actualDuration = hrs;
      s.status = "done";
      save("sl_schedules", schedules);
    }
  } else {
    // Create new schedule entry
    const now = new Date();
    now.setSeconds(0, 0);
    schedules.push({
      id: uid(),
      subjectId: focusSubjectId,
      datetime: now.toISOString(),
      duration: hrs,
      actualDuration: hrs,
      content: "集中モードで記録",
      note: "",
      status: "done",
    });
    save("sl_schedules", schedules);
  }
  // Reset UI
  document.getElementById("focus-setup").style.display = "";
  document.getElementById("focus-active").style.display = "none";
  render();
  alert(`記録完了！ 勉強時間: ${Math.floor(elapsedSec / 60)}分 (${hrs} h)`);
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
    <div class="stat-card"><div class="stat-label">考査</div><div class="stat-value">${exams.length}<span class="stat-unit"> 件</span></div></div>
  `;
}

function exportData() {
  const payload = {
    version: 2,
    app: APP_NAME,
    exportedAt: new Date().toISOString(),
    subjects,
    schedules,
    exams,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `studycopi-backup-${new Date().toISOString().slice(0, 10)}.json`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function importData(input) {
  const file = input.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const data = JSON.parse(e.target.result);
      if (
        !Array.isArray(data.subjects) ||
        !Array.isArray(data.schedules) ||
        !Array.isArray(data.exams)
      )
        throw new Error("invalid");
      if (!confirm("現在のデータを上書きして復元します。よろしいですか？"))
        return;
      subjects = data.subjects;
      schedules = data.schedules;
      exams = data.exams;
      save("sl_subjects", subjects);
      save("sl_schedules", schedules);
      save("sl_exams", exams);
      render();
      alert("データを復元しました");
    } catch {
      alert(
        `ファイルの読み込みに失敗しました。${APP_NAME} のバックアップファイルか確認してください。`,
      );
    }
    input.value = "";
  };
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
  }
}
function deleteSchedule(id) {
  if (!confirm("削除しますか？")) return;
  schedules = schedules.filter((s) => s.id !== id);
  save("sl_schedules", schedules);
  render();
}
function deleteExam(id) {
  if (!confirm("削除しますか？")) return;
  exams = exams.filter((e) => e.id !== id);
  save("sl_exams", exams);
  render();
}
function deleteSubject(id) {
  if (!confirm("この教科を削除しますか？")) return;
  subjects = subjects.filter((s) => s.id !== id);
  save("sl_subjects", subjects);
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
function openModal(type, data = {}) {
  document.getElementById("modal-backdrop").classList.add("open");
  const body = document.getElementById("modal-body");

  if (type === "schedule") {
    const now = new Date();
    now.setMinutes(0, 0, 0);
    const dtLocal = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
    body.innerHTML = `
      <div class="modal-title">${data.id ? "スケジュールを編集" : "スケジュールを追加"}</div>
      <div class="form-grid">
        <div class="form-group form-full"><label>教科</label>
          <select id="m-subjectId">${subjects.map((s) => `<option value="${s.id}" ${data.subjectId === s.id ? "selected" : ""}>${s.name}</option>`).join("")}</select>
        </div>
        <div class="form-group"><label>日時</label><input type="datetime-local" id="m-datetime" value="${data.datetime ? new Date(new Date(data.datetime).getTime() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16) : dtLocal}"></div>
        <div class="form-group"><label>予定時間 (h)</label><input type="number" id="m-duration" value="${data.duration || 1}" min="0.25" max="24" step="0.25"></div>
        <div class="form-group"><label>実績時間 (h) <span style="font-weight:400;color:var(--text3)">任意</span></label><input type="number" id="m-actual" value="${data.actualDuration !== undefined ? data.actualDuration : ""}" min="0" max="24" step="0.25" placeholder="未記録"></div>
        <div class="form-group form-full"><label>学習内容</label><input type="text" id="m-content" value="${data.content || ""}" placeholder="例: 教科書 p.50〜80"></div>
        <div class="form-group form-full"><label>メモ</label><textarea id="m-note">${data.note || ""}</textarea></div>
      </div>
      <div class="modal-footer">
        <button class="btn" onclick="closeModal()">キャンセル</button>
        <button class="btn btn-primary" onclick="saveSchedule('${data.id || ""}')">保存</button>
      </div>`;
  } else if (type === "exam") {
    // Subject select: subjects list + manual input toggle
    const subjOpts = subjects
      .map((s) => `<option value="${s.name}">${s.name}</option>`)
      .join("");
    body.innerHTML = `
      <div class="modal-title">${data.id ? "考査を編集" : "考査を追加"}</div>
      <div class="form-grid">
        <div class="form-group form-full">
          <label>教科名</label>
          <select id="m-esubject-sel" onchange="onExamSubjectSel(this)">
            ${subjOpts}
            <option value="__custom__">手動入力…</option>
          </select>
        </div>
        <div class="form-group form-full" id="m-esubject-custom-wrap" style="display:none">
          <label>教科名（手動入力）</label>
          <input type="text" id="m-esubject-custom" value="${data.subject || ""}" placeholder="例: 数学I・A">
        </div>
        <div class="form-group"><label>日付</label><input type="date" id="m-edate" value="${data.date || ""}"></div>
        <div class="form-group"><label>開始時刻</label><input type="time" id="m-estart" value="${data.startTime || ""}"></div>
        <div class="form-group"><label>終了時刻</label><input type="time" id="m-eend" value="${data.endTime || ""}"></div>
        <div class="form-group form-full"><label>メモ・範囲</label><textarea id="m-enote">${data.note || ""}</textarea></div>
      </div>
      <div class="modal-footer">
        <button class="btn" onclick="closeModal()">キャンセル</button>
        <button class="btn btn-primary" onclick="saveExam('${data.id || ""}')">保存</button>
      </div>`;
    // Set initial value
    const selEl = document.getElementById("m-esubject-sel");
    if (data.subject) {
      const found = Array.from(selEl.options).find(
        (o) => o.value === data.subject,
      );
      if (found) {
        selEl.value = data.subject;
      } else {
        selEl.value = "__custom__";
        onExamSubjectSel(selEl);
        document.getElementById("m-esubject-custom").value = data.subject;
      }
    }
  } else if (type === "subject") {
    const color = data.color || COLORS[subjects.length % COLORS.length];
    body.innerHTML = `
      <div class="modal-title">${data.id ? "教科を編集" : "教科を追加"}</div>
      <div class="form-grid">
        <div class="form-group form-full"><label>教科名</label><input type="text" id="m-sname" value="${data.name || ""}" placeholder="例: 数学I・A"></div>
        <div class="form-group form-full"><label>カラー</label>
          <div class="subject-colors">${COLORS.map((c) => `<div class="color-pick ${c === color ? "selected" : ""}" style="background:${c}" onclick="pickColor(this,'${c}')" data-color="${c}"></div>`).join("")}</div>
          <input type="hidden" id="m-scolor" value="${color}">
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn" onclick="closeModal()">キャンセル</button>
        <button class="btn btn-primary" onclick="saveSubject('${data.id || ""}')">保存</button>
      </div>`;
  }
}

function onExamSubjectSel(sel) {
  const wrap = document.getElementById("m-esubject-custom-wrap");
  wrap.style.display = sel.value === "__custom__" ? "" : "none";
}

function pickColor(el, color) {
  document
    .querySelectorAll(".color-pick")
    .forEach((e) => e.classList.remove("selected"));
  el.classList.add("selected");
  document.getElementById("m-scolor").value = color;
}

function closeModal(e) {
  if (e && e.target !== document.getElementById("modal-backdrop")) return;
  document.getElementById("modal-backdrop").classList.remove("open");
}
window.addEventListener("keydown", (e) => {
  if (e.key === "Escape")
    document.getElementById("modal-backdrop").classList.remove("open");
});

function editSchedule(id) {
  openModal("schedule", schedules.find((s) => s.id === id) || {});
}
function editExam(id) {
  openModal("exam", exams.find((e) => e.id === id) || {});
}
function editSubject(id) {
  openModal("subject", subjects.find((s) => s.id === id) || {});
}

function saveSchedule(id) {
  const actualVal = document.getElementById("m-actual").value;
  const obj = {
    id: id || uid(),
    subjectId: document.getElementById("m-subjectId").value,
    datetime: document.getElementById("m-datetime").value,
    duration: document.getElementById("m-duration").value,
    actualDuration: actualVal === "" ? undefined : parseFloat(actualVal),
    content: document.getElementById("m-content").value,
    note: document.getElementById("m-note").value,
    status: "pending",
  };
  if (id) {
    const i = schedules.findIndex((s) => s.id === id);
    if (i > -1) {
      obj.status = schedules[i].status;
      schedules[i] = obj;
    }
  } else schedules.push(obj);
  save("sl_schedules", schedules);
  closeModal();
  render();
}

function saveExam(id) {
  const selEl = document.getElementById("m-esubject-sel");
  const isCustom = selEl.value === "__custom__";
  const subjName = isCustom
    ? document.getElementById("m-esubject-custom").value
    : selEl.value;
  const obj = {
    id: id || uid(),
    subject: subjName,
    date: document.getElementById("m-edate").value,
    startTime: document.getElementById("m-estart").value,
    endTime: document.getElementById("m-eend").value,
    note: document.getElementById("m-enote").value,
  };
  if (id) {
    const i = exams.findIndex((e) => e.id === id);
    if (i > -1) exams[i] = obj;
  } else exams.push(obj);
  save("sl_exams", exams);
  closeModal();
  render();
}

function saveSubject(id) {
  const obj = {
    id: id || uid(),
    name: document.getElementById("m-sname").value,
    color: document.getElementById("m-scolor").value,
  };
  if (id) {
    const i = subjects.findIndex((s) => s.id === id);
    if (i > -1) subjects[i] = obj;
  } else subjects.push(obj);
  save("sl_subjects", subjects);
  closeModal();
  render();
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

  const perm = Notification.permission;
  if (perm === "granted") {
    statusEl.textContent = "✅ 通知は許可されています";
    statusEl.style.color = "var(--accent)";
    if (btnEl) btnEl.style.display = "none";
    if (gridEl) gridEl.style.opacity = "1";
  } else if (perm === "denied") {
    statusEl.textContent =
      "❌ 通知がブロックされています。ブラウザの設定から許可してください。";
    statusEl.style.color = "var(--danger)";
    if (btnEl) btnEl.style.display = "none";
    if (gridEl) gridEl.style.opacity = "0.4";
  } else {
    statusEl.textContent = "🔔 通知が許可されていません";
    statusEl.style.color = "var(--warn)";
    if (btnEl) btnEl.style.display = "inline-flex";
    if (gridEl) gridEl.style.opacity = "0.6";
  }
}

async function requestNotifPermission() {
  const result = await Notification.requestPermission();
  updateNotifPermissionUI();
  if (result === "granted") restartNotifPolling();
}

function sendNotif(title, body, tag) {
  if (Notification.permission !== "granted") return;
  // タグで重複送信防止
  if (tag && _firedNotifs.has(tag)) return;
  if (tag) {
    _firedNotifs.add(tag);
    // fired セットは当日のみ保持（midnight にクリア）
    save("sl_notif_fired", [..._firedNotifs]);
  }

  // アイコンは絶対URLで指定（Android Chrome が相対パスを正しく解決しない場合があるため）
  const iconUrl = new URL("icons/icon-192.png", window.location.href).href;
  const badgeUrl = new URL("icons/icon-192.png", window.location.href).href;

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
  if (!notifSettings.timer) return;
  sendNotif(
    "⏰ タイマー終了！",
    `${subjectName} の集中タイマーが終わりました。お疲れ様でした！`,
    null,
  );
}

// ─ スケジュール通知ポーリング（1分ごと）
function pollScheduleNotifs() {
  if (Notification.permission !== "granted") return;
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
          `📅 ${subj.name} の時間です`,
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
          `⏱ ${pre}分後に ${subj.name} があります`,
          `${s.content || "勉強"} の準備をしましょう！`,
          tag,
        );
      }
    }
  });
}

// ─ 考査通知ポーリング（1分ごと）
function pollExamNotifs() {
  if (Notification.permission !== "granted") return;
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
          `📝 明日は ${e.subject} の考査です`,
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
          `📝 今日は ${e.subject} の考査です`,
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
  if (Notification.permission !== "granted") return;
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
