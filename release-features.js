/* Version metadata and scheduled focus: study starts only with the user's action. */
const STUDYCOPI_VERSION = '1.5.0';
document.querySelectorAll('[data-app-version]').forEach(node => { node.textContent = STUDYCOPI_VERSION; });
BACKUP_KEYS.add('sl_scheduled_focus');
let scheduledFocusEnabled = load('sl_scheduled_focus', false) === true;
let scheduledFocusPlan = null;
let scheduledFocusChecking = false;
let scheduledFocusPreviousElement = null;
const scheduledFocusDialog = document.getElementById('scheduled-focus-dialog');
document.getElementById('scheduled-focus-enabled').checked = scheduledFocusEnabled;

function setScheduledFocus(value) {
  scheduledFocusEnabled = value === true;
  save('sl_scheduled_focus', scheduledFocusEnabled);
  document.getElementById('scheduled-focus-enabled').checked = scheduledFocusEnabled;
  if (!scheduledFocusEnabled) closeScheduledFocus();
}
function validateScheduledFocus(value) {
  if (value !== undefined && typeof value !== 'boolean') throw new Error('予定時刻の集中モード設定が不正です');
  return value === true;
}
function scheduleFocusKey(plan) { return `${plan.id}:${new Date(plan.datetime).getTime()}`; }
function scheduledFocusClaims() {
  const value = load('sl_scheduled_focus_seen', []);
  return Array.isArray(value) ? value.filter(item => typeof item === 'string').slice(-256) : [];
}
function eligibleFocusPlans(now) {
  if (!scheduledFocusEnabled || document.hidden || document.getElementById('focus-active').style.display !== 'none') return [];
  return schedules.filter(plan => {
    const elapsed = now - new Date(plan.datetime).getTime();
    return !['done','miss'].includes(plan.status) && elapsed >= 0 && elapsed < Math.min(300000, plan.duration * 3600000);
  }).sort((a,b) => new Date(a.datetime) - new Date(b.datetime));
}
async function checkScheduledFocus(now = Date.now()) {
  if (scheduledFocusPlan) {
    const current = schedules.find(plan => plan.id === scheduledFocusPlan.id);
    if (!scheduledFocusEnabled || !current || scheduleFocusKey(current) !== scheduleFocusKey(scheduledFocusPlan) || ['done','miss'].includes(current.status) || document.getElementById('focus-active').style.display !== 'none') closeScheduledFocus();
    return;
  }
  if (!scheduledFocusEnabled || document.hidden || document.getElementById('focus-active').style.display !== 'none') return;
  if (scheduledFocusChecking || document.querySelector('dialog[open]') || document.querySelector('.modal-backdrop.open')) return;
  scheduledFocusChecking = true;
  try {
    const enter = () => {
      if (scheduledFocusPlan || !scheduledFocusEnabled) return;
      const claims = scheduledFocusClaims();
      const plan = eligibleFocusPlans(now).find(item => !claims.includes(scheduleFocusKey(item)));
      if (!plan) return;
      // Persist first; dismissed or reloaded schedules should not repeatedly interrupt.
      localStorage.setItem('sl_scheduled_focus_seen', JSON.stringify([...claims, scheduleFocusKey(plan)].slice(-256)));
      scheduledFocusPlan = {...plan};
      scheduledFocusPreviousElement = document.activeElement;
      showView('focus');
      document.getElementById('scheduled-focus-subject').textContent = subjectById(plan.subjectId).name;
      document.getElementById('scheduled-focus-content').textContent = plan.content || '予定していた勉強を始めましょう';
      document.getElementById('scheduled-focus-duration').textContent = `${Math.round(plan.duration * 60)}分`;
      scheduledFocusDialog.showModal();
      document.getElementById('scheduled-focus-start').focus();
    };
    if (navigator.locks) await navigator.locks.request('studycopi-scheduled-focus', enter); else enter();
  } catch { toast('集中画面を表示できませんでした。予定からタイマーを開いてください'); }
  finally { scheduledFocusChecking = false; }
}
function closeScheduledFocus() {
  scheduledFocusPlan = null;
  if (scheduledFocusDialog.open) scheduledFocusDialog.close();
  if (scheduledFocusPreviousElement?.isConnected) scheduledFocusPreviousElement.focus();
  scheduledFocusPreviousElement = null;
}
function dismissScheduledFocus() {
  if (confirm('タイマーを開始せずに戻ります。この予定では再表示しません。解除しますか？')) closeScheduledFocus();
}
function startScheduledFocus() {
  const plan = schedules.find(item => item.id === scheduledFocusPlan?.id && scheduleFocusKey(item) === scheduleFocusKey(scheduledFocusPlan));
  closeScheduledFocus();
  if (!plan || ['done','miss'].includes(plan.status)) { toast('予定が変更されました'); return; }
  startScheduleFocus(plan.id);
}
scheduledFocusDialog.addEventListener('cancel', event => { event.preventDefault(); dismissScheduledFocus(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden) checkScheduledFocus(); });
window.addEventListener('storage', event => {
  if (event.key === 'sl_scheduled_focus') {
    scheduledFocusEnabled = load('sl_scheduled_focus', false) === true;
    document.getElementById('scheduled-focus-enabled').checked = scheduledFocusEnabled;
    if (!scheduledFocusEnabled) closeScheduledFocus();
  }
});
setInterval(() => checkScheduledFocus(), 1000);
