const {test} = require('node:test');
const assert = require('node:assert/strict');
const model = require('../study-model.js');
const records = [
  {datetime:'2026-12-31T18:00:00',duration:1,status:'done',examGroupId:'a'},
  {datetime:'2027-01-01T18:00:00',duration:2,status:'partial',actualDuration:.5,examGroupId:'a'},
  {datetime:'2027-01-03T18:00:00',duration:3,status:'done',actualDuration:0,examGroupId:'b'},
];
test('weekly buckets cross the year and include empty weeks without mixing exams', () => {
  const buckets = model.timeBuckets(model.forExam(records,'a'),new Date('2026-12-28T00:00:00'),new Date('2027-01-12T00:00:00'),'week');
  assert.deepEqual(buckets.map(b => [b.date,b.actual,b.planned]),[['2026-12-28',1.5,3],['2027-01-04',0,0],['2027-01-11',0,0]]);
});
test('monthly buckets clip the first and final months to the chosen inclusive days', () => {
  const buckets = model.timeBuckets(records,new Date('2027-01-01T00:00:00'),new Date('2027-01-04T00:00:00'),'month');
  assert.equal(buckets.length,1); assert.equal(buckets[0].actual,.5); assert.equal(buckets[0].planned,5);
  assert.deepEqual(model.timeBuckets(records,new Date('2027-02-01'),new Date('2027-01-01')),[]);
});
test('leap days and explicit zero have the correct heatmap intensity', () => {
  const buckets=model.timeBuckets([],new Date('2024-02-28T00:00:00'),new Date('2024-03-02T00:00:00'));
  assert.deepEqual(buckets.map(b=>b.date),['2024-02-28','2024-02-29','2024-03-01']);
  assert.deepEqual([0,.1,.5,1,2,20].map(model.heatLevel),[0,1,2,3,4,4]);
  assert.equal(model.dailyTotals(records).get('2027-01-03').actual,0);
});
test('calendar charts stay bounded for many years of imported records', () => {
  const buckets=model.timeBuckets([],new Date('2000-01-01T00:00:00'),new Date('2027-01-01T00:00:00'));
  assert.equal(buckets.length,366); assert.equal(buckets.at(-1).date,'2026-12-31');
});
test('GitHub calendar uses Sunday rows and handles a rolling year ending on leap day', () => {
  const rolling=model.activityRange(new Date('2024-02-29T12:00:00'));
  assert.equal(model.localDate(rolling.from),'2023-03-01'); assert.equal(model.localDate(rolling.to),'2024-03-01');
  assert.equal(rolling.start.getDay(),0); assert.equal(rolling.end.getDay(),0);
  const annual=model.activityRange(new Date('2026-10-06T12:00:00'),2024);
  assert.equal(model.localDate(annual.from),'2024-01-01'); assert.equal(model.localDate(annual.to),'2025-01-01');
});
