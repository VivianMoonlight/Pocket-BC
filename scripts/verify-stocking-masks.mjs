// Compare every mask recipe with the pinned upstream generator and exercise BC cache lifecycle.
import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const root = process.argv[2];
if (!root) throw new Error('Expected pinned Echo source directory');
const index = JSON.parse(await fs.readFile('app/src/main/assets/portrait/echo/source-index.json','utf8'));
async function source(path) {
    const bytes = await fs.readFile(`${root}/${path}`);
    assert.equal(createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),
        index.dependencies[path] ?? index.components[path]?.sha);
    return bytes.toString('utf8');
}
const component = 'src/components/衣服和装饰/衣服/全身连体/油光袜.js';
const original = await source(component);
const pose = await source('src/lib/generator/poseMapTool.js');
const port = await fs.readFile('app/src/main/assets/portrait/supplement/stocking-masks.js','utf8');
const recipes = new Map();
const canvas = () => {
    const recipe = {fill:false,images:[]};
    return {getContext:()=>({clearRect(){},fillRect(){recipe.fill=true;},drawImage(image){recipe.images.push(image.source);}}),
        toBlob:callback=>callback(recipe)};
};
const upstream = vm.createContext({PoseType:{HIDE:'Hide'},asset:[['Suit',{Name:'马油袜'}]],
    ImageMapTools:{assetLayer:(group,name,pose)=>`Assets/Female3DCG/${group}/${pose?pose+'/':''}${name}.png`},
    AssetManager:{imageMapping:{createVirtualPath:()=>{let key;return{map:url=>{key=url;},resolve:recipe=>recipes.set(key,recipe)};}}},
    document:{createElement:canvas},URL:{createObjectURL:value=>value},DrawImageCache:true,DrawGetImage:source=>({source}),
    Logger:{error:message=>{throw new Error(message);}},
});
vm.runInContext(pose.replace('export class','class')+'\n'+original.slice(original.indexOf('const armPMap ='),original.indexOf('AfterAssetOverrides.register'))+'\npreload();',upstream);
await new Promise(setImmediate);
assert.equal(recipes.size,120);
const context = vm.createContext({});
vm.runInContext(port,context);
const images = new Set(JSON.parse(await fs.readFile('app/src/main/assets/portrait/echo/catalog.json','utf8')).images);
for(const [url,recipe] of recipes) {
    assert.equal(JSON.stringify(context.MobileBcStockingMaskRecipe(url)),JSON.stringify(recipe));
    for(const image of recipe.images) assert.ok(images.has(image),`Missing pinned mask source: ${image}`);
}
for(const url of ['Assets/Female3DCG/Suit/马油袜_Normal_m5_gmask.png','Assets/Female3DCG/Suit/../马油袜_Normal_m1_gmask.png','other']) {
    assert.equal(context.MobileBcStockingMaskRecipe(url),null);
}

const bc = await fs.readFile('_local-artifacts/portrait-probe/remote/Scripts/ImageCache.js','utf8');
const scripts = JSON.parse(await fs.readFile('app/src/main/assets/portrait/scripts.json','utf8'));
assert.equal(createHash('sha256').update(bc).digest('hex'),scripts.find(s=>s.path==='Scripts/ImageCache.js').sha256);
const pending = new Map();
const failures = [];
const life = vm.createContext({MS_PER_HOUR:3600000,CurrentTime:0,CommonTime:()=>1,
    CommonPromiseCatch:promise=>promise.catch(error=>failures.push(error)),console:{error(){},warn(){},log(){}},
    document:{createElement:canvas},URL,location:{href:'https://portrait.mobilebc.invalid/'},Character:[],
});
vm.runInContext(bc+'\n'+port+`\nvar cache = new ImageCache('test',null,{decode:blob=>({blob}),dispose:()=>{}});
cache._refreshCharactersForImage = () => {};
CachedImage.prototype._fetch = function() { return new Promise((resolve,reject)=>hold(this.url,resolve,reject)); };
var generated = MobileBcInstallStockingMasks({sources:[${JSON.stringify(component)}]},cache);
var originalGet = cache.get.bind(cache);
cache.get = url => generated(url) || originalGet(url);
`,Object.assign(life,{hold:(url,resolve,reject)=>pending.set(url,{resolve,reject})}));
const mask = 'Assets/Female3DCG/Suit/马油袜_Normal_m1_gmask.png';
const tick = () => new Promise(setImmediate);
const get = () => vm.runInContext(`cache.get(${JSON.stringify(mask)})`,life);
let image = get(); assert.equal(image.state,'loading');
pending.values().next().value.resolve({source:'torso'}); await tick();
assert.equal(image.state,'loaded');
vm.runInContext('cache.clear()',life); assert.equal(image.state,'uncached');
image=get();pending.values().next().value.reject(new Error('offline'));await tick();assert.equal(image.state,'failed');
vm.runInContext('cache.clear()',life);image=get();vm.runInContext('cache.clear()',life);
pending.values().next().value.resolve({source:'late'});await tick();assert.equal(image.state,'uncached');
assert.equal(image.data,null);assert.deepEqual(failures,[]);
console.log('PASS 120 upstream stocking recipes; loaded, failed, cleared and late-completion cache states');
