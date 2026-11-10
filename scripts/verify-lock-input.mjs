import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

// Fixed official R133 script. Execute only the input handler against synthetic fields.
const file = '_local-artifacts/portrait-probe/remote/Screens/Inventory/ItemMisc/CombinationPadlock/CombinationPadlock.js';
const source = await fs.readFile(file, 'utf8');
if (createHash('sha256').update(source).digest('hex') !== 'ecf1cc085e6dd373ae9873b0422144649a0de3fae66f39654b9d8b73a241309e') throw new Error('Native combination input source changed');
const handler = source.match(/^function InventoryItemMiscCombinationPadlockModifyInput\([\s\S]*?^}/m)?.[0];
if (!handler) throw new Error('Native input handler missing');
const fixtures = JSON.parse(await fs.readFile('domain/character/src/test/resources/bc-fixtures/combination-input-r133.json', 'utf8'));
for (const fixture of fixtures) {
  const random = [...fixture.random];
  const context = vm.createContext({ Player: { GetClumsiness: () => fixture.clumsiness }, nextRandom: () => {
    if (!random.length) throw new Error(fixture.id + ': random values exhausted');
    return random.shift();
  } });
  vm.runInContext(`Math.random = nextRandom;
    let CombinationPadlockPlayerIsBlind = ${fixture.blind};
    let CombinationPadlockBlindCombinationOffset = CombinationPadlockPlayerIsBlind ? Math.floor(Math.random() * 10) : null;
    let CombinationPadlockCombinationLastValue = '';
    ${handler}`, context);
  for (const edit of fixture.edits) {
    context.event = { target: { value: edit.value } };
    vm.runInContext('InventoryItemMiscCombinationPadlockModifyInput(event)', context);
    if (context.event.target.value !== edit.expected) throw new Error(fixture.id + ': expected ' + edit.expected + ', got ' + context.event.target.value);
  }
  if (random.length) throw new Error(fixture.id + ': unused random values');
  console.log('PASS official R133 combination input:', fixture.id);
}
