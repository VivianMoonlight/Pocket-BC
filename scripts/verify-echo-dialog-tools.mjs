import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { DialogTools } from './echo-dialog-tools.mjs';

const source = await fs.readFile(process.argv[2]);
assert.equal(createHash('sha1').update(`blob ${source.length}\0`).update(source).digest('hex'), 'f864a9219d8df15dd756d3aaf27fa2802ba3c302');
const context = vm.createContext({ ExtendedArchetype: { MODULAR: 'modular', TYPED: 'typed' } });
const original = vm.runInContext(source.toString().replace('export class DialogTools', 'class DialogTools') + '\nDialogTools', context);
let count = 0;
function compare(method, ...args) {
    const before = JSON.stringify(args);
    assert.deepEqual(JSON.parse(JSON.stringify(DialogTools[method](...args))), JSON.parse(JSON.stringify(original[method](...args))));
    assert.equal(JSON.stringify(args), before);
    count++;
}
for (const reverse of [undefined, false, true]) for (const fullText of [undefined, {CN:'完整',EN:'Full'}, {CN:''}]) {
    compare('showHide', {moduleName:'发型',key:'F',moduleText:{CN:'头发',EN:'Hair'},reverse,fullText});
}
for (const config of [
    {Archetype:'typed',Options:[{Name:'A'},{Name:'B'}]},
    {Archetype:'modular',Modules:[{Key:'a',Options:[{},{}]},{Key:'b',Options:[{}]}]},
    {Archetype:'other'},
]) for (const sets of [undefined, {CN:(from,s)=>s[from]||'fallback',EN:()=>''}]) {
    compare('autoItemStrings',{CN:{A:'一',B:'二',Seta0:'零'},EN:{A:'One',B:'Two'},RU:{A:'a'}},config,sets);
}
compare('repeatEntries', [['a','b'],'value'], ['c','other']);
compare('combine', {CN:{a:'old'},EN:{a:'one'}}, {CN:{a:'new',b:'two'}});
compare('modularSetMessage', 'm', ['Off','On','Other'], (field,index)=>`${index}:${field}`);
console.log(`PASS ${count} pinned dialog helper comparisons, inputs unchanged`);

const toolsSource = await fs.readFile(path.join(path.dirname(process.argv[2]), 'index.js'));
assert.equal(createHash('sha1').update(`blob ${toolsSource.length}\0`).update(toolsSource).digest('hex'), '94cce7bf728ae1d00f3e8f260d05218e1e0888ec');
const upstreamTools = toolsSource.toString();
const entrySource = await fs.readFile(path.join(path.dirname(process.argv[2]), '..', 'index.js'));
assert.equal(createHash('sha1').update(`blob ${entrySource.length}\0`).update(entrySource).digest('hex'), 'd43abad791211f711770882c35cd0790b579f2ea');
assert.equal(entrySource.toString().replace(/\s+/g,' ').trim(), 'import { Tools } from "./Tools"; export { Tools };');
const generator = await fs.readFile('scripts/generate-echo-portrait.mjs', 'utf8');
const groups = code => code.slice(code.indexOf('const ItemGroups ='), code.indexOf('\n];',code.indexOf('const ItemGroups ='))+3);
const methodStart = upstreamTools.indexOf('static AllItemGroups(');
const originalGroups = vm.runInNewContext(groups(upstreamTools) + '\nfunction ' +
    upstreamTools.slice(methodStart + 'static '.length,upstreamTools.indexOf('\n    }',methodStart)+6) + '\nAllItemGroups');
const portGroups = vm.runInNewContext(groups(generator) + '\n' +
    generator.slice(generator.indexOf('    const tools ='),generator.indexOf('    const synthetic =')) + '\ntools.AllItemGroups');
for (const excluded of [[], ['ItemTorso'], ['ItemTorso','ItemTorso2','ItemMisc'], ['Unknown'], Array.from(originalGroups())]) {
    const before = JSON.stringify(excluded);
    assert.deepEqual(Array.from(portGroups(excluded)), Array.from(originalGroups(excluded)));
    assert.equal(JSON.stringify(excluded), before);
}
console.log('PASS pinned AllItemGroups exclusions, ordering and input isolation');
const buttonStart = upstreamTools.indexOf('static makeButtonGroup(');
const originalButtons = vm.runInNewContext('function ' + upstreamTools.slice(buttonStart + 7,
    upstreamTools.indexOf('\n    }',buttonStart)+6) + '\nmakeButtonGroup');
const portButtons = vm.runInNewContext(groups(generator) + '\n' +
    generator.slice(generator.indexOf('    const tools ='),generator.indexOf('    const synthetic =')) + '\ntools.makeButtonGroup');
for (const size of [0,1,17,18,19,50]) {
    assert.equal(JSON.stringify(portButtons(size)), JSON.stringify(originalButtons(size)));
}
for (const groups of [undefined,[],['ItemMisc','ItemHandheld']]) for (const itemNames of [undefined,[],['玩偶','Other']]) {
    const configs = [{Name:'Room',Key:'r',Options:[{},{}]}, {Name:'Other',Key:'o',Options:[{}]}];
    const params = {groups,itemNames,selectBase:'Choose',module:m=>({Select:m.Name,Module:m.Key}),
        option:(_,i,m)=>({Option:`${m.Key}${i}`,Set:`Set ${i}`})};
    const first = {SelectBase:'Keep',Optionr0:'',Setr1:'Custom'};
    const second = {...first};
    assert.equal(DialogTools.dialogGenerator(configs,params,first),first);
    original.dialogGenerator(configs,params,second);
    assert.deepEqual(first,second);
}
console.log('PASS pinned button pagination and dialog generator prefixes, overrides and mutation semantics');
