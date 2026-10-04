// Backup reminders are checked on opening/returning to the app, never while editing.
const BACKUP_INTERVAL = 3 * 86400000;
const BACKUP_KEYS = new Set(['sl_subjects', 'sl_schedules', 'sl_exams', 'sl_exam_groups', 'sl_android_backup', 'sl_sound']);
let backupState = load('sl_backup_state', null);
if (!backupState || !Number.isFinite(backupState.lastAt)) {
  backupState = {lastAt: Date.now(), dirty: !!(schedules.length || exams.length || examGroups.length || localStorage.getItem("sl_subjects")), snoozeUntil: 0};
  localStorage.setItem('sl_backup_state', JSON.stringify(backupState));
}
function persistBackupState() { localStorage.setItem('sl_backup_state', JSON.stringify(backupState)); }
function markBackupDirty(key) {
  if (!BACKUP_KEYS.has(key)) return;
  backupState.dirty = true;
  persistBackupState();
}
function showBackupReminder(now = Date.now()) {
  document.getElementById('backup-reminder').hidden = !(backupState.dirty && now - backupState.lastAt >= BACKUP_INTERVAL && now >= (backupState.snoozeUntil || 0));
}
function markBackupExported() {
  backupState = {lastAt: Date.now(), dirty: false, snoozeUntil: 0};
  persistBackupState(); showBackupReminder();
  toast('バックアップファイルを作成しました');
}
function snoozeBackupReminder() {
  backupState.snoozeUntil = Date.now() + 86400000;
  persistBackupState(); showBackupReminder();
}
function showPlansMode(mode) {
  if (mode === 'schedule') showView('schedule');
  else { setTTMode(mode); showView('timetable'); }
  updatePlanTabs();
}
function updatePlanTabs() {
  const active = document.body.dataset.view === 'schedule' ? 'schedule' : ttMode;
  document.querySelectorAll('[data-plan-mode]').forEach(button => {
    const selected = button.dataset.planMode === active;
    button.classList.toggle('selected', selected);
    if (selected) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  });
  const heading = document.querySelector('#view-timetable .view-subtitle');
  if (heading) heading.textContent = ttMode === 'grid' ? '横にスワイプして各曜日を確認。予定をタップして編集' : '日付を選んで予定を確認';
}
const SOUND_CHOICES = [['bell', 'ベル'], ['pulse', '電子音'], ['chime', 'チャイム']];
let soundPreview = null;
function stopSoundPreview() { if (soundPreview) { soundPreview.pause(); soundPreview = null; } }
function playSoundPreview(key) {
  stopSoundPreview();
  if (!SOUND_CHOICES.some(([id]) => id === key)) return;
  soundPreview = new Audio(`shared/sounds/${key}.wav`);
  soundPreview.play().catch(() => toast('音を再生できませんでした。音量とブラウザの設定をご確認ください'));
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopSoundPreview(); else showBackupReminder();
});
window.addEventListener('pageshow', () => showBackupReminder());
window.addEventListener('storage', event => {
  if (event.key === 'sl_backup_state') { backupState = load('sl_backup_state', backupState); showBackupReminder(); }
});
showBackupReminder(); updatePlanTabs();

function validateWebSound(value) {
  if (value === undefined) return {key: 'bell', enabled: true};
  if (!value || !SOUND_CHOICES.some(([id]) => id === value.key) || typeof value.enabled !== 'boolean') throw new Error('音源設定が不正です');
  return {key: value.key, enabled: value.enabled};
}
let webSound;
try { webSound = validateWebSound(load('sl_sound', undefined)); } catch { webSound = {key: 'bell', enabled: true}; }
const timerAudio = new Audio();
function loadWebSoundUI() {
  document.getElementById('alarm-sound').value = webSound.key;
  document.getElementById('alarm-sound-enabled').checked = webSound.enabled;
}
function saveWebSound() {
  stopSoundPreview(); timerAudio.pause();
  webSound = {key: document.getElementById('alarm-sound').value, enabled: document.getElementById('alarm-sound-enabled').checked};
  save('sl_sound', webSound);
}
function unlockTimerSound() {
  if (!webSound.enabled) return;
  timerAudio.src = `shared/sounds/${webSound.key}.wav`;
  timerAudio.volume = 0;
  timerAudio.play().then(() => { timerAudio.pause(); timerAudio.currentTime = 0; timerAudio.volume = 1; }).catch(() => { timerAudio.volume = 1; });
}
function playTimerSound() {
  if (!webSound.enabled) return;
  stopSoundPreview();
  timerAudio.src = `shared/sounds/${webSound.key}.wav`; timerAudio.volume = 1;
  timerAudio.play().catch(() => toast('タイマーが終了しました。音の再生をブラウザが許可していません'));
}
loadWebSoundUI();
