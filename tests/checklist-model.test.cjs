const {test} = require('node:test');
const assert = require('node:assert/strict');
const model = require('../study-model.js');
const backup = () => ({version:4,subjects:[{id:'s1',name:'数学',color:'#185fa5'}],examGroups:[{id:'g1',name:'中間考査',type:'定期考査',startDate:'2026-10-20',endDate:'2026-10-20'}],schedules:[],exams:[{id:'e1',subject:'数学',date:'2026-10-20',examGroupId:'g1',note:'問題集 p.20〜40',checklist:[{id:'c1',title:'p.20〜25',status:'done'},{id:'c2',title:'p.26〜30',status:'review'}]}]});
test('v4 checklists round trip without losing scope or the original range note', () => {
  const data = model.validateBackup(JSON.parse(JSON.stringify(backup())));
  assert.equal(data.exams[0].examGroupId,'g1'); assert.equal(data.exams[0].note,'問題集 p.20〜40');
  assert.deepEqual(data.exams[0].checklist,backup().exams[0].checklist);
  assert.deepEqual(model.checklistTotals(data.exams[0].checklist),{count:2,done:1,review:1});
});
test('legacy backups accept exams without checklists and unsupported schemas fail', () => {
  const old = backup(); old.version=3; delete old.exams[0].checklist;
  assert.equal(model.validateBackup(old).exams[0].checklist,undefined);
  assert.throws(()=>model.validateBackup({...old,version:5}),/未対応/);
});
test('invalid and duplicate checklist items are rejected before data is restored', () => {
  for (const change of [data=>data.exams[0].checklist.push(data.exams[0].checklist[0]),data=>data.exams[0].checklist[0].status='unknown',data=>data.exams[0].checklist[0].title='  ',data=>data.exams[0].checklist[0].title=42,data=>data.exams[0].checklist[0].id="x');alert(1)",data=>data.exams[0].checklist='bad']) {
    const data=backup(); change(data); assert.throws(()=>model.validateBackup(data),/チェックリスト/);
  }
});
