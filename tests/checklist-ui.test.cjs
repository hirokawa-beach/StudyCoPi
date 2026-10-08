const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser=await chromium.launch({headless:true,channel:'chrome'});
  for(const width of [320,390,1440]) {
    const context=await browser.newContext({viewport:{width,height:900},timezoneId:'Asia/Tokyo'});
    const page=await context.newPage(), errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>{
      localStorage.setItem('sl_guide_version','1');
      if (!localStorage.getItem('sl_exam_groups')) localStorage.setItem('sl_exam_groups',JSON.stringify([{id:'g1',name:'中間考査A',type:'定期考査',startDate:'2026-10-20',endDate:'2026-10-20'},{id:'g2',name:'模試B',type:'模試',startDate:'2026-11-01',endDate:'2026-11-01'}]));
      if (!localStorage.getItem('sl_exams')) localStorage.setItem('sl_exams',JSON.stringify([{id:'e1',examGroupId:'g1',subject:'数学',date:'2026-10-20',note:'問題集 p.20〜40'},{id:'e2',examGroupId:'g2',subject:'数学',date:'2026-11-01',note:'別の範囲'}]));
    });
    await page.clock.install({time:new Date('2026-10-08T18:00:00+09:00')});
    await page.goto(process.env.STUDYCOPI_TEST_URL || 'http://127.0.0.1:8769');
    await page.clock.pauseAt(new Date('2026-10-08T20:00:00+09:00'));
    await page.evaluate(()=>{showView('examgroups');openChecklist('e1')});
    await page.locator('#checklist-add').fill('問題集 p.20〜25\n\n問題集 p.26〜30');
    await page.locator('#checklist-add-panel button[type=submit]').click();
    assert.equal(await page.locator('.checklist-item').count(),2);
    await page.locator('.checklist-item').first().locator('input[type=checkbox]').check();
    await page.locator('.checklist-item').last().locator('select').selectOption('review');
    assert.match(await page.locator('#checklist-summary').innerText(),/全2件 · 完了1件 · 未完了1件 · 要復習1件/);
    await page.locator('[data-checklist-filter=review]').click(); assert.equal(await page.locator('.checklist-item').count(),1);
    await page.locator('[data-checklist-filter=all]').click();
    assert.equal(await page.locator('.checklist-detail').count(),0);
    await page.locator('.checklist-item').last().locator('.checklist-title').click();
    await page.locator('.checklist-item').last().locator('input[type=text]').fill('二次関数の復習');
    await page.locator('.checklist-item').last().getByRole('button',{name:'保存',exact:true}).click();
    await page.locator('.checklist-item').last().locator('.checklist-title').click();
    await page.locator('.checklist-item').last().getByRole('button',{name:'削除'}).click();
    assert.equal(await page.locator('.checklist-item').count(),1);
    await page.evaluate(()=>undoLastAction());assert.equal(await page.locator('.checklist-item').count(),2);
    await page.clock.runFor(8000);
    const directory=path.join(__dirname,'../.artifacts/web');fs.mkdirSync(directory,{recursive:true});
    await page.screenshot({path:path.join(directory,`checklist-${width}.png`),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.locator('.modal-header button').click();
    await page.evaluate(()=>editExam('e1')); await page.locator('#m-esubject').fill('数学I');
    await page.locator('#modal-form button[type=submit]').click();
    assert.equal(await page.evaluate(()=>exams.find(e=>e.id==='e1').checklist[1].title),'二次関数の復習');
    await page.evaluate(()=>openChecklist('e2')); assert.equal(await page.locator('.checklist-item').count(),0);
    await page.locator('.modal-header button').click();
    const [download]=await Promise.all([page.waitForEvent('download'),page.evaluate(()=>exportData())]);
    const backup=JSON.parse(fs.readFileSync(await download.path(),'utf8'));
    assert.equal(backup.version,4);assert.equal(backup.exams.find(e=>e.id==='e1').checklist[0].status,'done');
    await page.locator('#import-file').setInputFiles({name:'checklist-v4.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(backup))});
    await page.waitForFunction(()=>exams.find(e=>e.id==='e1')?.checklist?.length===2);
    await page.reload(); await page.evaluate(()=>openChecklist('e1')); assert.equal(await page.locator('.checklist-item').count(),2);
    await page.locator('.modal-header button').click();
    // A realistic 20-item list fits several rows and keeps the overall counts fixed.
    await page.evaluate(()=>{
      const exam=exams.find(e=>e.id==='e1');exam.checklist=Array.from({length:20},(_,i)=>({id:`dense-${i}`,title:`問題集 p.${20+i*2}〜${21+i*2}`,status:i<8?'done':i<11?'review':'pending'}));saveChecklist(exam);openChecklist('e1');
    });
    assert.equal(await page.locator('.checklist-detail').count(),0);
    assert.equal(await page.locator('.checklist-item input[type=text]').count(),0);
    const shown=await page.evaluate(()=>{
      const bounds=document.getElementById('checklist-scroll').getBoundingClientRect();
      return [...document.querySelectorAll('.checklist-item')].filter(row=>{const b=row.getBoundingClientRect();return b.top>=bounds.top && b.bottom<=bounds.bottom}).length;
    });
    assert.ok(shown>=6,`only ${shown} rows fit at ${width}px`);
    await page.screenshot({path:path.join(directory,`checklist-compact-${width}.png`),fullPage:true});
    const before=await page.locator('.checklist-overview').boundingBox();
    await page.locator('#checklist-scroll').evaluate(el=>el.scrollTop=400);
    const after=await page.locator('.checklist-overview').boundingBox();assert.equal(before.y,after.y);
    const scroll=await page.locator('#checklist-scroll').evaluate(el=>el.scrollTop);
    await page.locator('[data-item-id=dense-12] input[type=checkbox]').check();
    assert.ok(Math.abs(await page.locator('#checklist-scroll').evaluate(el=>el.scrollTop)-scroll)<=1,'checking a row moved the list');
    await page.locator('[data-checklist-filter=remaining]').click();assert.equal(await page.locator('.checklist-item').count(),11);
    await page.locator('[data-checklist-filter=review]').click();assert.equal(await page.locator('.checklist-item').count(),3);
    assert.match(await page.locator('#checklist-summary').innerText(),/全20件/);
    await page.locator('.modal-header button').click();
    // A short unplanned session, including zero seconds, is a completed record with real elapsed time.
    for(const seconds of [0,1,60]) {
      await page.evaluate(()=>{showView('focus');document.getElementById('focus-minutes').value=25;startFocus()});
      if(seconds) await page.clock.runFor(seconds*1000);
      await page.evaluate(()=>stopFocus());
      const last=await page.evaluate(()=>schedules.at(-1)); assert.equal(last.status,'done'); assert.ok(Math.abs(last.actualDuration-seconds/3600)<.000001);
    }
    assert.deepEqual(errors,[]);await context.close();
  }
  await browser.close();console.log('PASS: checklist editing, four states, scope isolation, backup/reload, responsive UI, and unplanned timer completion');
})().catch(e=>{console.error(e);process.exit(1)});
