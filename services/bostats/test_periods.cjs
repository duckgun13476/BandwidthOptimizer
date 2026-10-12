const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
let player = '__server', version = '__all';
const context = {
  language: 'en-US', reportBundle: { generatedAtMillis: 5000 },
  document: { querySelector: id => ({ value: id === '#trend-player' ? player : version }) },
  hourVersion: hour => hour.modVersion || 'legacy',
  escapeText: value => String(value).replaceAll('<', '&lt;'),
  formatDateTime: value => String(value), t: () => 'Window',
  sectionMetrics: section => Object.fromEntries(section.metrics.map(m => [m.id, m])),
  jvmLabels: { 'en-US': { title: 'JVM', description: 'state' } }
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const periodLabels ='), source.indexOf('const jvmLabels =')), context);
vm.runInContext(source.slice(source.indexOf('function chartPoints('), source.indexOf('function drawChart(')), context);
vm.runInContext(source.slice(source.indexOf('function observationPeriod('), source.indexOf('const sourceDetailLabels =')), context);
vm.runInContext(source.slice(source.indexOf('function detailSection('), source.indexOf('function sectionMetrics(')), context);
const oldZone = process.env.TZ;
for (const zone of ['UTC', 'Australia/Sydney', 'America/New_York']) {
  process.env.TZ = zone;
  // Includes both DST transitions, an incomplete hour, and a missing calendar day.
  const dates = ['2026-03-08T06:00:00Z', '2026-03-08T07:00:00Z', '2026-03-10T07:00:00Z', '2026-10-03T15:00:00Z', '2026-10-03T16:00:00Z', '2026-11-01T05:00:00Z', '2026-11-01T06:00:00Z'];
  const hours = dates.map((date, i) => ({ periodStartMillis: Date.parse(date), modVersion: i % 2 ? 'v2' : 'v1', complete: i !== 1,
    totals: { outboundWireBytes: i + 1, inboundWireBytes: 10 }, players: [{ playerUuid: 'player', outboundWireBytes: 1, inboundWireBytes: 2 }] }));
  player = '__server'; version = '__all';
  vm.runInContext("trendGranularity = 'hour'", context);
  const hourly = context.chartPoints({ hours });
  vm.runInContext("trendGranularity = 'day'", context);
  const daily = context.chartPoints({ hours });
  const sum = (points, field) => points.reduce((s, p) => s + p[field], 0);
  for (const field of ['outbound', 'inbound', 'total']) assert.equal(sum(daily, field), sum(hourly, field));
  assert.equal(sum(daily, 'recordedHours'), hours.length);
  assert.equal(sum(daily, 'partial'), 1);
  assert.ok(daily.length < hourly.length);
  assert.ok(daily.every(p => p.total > 0));
  player = 'player'; version = 'v2';
  const filtered = context.chartPoints({ hours });
  assert.equal(sum(filtered, 'total'), 9);
  assert.ok(filtered.every(p => p.version === 'v2'));
  assert.equal(context.chartPoints({ hours: [] }).length, 0);
}
if (oldZone === undefined) delete process.env.TZ; else process.env.TZ = oldZone;
for (const language of ['en-US', 'zh-CN', 'pt-BR']) {
  context.language = language;
  const html = context.observationPeriod({ countersStartedAtMillis: 1000, observedAtMillis: 2000, windowStartedAtMillis: 1500 });
  assert.match(html, /1000/); assert.match(html, /1500/); assert.match(html, /2000/);
  assert.doesNotMatch(html, /5000|undefined|NaN/);
  const old = context.observationPeriod({});
  assert.match(old, /5000/); assert.doesNotMatch(old, /undefined|NaN/);
  context.t = key => key === 'sections' ? { transport: 'Transport' } : key === 'descriptions' ? { transport: 'Pipeline' } : 'Window';
  const details = context.detailSection({ id: 'transport', metrics: [{ id: 'counters_started_at_millis', value: 1000 }] }, 'stages');
  assert.match(details, /1000/); assert.match(details, /5000/);
  assert.doesNotMatch(details, /undefined|NaN/);
}
console.log('Period regression passed (hour/day conservation, player/version filters, DST, missing hours, old/new intervals, three languages).');
