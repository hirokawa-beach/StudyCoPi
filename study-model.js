/* Shared, DOM-free data rules. Hours stay in the existing storage format. */
(function (root) {
  const actualHours = (item) => {
    if (item.actualDuration !== undefined && item.actualDuration !== "") {
      return Math.max(0, Number(item.actualDuration) || 0);
    }
    return item.status === "done" ? Math.max(0, Number(item.duration) || 0) : 0;
  };
  const totals = (items) => ({
    planned: items.reduce((n, s) => n + (Number(s.duration) || 0), 0),
    actual: items.reduce((n, s) => n + actualHours(s), 0),
    done: items.filter((s) => s.status === "done").length,
    count: items.length,
  });
  const forExam = (items, id) => id === "unassigned"
    ? items.filter((s) => !s.examGroupId)
    : id ? items.filter((s) => s.examGroupId === id) : items;
  function localDate(value = new Date()) {
    const d = new Date(value);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  }
  function weekStart(value = new Date()) {
    const d = new Date(value);
    d.setDate(d.getDate() - (d.getDay() + 6) % 7);
    d.setHours(0, 0, 0, 0);
    return d;
  }
  // Calendar boundaries use local dates, including offset timestamps and DST days.
  function dailyTotals(items) {
    const days = new Map();
    for (const item of items) {
      const key = localDate(item.datetime);
      const day = days.get(key) || { date: key, planned: 0, actual: 0, count: 0, done: 0 };
      day.planned += Number(item.duration) || 0;
      day.actual += actualHours(item);
      day.count++;
      if (item.status === 'done') day.done++;
      days.set(key, day);
    }
    return days;
  }
  function timeBuckets(items, from, to, unit = 'day') {
    if (!from || !to || from >= to) return [];
    const days = dailyTotals(items);
    const start = new Date(from);
    start.setHours(0, 0, 0, 0);
    let cursor = unit === 'week' ? weekStart(start) : unit === 'month' ? new Date(start.getFullYear(), start.getMonth(), 1) : start;
    const last = new Date(to.getTime() - 1);
    const oldest = unit === 'month' ? new Date(last.getFullYear(), last.getMonth() - 365, 1) : unit === 'week' ? weekStart(last) : new Date(last);
    if (unit !== 'month') { oldest.setHours(0, 0, 0, 0); oldest.setDate(oldest.getDate() - (unit === 'week' ? 365 * 7 : 365)); }
    if (cursor < oldest) cursor = oldest;
    const buckets = [];
    while (cursor < to) {
      const end = new Date(cursor);
      if (unit === 'month') end.setMonth(end.getMonth() + 1);
      else end.setDate(end.getDate() + (unit === 'week' ? 7 : 1));
      const bucket = { date: localDate(cursor), end: localDate(end), planned: 0, actual: 0, count: 0 };
      for (const day = new Date(cursor); day < end && day < to; day.setDate(day.getDate() + 1)) {
        if (day < from) continue;
        const total = days.get(localDate(day));
        if (total) { bucket.planned += total.planned; bucket.actual += total.actual; bucket.count += total.count; }
      }
      buckets.push(bucket);
      cursor = end;
    }
    return buckets;
  }
  const heatLevel = (hours) => hours <= 0 ? 0 : hours < .5 ? 1 : hours < 1 ? 2 : hours < 2 ? 3 : 4;
  function activityRange(value = new Date(), year = null) {
    const today = new Date(value);
    const previousMonthLastDay = new Date(today.getFullYear() - 1, today.getMonth() + 1, 0).getDate();
    const from = year === null ? new Date(today.getFullYear() - 1, today.getMonth(), Math.min(today.getDate(), previousMonthLastDay) + 1) : new Date(year, 0, 1);
    const to = year === null ? new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1) : new Date(year + 1, 0, 1);
    const start = new Date(from); start.setDate(start.getDate() - start.getDay());
    const end = new Date(to.getTime() - 1); end.setHours(0, 0, 0, 0); end.setDate(end.getDate() + 7 - end.getDay());
    return {from, to, start, end};
  }
  function validateBackup(data) {
    if (data?.version !== undefined && (!Number.isInteger(data.version) || data.version < 1 || data.version > 4)) throw new Error("未対応のバックアップ形式です");
    if (!data || ![data.subjects, data.schedules, data.exams].every(Array.isArray)
      || (data.examGroups !== undefined && !Array.isArray(data.examGroups))) throw new Error("バックアップの形式が正しくありません");
    const groups = data.examGroups || [];
    const validId = (id) => typeof id === "string" && /^[a-zA-Z0-9_-]+$/.test(id);
    const validDate = (v) => typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v)
      && !Number.isNaN(Date.parse(v)) && localDate(new Date(v + "T12:00:00")) === v;
    const validHours = (v, maximum = 24) => v !== "" && v !== null && Number.isFinite(Number(v)) && Number(v) >= 0 && Number(v) <= maximum;
    for (const list of [data.subjects, data.schedules, data.exams, groups]) {
      if (list.some((x) => !x || !validId(x.id)) || new Set(list.map((x) => x.id)).size !== list.length) throw new Error("IDが不正または重複しています");
    }
    if (data.subjects.some((s) => typeof s.name !== "string" || !/^#[0-9a-f]{6}$/i.test(s.color))) throw new Error("教科が不正です");
    if (groups.some((g) => typeof g.name !== "string" || !g.name.trim() || !validDate(g.startDate)
      || !validDate(g.endDate) || g.endDate < g.startDate || !["定期考査", "模試", "その他"].includes(g.type))) throw new Error("試験が不正です");
    const groupIds = new Set(groups.map((g) => g.id));
    const validLink = (x) => !x.examGroupId || groupIds.has(x.examGroupId);
    if (data.schedules.some((s) => typeof s.datetime !== "string" || Number.isNaN(Date.parse(s.datetime))
      || !validHours(s.duration) || (s.actualDuration !== undefined && s.actualDuration !== "" && !validHours(s.actualDuration, Number.MAX_SAFE_INTEGER))
      || !validLink(s) || (s.status && !["pending", "done", "partial", "miss"].includes(s.status)))) throw new Error("学習記録が不正です");
    if (data.exams.some((e) => !validDate(e.date) || typeof e.subject !== "string" || !validLink(e)
      || [e.startTime, e.endTime].some((t) => t && !/^([01]\d|2[0-3]):[0-5]\d$/.test(t)))) throw new Error("教科別日程が不正です");
    for (const exam of data.exams) {
      if (exam.checklist === undefined) continue;
      if (!Array.isArray(exam.checklist) || exam.checklist.length > 200 || new Set(exam.checklist.map(item => item?.id)).size !== exam.checklist.length ||
        exam.checklist.some(item => !item || !validId(item.id) || typeof item.title !== 'string' || !item.title.trim() || item.title.length > 300 || !checklistStatuses.some(status => status[0] === item.status))) throw new Error("試験範囲チェックリストが不正です");
    }
    return { subjects: data.subjects, schedules: data.schedules, exams: data.exams, examGroups: groups };
  }
  const checklistStatuses = [['pending','未着手'],['progress','途中'],['review','要復習'],['done','完了']];
  const checklistTotals = (items = []) => ({ count: items.length, done: items.filter(item => item.status === 'done').length, review: items.filter(item => item.status === 'review').length });
  const api = { actualHours, totals, forExam, localDate, weekStart, dailyTotals, timeBuckets, heatLevel, activityRange, checklistStatuses, checklistTotals, validateBackup };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.StudyModel = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
