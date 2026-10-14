const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
let change;
const node = { className: '', innerHTML: '', insertAdjacentHTML: (_, html) => { node.innerHTML = html + node.innerHTML; }, querySelector: () => ({ addEventListener: (_, callback) => { change = callback; } }) };
const context = {
  document: { querySelector: () => node }, language: 'en-US',
  pages: { sources: { page: 1, size: 10 } },
  bytes: value => String(Number(value || 0)) + ' B',
  escapeText: value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;'),
  t: key => key === 'units' ? { packets: 'packets', entries: 'entries' } : key,
  rt: key => key, sourcePeriodLabel: { 'en-US': 'process run', 'zh-CN': '进程', 'pt-BR': 'execução' },
  pageSlice: (rows, state) => rows.slice((state.page - 1) * state.size, state.page * state.size),
  renderPagination: () => {}
};
context.observationPeriod = () => 'observation period';
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const sourceDetailLabels ='), source.indexOf('function aggregateBypassSources')), context);
context.renderSourceTraffic(null);
assert.match(node.innerHTML, /sourceMissing/);
context.renderSourceTraffic({ distinctKeys: 1, rawBytes: 100, entries: [{ sourceKey: 'old', rawBytes: 100, packets: 1 }] });
assert.match(node.innerHTML, /old/);
assert.doesNotMatch(node.innerHTML, /id="source-traffic-order"/);
const report = { distinctKeys: 3, rawBytes: 1000, actualBytes: 100, windowRawBytes: 100, windowActualBytes: 20, observedAtMillis: 2000, windowStartedAtMillis: 1000,
  entries: [{ sourceKey: 'raw-leader', rawBytes: 900, actualBytes: 1, windowRawBytes: 1, windowActualBytes: 1 },
    { sourceKey: '<script>wire-leader', rawBytes: 100, actualBytes: 90, windowRawBytes: 90, windowActualBytes: 19, lastPath: '<unsafe>', lastFrame: 'BATCH', estimatedPackets: 1 }] };
for (const language of ['en-US', 'zh-CN', 'pt-BR']) {
  context.language = language;
  context.renderSourceTraffic(report);
  assert.doesNotMatch(node.innerHTML, /<script>|<unsafe>|NaN|undefined/);
  assert.match(node.innerHTML, /&lt;script>/);
  context.pages.sources.page = 2;
  change({ target: { value: 'actualBytes' } });
  assert.equal(context.pages.sources.page, 1);
  assert.ok(node.innerHTML.indexOf('&lt;script>wire-leader') < node.innerHTML.indexOf('raw-leader'));
  change({ target: { value: 'windowActualBytes' } });
  assert.match(node.innerHTML, /95.00%/);
}
console.log('Source traffic rendering regression passed (old/new, three languages, sorting, pagination, escaping).');
for (const language of ['en-US', 'zh-CN', 'pt-BR']) {
  context.language = language;
  const detail = context.renderEntitySyncDetails({ packets: 5, unknownTypePackets: 1, omittedFields: 4, trackedKeys: 2,
    entries: [{ kind: 'metadata', entityType: '<script>pig', fieldId: '<unsafe>', occurrences: 5, windowOccurrences: 2 }] });
  assert.match(detail, /&lt;script>/);
  assert.doesNotMatch(detail, /<script>|<unsafe>|NaN|undefined/);
  assert.match(detail, /4096/);
  context.renderSourceTraffic({ ...report, entitySync: { packets: 1, trackedKeys: 1,
    entries: [{ kind: 'attributes', entityType: 'mod:vehicle', fieldId: 'mod:mass', occurrences: 1, windowOccurrences: 1 }] } });
  assert.match(node.innerHTML, /mod:mass/);
}
assert.equal(context.renderEntitySyncDetails(null), '');
console.log('Entity sync field rendering passed (three languages, legacy reports, escaping).');
for (const language of ['en-US', 'zh-CN', 'pt-BR']) {
  context.language = language;
  const bank = 'custom_payload:lightmanscurrency:s_sync_custom_data/bank/account_update';
  const notification = 'custom_payload:lightmanscurrency:s_sync_custom_data/trader/notification_append';
  assert.notEqual(context.currencySourceName(bank), bank);
  assert.notEqual(context.currencySourceName(notification), notification);
  context.renderSourceTraffic({ ...report, entries: [{ ...report.entries[0], sourceKey: bank }, { ...report.entries[1], sourceKey: notification }] });
  assert.match(node.innerHTML, /LightmansCurrency/);
  assert.doesNotMatch(node.innerHTML, /NaN|undefined|\/bank\/account_update/);
  assert.equal(context.currencySourceName('custom_payload:lightmanscurrency:s_sync_custom_data'), 'custom_payload:lightmanscurrency:s_sync_custom_data');
  assert.equal(context.currencySourceName('custom_payload:lightmanscurrency:s_sync_custom_data/<script>/unknown'), 'custom_payload:lightmanscurrency:s_sync_custom_data/<script>/unknown');
}
console.log('Currency source detail rendering passed (three languages, old reports, whole-packet labels).');
