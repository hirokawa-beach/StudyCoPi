const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const url = process.env.STUDYCOPI_TEST_URL || 'http://127.0.0.1:8765';
(async () => {
  const browser = await chromium.launch({headless:true, channel:process.env.STUDYCOPI_BROWSER_CHANNEL || 'chrome'});
  const page = await browser.newPage({viewport:{width:390,height:844},locale:'en-US',timezoneId:'Asia/Tokyo'});
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(url);
  await page.waitForSelector('.today-summary strong');
  await page.evaluate(() => {
    const today = StudyModel.localDate();
    subjects = [{id:'long',name:'数学・場合の数と確率の間違えた問題の復習',color:'#185fa5'}];
    schedules = [
      {id:'one',subjectId:'long',datetime:`${today}T17:00`,duration:1/60,content:'1分の確認',status:'pending'},
      {id:'five',subjectId:'long',datetime:`${today}T17:01`,duration:5/60,content:'5分の確認',status:'pending'},
      {id:'late',subjectId:'long',datetime:`${today}T23:59`,duration:1/60,content:'日付の最後',status:'pending'}
    ];
    exams = [{id:'exam',subject:subjects[0].name,date:today,startTime:'17:02',endTime:'17:07'}];
    showView('timetable'); setTTMode('grid');
  });
  await page.evaluate(() => document.fonts.ready);
  assert.equal(await page.evaluate(() => document.fonts.check('16px "StudyCoPi Japanese"')),true);
  assert.equal(await page.evaluate(() => [...document.fonts].some(f => f.family === 'StudyCoPi Japanese' && f.status === 'loaded')),true);
  for (const width of [320,390,1440]) {
    await page.setViewportSize({width,height:844});
    await page.evaluate(() => renderTimetable());
    const result = await page.evaluate(() => {
      const blocks = [...document.querySelectorAll('.tt2-block')];
      const rects = blocks.map(b => b.getBoundingClientRect());
      return {
        count: blocks.length,
        clipped: blocks.some(b => {
          const label = b.querySelector('.tt2-block-name');
          return label.scrollHeight > label.clientHeight + 1 || label.scrollWidth > label.clientWidth + 1
            || label.getBoundingClientRect().bottom > b.getBoundingClientRect().bottom;
        }),
        overlaps: rects.some((a,i) => rects.slice(i+1).some(b => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top)),
        overflow: document.documentElement.scrollWidth > innerWidth + 1,
        lanes: blocks.map(b => b.dataset.lane),
        font: getComputedStyle(blocks[0]).fontFamily,
        lateFits: (() => { const b = document.querySelector('[data-schedule-id=late]'); return b.offsetTop + b.offsetHeight <= b.parentElement.offsetHeight; })()
      };
    });
    assert.equal(result.count,4);
    assert.equal(result.clipped,false,`full subject name @ ${width}`);
    assert.equal(result.overlaps,false,`short plans and exams stay separate @ ${width}`);
    assert.equal(result.overflow,false,`scroll stays inside calendar @ ${width}`);
    assert.equal(result.lateFits,true);
    assert.match(result.font,/StudyCoPi Japanese/);
    assert.ok(new Set(result.lanes).size >= 3);
  }
  await page.evaluate(() => {
    const block = document.querySelector('[data-schedule-id=one]');
    const wrap = document.querySelector('.tt2-wrap');
    const b = block.getBoundingClientRect(), w = wrap.getBoundingClientRect();
    wrap.scrollTop += b.top - w.top - 120;
    wrap.scrollLeft += b.left - w.left - 60;
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page.evaluate(() => {
    const b = document.querySelector('[data-schedule-id=one]').getBoundingClientRect();
    const w = document.querySelector('.tt2-wrap').getBoundingClientRect();
    return b.top >= w.top && b.bottom <= w.bottom && b.left >= w.left && b.right <= w.right;
  }),true);
  await page.screenshot({path:'.artifacts/week-short-web.png'});
  await page.locator('[data-schedule-id=one]').click();
  assert.equal(await page.locator('#m-content').inputValue(),'1分の確認');
  assert.equal(await page.locator('#m-duration').inputValue(),'1');
  await page.evaluate(() => closeModal());
  await page.evaluate(() => document.querySelector('[data-exam-id=exam]').scrollIntoView({block:'center',inline:'center'}));
  await page.locator('[data-exam-id=exam]').click();
  assert.equal(await page.locator('#m-esubject').inputValue(),'数学・場合の数と確率の間違えた問題の復習');
  assert.equal(await page.locator('#modal-backdrop').isVisible(),true);
  assert.deepEqual(errors,[]);
  await browser.close();
  console.log('PASS: 1/5-minute plans, long Japanese names, mixed collisions, last-minute events, mobile/desktop and edit actions');
})().catch(e => {console.error(e);process.exit(1);});
