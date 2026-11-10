import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

async function source(path, hash) {
  const value = await fs.readFile(path, 'utf8');
  if (createHash('sha256').update(value).digest('hex') !== hash) throw new Error('Official R133 source changed: ' + path);
  return value;
}
const lockSource = await source('_local-artifacts/portrait-probe/remote/Screens/Inventory/ItemMisc/HighSecurityPadlock/HighSecurityPadlock.js', '63b987a21821851050d68b1aa9e707b52b2f0db4c0204b46f734a9f1718d03e9');
const commonSource = await source('_local-artifacts/portrait-probe/remote/Scripts/Common.js', 'be2195fd43c243f4f72a1df241c505db521a1ffd4bd1ce9e9de5a1db4ee461c7');
const common = ['CommonConvertStringToArray', 'CommonConvertArrayToString'].map(name => {
  const start = commonSource.indexOf(`function ${name}(`);
  if (start < 0) throw new Error('Missing function: '+name);
  return commonSource.slice(start, commonSource.indexOf('\n}', start) + 2);
}).join('\n');
const fixtures = JSON.parse(await fs.readFile('domain/character/src/test/resources/bc-fixtures/high-security-keys-r133.json', 'utf8'));
for (const f of fixtures) {
  const item = {Property:{MemberNumberListKeys:f.before},Asset:{Group:{Name:'ItemArms'}}};
  const wearer = {FocusGroup:{Name:'ItemArms'}};
  const context = vm.createContext({DialogFocusSourceItem:item,DialogFocusItem:{Asset:{Name:'HighSecurityPadlock'}},CharacterGetCurrent:()=>wearer,
    Player:{MemberNumber:10,IsOwned:()=>f.owner?'online':false,OwnerNumber:()=>f.owner,GetLoversNumbers:()=>f.lovers??[],WhiteList:f.whitelist??[]},
    InventoryGroupIsBlocked:()=>!!f.blocked,MouseIn:(x,y)=>x===1135&&y===920,ElementValue:()=>f.input,
    ServerPlayerIsInChatRoom:()=>false,CharacterRefresh:()=>{},DialogLeaveFocusItem:()=>{}});
  vm.runInContext(common+'\n'+lockSource+`\nInventoryItemMiscHighSecurityPadlockPlayerCanUnlock=${!f.deposited};HighSecurityPadlockConfigOwner=${!!f.appendOwner};HighSecurityPadlockConfigLover=${!!f.appendLovers};HighSecurityPadlockConfigWhitelist=${!!f.appendWhitelist};InventoryItemMiscHighSecurityPadlockClickHook({},()=>{});`,context);
  if (item.Property.MemberNumberListKeys !== f.expected) throw new Error(f.id+': '+item.Property.MemberNumberListKeys+' != '+f.expected);
  console.log('PASS official R133 keyholder click:', f.id);
}
for (const keys of [undefined,null,'','30']) {
  const item = {Property:{LockMemberNumber:10,...keys!==undefined?{MemberNumberListKeys:keys}:{}}};
  const context = vm.createContext({CommonIsObject:x=>x!==null&&typeof x==='object'});
  vm.runInContext(lockSource,context);
  context.item = item;
  vm.runInContext('InventoryItemMiscHighSecurityPadlockInitHook({asset:{}},()=>false,{},item,false,false);',context);
  if (item.Property.MemberNumberListKeys !== (keys || '10')) throw new Error('Native initialization mismatch');
}
console.log('PASS official R133 keyholder initialization');
