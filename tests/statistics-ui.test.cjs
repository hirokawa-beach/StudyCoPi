const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({headless:true,channel:process.env.STUDYCOPI_BROWSER_CHANNEL || 'chrome'});
  for (const width of [320,390,1440]) for (const colorScheme of ['light','dark']) {
    const context = await browser.newContext({viewport:{width,height:900},timezoneId:'Asia/Tokyo',colorScheme});
    const page = await context.newPage();
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.clock.install({time:new Date('2026-10-06T12:00:00+09:00')});
    await page.addInitScript(() => {
      localStorage.setItem('sl_guide_version','1');
      localStorage.setItem('sl_exam_groups',JSON.stringify([{id:'g1',name:'中間考査',type:'定期考査',startDate:'2026-10-20',endDate:'2026-10-22'}]));
      localStorage.setItem('sl_schedules',JSON.stringify([
        {id:'a',subjectId:'s1',datetime:'2026-10-05T17:00:00',duration:2,actualDuration:1.5,status:'partial',examGroupId:'g1'},
        {id:'b',subjectId:'s2',datetime:'2026-10-06T18:00:00',duration:1,actualDuration:0,status:'done'},
        {id:'c',subjectId:'s1',datetime:'2026-09-30T17:00:00',duration:3,status:'done',examGroupId:'g1'}
      ]));
    });
    await page.goto(process.env.STUDYCOPI_TEST_URL || 'http://127.0.0.1:8768');
    await page.clock.pauseAt(new Date('2026-10-06T13:00:00+09:00'));
    await page.evaluate(()=>showView('hours'));
    await page.locator('#hours-period-preset').selectOption('week');
    await page.locator('#hours-bucket').selectOption('day');
    assert.equal(await page.locator('.history-column').count(),7);
    await page.locator('#hours-exam-filter').selectOption('g1');
    await page.locator('.heat-cell[aria-label^="2026-10-05"]').click();
    assert.match(await page.locator('#hours-history-detail').innerText(),/1時間30分/);
    assert.match(await page.locator('#hours-heat-summary').innerText(),/1日/);
    await page.locator('#hours-period-nav button').first().click();
    assert.match(await page.locator('#hours-period-label').innerText(),/2026-09-28/);
    assert.match(await page.locator('#hours-stats').innerText(),/3時間/);
    await page.locator('#hours-period-preset').selectOption('all');
    await page.locator('#hours-bucket').selectOption('month');
    assert.equal(await page.locator('.history-column').count(),2);
    assert.equal(await page.locator('#hours-heatmap .heat-cell:not(.outside)').count(),365);
    const output=path.join(__dirname,'../.artifacts/web'); fs.mkdirSync(output,{recursive:true});
    await page.screenshot({path:path.join(output,`statistics-${width}-${colorScheme}.png`),fullPage:true});
    await page.locator('.history-card').nth(1).screenshot({path:path.join(output,`heatmap-${width}-${colorScheme}.png`)});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth > innerWidth),false);
    // Check every second boundary and the fractional time preserved across pause.
    await page.evaluate(()=>{showView('focus');document.getElementById('focus-minutes').value=1;startFocus()});
    for (let i=1;i<=24;i++) {
      await page.clock.runFor(250);
      const remaining=60-Math.floor(i/4);
      assert.equal(await page.locator('#focus-timer-display').innerText(),`${String(Math.floor(remaining/60)).padStart(2,'0')}:${String(remaining%60).padStart(2,'0')}`);
    }
    await page.clock.runFor(450);
    await page.evaluate(()=>pauseFocus());
    const elapsed=await page.evaluate(()=>focusElapsed); assert.ok(elapsed>=6.45 && elapsed<6.46, `fractional elapsed: ${elapsed}`);
    await page.clock.runFor(5000);
    assert.equal(await page.locator('#focus-timer-display').innerText(),'00:54');
    await page.evaluate(()=>pauseFocus());
    await page.clock.runFor(600);
    assert.equal(await page.locator('#focus-timer-display').innerText(),'00:53');
    assert.deepEqual(errors,[]);
    await context.close();
  }
  await browser.close(); console.log('Statistics layouts, filters, navigation, and timer cadence passed.');
})().catch(e=>{console.error(e);process.exit(1)});
