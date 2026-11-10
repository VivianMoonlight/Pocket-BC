import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

const hashes = {
  OwnerTimerPadlock:'71a50b3509d91987ccd2049a26468d10f492e743ef6a3714202e50b9ea6d37d1',
  TimerPadlock:'00f9e0615f6bc35eecbae9a524d313aeaa3314968ad938cef1c1461dfcee5bc0',
  MistressTimerPadlock:'c52f4acbae0710f945bb41e208ef5d72ae52678f6776f56937eb902108e68771',
  TimerPasswordPadlock:'3eedc44106696d569f62b3e49fef84d6b9c7d0233b54dd8d9d8840cf6649ba68',
  LoversTimerPadlock:'2d48328df4a62a9c1d6d632927a5f2b94bf2c443a85469512137aa67ae04dd91'
};
const sources = [];
for (const [name,hash] of Object.entries(hashes)) {
  const source = await fs.readFile(`_local-artifacts/portrait-probe/remote/Screens/Inventory/ItemMisc/${name}/${name}.js`, 'utf8');
  if (createHash('sha256').update(source).digest('hex') !== hash) throw new Error('Official R133 source changed: '+name);
  sources.push(source);
}
const fixtures = JSON.parse(await fs.readFile('core/socket/src/test/resources/bc-timer-edit-r133.json', 'utf8'));
for (const f of fixtures) {
  const property = {RemoveTimer:f.before,LockMemberNumber:f.creator ?? 30,RemoveItem:false,ShowTimer:true,EnableRandomInput:f.random ?? false,MemberNumberList:[...(f.used ?? [])]};
  const item = {Property:property,Asset:{Group:{Name:'ItemArms'}}};
  const lock = {Asset:{Name:f.lock,Group:{Name:'ItemMisc'},RemoveTimer:300,MaxTimer:f.maximum}};
  const wearer = {FocusGroup:{Name:'ItemArms'},Appearance:[item],IsOwnedByPlayer:()=>!!f.owner,IsLoverOfPlayer:()=>!!f.lover,GetLockTimerLimit:()=>f.limit ?? null};
  const point = f.action === 'restart' ? [1350,910] : f.action === 'remove-setting' ? [1100,f.lock==='TimerPadlock'?836:666] : [f.action==='random'?1400:f.action==='increase'?1700:1100,910];
  class Dictionary {sourceCharacter(){return this;}destinationCharacter(){return this;}focusGroup(){return this;}if(){return this;}text(){return this;}endif(){return this;}build(){return [];}}
  const context = vm.createContext({CurrentTime:f.now,CurrentScreen:'ChatRoom',DialogFocusSourceItem:item,DialogFocusItem:lock,
    Player:{MemberNumber:10,CanInteract:()=>!f.bound},CharacterGetCurrent:()=>wearer,InventoryGroupIsBlocked:()=>!!f.blocked,
    LogQuery:()=>!!f.club,TimeUnits:{MINUTES:{seconds:60,label:'Minutes'},HOURS:{seconds:3600,label:'Hours'},DAYS:{seconds:86400,label:'Days'}},
    MouseIn:(x,y,w,h)=>point[0]>=x&&point[0]<x+w&&point[1]>=y&&point[1]<y+h,
    MouseXIn:(x,w)=>point[0]>=x&&point[0]<x+w,MouseYIn:(y,h)=>point[1]>=y&&point[1]<y+h,
    DictionaryBuilder:Dictionary,ChatRoomCharacterItemUpdate:()=>{},ChatRoomPublishCustomAction:()=>{},DialogLeaveFocusItem:()=>{},
    InventoryItemMiscPasswordPadlockIsSet:()=>true,InventoryItemMiscPasswordPadlockHandleOpenClick:()=>{},InventoryItemMiscPasswordPadlockHandleFirstSet:()=>{}});
  vm.runInContext(sources.join('\n')+`\nMath.random=()=>${f.positive?0.75:0.25};`,context);
  if (f.action==='adjust') {
    const prefix = f.lock==='TimerPasswordPadlock'?'Password':f.lock==='MistressTimerPadlock'?'Mistress':'Owner';
    vm.runInContext(`${prefix}TimerChooseOptionsIndex=${f.unit}; ${prefix}TimerChooseIndexes[${f.unit}]=${prefix}TimerChooseOptions[${f.unit}].values.indexOf(${f.amount});`,context);
  }
  const call = f.lock==='LoversTimerPadlock'?'InventoryItemMiscOwnerTimerPadlockClickHook({},null,InventoryItemMiscLoversTimerPadlockValidator)':`InventoryItemMisc${f.lock}ClickHook({},()=>{})`;
  vm.runInContext(call,context);
  if (property.RemoveTimer!==f.after || JSON.stringify(property.MemberNumberList)!==JSON.stringify(f.members??f.used??[]) || 'removeItem' in f && property.RemoveItem!==f.removeItem)
    throw new Error(f.id+': mismatch '+JSON.stringify(property));
  console.log('PASS official R133 timer controls:',f.id);
}
