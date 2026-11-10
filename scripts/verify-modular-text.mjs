import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

async function source(name, hash) {
  const text = await fs.readFile(`_local-artifacts/portrait-probe/remote/Scripts/${name}.js`, 'utf8');
  if (createHash('sha256').update(text).digest('hex') !== hash) throw new Error('Official R133 source changed: '+name);
  return text;
}
const modular = await source('ModularItem','d3f375072d82042ec04a2f35be5b757964939913cbaa424212116f5e85d97219');
const draw = await source('DynamicDraw','cfe29ed164fb98cf8ce4c5fa43c1f783003d510c7fd662c79e55e7bfab27b71e');
const merge = modular.match(/^function ModularItemMergeModuleValues\([\s\S]*?^}/m)?.[0];
const sanitize = modular.match(/^function ModularItemSanitizeProperties\([\s\S]*?^}/m)?.[0];
const textRegex = draw.match(/^const DynamicDrawTextRegex = .+;/m)?.[0];
if (!merge || !sanitize || !textRegex) throw new Error('Native modular text functions missing');
const fixtures = JSON.parse(await fs.readFile('domain/character/src/test/resources/bc-fixtures/modular-text-r133.json','utf8'));
for (const f of fixtures) {
  const typeEntries=Object.entries(f.type);
  const data={asset:{Layer:[]},modules:f.options.map((property,i)=>({Options:[{Property:{...property,TypeRecord:Object.fromEntries(typeEntries.filter((_,index)=>index===i))}}]}))};
  const context=vm.createContext({data,baseline:f.baseline,
    CommonAssign:Object.assign,CommonIsObject:x=>!!x&&typeof x==='object'&&!Array.isArray(x),
    CommonArrayConcatDedupe:(a,b)=>[...new Set([...a,...b])],CommonEntries:Object.entries});
  vm.runInContext(textRegex+'\n'+merge+'\n'+sanitize+'\nresult=ModularItemMergeModuleValues(data,data.modules.map(()=>0),baseline);',context);
  const text=Object.fromEntries(Object.entries(context.result).filter(([key])=>['Text','Text2','Text3'].includes(key)));
  if(JSON.stringify(text)!==JSON.stringify(f.expectedText)) throw new Error(f.id+': '+JSON.stringify(text));
  console.log('PASS official R133 modular text:',f.id);
}
