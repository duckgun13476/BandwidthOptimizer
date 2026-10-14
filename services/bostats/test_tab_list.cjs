const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
const locales = ['zh-CN', 'en-US', 'pt-BR'];
const context = { translations: Object.fromEntries(locales.map(locale => [locale, { sections: {}, descriptions: {} }])) };
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const tabListLabels ='), source.indexOf('const sifterLabels =')), context);
vm.runInContext('globalThis.labels = tabListLabels', context);
for (const locale of locales) {
  const entry = context.labels[locale];
  assert.equal(Object.keys(entry.labels).length, 5);
  assert.equal(context.translations[locale].sections.tab_list, entry.title);
  assert.equal(context.translations[locale].descriptions.tab_list, entry.description);
  assert.match(entry.description, /五秒|five seconds|cinco segundos/);
  assert.match(entry.description, /不是线路节省|not wire savings|não economia na rede/);
  for (const text of Object.values(entry.labels)) assert.ok(text.length > 0);
}
assert.match(source, /sectionId === 'tab_list' && tabListLabels\[language\]/);
assert.match(source, /'watut', 'tab_list', 'create_sifter'\]\.includes\(section.id\)/);
console.log('TAB report localization passed (three languages, metrics and period).');
