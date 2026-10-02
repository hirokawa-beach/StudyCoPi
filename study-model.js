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
  function validateBackup(data) {
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
    return { subjects: data.subjects, schedules: data.schedules, exams: data.exams, examGroups: groups };
  }
  const api = { actualHours, totals, forExam, localDate, weekStart, validateBackup };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.StudyModel = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
