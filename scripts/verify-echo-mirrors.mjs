import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

// Exercise the packaged adapter's mirror boundaries without game/session code.
const catalog = {
    revision: 'a'.repeat(40), groups: [],
    mirrors: [
        {group:'Copy',from:'Source',overrides:{},clearHide:true},
        {group:'Nested',from:'Copy',overrides:{}},
        {group:'Explicit',from:'Source',overrides:{Asset:[{Name:'Selected',Value:7}]}},
    ],
    assets: [
        {group:'Source',definition:{Name:'Public',Value:50},extended:{Archetype:'typed',Options:[{Name:'A'}]}},
        {group:'Source',definition:{Name:'Private'},noMirror:true,extended:{Archetype:'typed',Options:[{Name:'A'}]}},
        {group:'Copy',definition:{Name:'CopyOnly'},extended:{Archetype:'typed',Options:[{Name:'A'}]}},
    ],
    aliases:[{group:'Source',oldName:'OldPublic',name:'Public'}],
    images:['Assets/Female3DCG/Source/Public.png'], imageMappings:{},
};
const source = {Group:'Source',Hide:['Other'],Asset:[{Name:'Native',Value:99}]};
const hook = () => ({Opacity:1});
const context = vm.createContext({
    fetch:async()=>({ok:true,json:async()=>structuredClone(catalog)}),
    MobileBcSupplement:{prepare:async()=>{},extended:record=>record.extended},
    MobileBcInstallStockingMasks:()=>()=>null,
    AssetFemale3DCG:[source], AssetFemale3DCGExtended:{Source:{Native:{Archetype:'typed'}}},
    DrawImageCache:{get:url=>url}, CharacterAppearanceVisible:()=>true,
    AssetGet:(_family,group,name)=>({group,name}),
    AssetsSourceNativeBeforeDraw:hook,
});
context.window=context;
vm.runInContext(await fs.readFile('app/src/main/assets/portrait/echo.js','utf8'),context);
await context.MobileBcEcho.prepare([]);
const groups=new Map(context.AssetFemale3DCG.map(g=>[g.Group,g]));
const names=group=>Array.from(groups.get(group).Asset,a=>typeof a==='string'?a:a.Name);
assert.deepEqual(names('Source'),['Native','Public','Private']);
assert.deepEqual(names('Copy'),['Native','Public','CopyOnly']);
assert.deepEqual(names('Nested'),['Native','Public','CopyOnly']);
assert.deepEqual(names('Explicit'),['Selected']);
assert.equal(source.Asset[0].Value,99);
assert.equal(groups.get('Copy').Asset[0].Value,0);
assert.equal(groups.get('Explicit').Asset[0].Value,7);
assert.equal(groups.get('Copy').Hide,undefined);
assert.deepEqual(source.Hide,['Other']);
assert.equal(groups.get('Nested').DynamicGroupName,'Source');
assert.equal(context.AssetFemale3DCGExtended.Copy.Private,undefined);
assert.notEqual(context.AssetFemale3DCGExtended.Copy,context.AssetFemale3DCGExtended.Source);
assert.ok(context.AssetFemale3DCGExtended.Nested.CopyOnly);
assert.equal(context.AssetsCopyNativeBeforeDraw,hook);
assert.equal(context.AssetsNestedNativeBeforeDraw,hook);
assert.equal(context.MobileBcEcho.asset('Copy','OldPublic').name,'Public');
assert.equal(context.DrawImageCache.get('Assets/Female3DCG/Source/Public.png'),`/echo/${catalog.revision}/Assets/Female3DCG/Source/Public.png`);
console.log('PASS directional and nested mirrors; private assets; explicit assets; free copies; inherited image groups, callbacks, configs and aliases');
const generator=await fs.readFile('scripts/generate-echo-portrait.mjs','utf8');
const custom=generator.match(/customFixup\(rule\) \{[\s\S]*?\n            \},/)[0].slice(0,-1);
const result={aliases:[]};
const migration=vm.runInNewContext('({'+custom+'})',{result}).customFixup;
const rule={Old:{Group:'ItemTorso',Name:'Old'},New:{Group:'ItemTorso',Name:'Current'}};
const before=JSON.stringify(rule);
migration(rule);
assert.equal(JSON.stringify(rule),before);
assert.equal(JSON.stringify(result.aliases),JSON.stringify([{group:'ItemTorso',oldName:'Old',name:'Current'}]));
for(const invalid of [
    {...rule,New:{Group:'ItemTorso2',Name:'Current'}},
    {...rule,New:{...rule.New,Property:{}}},
    {...rule,Property:{}},
    {...rule,Old:{Group:'ItemTorso',Name:1}},
]) assert.throws(()=>migration(invalid),/Unsupported custom migration/);
assert.equal(result.aliases.length,1);
console.log('PASS custom renames preserve exact group without mirroring or richer migration effects');
