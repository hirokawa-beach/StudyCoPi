// Run with NODE_PATH pointing to a Playwright installation; serve the app on 8765.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const url = process.env.STUDYCOPI_TEST_URL || 'http://127.0.0.1:8765';
(async () => {
  const browser = await chromium.launch({headless:true,channel:process.env.STUDYCOPI_BROWSER_CHANNEL || 'chrome'});
  const context = await browser.newContext({viewport:{width:390,height:844},timezoneId:'Asia/Tokyo',colorScheme:'light',isMobile:true,hasTouch:true});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('dialog', (d) => d.accept());
  await page.clock.install({time:new Date('2026-10-03T12:00:00+09:00')});
  await page.goto(url);
  await page.waitForSelector('.today-summary strong');
  const visible = async (selector) => assert.equal(await page.locator(selector).isVisible(), true, selector);
  const save = async () => page.locator('#modal-form button[type=submit]').click();
  const evalApp = async (source) => page.evaluate((source) => eval(source), source);
  await visible('.mobile-bottom-nav button.active[data-view=dashboard]');
  assert.equal(await page.locator('.mobile-bottom-nav button').count(), 5);

  async function group(name,type,start,end) {
    await evalApp("showView('examgroups'); openModal('examgroup')");
    await page.locator('#m-group-name').fill(name);
    await page.locator('#m-group-type').selectOption(type);
    await page.locator('#m-group-start').fill(start);
    await page.locator('#m-group-end').fill(end);
    await save();
    return evalApp('examGroups.at(-1).id');
  }
  const first = await group('2学期中間考査','定期考査','2026-10-20','2026-10-23');
  const second = await group('第2回模試','模試','2026-10-20','2026-10-20');
  const past = await group('第1回模試','模試','2025-06-10','2025-06-10');
  assert.equal(await page.locator('.exam-group-card').count(),3);
  await page.locator('#exam-year-filter').selectOption('2025');
  assert.equal(await page.locator('.exam-group-card').count(),1);
  await page.locator('#exam-year-filter').selectOption('');

  await evalApp(`openModal('examgroup',examGroups.find(g=>g.id==='${first}'))`);
  await page.locator('#m-group-end').fill('2026-10-19'); await save();
  await visible('#form-error');
  assert.equal(await evalApp(`examGroups.find(g=>g.id==='${first}').endDate`),'2026-10-23');
  await evalApp('closeModal()');

  async function schedule(groupId,minutes,content) {
    await evalApp("showView('schedule');openModal('schedule')");
    await page.locator('#m-group').selectOption(groupId);
    await page.locator('#m-datetime').fill('2026-10-03T17:00');
    await page.locator('#m-duration').fill(String(minutes));
    await page.locator('#m-content').fill(content);
    await save();
    return evalApp('schedules.at(-1).id');
  }
  const s1 = await schedule(first,45,'問題集 p.24〜28');
  const s2 = await schedule(second,60,'模試の過去問');
  await evalApp(`openRecord('${s1}')`);
  await page.locator('label:has(input[value=partial])').click();
  await page.locator('#record-minutes').fill('20'); await save();

  assert.equal(await evalApp(`StudyModel.totals(StudyModel.forExam(schedules,'${first}')).actual`),1/3);
  await page.locator('#toast-undo').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').status`),'pending');
  await evalApp(`openRecord('${s1}')`);
  await page.locator('label:has(input[value=partial])').click();
  await page.locator('#record-minutes').fill('20'); await save();

  // A single tap records completion; undo preserves the existing partial time.
  await page.locator('.mobile-bottom-nav button[data-view=dashboard]').tap();
  for (const selector of ['#quick-add','.study-check','.study-actions .btn','.mobile-bottom-nav button']) {
    for (const target of await page.locator(selector).all()) {
      const bounds = await target.boundingBox();
      if (bounds) assert.ok(bounds.width >= 44 && bounds.height >= 44, `touch target ${selector}`);
    }
  }
  const rowBounds = await page.locator('#dash-mobile-list .study-card').first().boundingBox();
  assert.ok(rowBounds.height <= 145, 'compact study rows on a phone');
  const firstRow = page.locator('#dash-mobile-list .study-card').filter({hasText:'問題集 p.24〜28'});
  await firstRow.locator('.study-check').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').status`),'done');
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').actualDuration`),1/3);
  assert.equal(await page.locator('#modal-backdrop').isVisible(),false);
  await page.locator('#toast-undo').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').status`),'partial');
  const secondRow = page.locator('#dash-mobile-list .study-card').filter({hasText:'模試の過去問'});
  await secondRow.locator('.study-check').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s2}').actualDuration`),1);
  await page.locator('#toast-undo').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s2}').actualDuration`),undefined);
  await page.locator('.mobile-bottom-nav button[data-view=examgroups]').tap();
  await page.locator('#quick-add').tap();
  await visible('#m-group-name');
  await page.locator('.modal-header button').tap();

  await page.locator('.mobile-bottom-nav button[data-view=schedule]').tap();
  assert.equal(await page.locator('#filter-exam').isVisible(),false);
  await page.locator('#schedule-filters summary').tap();
  await visible('#filter-exam');
  await page.locator('#filter-exam').selectOption(first);
  assert.equal(await page.locator('#schedule-mobile-list .study-card').count(),1);
  await page.locator('#filter-exam').selectOption('');
  assert.equal(await evalApp(`StudyModel.totals(StudyModel.forExam(schedules,'${first}')).actual`),1/3);

  await evalApp(`viewExamHours('${first}')`);
  assert.match(await page.locator('#hours-stats').innerText(),/20分/);
  assert.match(await page.locator('#hours-stats').innerText(),/45分/);
  await page.locator('#hours-exam-filter').selectOption(second);
  assert.match(await page.locator('#hours-stats').innerText(),/0分/);
  assert.match(await page.locator('#hours-stats').innerText(),/1時間/);
  await page.locator('#hours-period-preset').selectOption('custom');
  await page.locator('#hours-from').fill('2026-10-04');
  assert.match(await page.locator('#hours-context').innerText(),/0件/);
  await page.locator('#hours-period-preset').selectOption('all');

  await evalApp(`startScheduleFocus('${s1}')`);
  assert.equal(await page.locator('#focus-exam-group').inputValue(),first);
  assert.equal(await page.locator('#focus-exam-group').isDisabled(),true);
  await page.locator('.mobile-bottom-nav button[data-view=dashboard]').tap();
  await visible('#running-focus');
  await page.locator('#dash-mobile-list .study-card').filter({hasText:'問題集 p.24〜28'}).locator('.study-check').tap();
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').status`),'partial');
  await page.locator('#running-focus').tap();
  await visible('#focus-active');
  await page.clock.fastForward(10*60*1000);
  await page.locator('#focus-pause-btn').click();
  assert.equal(await page.locator('#running-focus-label').textContent(),'一時停止中');
  await page.clock.fastForward(5*60*1000);
  await evalApp('stopFocus()');
  assert.equal(await page.locator('#running-focus').isVisible(),false);
  const accumulated = await evalApp(`schedules.find(s=>s.id==='${s1}').actualDuration`);
  assert.ok(Math.abs(accumulated-.5)<.02,`accumulated ${accumulated}`);
  assert.equal(await evalApp(`schedules.find(s=>s.id==='${s1}').examGroupId`),first);

  await evalApp("showView('focus')");
  await page.locator('#focus-exam-group').selectOption(second);
  await page.locator('#focus-minutes').fill('5');
  await evalApp('startFocus()');
  await page.clock.fastForward(5*60*1000);
  assert.equal(await page.locator('#focus-active').isVisible(),false);
  assert.equal(await evalApp('schedules.at(-1).examGroupId'),second);

  await evalApp(`openModal('exam',{examGroupId:'${first}',date:'2026-10-20'})`);
  await page.locator('#m-esubject').fill('数学');
  await page.locator('#m-estart').fill('09:00');
  await page.locator('#m-eend').fill('10:00'); await save();
  assert.equal(await evalApp('exams[0].examGroupId'),first);
  await evalApp(`openModal('examgroup',examGroups.find(g=>g.id==='${first}'))`);
  await page.locator('#m-group-start').fill('2026-10-21'); await save(); await visible('#form-error');
  await evalApp('closeModal()');

  // User text is rendered as text, including in the retained calendar.
  const xss = await schedule(second,30,'<img src=x onerror=alert(1)>');
  assert.equal(await page.locator('#schedule-mobile-list img').count(),0);
  await evalApp(`deleteSchedule('${xss}')`);
  await page.locator('#toast-undo').click();
  assert.equal(await evalApp(`schedules.some(s=>s.id==='${xss}')`),true);
  await evalApp(`deleteSchedule('${xss}')`);

  // Backup download, new-format restore and old-format restore.
  await evalApp("showView('data')");
  const [download] = await Promise.all([page.waitForEvent('download'),evalApp('exportData()')]);
  const backup = JSON.parse(fs.readFileSync(await download.path(),'utf8'));
  assert.equal(backup.version,3); assert.equal(backup.examGroups.length,3);
  const before = await evalApp('JSON.stringify(schedules)');
  await page.locator('#import-file').setInputFiles({name:'backup.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(backup))});
  await page.waitForFunction("document.getElementById('toast-message').textContent === 'データを復元しました'");
  assert.equal(await evalApp('JSON.stringify(schedules)'),before);
  await evalApp(`openModal('examgroup',examGroups.find(g=>g.id==='${past}'));deleteExamGroup('${past}')`);
  assert.equal(await evalApp(`examGroups.some(g=>g.id==='${past}')`),false);

  // Layout and keyboard checks for each active view on narrow/mobile/desktop.
  fs.mkdirSync(path.resolve('.artifacts'),{recursive:true});
  for (const width of [320,390,768,1440]) {
    await page.setViewportSize({width,height:900});
    for (const view of ['dashboard','schedule','timetable','examgroups','hours','focus','settings','data','more','help']) {
      await evalApp(`showView('${view}')`);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
      assert.equal(overflow,false,`horizontal overflow ${view} @ ${width}`);
    }
    await evalApp("showView('dashboard')");
    if ([390,1440].includes(width)) {
      await page.evaluate(() => document.getElementById('toast').hidden = true);
      await page.screenshot({path:`.artifacts/home-${width}.png`,fullPage:true});
    }
  }
  await page.setViewportSize({width:390,height:844});
  await evalApp(`viewExamSchedules('${first}')`);
  assert.equal(await page.locator('#schedule-mobile-list .study-card').count(),1);
  await evalApp("openModal('schedule')");
  assert.equal(await page.locator('.app').getAttribute('inert'),'');
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.closest('#modal-content') !== null),true);
  await page.screenshot({path:'.artifacts/add-schedule-mobile.png',fullPage:true});
  await page.keyboard.press('Escape'); assert.equal(await page.locator('.modal-backdrop').isVisible(),false);
  await page.emulateMedia({colorScheme:'dark',reducedMotion:'reduce'});
  await evalApp("showView('examgroups')");
  await page.screenshot({path:'.artifacts/exams-dark-mobile.png',fullPage:true});
  await page.reload(); await page.waitForSelector('.today-summary strong');
  assert.equal(await evalApp('examGroups.length'),2);
  const legacy = {subjects:backup.subjects,schedules:backup.schedules.map(({examGroupId,...s})=>s),exams:backup.exams.map(({examGroupId,...e})=>e)};
  await page.locator('#import-file').setInputFiles({name:'legacy.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(legacy))});
  await page.waitForFunction('examGroups.length===0');
  assert.equal(await evalApp('schedules.length'),legacy.schedules.length);
  assert.equal(await evalApp('exams.length'),legacy.exams.length);
  assert.deepEqual(errors,[]);
  const unsupported = await browser.newContext({viewport:{width:390,height:844},timezoneId:'Asia/Tokyo'});
  await unsupported.addInitScript(() => { delete window.Notification; });
  const fallback = await unsupported.newPage();
  fallback.on('pageerror', (e) => errors.push(e.message));
  await fallback.goto(url);
  await fallback.waitForSelector('.today-summary strong');
  await fallback.evaluate(() => { showView('focus'); document.getElementById('focus-minutes').value = 1; startFocus(); focusStart = Date.now()-120000; tickFocus(); stopFocus(); });
  assert.equal(await fallback.evaluate(() => schedules.length),1);
  assert.equal(await fallback.evaluate(() => Math.round(schedules[0].actualDuration*60)),1);
  assert.deepEqual(errors,[]);
  await browser.close();
  console.log('PASS: trial groups, partial totals, timer accumulation, backup migration, mobile/desktop layout and keyboard navigation');
})().catch((e) => { console.error(e); process.exit(1); });
