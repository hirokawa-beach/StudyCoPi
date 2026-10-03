/* The guide points at real controls. Its example card never enters study data. */
let guideContent = null, guideStep = 0, guideTrigger = null, guideReturnView = 'dashboard', guideTarget = null;
const GUIDE_ROUTES = {today:'dashboard',plans:'schedule',exams:'examgroups',timer:'focus',record:'dashboard',stats:'hours',subjects:'subjects',backup:'data',settings:'settings',wake:'android'};
const TOUR_VIEWS = ['examgroups','schedule','focus','schedule','hours'];
function guideRoute(route) { showView(GUIDE_ROUTES[route] || 'dashboard'); }
function openGuide() {
  if (!guideContent) { toast('使い方を読み込み中です。少し待ってから開いてください'); return; }
  if (document.getElementById('modal-backdrop').classList.contains('open')) return;
  guideStep = 0; guideTrigger = document.activeElement; guideReturnView = document.body.dataset.view || 'dashboard';
  document.getElementById('guide-dialog').showModal();
  document.body.classList.add('guide-open'); renderGuideStep();
}
function renderGuideStep() {
  const step = guideContent.steps[guideStep];
  showView(TOUR_VIEWS[guideStep]);
  if (guideStep === 3) {
    const example = {id:'guide-example',subjectId:subjects[0]?.id,datetime:StudyModel.localDate()+'T17:00',duration:.75,content:'問題集 p.24〜28',status:'pending'};
    document.getElementById('schedule-mobile-list').innerHTML = '<p class="muted">操作例 · この予定は保存されません</p>' + buildScheduleCard(example);
  }
  const visible = selector => [...document.querySelectorAll(selector)].find(el=>el.getClientRects().length);
  guideTarget = guideStep < 2 ? visible('#quick-add, .view.active .desktop-add, .view.active .page-heading > .btn')
    : guideStep === 2 ? visible('#focus-setup button[onclick="startFocus()"], #focus-active .timer-ring')
    : guideStep === 3 ? visible('#schedule-mobile-list .study-card') : visible('#hours-exam-filter');
  document.getElementById('guide-count').textContent = `${guideStep + 1} / ${guideContent.steps.length}`;
  const progress = document.getElementById('guide-progress'); progress.value=guideStep+1; progress.max=guideContent.steps.length;
  document.getElementById('guide-title').textContent = step.coachTitle;
  document.getElementById('guide-description').textContent = step.coach;
  document.getElementById('guide-back').disabled = guideStep === 0;
  document.getElementById('guide-next').textContent = guideStep === guideContent.steps.length - 1 ? '使い始める' : '次へ';
  document.getElementById('guide-step-body').scrollTop = 0;
  guideTarget?.scrollIntoView({block:'center',behavior:'instant'});
  positionGuide(); document.getElementById('guide-title').focus({preventScroll:true}); requestAnimationFrame(positionGuide);
}
function positionGuide() {
  if (!document.getElementById('guide-dialog').open || !guideTarget) return;
  const rect = guideTarget.getBoundingClientRect(), viewport = window.visualViewport;
  const height = viewport?.height || innerHeight, width = viewport?.width || innerWidth, top = viewport?.offsetTop || 0, left = viewport?.offsetLeft || 0;
  const panel = document.querySelector('.guide-shell');
  panel.style.maxHeight = `${Math.max(160, Math.max(rect.top-top, top+height-rect.bottom)-32)}px`;
  const panelHeight = panel.getBoundingClientRect().height, below = rect.bottom + 16 + panelHeight <= top + height - 16;
  panel.style.top = `${below ? rect.bottom + 16 : Math.max(top+16,rect.top-panelHeight-16)}px`;
  panel.style.left = `${Math.min(Math.max(left+16,rect.left+rect.width/2-panel.offsetWidth/2),left+width-panel.offsetWidth-16)}px`;
  for (const id of ['guide-hole','guide-outline']) for (const [attr,value] of Object.entries({x:rect.left-6,y:rect.top-6,width:rect.width+12,height:rect.height+12})) document.getElementById(id).setAttribute(attr,value);
  document.getElementById('guide-location').textContent = (below ? '↑ ' : '↓ ') + guideContent.steps[guideStep].targetLabel;
  panel.dataset.placement = below ? 'below' : 'above';
}
function moveGuide(direction) {
  if (direction === 1 && guideStep === guideContent.steps.length - 1) { closeGuide(); return; }
  guideStep = Math.max(0, Math.min(guideContent.steps.length - 1, guideStep + direction)); renderGuideStep();
}
function closeGuide() {
  if (guideContent) { try { save('sl_guide_version', guideContent.version); } catch {} }
  document.getElementById('guide-dialog').close(); document.body.classList.remove('guide-open'); guideTarget = null;
  showView(guideReturnView); if (guideTrigger?.isConnected) guideTrigger.focus({preventScroll:true});
}
async function initializeGuide() {
  try {
    const response = await fetch('./shared/study-guide.json'); if (!response.ok) throw new Error('Guide unavailable');
    guideContent = await response.json();
    document.getElementById('help-topics').innerHTML = guideContent.topics.map(topic => `<details class="help-topic"><summary>${esc(topic.title)}</summary><div>${topic.body.map(line=>`<p>${esc(line)}</p>`).join('')}${topic.web ? `<p class="muted">${esc(topic.web)}</p>` : ''}<button class="btn btn-tonal" onclick="guideRoute('${topic.route}')">${topic.route === 'wake' ? 'Android版の案内を開く' : esc(topic.action)}</button></div></details>`).join('');
    if (load('sl_guide_version', 0) < guideContent.version && document.getElementById('focus-active').style.display === 'none') openGuide();
  } catch { document.getElementById('help-topics').textContent = '使い方を読み込めませんでした。通信を確認して再読み込みしてください。'; }
}
document.getElementById('guide-dialog').addEventListener('cancel', event => { event.preventDefault(); closeGuide(); });
new ResizeObserver(positionGuide).observe(document.querySelector('.guide-shell'));
addEventListener('resize',positionGuide);
window.visualViewport?.addEventListener('resize',positionGuide);
window.visualViewport?.addEventListener('scroll',positionGuide);
initializeGuide();
