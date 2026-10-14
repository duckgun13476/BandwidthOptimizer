const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'static/app.js'), 'utf8');
const locales = ['zh-CN', 'en-US', 'pt-BR'];
const context = { translations: Object.fromEntries(locales.map(locale => [locale, { sections: {}, descriptions: {} }])) };
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const currencyLabels ='), source.indexOf('const watutLabels =')), context);
vm.runInContext('globalThis.labels = currencyLabels', context);
for (const locale of locales) {
  const entry = context.labels[locale];
  assert.deepEqual(Object.keys(entry.labels).sort(), ['flushed_calls', 'requested_calls', 'saved_calls']);
  assert.equal(context.translations[locale].sections.lightmanscurrency, entry.title);
  assert.equal(context.translations[locale].descriptions.lightmanscurrency, entry.description);
  assert.match(entry.description, /不是流量节省|not measured traffic savings|não são economia medida/);
}
assert.match(source, /sectionId === 'lightmanscurrency' && currencyLabels\[language\]/);
assert.match(source, /'lightmanscurrency'\]\.includes\(section.id\)/);
console.log('Currency bank sync localization PASS (three languages, calls and period).');
