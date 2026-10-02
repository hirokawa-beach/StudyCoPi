const { test } = require('node:test');
const assert = require('node:assert/strict');
process.env.TZ = 'Asia/Tokyo';
const model = require('../study-model.js');

test('exam totals keep overlapping and repeated exams separate', () => {
  const items = [
    { examGroupId: 'midterm', duration: 2, actualDuration: .5, status: 'partial' },
    { examGroupId: 'mock', duration: 1, actualDuration: 1, status: 'done' },
    { examGroupId: 'old-mock', duration: 3, status: 'done' },
    { duration: 1, status: 'pending' },
  ];
  assert.deepEqual(model.totals(model.forExam(items, 'midterm')), { planned: 2, actual: .5, count: 1, done: 0 });
  assert.equal(model.totals(model.forExam(items, 'old-mock')).actual, 3);
  assert.equal(model.forExam(items, 'unassigned').length, 1);
  assert.equal(model.totals(items).actual, 4.5);
});
test('explicit zero is retained, partial study counts, unrecorded plans do not', () => {
  assert.equal(model.actualHours({ duration: 1, actualDuration: 0, status: 'done' }), 0);
  assert.equal(model.actualHours({ duration: 1, actualDuration: .25, status: 'partial' }), .25);
  assert.equal(model.actualHours({ duration: 1, status: 'partial' }), 0);
  assert.equal(model.actualHours({ duration: 1, status: 'pending' }), 0);
});
test('accumulated actual time remains importable above a single-day planned duration', () => {
  const backup = structuredClone(legacy);
  backup.schedules[0].actualDuration = 26;
  assert.equal(model.validateBackup(backup).schedules[0].actualDuration,26);
});
test('Sunday belongs to the preceding Monday and dates use local time', () => {
  assert.equal(model.localDate(model.weekStart('2026-10-04T12:00:00+09:00')), '2026-09-28');
  assert.equal(model.localDate('2026-10-02T16:00:00Z'), '2026-10-03');
});
const legacy = {subjects:[{id:'s1',name:'数学',color:'#185fa5'}],schedules:[{id:'schedule1',subjectId:'s1',datetime:'2026-10-03T17:00',duration:'1',status:'done'}],exams:[{id:'exam1',subject:'数学',date:'2026-10-10'}]};
test('legacy backups retain data and do not guess exam associations', () => {
  const restored = model.validateBackup(legacy);
  assert.deepEqual(restored.examGroups, []);
  assert.equal(restored.schedules[0].duration, '1');
  assert.equal(restored.schedules[0].examGroupId, undefined);
});
test('new backup round trips groups and rejects broken references before restore', () => {
  const backup = structuredClone(legacy);
  backup.examGroups = [{id:'g1',name:'第2回模試',type:'模試',startDate:'2026-10-10',endDate:'2026-10-10'}];
  backup.schedules[0].examGroupId = 'g1';
  assert.deepEqual(model.validateBackup(JSON.parse(JSON.stringify(backup))), backup);
  backup.schedules[0].examGroupId = 'missing';
  assert.throws(() => model.validateBackup(backup), /学習記録/);
});
test('invalid dates, duplicate IDs, and executable IDs are rejected', () => {
  let backup = structuredClone(legacy); backup.exams[0].date = '2026-02-31';
  assert.throws(() => model.validateBackup(backup), /教科別日程/);
  backup = structuredClone(legacy); backup.subjects.push(backup.subjects[0]);
  assert.throws(() => model.validateBackup(backup), /ID/);
  backup = structuredClone(legacy); backup.schedules[0].id = "x');alert(1)//";
  assert.throws(() => model.validateBackup(backup), /ID/);
});
