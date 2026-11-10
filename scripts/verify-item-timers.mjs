import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

async function source(name, hash) {
  const text = await fs.readFile('_local-artifacts/portrait-probe/remote/Scripts/' + name + '.js', 'utf8');
  if (createHash('sha256').update(text).digest('hex') !== hash) throw new Error('Official R133 source changed: ' + name);
  return text;
}
const timer = await source('Timer', 'd145888ec2426478fa7222d0776f18edc1ff50d9484d16ce23136a3b9eeea6e8');
const validation = await source('Validation', '27c76575e5bf6a628681b0840a94293c985fa63724b0acfbbf87ef30baf8b419');
const dictionary = await source('DictionaryBuilder', 'b3365c366d608d17d2edbf096d61376e75406cd03d2c44d0ab93aa4354cb855c');
const expire = timer.match(/^function TimerInventoryRemove\([\s\S]*?^}/m)?.[0];
const deleteLock = validation.match(/^function ValidationDeleteLock\([\s\S]*?^}/m)?.[0];
const lockKeys = validation.slice(validation.indexOf('const ValidationNonModifiableLockProperties'), validation.indexOf('const ValidationModifiableProperties'));
if (!expire || !deleteLock) throw new Error('Native expiry functions missing');
const fixtures = JSON.parse(await fs.readFile('domain/character/src/test/resources/bc-fixtures/item-timer-r133.json', 'utf8'));
for (const fixture of fixtures) {
  const characters = fixture.characters.map(c => ({ MemberNumber: c.member, Name: 'Self', IsPlayer: () => c.player, IsNpc: () => false,
    Appearance: c.items.map(i => ({ Asset: { Name: i.name, Group: { Name: i.group } }, Property: structuredClone(i.property) })) }));
  const before = characters.map(c => structuredClone(c.Appearance));
  const messages = [];
  const context = vm.createContext({ Character: characters, CurrentTime: fixture.now, messages,
    CharacterNickname: c => c.Name, ServerPlayerIsInChatRoom: () => true,
    InventoryGetLock: i => i.Property?.LockedBy ? { Asset: { Name: i.Property.LockedBy, Group: { Name: 'ItemMisc' } } } : null,
    // Ordinary fixtures have no removal cascades; only the requested group is removed here.
    InventoryRemove: (c, group) => { c.Appearance = c.Appearance.filter(i => i.Asset.Group.Name !== group); },
    CharacterRefresh: () => {}, ChatRoomCharacterUpdate: () => {}, ServerPrivateCharacterSync: () => {},
    ServerSend: (_event, payload) => messages.push(payload.Content), console });
  vm.runInContext(dictionary + '\n' + lockKeys + '\n' + deleteLock + '\n' + expire + '\nTimerInventoryRemove();', context);
  const actual = [];
  for (const [index, c] of characters.entries()) for (const previous of before[index]) {
    const next = c.Appearance.find(i => i.Asset.Group.Name === previous.Asset.Group.Name);
    if (JSON.stringify(previous) !== JSON.stringify(next)) actual.push({target:c.MemberNumber,group:previous.Asset.Group.Name,name:next?.Asset.Name ?? null,property:next?.Property ?? null});
  }
  if (JSON.stringify(actual) !== JSON.stringify(fixture.expected) || JSON.stringify(messages) !== JSON.stringify(fixture.messages)) throw new Error(fixture.id + ': mismatch ' + JSON.stringify({actual,messages}));
  console.log('PASS official R133 item timer:', fixture.id);
}
