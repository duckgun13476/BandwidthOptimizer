const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
const locales = ['zh-CN', 'en-US', 'pt-BR'];
const context = { translations: Object.fromEntries(locales.map(locale => [locale, { sections: {}, descriptions: {}, stages: {}, units: {} }])) };
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const sifterLabels ='), source.indexOf('const watutLabels =')), context);
vm.runInContext('globalThis.labels = sifterLabels', context);
for (const locale of locales) {
  const entry = context.labels[locale];
  assert.equal(Object.keys(entry.labels).length, 4);
  assert.equal(context.translations[locale].sections.create_sifter, entry.title);
  assert.equal(context.translations[locale].descriptions.create_sifter, entry.description);
  assert.equal(context.translations[locale].stages.source_sync, entry.stage);
  assert.ok(context.translations[locale].units.calls);
  assert.ok(context.translations[locale].units.transactions);
  assert.match(entry.description, /不是流量字节|not bytes|não são bytes/);
}
assert.match(source, /sectionId === 'create_sifter' && sifterLabels\[language\]/);
assert.match(source, /'create_sifter', 'lightmanscurrency'\]\.includes\(section.id\)/);
console.log('Sifter report localization passed (three languages, source-call units and period).');
