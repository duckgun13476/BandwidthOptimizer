const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
const locales = ['en-US', 'zh-CN', 'pt-BR'];
const context = { translations: Object.fromEntries(locales.map(locale => [locale, {sections: {}, descriptions: {}, stages: {}}])) };
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const watutLabels ='), source.indexOf('const recipeLabels =')), context);
vm.runInContext('globalThis.labels = watutLabels', context);
for (const locale of locales) {
  const labels = context.labels[locale];
  assert.equal(Object.keys(labels.labels).length, 19);
  assert.equal(context.translations[locale].sections.watut, labels.title);
  assert.equal(context.translations[locale].descriptions.watut, labels.description);
  assert.equal(context.translations[locale].stages.watut_image_payload, labels.payload);
  assert.match(labels.description, /NBT/);
  for (const label of Object.values(labels.labels)) assert.ok(label.length > 0);
}
assert.notEqual(context.labels['zh-CN'].labels.server_saved_payload_bytes, context.labels['en-US'].labels.server_saved_payload_bytes);
assert.notEqual(context.labels['pt-BR'].labels.server_saved_payload_bytes, context.labels['en-US'].labels.server_saved_payload_bytes);
assert.match(source, /sectionId === 'watut' && watutLabels\[language\]/);
console.log('WATUT report labels regression passed (three languages, units and section registration).');
