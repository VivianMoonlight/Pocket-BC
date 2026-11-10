// Compare the packaged ports with the pinned upstream callbacks, without a browser.
// node scripts/verify-portrait-supplement.mjs ECHO_SOURCE_DIRECTORY UTILS_TOOLS_INDEX [BC_RESOURCES]
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { monadic } from './echo-monadic.mjs';
import { DialogTools } from './echo-dialog-tools.mjs';

const sourceRoot = process.argv[2];
if (!sourceRoot) throw new Error('Expected pinned Echo source directory');
const root = 'app/src/main/assets/portrait';
const manifest = JSON.parse(await fs.readFile(`${root}/supplement/manifest.json`, 'utf8'));
const scripts = JSON.parse(await fs.readFile(`${root}/scripts.json`, 'utf8'));
const catalog = JSON.parse(await fs.readFile(`${root}/echo/catalog.json`, 'utf8'));
const code = await fs.readFile(`${root}/supplement/adapters.js`, 'utf8');
const drawingCode = await fs.readFile(`${root}/supplement/drawing.js`, 'utf8') + '\n' + await fs.readFile(`${root}/supplement/body-drawing.js`, 'utf8');
const maskSource = await fs.readFile(path.join(sourceRoot,'src/lib/draw/partsMask.js'),'utf8');
assert.ok(drawingCode.replaceAll('\r\n','\n').includes(maskSource.replaceAll('\r\n','\n').replace('export class PartsMask','class MobileBcPartsMask')));
const glSource = await fs.readFile(path.join(sourceRoot,'src/lib/draw/draw.js'),'utf8');
const glCode = await fs.readFile(`${root}/supplement/gl.js`,'utf8');
assert.ok(glCode.replaceAll('\r\n','\n').includes(glSource.replaceAll('\r\n','\n').replace('export class GLImageRenderer','class MobileBcGLImageRenderer').replaceAll('shouldCache(', 'mobileBcShouldCacheTexture(').replaceAll('isImmutable(', 'mobileBcImmutableTexture(')));
let drawCalls = [];
const drawing = {
    AnimationGenerateTempCanvas: (_c, _a, width, height) => {
        drawCalls.push(['canvas', width, height]);
        return { getContext: () => ({}) };
    },
    DynamicDrawTextArc: (text, context, x, y, options) => drawCalls.push(['text', text, context, x, y, options]),
    DynamicDrawText: (text, _context, x, y, options) => drawCalls.push(['text', text, x, y, options]),
    TextItem: { Init: (_data, _c, _item, push, refresh) => drawCalls.push(['init',push,refresh]) },
};
function runtime(version = 'R133', data = manifest, language = 'EN') {
    const context = vm.createContext({ ...drawing, TranslationLanguage: language, GameVersion: version, fetch: async () => ({ ok: true, json: async () => structuredClone(data) }) });
    vm.runInContext(drawingCode, context);
    vm.runInContext(code, context);
    return context.MobileBcSupplement;
}
const pack = runtime();
await pack.prepare(scripts, catalog);
let cases = 0;
{
    const file='src/components/束缚与玩具/幸运贞操带.js';
    const bytes=await fs.readFile(path.join(sourceRoot,file));
    assert.equal(createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),'9e69603ed46ab19fc22cc2090b9977a61e465781');
    const native=await fs.readFile(path.join(process.argv[4] || '_local-artifacts/portrait-probe/remote','Assets/Female3DCG.js'));
    assert.equal(createHash('sha256').update(native).digest('hex'),scripts.find(s=>s.path==='Assets/Female3DCG.js').sha256);
    const definitions=native.toString(),start=definitions.indexOf('const E =');
    const E=vm.runInNewContext(definitions.slice(start,definitions.indexOf('\n});',start)+4)+'\nE');
    const text=bytes.toString();
    const upstream=vm.runInNewContext(text.slice(text.indexOf('const asset ='),text.indexOf('const tamperArea ='))+'\n({asset,extended})',{
        E,ExtendedArchetype:{MODULAR:'modular'},Tools:{CommonChatTags:()=>['SourceCharacter','TargetCharacter','DestinationCharacter','AssetName']},
        scriptDraw:()=>{throw new Error('Unexpected gameplay');},dialogDrawHook:()=>{throw new Error('Unexpected editor');},
    });
    assert.deepEqual(Object.keys(upstream.extended.ScriptHooks).sort(),['Draw','ScriptDraw']);
    delete upstream.extended.ScriptHooks;
    const record=catalog.assets.find(a=>a.group==='ItemPelvis'&&a.definition.Name==='幸运贞操带');
    assert.deepEqual(record.definition,JSON.parse(JSON.stringify(upstream.asset)));
    assert.deepEqual(record.extended,JSON.parse(JSON.stringify(upstream.extended)));
    assert.equal(record.portraitAdapter,undefined);
    cases++;
    const appearance=await fs.readFile(path.join(process.argv[4] || '_local-artifacts/portrait-probe/remote','Screens/Character/Appearance/Appearance.js'));
    assert.equal(createHash('sha256').update(appearance).digest('hex'),scripts.find(s=>s.path==='Screens/Character/Appearance/Appearance.js').sha256);
    const constants={};
    for(const [code,names]of [[definitions,['AssetUpperOverflowAlpha','AssetLowerOverflowAlpha']],[appearance.toString(),['CanvasUpperOverflow','CanvasLowerOverflow']]])for(const name of names) {
        const start=code.indexOf(`const ${name} =`);
        constants[name]=vm.runInNewContext(code.slice(start,code.indexOf(';',start)+1)+`\n${name}`);
    }
    for(const [name,hash]of [['行李箱','9a24230beae04da17fc7e963a720adbeb4760625'],['硬壳行李箱','a12a5478a8a7ba45124b0df5f2554805015e22d9'],['宠物箱','07da530c09c5d1eb2214492adb566416f1afdd23']]) {
        const bytes=await fs.readFile(path.join(sourceRoot,`src/components/束缚与玩具/包装/${name}.js`));
        assert.equal(createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),hash);
        const text=bytes.toString();
        const before=text.includes('function beforeDraw(')?text.slice(text.indexOf('function beforeDraw('),text.indexOf('const assets =')):'';
        const assets=vm.runInNewContext(before+text.slice(text.indexOf('const assets ='),text.indexOf('export default function'))+'\nassets',{
            ...constants,E,DialogTools,ExtendedArchetype:{MODULAR:'modular'},Tools:{CommonChatTags:()=>['SourceCharacter','TargetCharacter','DestinationCharacter','AssetName']},
            dialog:{createHooks:(base={})=>({...base,Click:()=>{throw new Error('Editor ran');},Draw:()=>{throw new Error('Editor ran');}})},
        });
        delete assets[2].extended.ScriptHooks;
        const record=catalog.assets.find(a=>a.group===assets[0]&&a.definition.Name===name);
        assert.deepEqual(record.definition,JSON.parse(JSON.stringify(assets[1])));
        assert.deepEqual(record.extended,JSON.parse(JSON.stringify(assets[2].extended)));cases++;
    }
}
{
    const bytes=await fs.readFile(path.join(sourceRoot,'src/components/束缚与玩具/刺激/电击器.js'));
    assert.equal(createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),'e1217bca00dd913c88903fe1e1d4dfc64a529060');
    const text=bytes.toString();
    const beforeDraw=vm.runInNewContext(text.slice(text.indexOf('function beforeDraw('),text.indexOf('/** @type {ExtendedItemScriptHookCallbacks.ScriptDraw'))+'\nbeforeDraw');
    const record=catalog.assets.find(a=>a.group==='ItemLegs'&&a.definition.Name==='电击器');
    assert.equal(record.portraitAdapter,undefined);
    assert.equal(record.extended.ScriptHooks,undefined);
    for(const L of ['闪光','本体','绑带']) {
        const state={};
        const opacity=beforeDraw(null,null,{L,PersistentData:()=>state}).Opacity;
        const layer=record.definition.Layer.find(l=>l.Name===L);
        assert.equal(layer.HasImage,opacity===0?false:undefined);
        assert.equal(layer.Opacity,undefined);
        assert.deepEqual(state,{});cases++;
    }
}
const optionalSource=(await fs.readFile('scripts/echo-monadic.mjs','utf8')).replaceAll('\r\n','\n').replace(/^\/\*[\s\S]*?\*\/\n/,'');
assert.equal(createHash('sha1').update(`blob ${Buffer.byteLength(optionalSource)}\0`).update(optionalSource).digest('hex'),'feb4b05be271eb2ba667db153a893d5e51fc2e6e');
for (const adapter of manifest.adapters.filter(a => a.source === 'echo')) {
    const bytes = await fs.readFile(path.join(sourceRoot, adapter.path));
    const hash = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
    assert.equal(hash, adapter.sourceHash);
    const text = bytes.toString('utf8');
    if(adapter.id==='echo-hard-luggage') {
        const upstream=vm.runInNewContext(text.slice(text.indexOf('function beforeDraw('),text.indexOf('const assets ='))+'\nbeforeDraw');
        const port=pack.extended(catalog.assets.find(a=>a.portraitAdapter===adapter.id)).ScriptHooks.BeforeDraw;
        for(const s of [undefined,0,1,2,3,'0'])for(const c of [undefined,0,1,2,3,'3'])for(const L of ['箱盖','衣物堆','主体后','Unknown']) {
            const args={L,Property:{TypeRecord:{s,c}}},before=JSON.stringify(args);
            assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(null,null,args)));
            assert.equal(JSON.stringify(args),before);cases++;
        }
        continue;
    }
    if(adapter.id==='echo-chocolate') {
        const upstream=vm.runInNewContext(text.slice(text.indexOf('const extProp ='),text.indexOf('const itemDialog ='))+'\nbeforeDraw');
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=pack.extended(record).ScriptHooks.BeforeDraw;
        assert.deepEqual(Object.keys(pack.extended(record).ScriptHooks),['BeforeDraw']);
        const values=[undefined,null,0,-1,2147483647,2147483648,4294967295,Number.MAX_SAFE_INTEGER,'123','invalid',...Array.from({length:256},(_,i)=>i+1)];
        for(const LuziRid of values)for(const LuziCid of [undefined,0,123,2147483647,Number.MAX_SAFE_INTEGER,'123'])for(const L of ['Gt','Ch','Wr']) {
            const args={L,Property:{LuziRid,LuziCid}};
            const before=JSON.stringify(args);
            assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(null,null,args)));
            assert.equal(JSON.stringify(args),before);cases++;
        }
        continue;
    }
    if(adapter.id==='echo-tray') {
        let calls=[],missing=false;
        const image=url=>{calls.push(['url',url]);return missing?null:'image';};
        const globals={PoseType:{HIDE:'Hide',DEFAULT:''},DrawGetImage:image,
            DrawImageEx:(_image,_ctx,...args)=>calls.push(['image',...args]),
            AnimationGenerateTempCanvas:(_c,_a,width,height)=>{
                calls.push(['canvas',width,height]);return {width,height,getContext:()=>({clearRect:(...args)=>calls.push(['clear',...args])})};
            },GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})};
        const context=vm.createContext(globals);
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const upstream=vm.runInNewContext(text.slice(text.indexOf('const drinksImgs ='),text.indexOf('/**@type {NoArchItemConfig} */'))+'\nafterDraw',{
            ...globals,maxv:{饮料:4},Access:{getOr:(o,k,f)=>o[k]??f},
            Tools:{getAssetImageThen:(draw,name)=>monadic(image(context.MobileBcAssetImageUrl(draw,name)))},
        });
        const properties=Array.from({length:12},(_,i)=>({Luzi_InventoryType:'曲奇',Luzi_InventoryContent:Array.from({length:i+1},()=>({Property:{}}))}));
        for(let combo=0;combo<256;combo++)properties.push({Luzi_InventoryType:'饮料',Luzi_InventoryContent:Array.from({length:4},(_,i)=>({IAsset:['空杯','橙汁','可乐','牛奶'][(combo>>(i*2))&3]}))});
        properties.push({Luzi_InventoryType:'饮料',Luzi_InventoryContent:[{},null,{IAsset:'橙汁'}]},{});
        for(const record of catalog.assets.filter(a=>a.portraitAdapter===adapter.id)) {
            const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
            for(const Property of properties)for(missing of [false,true]) {
                const run=callback=>{calls=[];const data={};
                    for(let i=0;i<2;i++)for(const L of ['曲奇','饮料','盘']) callback(null,null,{
                        C:{},A:{...record.definition,Layer:record.definition.Layer.map(l=>({...l,PoseMapping:l.PoseMapping??{}})),Group:{Family:'Female3DCG'}},
                        L,Property,Pose:'',G:'',GroupName:'ItemTorso',LayerType:'',X:170,Y:1060,AlphaMasks:[[1,2,3,4]],PersistentData:()=>data,
                        drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args]),
                    });
                    return JSON.stringify(calls);
                };
                const before=JSON.stringify(Property);
                assert.equal(run(port),run(upstream));assert.equal(JSON.stringify(Property),before);cases++;
            }
            for(const Property of [{Luzi_InventoryType:'曲奇'},{Luzi_InventoryType:'曲奇',Luzi_InventoryContent:[]},{Luzi_InventoryType:'饮料',Luzi_InventoryContent:null}]) {
                calls=[];port(null,null,{L:Property.Luzi_InventoryType,Property,PersistentData:()=>({})});assert.equal(calls.length,0);cases++;
            }
        }
        continue;
    }
    if(adapter.id==='echo-android-body') {
        let calls=[],missing=false;
        const tracePartialDraw=(_c,_a,groups)=>{
            calls.push(['partial',groups]);
            const canvas=id=>({id,getContext:()=>id});
            return {Canvas:canvas('normal'),CanvasBlink:canvas('blink')};
        };
        const image=url=>{calls.push(['url',url]);return missing?null:'mask';};
        const globals={PoseType:{HIDE:'Hide',DEFAULT:''},monadic,Access:{get:(o,k)=>o[k]},tracePartialDraw,
            DrawGetImage:image,DrawImageEx:(...args)=>calls.push(['image',...args]),
            GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})};
        const context=vm.createContext(globals);
        vm.runInContext(drawingCode.replace('return {ghost:afterDraw,android:androidBodyDraw};','partialDraw=tracePartialDraw; return {ghost:afterDraw,android:androidBodyDraw};')+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const upstream=vm.runInNewContext(text.slice(text.indexOf('const drawConfig ='),text.indexOf('/** @type {CustomAssetDefinition} */'))+'\nafterDraw',{
            ...globals,partialDraw:tracePartialDraw,PartialDrawCanvasCacheData:class {get(){return {}; }},
            Tools:{getAssetImageThen:(draw,mask)=>monadic(image(context.MobileBcAssetImageUrl(draw,mask)))},
        });
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
        for(const Pose of ['', 'Kneel','KneelingSpread','Spread','Yoked','Hogtied'])for(const G of ['Small','Normal','Large','XLarge'])for(missing of [false,true]) {
            const run=callback=>{calls=[];const cache={};
                for(let i=0;i<2;i++)for(const L of record.definition.Layer.map(l=>l.Name)) {
                    const args={C:{},A:{...record.definition,Layer:record.definition.Layer.map(l=>({...l,PoseMapping:l.PoseMapping??record.definition.PoseMapping})),Group:{Family:'Female3DCG'}},L,Pose,G,GroupName:record.group,LayerType:'',X:0,Y:700,
                        PersistentData:()=>cache,AlphaMasks:[[1,2,3,4]],
                        drawCanvas:(canvas,...args)=>calls.push(['draw',canvas.id,...args]),drawCanvasBlink:(canvas,...args)=>calls.push(['blink',canvas.id,...args])};
                    callback(args);
                }
                return JSON.stringify(calls);
            };
            assert.equal(run(args=>port(null,null,args)),run(upstream));cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-ghost-body') {
        const bodyCode=(await fs.readFile(`${root}/supplement/body-drawing.js`,'utf8')).replaceAll('\r\n','\n');
        const helper=(await fs.readFile(path.join(sourceRoot,'src/components/衣服和装饰/替换身体/metaDraw.js'),'utf8')).replaceAll('\r\n','\n');
        const callback=text.slice(text.indexOf('const mergingClothes ='),text.indexOf('/** @type {CustomAssetDefinition} */')).replaceAll('\r\n','\n')
            .replace('CanvasBlink, 0.5, adjustedCache','CanvasBlink, 0.5, data.adjustedCache.get(adjustedDrawCacheKey + "_Blink")');
        assert.ok(bodyCode.includes(callback));
        const helperCode=bodyCode.slice(bodyCode.indexOf('/**'),bodyCode.indexOf('const mergingClothes ='));
        const original=helperCode.replace('    ctx.globalCompositeOperation = "source-over";\n    ctx.globalAlpha = 1;\n','')
            .replace('    try {\n','').replace('    } finally { GLDrawCanvas = oldDrawCanvas; }','')
            .replace('    return { Canvas: copyC.Canvas, CanvasBlink: copyC.CanvasBlink };','    GLDrawCanvas = oldDrawCanvas;\n\n    return { Canvas: copyC.Canvas, CanvasBlink: copyC.CanvasBlink };');
        assert.equal(original.replace(/\s+/g,''),helper.replaceAll('export ','').replace(/\s+/g,''));
        const host={},canvas=()=>{const ctx={globalCompositeOperation:'source-in',globalAlpha:0.2,clearRect(){},drawImage(){}};return {width:500,height:1000,getContext:()=>ctx};};
        let fail=false,selected;
        const context=vm.createContext({GLDrawCanvas:host,CanvasDrawWidth:500,CanvasDrawHeight:1000,
            AnimationGenerateTempCanvas:canvas,CharacterAppearanceStringify:c=>c.Appearance,
            AppearanceItemParse:x=>x,CharacterAppearanceSortLayers:c=>c.DrawAppearance,
            CharacterAppearanceBuildCanvas:c=>{selected=c;if(fail)throw new Error('nested draw failure');}});
        vm.runInContext(helperCode,context);
        const character={Appearance:['BodyUpper','Eyes','Cloth'].map(Name=>({Asset:{Group:{Name}}}))};
        context.character=character;context.cache={};
        const before=JSON.stringify(character);
        for(fail of [false,true,false]) {
            const run=()=>vm.runInContext('partialDraw(character,{},["BodyUpper","Eyes"],cache)',context);
            if(fail)assert.throws(run,/nested draw failure/);else run();
            assert.equal(context.GLDrawCanvas,host);
            assert.equal(JSON.stringify(selected.DrawAppearance.map(a=>a.Asset.Group.Name)),JSON.stringify(['BodyUpper','Eyes']));
            assert.equal(context.cache.Canvas.getContext('2d').globalCompositeOperation,'source-over');
            assert.equal(context.cache.CanvasBlink.getContext('2d').globalAlpha,1);
            assert.equal(JSON.stringify(character),before);cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-doll-body') {
        let hidden=false;
        const globals={asset:{Name:'人偶'},CharacterAppearanceItemIsHidden:()=>hidden};
        const start=text.indexOf('const shouldHide =');
        const upstream=vm.runInNewContext(text.slice(start,text.indexOf('export default function',start))+'\nbeforeDraw',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks.BeforeDraw;
        assert.equal(record.extended.Archetype,'noarch');
        for(let mask=0;mask<8;mask++)for(hidden of [false,true])for(const self of [false,true])
        for(const property of [false,true])for(const L of ['上半身','下半身','手','钥匙孔']) {
            const Hide=['BodyUpper','BodyLower','HandsLeft'].filter((_g,i)=>mask&(1<<i));
            const item={Asset:{Name:self?'人偶':'Other',Group:{Name:'ItemTorso'},...(!property?{Hide}:{})},Property:property?{Hide}:{}};
            const args={L,C:{DrawAppearance:[item]}};
            const before=JSON.stringify(args);
            assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(args)));
            assert.equal(JSON.stringify(args),before);cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-surveillance-robot') {
        let calls=[], fixedYOffset=0, missing=false;
        class Renderer {
            static BCColorToGLColor(color) { return ['rgba',color]; }
            constructor() { calls.push(['renderer']); }
            clearRect(...args) { calls.push(['glClear',...args]); }
            drawImage(...args) { calls.push(['glImage',...args]); }
        }
        const image=url=>{calls.push(['url',url]);return missing?null:'image';};
        const globals={Date:{now:()=>0},Math:Object.assign(Object.create(Math),{random:()=>0}),CanvasUpperOverflow:700,
            PoseType:{HIDE:'Hide',DEFAULT:''},GLImageRenderer:Renderer,MobileBcGLImageRenderer:Renderer,
            CommonDrawComputeDrawingCoordinates:()=>({fixedYOffset}),CommonDrawResolveLayerColor:()=> '#B2E8FF',
            AnimationGenerateTempCanvas:(_c,_a,width,height)=>{calls.push(['canvas',width,height]);
                const ctx=new Proxy({}, {set(o,k,v){o[k]=v;calls.push(['set',k,v]);return true;},get(o,k){return k in o?o[k]:(...args)=>calls.push([k,...args]);}});
                return {width,height,getContext:()=>ctx};},
            DrawGetImage:image,Tools:{getAssetImageThen:draw=>({then:callback=>{
                const img=image(`Assets/Female3DCG/${draw.GroupName}/${[adapter.name,draw.LayerType,draw.L].filter(Boolean).join('_')}.png`);if(img)callback(img);
            }})},
        };
        const helper=await fs.readFile(path.join(sourceRoot,'src/lib/draw/afterDraw.js'),'utf8');
        const upstream=vm.runInNewContext(helper.replace('export function','function')+'\n'+
            text.slice(text.indexOf('function compareRopeState('),text.indexOf('/** @type {TypedItemConfig} */'))+
            '\n({BeforeDraw:beforeDraw,...afterDraw.hooks()})',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks;
        assert.deepEqual(Object.keys(port).sort(),['AfterDraw','BeforeDraw']);
        for(const typed of [0,1,2])for(const leashed of [false,true])for(fixedYOffset of [-100,0,50])
        for(const Color of ['#123456','Default'])for(missing of [false,true]) {
            const run=hooks=>{
                calls=[];const data={};
                for(let repeat=0;repeat<2;repeat++)for(const L of ['绳子','绳子光芒','眼睛','跟随模式','跟随模式_抓住','固定模式']) {
                    const args={L,Color,Property:{TypeRecord:{typed}},C:{HasEffect:()=>leashed},CA:{},
                        A:{Name:adapter.name,Group:{Family:'Female3DCG'},Layer:[{Name:L,PoseMapping:{}},{Name:'绳子光芒'}]},
                        X:350,Y:800,Pose:'',G:'',GroupName:'ItemNeckRestraints',LayerType:'',AlphaMasks:[[1,2,3,4]],PersistentData:()=>data,
                        drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args])};
                    calls.push(['before',L,hooks.BeforeDraw(null,null,args)]);
                    hooks.AfterDraw(null,null,args);
                }
                return JSON.stringify(calls);
            };
            assert.equal(run(port),run(upstream));cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-liquid-bag') {
        const toolsBytes=await fs.readFile(process.argv[3] || path.join(sourceRoot,'utils/src/Tools/index.js'));
        assert.equal(createHash('sha1').update(`blob ${toolsBytes.length}\0`).update(toolsBytes).digest('hex'),'94cce7bf728ae1d00f3e8f260d05218e1e0888ec');
        const toolsText=toolsBytes.toString(), method=toolsText.indexOf('static getAssetURL(');
        const getAssetURL=vm.runInNewContext('(function '+toolsText.slice(method+'static '.length,toolsText.indexOf('\n    }',method)+6)+')',{PoseType:{HIDE:'Hide',DEFAULT:''}});
        let calls=[];
        const globals={PoseType:{HIDE:'Hide',DEFAULT:''},Tools:{getAssetURL},
            AnimationGenerateTempCanvas:(_c,_a,w,h)=>{calls.push(['canvas',w,h]);return {getContext:()=>({})};},
            DrawImageEx:(url,_ctx,...args)=>calls.push(['image',url,...args]),
        };
        const start=text.indexOf('function afterDraw(');
        const upstream=vm.runInNewContext('('+text.slice(start,text.indexOf('\n}',start)+2)+')',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        for(const record of catalog.assets.filter(a=>a.portraitAdapter===adapter.id)) {
            const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
            for(const Liquid of [undefined,null,-1,0,0.1,0.5,1,2,'0.5'])for(const Auto of [false,true])
            for(const Pose of ['', 'Kneel','KneelingSpread','Spread','Hogtied'])for(const L of ['尿','袋']) {
                const args={L,Pose,Property:{Liquid,Auto},C:{},A:{Name:adapter.name,Group:{Family:'Female3DCG'},Layer:[{Name:L,PoseMapping:record.definition.PoseMapping}]},
                    GroupName:'ItemTorso',G:'',LayerType:'',X:0,Y:700,AlphaMasks:[[1,2,3,4]],
                    drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args])};
                const before=JSON.stringify(args.Property);
                calls=[];upstream(null,null,args);const expected=JSON.stringify(calls);
                calls=[];port(null,null,args);assert.equal(JSON.stringify(calls),expected);
                assert.equal(JSON.stringify(args.Property),before);cases++;
            }
        }
        continue;
    }
    if (adapter.id === 'echo-cyber-shield') {
        const toolsBytes = await fs.readFile(process.argv[3] || path.join(sourceRoot,'utils/src/Tools/index.js'));
        assert.equal(createHash('sha1').update(`blob ${toolsBytes.length}\0`).update(toolsBytes).digest('hex'),'94cce7bf728ae1d00f3e8f260d05218e1e0888ec');
        const toolsText=toolsBytes.toString();
        const start=toolsText.indexOf('export class _ColorTools');
        const colorSource=toolsText.slice(start,toolsText.indexOf('\nexport const ColorTools',start)).replace('export class _ColorTools','class MobileBcColorTools');
        assert.ok(drawingCode.replaceAll('\r\n','\n').includes(colorSource.replaceAll('\r\n','\n')));
        const ColorTools=vm.runInNewContext(colorSource+'\nnew MobileBcColorTools()');
        let calls=[], missing=false;
        const image=url=>{calls.push(['url',url]);return missing?null:'image';};
        const globals={
            Date:{now:()=>0},Math:Object.assign(Object.create(Math),{random:()=>0}),ColorTools,CanvasUpperOverflow:700,PoseType:{HIDE:'Hide',DEFAULT:''},
            AnimationGenerateTempCanvas:(_c,_a,width,height)=>{calls.push(['canvas',width,height]);return {width,height,getContext:()=>new Proxy({}, {
                set(_o,key,value){calls.push(['set',key,value]);return true;},
                get(_o,key){return (...args)=>calls.push([key,...args]);},
            })};},
            DrawGetImage:image,
            DrawImageEx:(img,_ctx,...args)=>calls.push(['image',img,...args]),
            Tools:{getAssetImageThen:draw=>({then:callback=>{
                const img=image(`Assets/Female3DCG/${draw.GroupName}/${[adapter.name,draw.LayerType,draw.L].filter(Boolean).join('_')}.png`);
                if(img)callback(img);
            }})},
        };
        const helper=await fs.readFile(path.join(sourceRoot,'src/lib/draw/afterDraw.js'),'utf8');
        const upstream=vm.runInNewContext(helper.replace('export function','function')+'\n'+
            text.slice(text.indexOf('const afterDraw ='),text.indexOf('/** @type {AddAssetWithConfigParamsNoGroup} */'))+'\nafterDraw.hooks().AfterDraw',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
        for(const f of [undefined,0,1])for(const colors of [['#5CB5FF','#FF5CFC'],['#000000','#FFFFFF'],['#123456','#ABCDEF'],['','']])
        for(const [X,Y] of [[230,1208],[0,700],[250,1300]])for(missing of [false,true]) {
            const run=callback=>{
                calls=[];const data={};
                for(const [L,Color] of [['LightColor1',colors[0]],['LightColor2',colors[1]],['Light','Default'],['Bloom','Default'],['Base','Default']]) {
                    callback(null,null,{L,Color,Property:{TypeRecord:{f}},C:{},A:{Name:adapter.name,Group:{Family:'Female3DCG'},Layer:[{Name:L,PoseMapping:{}}]},
                        GroupName:'ItemVulva',Pose:'',G:'',LayerType:'',X,Y,PersistentData:()=>data,
                        drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args])});
                }
                return JSON.stringify(calls);
            };
            assert.equal(run(port),run(upstream));cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-one-bar-prison') {
        const start = text.indexOf('const lFunction =');
        const end = text.indexOf('/** @type {EffectName[]} */',start);
        const upstream = vm.runInNewContext(text.slice(start,end)+'\nbeforeDraw', {Date:{now:()=>0}});
        const record = catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        assert.ok(record);
        const port = pack.extended(record).ScriptHooks.BeforeDraw;
        for(const Property of [undefined,{}, {TypeRecord:{}}, ...[null,-1,0,1,2,3,4,5,6,'0','1','2'].map(d=>({TypeRecord:{d}}))])
        for(const L of ['A_柱子伸缩','A_阳具','A_柱子1','A_底盘'])for(const Y of [0,550,1250]) {
            const args={Property,L,Y};
            const before=JSON.stringify(args);
            assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(null,null,args)));
            assert.equal(JSON.stringify(args),before);
            cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-tentacle-suit') {
        const start = text.indexOf('function beforeDraw('), maskStart = text.indexOf('const mask =');
        const upstream = vm.runInNewContext(text.slice(maskStart,text.indexOf('\n];',maskStart)+3) + '\n(' +
            text.slice(start,text.indexOf('\n}',start)+2) + ')', {Date:{now:()=>0}});
        const records = catalog.assets.filter(a=>a.portraitAdapter===adapter.id);
        assert.equal(records.length,2);
        for(const record of records) {
            const port = pack.extended(record).ScriptHooks.BeforeDraw;
            for(const Property of [undefined,{},...[-1,0,1,2,3,4,null,'0'].map(Intensity=>Object.freeze({Intensity}))])
            for(const L of ['触手','触手背后','触手服','P'])for(const Y of [0,470]) {
                const args=Object.freeze({Property,L,Y});
                assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(null,null,args)));
                cases++;
            }
            assert.deepEqual(Object.keys(record.extended.Modules[0].Options[2].ArchetypeConfig),['Archetype']);
        }
        continue;
    }
    if (['echo-evilfall-glass','echo-evilfall-mask'].includes(adapter.id)) {
        let calls=[], missing=false;
        const image=url=>{calls.push(['url',url]);return missing ? null : 'image';};
        const globals={
            Date:{now:()=>0}, PoseType:{HIDE:'Hide',DEFAULT:''},
            AnimationGenerateTempCanvas:(_c,_a,width,height)=>{calls.push(['canvas',width,height]);return {width,height,getContext:()=>new Proxy({}, {
                set(_o,key,value){calls.push(['set',key,value]);return true;},
                get(_o,key){return (...args)=>calls.push([key,...args]);},
            })};},
            DrawGetImage:image,
            DrawImageEx:(img,_ctx,...args)=>calls.push(['image',img,...args]),
            Tools:{getAssetImageThen:draw=>({then:callback=>{
                const img=image(`Assets/Female3DCG/${draw.GroupName}/${[adapter.name,draw.LayerType,draw.L].filter(Boolean).join('_')}.png`);
                if(img)callback(img);
            }})},
        };
        const helper=await fs.readFile(path.join(sourceRoot,'src/lib/draw/afterDraw.js'),'utf8');
        const upstream=vm.runInNewContext(helper.replace('export function','function')+'\n'+
            text.slice(text.indexOf('const afterDraw ='),text.indexOf('const extended ='))+'\nafterDraw.hooks().AfterDraw',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        for(const record of catalog.assets.filter(a=>a.portraitAdapter===adapter.id)) {
            const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
            for(const L of ['light1','light2','effect','frame_diff'])for(const f of [undefined,0,1])
            for(const MemberNumber of [0,1,42])for(const Color of ['#123456','#FFFFFF'])for(missing of [false,true]) {
                const args=()=>({L,Color,Property:{TypeRecord:{f}},C:{MemberNumber},A:{Name:adapter.name,Group:{Family:'Female3DCG'},Layer:[{Name:L,PoseMapping:{}}]},
                    GroupName:record.definition.DynamicGroupName,Pose:'',G:'',LayerType:L==='effect'?'m1':'',X:160,Y:830,
                    AlphaMasks:[[1,2,3,4]],PersistentData:()=>({}),drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args])});
                calls=[];upstream(null,null,args());const expected=JSON.stringify(calls);
                calls=[];port(null,null,args());assert.equal(JSON.stringify(calls),expected);
                cases++;
            }
        }
        continue;
    }
    if (adapter.id === 'echo-cardboard-box') {
        let calls=[];
        const globals={
            AnimationGenerateTempCanvas:(_c,_a,w,h)=>{calls.push(['canvas',w,h]);return {getContext:()=>({rotate:a=>calls.push(['rotate',a])})};},
            TextItem:{Init:(_data,_c,_item,push,refresh)=>calls.push(['init',push,refresh])},
            DynamicDrawText:(text,_ctx,...args)=>calls.push(['text',text,...args]),
        };
        const start=text.indexOf('function afterDraw(');
        const upstream=vm.runInNewContext('('+text.slice(start,text.indexOf('\n}',start)+2)+')',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const extended=context.MobileBcSupplement.extended(record);
        const port=adapter.hookPath.reduce((value,key)=>value[key],extended).ScriptHooks.AfterDraw;
        for(const L of ['Text','F','Other'])for(const t of [undefined,0,1])
        for(const Text of [undefined,'','Fragile'])for(const Color of ['#123456','#FFFFFF']) {
            const args={L,Color,Property:{TypeRecord:{t}},CA:{Property:{Text}},X:0,Y:700,AlphaMasks:[[1,2,3,4]],
                drawCanvas:(_c,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_c,...args)=>calls.push(['blink',...args])};
            calls=[];upstream({font:'Archivo Black'},null,args);const expected=JSON.stringify(calls);
            calls=[];port({font:'Archivo Black'},null,args);assert.equal(JSON.stringify(calls),expected);
            cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-ruler') {
        let calls = [];
        let noContext = false;
        const globals = {
            AnimationGenerateTempCanvas: (_c,_a,w,h) => {
                calls.push(['canvas',w,h]);
                return {getContext:()=>noContext ? null : new Proxy({}, {
                    set(_target,key,value){calls.push(['set',key,value]);return true;},
                    get(_target,key){return (...args)=>calls.push([key,...args]);},
                })};
            },
            DynamicDrawText:(text,_ctx,...args)=>calls.push(['text',text,...args]),
        };
        const start=text.indexOf('function afterDraw(');
        const upstream=vm.runInNewContext('('+text.slice(start,text.indexOf('\n}',start)+2)+')',globals);
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
        for(const L of ['Lines','Text','Other'])for(const Color of ['Default','#123456','#FFFFFF'])
        for(const Property of [undefined,{}, {Text:'One',Text2:'Two',Text3:'三',Text4:'Four'}, {Text:'',Text4:'Last'}])
        for(noContext of [false,true]) {
            const args={L,Color,Property,X:150,Y:520,AlphaMasks:[[1,2,3,4]],
                drawCanvas:(_canvas,...args)=>calls.push(['draw',...args]),
                drawCanvasBlink:(_canvas,...args)=>calls.push(['blink',...args])};
            calls=[];upstream({font:'Nanum Pen Script'},null,args);const expected=JSON.stringify(calls);
            calls=[];port({font:'Nanum Pen Script'},null,args);assert.equal(JSON.stringify(calls),expected);
            cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-cross-collar') {
        // The pinned GL and mask algorithms are checked above; compare the wrapper's
        // final draw coordinates, colors, opacity and clipping groups here.
        let calls=[];
        class Mask { constructor(_canvas,groups,removed){this.result='mask';calls.push(['mask',groups,removed]);} draw(){calls.push(['mask.draw']);} }
        class GL { static BCColorToGLColor(c){return c;} clearRect(...args){calls.push(['clear',...args]);} drawImage(...args){calls.push(['image',...args]);} }
        const globals={...drawing,CanvasUpperOverflow:100,PoseType:{HIDE:'Hide',DEFAULT:''},
            MobileBcGLImageRenderer:GL,TestMask:Mask,DrawGetImage:url=>{calls.push(['url',url]);return 'image';}};
        const context=vm.createContext({...globals,GameVersion:'R133',fetch:async()=>({ok:true,json:async()=>structuredClone(manifest)})});
        vm.runInContext(drawingCode+'\nMobileBcPartsMask = TestMask;\n'+code,context);
        await context.MobileBcSupplement.prepare(scripts,catalog);
        const record=catalog.assets.find(a=>a.portraitAdapter===adapter.id);
        const port=context.MobileBcSupplement.extended(record).ScriptHooks.AfterDraw;
        for(const L of ['A1','A3','A4'])for(const Color of ['Default','#123456'])for(const Opacity of [0,0.5,1]) {
            calls=[];
            port(null,null,{L,Color,Opacity,A:{Name:'交领右衽',Group:{Family:'Female3DCG'},Layer:[{Name:L,PoseMapping:{}}]},
                Pose:'',G:'',GroupName:'ClothAccessory',LayerType:'',X:130,Y:310,AlphaMasks:[],PersistentData:()=>({}),
                drawCanvas:(_canvas,...args)=>calls.push(['draw',...args]),drawCanvasBlink:(_canvas,...args)=>calls.push(['blink',...args])});
            if(L==='A1')assert.deepEqual(calls,[]);
            else assert.deepEqual(JSON.parse(JSON.stringify(calls)),[
                ['url',`Assets/Female3DCG/ClothAccessory/交领右衽_${L}.png`],
                ['mask',['Cloth','ClothAccessory','Bra','BodyUpper'],['ArmsLeft','AnkletRight','HandsLeft','HandsRight']],
                ['mask.draw'],['clear',0,0,500,1000],['image','image',130,210,{color:Color,colorAlpha:Opacity,alphaTex:'mask'}],
                ['draw',0,100,[]],['blink',0,100,[]],
            ]);
            cases++;
        }
        continue;
    }
    if (['echo-gradient','echo-double-gradient'].includes(adapter.id)) {
        let calls = [], canvasId = 0;
        const fake = () => ({id:canvasId++,getContext:()=>new Proxy({}, {
            set(_o,key,value) { calls.push(['set',key,value]); return true; },
            get(_o,key) {
                if(key==='createLinearGradient')return (...args)=>{
                    calls.push([key,...args]); return {addColorStop:(...args)=>calls.push(['stop',...args])};
                };
                return (...args)=>calls.push([key,...args]);
            },
        })});
        class Mask {
            constructor(canvas,groups) { this.result=canvas; calls.push(['mask',groups]); }
            draw() { calls.push(['mask.draw']); }
        }
        const globals={AnimationGenerateTempCanvas:(_c,_a,w,h)=>{calls.push(['canvas',w,h]);return fake();},CanvasUpperOverflow:100,Mask};
        const context=vm.createContext(globals);
        vm.runInContext(drawingCode+'\nMobileBcPartsMask = Mask;',context);
        const start=text.indexOf('function afterDraw('), end=text.indexOf('\n}',start)+2;
        const upstream=vm.runInNewContext('('+text.slice(start,end)+')',{...globals,PartsMask:Mask,props:p=>p,baseline:{upperBound:125,gradientSize:300,rotation:180}});
        for(const L of ['Front','Back','Other'])for(const enabled of [0,1])for(const rotation of [0,90,180,270])for(const gradientSize of [1,300]) {
            const args=()=>({A:{Name:adapter.name},Color:'#AA4488',Property:{TypeRecord:{f:enabled,b:enabled},rotation,gradientSize},L,
                PersistentData:()=>({}),AlphaMasks:[],drawCanvas:(canvas,...args)=>calls.push(['draw',canvas.id,...args]),
                drawCanvasBlink:(canvas,...args)=>calls.push(['blink',canvas.id,...args])});
            calls=[];canvasId=0;upstream(null,null,args());const expected=JSON.stringify(calls);
            calls=[];canvasId=0;context.MobileBcGradientDraw(null,null,args());assert.equal(JSON.stringify(calls),expected);
            cases++;
        }
        continue;
    }
    if (adapter.id === 'echo-body-text') {
        const upstream = vm.runInNewContext(text.slice(text.indexOf('const Positions ='),text.indexOf('const asset =')) + '\nafterDrawHook', drawing);
        const record = catalog.assets.find(a => a.portraitAdapter === adapter.id);
        const extended = pack.extended(record);
        assert.throws(() => pack.extended({...record,group:'ItemMisc'}),/Invalid portrait adapter/);
        for (const hookPath of adapter.hookPaths) {
            const port = hookPath.reduce((v,k)=>v[k],extended).ScriptHooks.AfterDraw;
            for(const L of ['Text','Other']) for(const filled of [false,true]) {
                const Property = Object.fromEntries(Array.from({length:45},(_,i)=>[`Text${i+1}`,filled?`line${i+1}`:'']));
                const args = { L, CA:{Property}, X:0,Y:120,Color:'#123456',AlphaMasks:[],
                    drawCanvas:(_canvas,...args)=>drawCalls.push(['draw',...args]),
                    drawCanvasBlink:(_canvas,...args)=>drawCalls.push(['blink',...args]) };
                drawCalls=[]; upstream({font:'Ananda Black'},null,args); const expected=JSON.stringify(drawCalls);
                drawCalls=[]; port({font:'Ananda Black'},null,args); assert.equal(JSON.stringify(drawCalls),expected);
                cases++;
            }
        }
        continue;
    }
    if (adapter.id === 'echo-dog-house') {
        const start = text.indexOf('function afterDraw(');
        const upstream = vm.runInNewContext('(' + text.slice(start, text.indexOf('\n}', start) + 2) + ')', drawing);
        const record = catalog.assets.find(a => a.portraitAdapter === adapter.id);
        for (const hookPath of adapter.hookPaths) {
            const port = hookPath.reduce((v,k)=>v[k], pack.extended(record)).ScriptHooks.AfterDraw;
            for (const t of [undefined, 0, 1]) for (const L of ['Text', 'Text_F', 'Other'])
            for (const Text of [undefined, '', 'Home']) for (const Text2 of [undefined, '', 'Second']) {
                const args = { L, Property: {TypeRecord:{t}}, CA:{Property:{Text,Text2}}, X:20, Y:30,
                    Color:'#123456', AlphaMasks:[], drawCanvas:(_c,...args)=>drawCalls.push(['draw',...args]),
                    drawCanvasBlink:(_c,...args)=>drawCalls.push(['blink',...args]) };
                drawCalls=[]; upstream({font:'Arial'},null,args); const expected=JSON.stringify(drawCalls);
                drawCalls=[]; port({font:'Arial'},null,args); assert.equal(JSON.stringify(drawCalls),expected);
                cases++;
            }
        }
        continue;
    }
    if (adapter.id === 'echo-marking-sign') {
        const start = text.indexOf('function beforeDraw(');
        const record = catalog.assets.find(a => a.portraitAdapter === adapter.id);
        for (const language of ['CN','TW','EN','DE','',null]) {
            const localized = runtime('R133',manifest,language);
            await localized.prepare(scripts,catalog);
            const port = localized.extended(record).ScriptHooks.BeforeDraw;
            const upstream = vm.runInNewContext('(' + text.slice(start,text.indexOf('\n}',start)+2) + ')',
                {TranslationLanguage:language,langMapping:{CN:'CN',TW:'CN'}});
            for (const L of ['文字','牌子']) for (const LayerType of ['', 'typed1', 'typed4']) {
                assert.equal(JSON.stringify(port(null,null,{L,LayerType})),JSON.stringify(upstream(null,null,{L,LayerType})));
                cases++;
            }
        }
        continue;
    }
    if (adapter.mode === 'AfterDraw') {
        const helper = await fs.readFile(path.join(sourceRoot, 'src/lib/draw/afterDraw.js'), 'utf8');
        const sourceIndex = JSON.parse(await fs.readFile(`${root}/echo/source-index.json`, 'utf8'));
        assert.equal(createHash('sha1').update(`blob ${Buffer.byteLength(helper)}\0`).update(helper).digest('hex'), sourceIndex.dependencies['src/lib/draw/afterDraw.js']);
        const upstream = vm.runInNewContext(helper.replace('export function', 'function') + '\n' +
            text.slice(text.indexOf('const afterDraw ='), text.indexOf(adapter.id === 'echo-drone-hood' ? 'const asset =' : 'const assetStrings =')) + '\nafterDraw.hooks().AfterDraw',
            { ...drawing, Access: { getOr: (object, key, fallback) => object[key] ?? fallback } });
        for (const record of catalog.assets.filter(a => a.portraitAdapter === adapter.id)) {
            assert.throws(() => pack.extended({ ...record, group: 'ItemMisc' }), /Invalid portrait adapter/);
            const withoutSubscreen = structuredClone(record);
            const ownerPath = adapter.hookPath.slice(0, -1);
            delete ownerPath.reduce((v,k)=>v[k],withoutSubscreen.extended)[adapter.hookPath.at(-1)];
            assert.throws(() => pack.extended(withoutSubscreen), /Invalid portrait adapter/);
            const port = adapter.hookPath.reduce((v, k) => v[k], pack.extended(record)).ScriptHooks.AfterDraw;
            for (const G of ['Small','Normal','Large','XLarge','FlatSmall']) for(const t of [undefined,0,1])
            for (const L of ['text','bd']) for(const Text of [undefined,'','Butterfly']) {
                const args = { G, L, Property: { TypeRecord: { t }, Text }, X:250, Y:280, Color:'#123456', AlphaMasks:[],
                    drawCanvas: (_canvas,...args) => drawCalls.push(['draw',...args]),
                    drawCanvasBlink: (_canvas,...args) => drawCalls.push(['blink',...args]) };
                drawCalls = []; upstream({font:'Arial'},null,args); const expected = JSON.stringify(drawCalls);
                drawCalls = []; port({font:'Arial'},null,args); assert.equal(JSON.stringify(drawCalls),expected);
                cases++;
            }
        }
        continue;
    }
    const start = text.search(/function [Bb]eforeDraw\(/);
    const end = text.indexOf('\n}', start) + 2;
    assert.ok(start >= 0 && end > start);
    const posStart = text.indexOf('const posModules =');
    const positions = posStart < 0 ? '' : text.slice(posStart, text.indexOf('\n];', posStart) + 3);
    const upstream = vm.runInNewContext(`${positions}\n(${text.slice(start, end).trim()})`, { E: Object.freeze({ Suspended: 'Suspended' }) });
    const records = catalog.assets.filter(a => a.portraitAdapter === adapter.id);
    assert.deepEqual(records.map(r => r.group).sort(), [...adapter.groups].sort());
    for (const record of records) {
        const port = pack.extended(record).ScriptHooks.BeforeDraw;
        if (adapter.id === 'echo-large-beads') {
            for (const InsertedBeads of [undefined, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9]) for (const Y of [-200, 0, 512]) {
                const args = Object.freeze({Y,Property:Object.freeze({InsertedBeads})});
                assert.equal(JSON.stringify(port(null,null,args)),JSON.stringify(upstream(null,null,args)));
                cases++;
            }
        }
        if (adapter.id === 'echo-icon-markings') {
            for (let mask = 0; mask < 1024; mask++) {
                const TypeRecord = Object.fromEntries([..."abcdefghij"].map((key, bit) => [key, (mask >> bit) & 1]));
                for (const Pose of [null, 'Hogtied', 'AllFours']) for (const L of ['狗爪_1', '狗爪_2']) {
                    const args = { Property: { TypeRecord }, Pose, L };
                    assert.equal(JSON.stringify(port(null, null, args)), JSON.stringify(upstream(null, null, args)));
                    cases++;
                }
            }
        }
        for (const mode of [undefined, 0, 1, 2]) for (const Pose of [null, 'Yoked', 'BackBoxTie', 'BackCuffs', 'OverTheHead', 'Kneel', 'KneelingSpread', 'Hogtied', 'AllFours']) {
            for (const restrained of [false, true]) for (const allowed of [false, true])
            for (const suspended of [false, true]) for (const L of ['A2', 'A3', 'B', 'g_3', 'd_3', 'g_1', '鞋底', '狗爪_1', '狗爪_2']) {
                const args = Object.freeze({
                    C: Object.freeze({ AllowedActivePose: Object.freeze(allowed ? ['BaseUpper'] : []), IsRestrained: () => restrained,
                        HasEffect: effect => { assert.equal(effect, 'Suspended'); return suspended; } }),
                    Property: Object.freeze({ TypeRecord: Object.freeze({ typed: mode, d: mode, b: mode }) }), Pose, L, Y: 500,
                });
                const original = () => { throw new Error('Unexpected game callback'); };
                assert.equal(JSON.stringify(port(null, original, args)), JSON.stringify(upstream(null, original, args)), `${adapter.id}/${record.group}/${mode}/${Pose}/${restrained}/${allowed}/${L}`);
                cases++;
            }
        }
        assert.throws(() => pack.extended({ ...record, group: 'ItemMisc' }), /Invalid portrait adapter/);
        assert.throws(() => pack.extended({ ...record, definition: { Name: 'Unknown' } }), /Invalid portrait adapter/);
    }
}
await assert.rejects(runtime('R134').prepare(scripts, catalog), /version mismatch/);
await assert.rejects(runtime().prepare(scripts, { ...catalog, revision: 'other-revision' }), /version mismatch/);
await assert.rejects(runtime().prepare(scripts.map(s => ({ ...s, sha256: 'wrong' })), catalog), /source mismatch/);
for (const mutate of [
    m => { delete m.adapters.find(a=>a.id==='echo-tentacle-suit').frozenFrame; },
    m => { m.adapters[0].frozenFrame=true; },
    m => { m.adapters.find(a => a.mode === 'AfterDraw').hookPath = ['Modules', 0]; },
    m => { m.version = 1; },
    m => { m.adapters.pop(); },
    m => { m.adapters[1] = structuredClone(m.adapters[0]); },
    m => { m.adapters[0].mode = 'ScriptDraw'; },
    m => { m.adapters.find(a=>a.id==='echo-surveillance-robot').mode='BeforeDraw'; },
    m => { m.adapters.find(a=>a.id==='echo-surveillance-robot').mode='AfterDraw'; },
    m => { m.adapters.find(a=>a.id==='echo-surveillance-robot').hookPath=[]; },
    m => { m.adapters[0].id = 'unreviewed'; },
    m => { m.adapters[0].id = 'toString'; },
    m => { m.adapters[0].source = 'other'; },
    m => { delete m.adapters[0].sourceHash; },
    m => { m.adapters[0].groups.push(m.adapters[0].groups[0]); },
    m => { m.adapters[1].name = m.adapters[0].name; },
    m => { m.adapters[0].path = 'src/missing.js'; },
    m => { m.adapters[0].path = '../outside.js'; },
]) {
    const data = structuredClone(manifest); mutate(data);
    await assert.rejects(runtime('R133', data).prepare(scripts, catalog), /portrait supplement|supplement version/i);
}
for (const mutate of [
    c => { delete c.supplementVersion; },
    c => { c.assets = c.assets.filter(a => !a.portraitAdapter); },
    c => { delete c.assets.find(a => a.portraitAdapter).portraitAdapter; },
    c => { c.assets.find(a => a.portraitAdapter).portraitAdapter = 'unreviewed'; },
    c => { c.assets.push(structuredClone(c.assets.find(a => a.portraitAdapter))); },
    c => { c.assets.find(a => a.portraitAdapter).extended.ScriptHooks = { BeforeDraw: 'unreviewed' }; },
    c => { c.assets.find(a => a.portraitAdapter).definition.Name = 'Unknown'; },
    c => { c.assets.find(a => a.portraitAdapter).group = 'ItemMisc'; },
]) {
    const data = structuredClone(catalog); mutate(data);
    await assert.rejects(runtime().prepare(scripts, data), /portrait adapter|supplement version/i);
}
const reloadedManifest = structuredClone(manifest);
const reloaded = runtime('R133', reloadedManifest);
await reloaded.prepare(scripts, catalog);
reloadedManifest.version = 1;
await assert.rejects(reloaded.prepare(scripts, catalog), /version mismatch/);
assert.equal(reloaded.allowsFrozenFrame({ Name: 'SteampunkWings', DynamicGroupName: 'Wings' }), false);
assert.throws(() => reloaded.extended(catalog.assets.find(a => a.portraitAdapter)), /Invalid portrait adapter/);
const scarf = catalog.assets.filter(a => a.definition.Name === '白色礼服丝巾');
assert.equal(scarf.length, 2);
for (const record of scarf) {
    assert.equal(record.portraitAdapter, undefined);
    assert.equal(pack.extended(record), record.extended);
}
assert.throws(() => pack.extended({ portraitAdapter: 'unreviewed' }), /Invalid portrait adapter/);
assert.equal(pack.allowsFrozenFrame({ Name: 'SteampunkWings', DynamicGroupName: 'Wings' }), true);
assert.equal(pack.allowsFrozenFrame({ Name: 'SteampunkWings', DynamicGroupName: 'ItemMisc' }), false);
assert.equal(pack.allowsFrozenFrame({ Name: 'Unreviewed', DynamicGroupName: 'Wings' }), false);
for(const group of ['ItemTorso','ItemTorso2']) assert.equal(pack.allowsFrozenFrame({Name:'触手服',DynamicGroupName:group}),true);
assert.equal(pack.allowsFrozenFrame({Name:'触手服',DynamicGroupName:'ItemMisc'}),false);
assert.equal(reloaded.allowsFrozenFrame({Name:'触手服',DynamicGroupName:'ItemTorso'}),false);
console.log(`PASS ${cases} upstream/port comparisons; version/source/binding failures; mixed static assets; failed reload clears permissions; frozen-frame allowlist`);
