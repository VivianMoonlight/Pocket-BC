import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
// Run from the repository root. Uses a separate browser profile and synthetic data.
const root = path.resolve('_local-artifacts/portrait-probe');
const appAssets = path.resolve('app/src/main/assets/portrait');
const pack = JSON.parse(await fs.readFile(process.env.ECHO_DATA_PACK || path.join(appAssets, 'echo/pack.json'), 'utf8'));
const echo = pack.images;
const scripts = JSON.parse(await fs.readFile(path.join(appAssets, 'scripts.json'), 'utf8'));
const requests = new Set();
const blockedImages = new Set();
let resourceRequests = 0;
async function download(url, attempts = 3, validate = bytes => bytes) {
  let error;
  for(let attempt=0;attempt<attempts;attempt++) {
    try {
      const response=await fetch(url, {signal:AbortSignal.timeout(30000)});
      if(!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
      return validate(Buffer.from(await response.arrayBuffer()));
    } catch(e) {error=e;}
  }
  throw error;
}
const server = http.createServer(async (req, res) => {
  resourceRequests++;
  const name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname.slice(1)).replace(/^R133\/BondageClub\//,'');
  try {
    let data;
    if (name === 'favicon.ico') {res.writeHead(204);res.end();return;}
    if(name.includes('..') || name.includes('\\')) throw new Error('Invalid path');
    if (blockedImages.has(name)) { res.writeHead(404); res.end(); return; }
    if (name === 'portrait/echo/catalog.json') data = JSON.stringify(pack.catalog);
    else if (name === 'portrait/supplement/manifest.json') data = JSON.stringify(pack.supplement);
    else if (name.startsWith('portrait/')) data = await fs.readFile(path.join(appAssets, name.slice(9)));
    else {
      const file = path.join(process.env.PORTRAIT_REMOTE_CACHE || path.join(root, 'remote'), name);
      const validate = bytes => {
        if (name.startsWith('echo/')) {
          const resource=name.split('/').slice(2).join('/');
          const hash=createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
          if(bytes.length>8*1024*1024 || name.split('/')[1]!==echo.revision || hash!==echo.images[resource]) throw new Error('Echo image integrity: '+name);
        }
        return bytes;
      };
      try { data = validate(await fs.readFile(file)); } catch {
        const remote = name.startsWith('echo/')
          ? 'https://cdn.jsdelivr.net/gh/SugarChain-Studio/echo-clothing-ext@' + name.split('/')[1] + '/resources/' + name.split('/').slice(2).join('/')
          : 'https://www.bondageprojects.elementfx.com/R133/BondageClub/' + name;
        try { data = await download(remote, name.startsWith('echo/') ? 1 : 3, validate); }
        catch (error) {
          if (!name.startsWith('echo/')) throw error;
          const resource = name.split('/')[1] + '/resources/' + name.split('/').slice(2).join('/');
          try { data = await download('https://raw.githubusercontent.com/SugarChain-Studio/echo-clothing-ext/' + resource, 3, validate); }
          catch {
            try { data = await download('https://github.com/SugarChain-Studio/echo-clothing-ext/raw/' + resource, 3, validate); }
            catch { data = await download(remote.replace('cdn.jsdelivr.net', 'gcore.jsdelivr.net'), 3, validate); }
          }
        }
        await fs.mkdir(path.dirname(file), { recursive: true });
        await fs.writeFile(file, data);
      }
      if(name.startsWith('echo/')) {
        const resource=name.split('/').slice(2).join('/');
        requests.add(resource);
      } else if(name.endsWith('.js')) {
        if(createHash('sha256').update(data).digest('hex')!==scripts.find(s=>s.path===name)?.sha256)throw new Error('BC script integrity: '+name);
      }
    }
    res.setHeader('Content-Type', name.endsWith('.js') ? 'text/javascript' : name.endsWith('.html') ? 'text/html' : name.endsWith('.png') ? 'image/png' : 'text/plain');
    res.end(data);
  } catch (e) { console.log('REQUEST', String(e)); res.writeHead(404); res.end(); }
});
let chrome, socket, closeBrowser;
async function main() {
await new Promise(resolve => server.listen(18763, '127.0.0.1', resolve));
chrome = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new', '--window-size=600,1000', '--remote-debugging-port=18764', '--user-data-dir=' + path.join(root, 'chrome-profile'), '--no-first-run', '--no-default-browser-check', 'about:blank'], { windowsHide: true, stdio: 'ignore' });
let targets;
for (let n=0;n<50;n++) { try { targets = await (await fetch('http://127.0.0.1:18764/json', { signal: AbortSignal.timeout(1000) })).json(); if(targets.some(t=>t.type==='page')) break; } catch {} await new Promise(r=>setTimeout(r,200)); }
socket = new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{
 const timer=setTimeout(()=>reject(new Error('Browser connection timed out')),10000);
 socket.addEventListener('open',()=>{clearTimeout(timer);resolve();},{once:true});
 socket.addEventListener('error',()=>{clearTimeout(timer);reject(new Error('Browser connection failed'));},{once:true});
});
let id=0;
const pending=new Map();
socket.addEventListener('message', e=>{ const m=JSON.parse(e.data); if(m.id) {
 const request=pending.get(m.id);if(!request)return;
 clearTimeout(request.timer);pending.delete(m.id);
 if(m.error)request.reject(new Error(JSON.stringify(m.error)));else request.resolve(m.result);
} else if(m.method==='Runtime.exceptionThrown') console.log('EXCEPTION',JSON.stringify(m.params.exceptionDetails)); else if(m.method==='Runtime.consoleAPICalled') console.log('CONSOLE', m.params.type, m.params.args.map(a=>a.value??a.description).join(' ')); });
socket.addEventListener('close',()=>{
 for(const request of pending.values()){clearTimeout(request.timer);request.reject(new Error('Browser disconnected'));}
 pending.clear();
});
const call=(method,params={})=>new Promise((resolve,reject)=>{
 const i=++id;
 const timer=setTimeout(()=>{pending.delete(i);reject(new Error('Browser command timed out: '+method));},15000);
 pending.set(i,{resolve,reject,timer});socket.send(JSON.stringify({id:i,method,params}));
});
closeBrowser=()=>call('Browser.close').catch(()=>{});
await call('Runtime.enable');
await call('Page.enable');
await call('Network.enable');
await call('Network.setCacheDisabled',{cacheDisabled:true});
await call('Page.navigate',{url:'http://127.0.0.1:18763/portrait/index.html'});
for(let n=0;n<360;n++) {
 if(await evaluate('typeof Player!=="undefined" && Player?.CharacterID==="mobile-viewer"')) break;
 if(n===359)throw new Error('Renderer initialization timed out');
 await new Promise(r=>setTimeout(r,250));
}
console.log('INITIAL',JSON.stringify(await call('Runtime.evaluate',{expression:'JSON.stringify(window.mobileBcPortraitStatus)',returnByValue:true})));
if (process.env.BC_ITEM_INTEROP_OUT) {
 const serverSource = await fs.readFile(path.resolve(process.env.BC_SERVER_SOURCE || '_local-artifacts/relationship-Server.js'), 'utf8');
 if (createHash('sha256').update(serverSource).digest('hex') !== '539a7c1839242197a748f633b4bf6c0ef9a028e34885b8f7f48d690f8d2ea109') throw new Error('Native Server.js fixture changed');
 const convert = serverSource.match(/^function ServerBundledItemToAppearanceItem\([\s\S]*?^}/m)?.[0];
 if (!convert) throw new Error('Native item decoder missing');
 await evaluate(convert);
 const parseColor = serverSource.match(/^function ServerParseColor\([\s\S]*?^}/m)?.[0];
 if (!parseColor) throw new Error('Native color decoder missing');
 await evaluate(parseColor);
 const fixtures = JSON.parse(await fs.readFile('core/socket/src/test/resources/bc-item-wire-r133.json', 'utf8'));
 const result = await evaluate(`(() => {
   const wearer = Player;
   wearer.MemberNumber = 20;
   wearer.Type = CharacterType.ONLINE;
   wearer.OnlineSharedSettings = {};
   wearer.FriendList = [];
   wearer.WhiteList = [];
   wearer.BlackList = [];
   wearer.BlockItems = [];
   wearer.LimitedItems = [];
   const actor = CharacterCreate('Female3DCG', CharacterType.ONLINE, 'item-update-actor');
   actor.MemberNumber = 10;
   window.ChatRoomCharacter = [wearer, actor];
   window.OnlineGameAllowBlockItems = () => true;
   window.LogQuery = () => false;
   window.AsylumGGTSGetLevel = () => 0;
   // Ordinary item fixtures use no ItemScript properties; keep the permission tags distinct.
   window.ScriptPermissionLevel = Object.fromEntries(['SELF','OWNER','LOVERS','FRIENDS','WHITELIST','PUBLIC'].map(key => [key, key]));
   const rows = [];
   for (const fixture of ${JSON.stringify(fixtures)}) {
     wearer.PermissionItems = fixture.permissionItems ?? {};
     wearer.Ownership = fixture.ownership ?? null;
     const previous = fixture.previous ? ServerBundledItemToAppearanceItem('Female3DCG', fixture.previous) : null;
     wearer.Appearance = previous ? [previous] : [];
     if (fixture.previous?.Property?.LockedBy && !InventoryDoesItemAllowLock(previous))
       throw new Error(fixture.id + ': previous item does not support a lock');
     if (fixture.previous?.Property?.LockedBy && ValidationSanitizeLock(wearer, previous))
       throw new Error(fixture.id + ': previous lock is invalid');
     const next = ServerBundledItemToAppearanceItem('Female3DCG', fixture.wire);
     const params = ValidationCreateDiffParams(wearer, fixture.fromSelf ? 20 : 10);
     if (params.fromSelf !== (fixture.fromSelf ?? false)) throw new Error('Receiver test source mismatch');
     const resolved = ValidationResolveAppearanceDiff(fixture.wire.Group, previous, next, params, false);
     const name = resolved.item?.Asset.Name ?? null;
     const type = resolved.item?.Property?.TypeRecord?.typed;
     if (resolved.valid !== (fixture.expectedValid ?? true) || name !== fixture.expectedName || fixture.expectedType != null && type !== fixture.expectedType)
       throw new Error(fixture.id + ': native receiver rejected or changed update: ' + JSON.stringify({valid:resolved.valid,name,type}));
     for (const [expected,field] of [['expectedText','Text'],['expectedText2','Text2']]) {
       if (fixture[expected] != null && resolved.item?.Property?.[field] !== fixture[expected]) throw new Error(fixture.id + ': text was changed by native receiver');
     }
     if ('expectedLockedBy' in fixture && (resolved.item?.Property?.LockedBy ?? null) !== fixture.expectedLockedBy)
       throw new Error(fixture.id + ': lock was changed by native receiver');
     for (const [field, expected] of Object.entries(fixture.expectedProperties ?? {})) {
       if (JSON.stringify(resolved.item?.Property?.[field]) !== JSON.stringify(expected)) throw new Error(fixture.id + ': ' + field + ' was changed by native receiver');
     }
     rows.push({id:fixture.id,valid:resolved.valid,name,type});
   }
   return rows;
 })()`);
 await fs.writeFile(path.resolve(process.env.BC_ITEM_INTEROP_OUT), JSON.stringify(result, null, 2));
 console.log('PASS native remote item receiver', JSON.stringify(result));
 return;
}
if (process.env.PORTRAIT_REPLAY_DUMP) {
 const dump = JSON.parse(await fs.readFile(process.env.PORTRAIT_REPLAY_DUMP, 'utf8'));
 if (dump.format !== 1 || !dump.scene || !Array.isArray(dump.scene.appearance)) throw new Error('Replay requires a format-1 dump captured with --include-scene');
 if (dump.environment.bcVersion !== 'R133' || dump.environment.echoRevision !== echo.revision) throw new Error('Replay BC/Echo version mismatch; supply the matching ECHO_DATA_PACK');
 await evaluate(`window.mobileBcSetPortrait(${JSON.stringify(dump.scene)},1)`);
 for (let i = 0; i < 380; i++) {
  if (await evaluate('window.mobileBcPortraitStatus.state!=="loading"')) break;
  await new Promise(resolve => setTimeout(resolve, 250));
 }
 const replay = JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture({includeCanvas:true}))'));
 const output = path.resolve(process.env.PORTRAIT_REPLAY_OUT || `_local-artifacts/portrait-replay-${Date.now()}`);
 await fs.mkdir(path.dirname(output), { recursive: true });
 await fs.mkdir(output);
 if (replay.canvas.png) {
  await fs.writeFile(path.join(output, 'canvas.png'), Buffer.from(replay.canvas.png.split(',')[1], 'base64'));
  delete replay.canvas.png;
 }
 await fs.writeFile(path.join(output, 'dump.json'), JSON.stringify(replay, null, 2));
 console.log('REPLAY', JSON.stringify({ output, state: replay.status.state, checks: replay.checks }));
 if (!['ready', 'partial'].includes(replay.status.state) || !replay.checks.hasLayout || !replay.checks.hasPixels) throw new Error('Replay did not produce a visible portrait; see the saved dump');
 return;
}
console.log('SCENE',JSON.stringify(await call('Runtime.evaluate',{expression:`(() => {
 const groups=AssetGroup.filter(g => (g.Category === 'Appearance' && !g.AllowNone) || ['Cloth','ClothLower','Bra','Panties'].includes(g.Name));
 const scene={memberNumber:42,assetFamily:'Female3DCG',activePose:['BaseUpper','BaseLower'],appearance:groups.map(g=>{
 const asset=g.Asset.find(a=>!a.DynamicScriptDraw&&!a.DynamicBeforeDraw&&!a.DynamicAfterDraw&&!a.Extended)??g.Asset[0];
 return {group:g.Name,name:asset.Name,color:g.ColorSchema[0],property:{}};
 })};
 window.probeScene=scene;
 window.vanillaScene=JSON.parse(JSON.stringify(scene));
 window.mobileBcSetPortrait(scene,1);
 return JSON.stringify(scene);
})()`,returnByValue:true})));
console.log('STATE',JSON.stringify(await call('Runtime.evaluate',{expression:'JSON.stringify(window.mobileBcPortraitStatus)',returnByValue:true})));
async function evaluate(expression) {
 const result=await call('Runtime.evaluate',{expression,returnByValue:true});
 if(result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
 return result.result.value;
}
async function expectState(revision,state) {
 for(let i=0;i<380;i++) {
  const s=JSON.parse(await evaluate('JSON.stringify(window.mobileBcPortraitStatus??{})'));
  if(s.revision===revision && s.state===state) {console.log('PASS state',revision,state);return;}
  if(s.revision===revision && s.state!=='loading')throw new Error(`Expected state ${revision} ${state}, got ${s.state}`);
  await new Promise(r=>setTimeout(r,250));
 }
 throw new Error('Expected state '+revision+' '+state);
}
await expectState(1,'ready');
// Compare against the pinned R133 WebGL implementation, including its second
// application of translation and its pre-transform alpha-mask origin.
const nativeTransformParity = await evaluate(`(() => {
 const savedOptions=GLDrawGetOptions, savedCanvas=GLDrawCanvas, savedVersion=GLVersion, savedCache=GLDrawImageCache;
 GLDrawGetOptions=()=>({preserveDrawingBuffer:true,antialias:false});
 GLDrawLoad(null);
 if (!GLDrawCanvas?.GL) throw new Error('WebGL required for native transform parity');
 const gl=GLDrawCanvas.GL;
 const src=document.createElement('canvas');src.width=40;src.height=30;
 const source=src.getContext('2d');source.fillStyle='#f00';source.fillRect(0,0,12,30);
 source.fillStyle='#0f0';source.fillRect(12,0,28,12);
 const dest=document.createElement('canvas');dest.width=500;dest.height=CanvasDrawHeight;
 const ctx=dest.getContext('2d');
 const mask=document.createElement('canvas');mask.width=40;mask.height=30;
 mask.getContext('2d').fillRect(0,0,20,30);
 const savedGet=DrawImageCache.get;
 DrawImageCache.get=(url,...args)=>url==='mobilebc-parity-mask'
  ? {isLoaded:()=>true,data:mask} : savedGet.call(DrawImageCache,url,...args);
 const alpha=()=>ctx.getImageData(0,0,500,CanvasDrawHeight).data;
 const fixtures=[{}, {TranslationX:25,TranslationY:-15}, {ScaleX:1.6,ScaleY:.6},
  {ScaleX:1.6,ScaleY:.6,TranslationX:25,TranslationY:-15}, {ScaleX:1.6,ScaleY:.6,Rotation:45},
  {Mirror:true,TranslationX:25,TranslationY:-15}, {Invert:true,TranslationX:25,TranslationY:-15},
  {Mirror:true,ScaleX:1.5,ScaleY:.7,Rotation:30,TranslationX:8,TranslationY:12},
  {Invert:true,ScaleX:1.5,ScaleY:.7,Rotation:-35,TranslationX:8,TranslationY:12},
  {ScaleX:1.6,ScaleY:.6,TranslationX:25,TranslationY:-15,AlphaMasks:[[130,690,12,8]]},
  {ScaleX:1.6,ScaleY:.6,TranslationX:25,TranslationY:-15,
   TextureAlphaMask:[{Url:'mobilebc-parity-mask',X:125,Y:685,Mode:'destination-in'}]}];
 const results=[];
 for (const options of fixtures) {
  const x=100+(options.TranslationX||0),y=700+(options.TranslationY||0);
  ctx.clearRect(0,0,500,CanvasDrawHeight);GLDrawClearRect(gl,0,0,1000,CanvasDrawHeight,0);
  GLDrawImage(src,gl,x,y,options);gl.finish();ctx.drawImage(GLDrawCanvas,0,0);
  const expected=alpha();
  ctx.clearRect(0,0,500,CanvasDrawHeight);DrawImageCanvas(src,ctx,x,y,options);
  const actual=alpha();let count=0,different=0;
  for(let i=3;i<expected.length;i+=4) {
   const a=expected[i]>127,b=actual[i]>127;
   if(a||b)count++;if(a!==b)different++;
  }
  results.push({options,count,different});
 }
 if (GLDrawScratchTexture) {gl.deleteTexture(GLDrawScratchTexture);GLDrawScratchTexture=undefined;}
 GLDrawImageCache?.clear();GLDrawCanvas.remove();GLDrawCanvas=savedCanvas;GLVersion=savedVersion;
 GLDrawImageCache=savedCache;GLDrawGetOptions=savedOptions;DrawImageCache.get=savedGet;
 return results;
})()`);
for (const result of nativeTransformParity) {
 if (!result.count || result.different > Math.max(3,result.count * .08))
  throw new Error('2D transform differs from native R133 WebGL: '+JSON.stringify(result));
}
console.log('PASS 2D layer translation, scale, rotation, mirror, inversion and masks against native R133 WebGL');
// Legacy LSCG positions are indexed by the original asset layers; BC's
// relative translations/scales remain independent and compose with them.
const transformProbe = await evaluate(`(() => {
 const original = DrawImageCanvas;
 const src = document.createElement('canvas'); src.width=20; src.height=10;
 src.getContext('2d').fillRect(0,0,20,10);
 const dest = document.createElement('canvas'); dest.width=100; dest.height=100;
 const ctx = dest.getContext('2d');
 original(src,ctx,30,40,{ScaleX:2,ScaleY:0.4});
 const pixel=(x,y)=>ctx.getImageData(x,y,1,1).data[3];
 return [pixel(20,43),pixel(59,46),pixel(19,43),pixel(60,46),pixel(30,42),pixel(30,47)];
})()`);
if (JSON.stringify(transformProbe) !== JSON.stringify([255,255,0,0,0,0])) throw new Error('2D layer scaling does not preserve center: '+JSON.stringify(transformProbe));
const transformedScene = await evaluate(`(() => {
 const scene=structuredClone(window.vanillaScene);
 const entry=scene.appearance.find(x=>AssetGet('Female3DCG',x.group,x.name)?.Visible);
 const asset=AssetGet('Female3DCG',entry.group,entry.name);
 entry.property={LayerOverrides:asset.Layer.map((_,i)=>i===0?{DrawingLeft:{'':123},DrawingTop:{'':234}}:{}),TranslationX:12,ScaleX:0.5};
 window.mobileBcSetPortrait(scene,70001);
 return {group:entry.group,name:entry.name,layer:asset.Layer[0].Name ?? ''};
})()`);
await expectState(70001,'ready');
const origins = await evaluate(`(() => {
 const C=Character[1];
 const item=C.Appearance.find(i=>i.Asset.Group.Name===${JSON.stringify(transformedScene.group)} && i.Asset.Name===${JSON.stringify(transformedScene.name)});
 const layer=item.Asset.Layer[0];
 const pos=CommonDrawComputeDrawingCoordinates(C,item.Asset,layer,item.Asset.DynamicGroupName,item.Property);
 const moves=C.DrawPose.map(p=>PoseRecord[p]?.MovePosition?.find(m=>m.Group===item.Asset.DynamicGroupName)).filter(Boolean);
 return [pos.X-moves.reduce((sum,m)=>sum+m.X,0),pos.Y-CanvasUpperOverflow-moves.reduce((sum,m)=>sum+m.Y,0),item.Property.TranslationX,item.Property.ScaleX];
})()`);
if (JSON.stringify(origins)!==JSON.stringify([123,234,12,0.5])) throw new Error('Legacy layer origins or native transforms lost: '+JSON.stringify(origins));
const legacyCoordinates = await evaluate(`(() => {
 const C=Character[1], item=C.Appearance.find(i=>i.Asset.Group.Name===${JSON.stringify(transformedScene.group)} && i.Asset.Name===${JSON.stringify(transformedScene.name)});
 const asset=item.Asset, layer=asset.Layer[0], group=asset.DynamicGroupName;
 const body=InventoryGet(C,'BodyStyle').Asset, savedOffsets=body.DrawOffset, savedFixed=layer.FixedPosition;
 const savedHeight=C.HeightModifier, savedBefore=asset.DynamicBeforeDraw;
 const savedPose=C.DrawPose, savedRecord=PoseRecord.MobileBcLegacyProbe;
 const property={LayerOverrides:asset.Layer.map((_,i)=>i===0?{DrawingLeft:{'':123},DrawingTop:{'':234}}:{})};
 try {
  body.DrawOffset=[{Group:group,Asset:asset.Name,Layer:[layer.Name??''],X:17,Y:29}];
  layer.FixedPosition=true;C.HeightModifier=80;
  PoseRecord.MobileBcLegacyProbe={MovePosition:[{Group:group,X:7,Y:11}]};C.DrawPose=['MobileBcLegacyProbe'];
  asset.DynamicBeforeDraw=false;
  const direct=CommonDrawComputeDrawingCoordinates(C,asset,layer,group,property);
  const texture=CommonDrawComputeDrawingCoordinates(C,asset,{...layer,TextureMask:{}},group,property);
  asset.DynamicBeforeDraw=true;
  const fn='AssetsMobileBcLegacyProbeBeforeDraw';window[fn]=()=>({X:999,Y:888,Opacity:.5});
  const callback=CommonCallFunctionByNameWarn(fn,{C,CA:{Asset:asset},Property:property,GroupName:group,L:layer.Name??''});
  delete window[fn];
  const oneAxis={LayerOverrides:asset.Layer.map((_,i)=>i===0?{DrawingLeft:{'':0},DrawingTop:{'':234}}:{})};
  asset.DynamicBeforeDraw=false;
  const partial=CommonDrawComputeDrawingCoordinates(C,asset,layer,group,oneAxis);
  const baseline=CommonDrawComputeDrawingCoordinates(C,asset,layer,group,{});
  return {direct:[direct.X,direct.Y],texture:[texture.X,texture.Y],callback:[callback.X,callback.Y,callback.Opacity],
   partial:[partial.X,partial.Y],baseline:[baseline.X,baseline.Y]};
 } finally {body.DrawOffset=savedOffsets;layer.FixedPosition=savedFixed;C.HeightModifier=savedHeight;
  C.DrawPose=savedPose;asset.DynamicBeforeDraw=savedBefore;
  if(savedRecord===undefined)delete PoseRecord.MobileBcLegacyProbe;else PoseRecord.MobileBcLegacyProbe=savedRecord;}
})()`);
const upperOverflow = await evaluate('CanvasUpperOverflow');
if (JSON.stringify(legacyCoordinates.direct)!==JSON.stringify([130,upperOverflow+245]) ||
    JSON.stringify(legacyCoordinates.callback)!==JSON.stringify([130,upperOverflow+245,0.5]) ||
    legacyCoordinates.partial[0]!==legacyCoordinates.baseline[0] || legacyCoordinates.partial[1]!==upperOverflow+245 ||
    JSON.stringify(legacyCoordinates.texture)===JSON.stringify(legacyCoordinates.direct))
 throw new Error('Legacy LSCG BeforeDraw coordinates differ: '+JSON.stringify(legacyCoordinates));
await evaluate(`window.mobileBcSetPortrait(window.vanillaScene,70002)`);
await expectState(70002,'ready');
const baseHeight = await evaluate('Character[1].HeightRatio');
const baseGeometry = await evaluate('window.mobileBcPortraitGeometry()');
await evaluate(`(() => {
 window.originalPortraitDraw = DrawImageEx;
 window.normalizedFrameDraw = null;
 DrawImageEx = function(image, destination, x, y, options) {
  if (destination === MainCanvas && image === Character[1]?.Canvas) {
   window.normalizedFrameDraw = { x, y, source: options.SourcePos, width: options.Width, height: options.Height };
  }
  return window.originalPortraitDraw.apply(this, arguments);
 };
})()`);
await evaluate(`window.mobileBcSetPortrait({...window.vanillaScene,appearance:[...window.vanillaScene.appearance,{group:'额外身高_Luzi',name:'身高减10cm',property:{}}]},70003)`);
await expectState(70003,'ready');
const adjustedHeight = await evaluate('Character[1].HeightRatio');
if (Math.abs(adjustedHeight-(baseHeight-0.02)) > 1e-8) throw new Error('Echo height adjustment missing');
const adjustedGeometry = await evaluate('window.mobileBcPortraitGeometry()');
const frameDraw = await evaluate('window.normalizedFrameDraw');
await evaluate('DrawImageEx=window.originalPortraitDraw');
if (JSON.stringify(frameDraw) !== JSON.stringify({x:0, y:0, source:[0, upperOverflow, 500, 1000], width:500, height:1000}))
 throw new Error('Portrait frame was drawn with room scaling or offsets: '+JSON.stringify(frameDraw));
const expectedGeometry = {x:0, width:500, sourceY:0, sourceHeight:1000, destY:0, destHeight:1000, inverted:false};
if (JSON.stringify(baseGeometry) !== JSON.stringify(expectedGeometry) ||
    JSON.stringify(adjustedGeometry) !== JSON.stringify(expectedGeometry)) {
 throw new Error('Character height changed normalized portrait geometry: '+JSON.stringify({baseGeometry, adjustedGeometry}));
}
const shiftedGeometry = await evaluate(`(() => {
 const C=Character[1], oldHeight=C.HeightModifier, oldRatio=C.HeightRatio;
 try { C.HeightModifier=350; C.HeightRatio=0.6; return window.mobileBcPortraitGeometry(); }
 finally { C.HeightModifier=oldHeight; C.HeightRatio=oldRatio; }
})()`);
if (JSON.stringify(shiftedGeometry) !== JSON.stringify(expectedGeometry))
 throw new Error('Height offset or scale moved normalized portrait geometry: '+JSON.stringify(shiftedGeometry));
console.log('PASS portrait frame stays unshifted and uniformly sized across character heights');
if (process.env.PORTRAIT_GEOMETRY_PROBE) return;
await evaluate(`window.mobileBcSetPortrait(window.vanillaScene,70004)`);
await expectState(70004,'ready');
await evaluate(`(() => {
 window.originalLayerDraw=DrawImageCanvas;window.recordedLayerDraws=[];
 DrawImageCanvas=(source,canvas,x,y,options)=>{
  if(typeof source==='string' && source.includes('RoundPiercing') && source.includes('WeightsChained'))
   recordedLayerDraws.push({x,y,scaleX:options.ScaleX,scaleY:options.ScaleY,translationY:options.TranslationY});
  return originalLayerDraw(source,canvas,x,y,options);
 };
 const scene=structuredClone(vanillaScene);
 scene.appearance=scene.appearance.filter(item=>!['Cloth','Bra'].includes(item.group));
 scene.appearance.push({group:'ItemNipplesPiercings',name:'RoundPiercing',color:'Default',property:{
  TypeRecord:{typed:3},LayerTranslationY:{WeightsChained:10},LayerScaleX:{WeightsChained:2.9},LayerScaleY:{WeightsChained:2}}});
 window.mobileBcSetPortrait(scene,70005);
})()`);
await expectState(70005,'ready');
const weightedPiercing = await evaluate('window.recordedLayerDraws.at(-1)');
if (!weightedPiercing || weightedPiercing.scaleX!==2.9 || weightedPiercing.scaleY!==2 || weightedPiercing.translationY!==10)
 throw new Error('Per-layer scale and position did not reach the drawing path: '+JSON.stringify({weightedPiercing,
  layers:await evaluate("Character[1].AppearanceLayers.filter(l=>l.Asset.Group.Name==='ItemNipplesPiercings').map(l=>l.Name)")}));
await evaluate(`DrawImageCanvas=window.originalLayerDraw;window.mobileBcSetPortrait(window.vanillaScene,70006)`);
await expectState(70006,'ready');
console.log('PASS legacy layer positions and centered 2D scale');
if (process.env.PORTRAIT_FRAME_PROBE) {
 await evaluate(`window.frameStatuses=[];window.frameChannel=new MessageChannel();frameChannel.port1.onmessage=e=>frameStatuses.push(JSON.parse(e.data));window.postMessage('mobilebc-portrait-status',location.origin,[frameChannel.port2]);`);
 const frames=[];
 for(let i=0;i<3;i++) {
  const revision=100+i;
  await evaluate(`window.changedScene=JSON.parse(JSON.stringify(vanillaScene));changedScene.appearance.find(i=>i.group==='Cloth').color=${JSON.stringify(i===1?'#00FF00':'#FF0000')};window.mobileBcSetPortrait(changedScene,${revision});`);
  await expectState(revision,'ready');
  for(let poll=0;poll<100;poll++) {
   if(await evaluate('frameStatuses.some(s=>s.revision==='+revision+"&&s.state==='ready')"))break;
   if(poll===99)throw new Error('Frame completion was not delivered through the message port');
   await new Promise(resolve=>setTimeout(resolve,10));
  }
  frames.push(await evaluate('document.getElementById("MainCanvas").toDataURL("image/png")'));
 }
 if(frames[0]===frames[1]||frames[0]!==frames[2])throw new Error('Dynamic color update or restore was ignored');
 await fs.writeFile('_local-artifacts/portrait-persisted-fixture.png',Buffer.from(frames[0].split(',')[1],'base64'));
 await evaluate(`window.frameStatuses=[];window.mobileBcSetPortrait(vanillaScene,110);window.mobileBcSetPortrait(changedScene,111);`);
 await expectState(111,'ready');
 await new Promise(resolve=>setTimeout(resolve,25));
 if(await evaluate("frameStatuses.some(s=>s.revision===110&&s.state==='ready')"))throw new Error('Superseded drawing completed as current');
 if(await evaluate('document.getElementById("MainCanvas").toDataURL("image/png")')!==frames[2])throw new Error('Rapid snapshot switch persisted an old frame');

 await evaluate('window.mobileBcClearPortrait(104)');
 if(await evaluate('MobileBcPortraitDump.capture({includeScene:true}).scene'))throw new Error('Snapshot retained after export');
 const statuses=await evaluate('window.frameStatuses.map(({revision,state,elapsedMs})=>({revision,state,elapsedMs}))');
 console.log('PASS dynamic update, event completion, PNG export and cleanup',JSON.stringify(statuses));
 return;
}

if (process.env.ITEM_TRYON_PROBE_OUT) {
 const output = path.resolve(process.env.ITEM_TRYON_PROBE_OUT);
 await fs.mkdir(output);
 const baseline = await evaluate('document.getElementById("MainCanvas").toDataURL()');
 const rows = [];
 const frames = [];
 const fixtures = [{id:'native-default',name:'HempRope',type:0},{id:'native-mode',name:'HempRope',type:1},{id:'echo-default',name:'简单绳',type:0}];
 for (const [index,fixture] of fixtures.entries()) {
   const revision=index+2;
   await evaluate(`(() => {
     const scene=structuredClone(window.vanillaScene);
     scene.appearance=scene.appearance.filter(item=>item.group!=='ItemArms');
     scene.appearance.push({group:'ItemArms',name:${JSON.stringify(fixture.name)},color:'Default',property:{TypeRecord:{typed:${fixture.type}}}});
     window.mobileBcSetPortrait(scene,${revision});
   })()`);
   await expectState(revision,'ready');
   const dump=JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture({includeCanvas:true}))'));
   const frame=dump.canvas.png;
   if (!dump.checks.hasLayout || !dump.checks.hasPixels || !frame || frame===baseline) throw new Error('Try-on produced no visible item: '+fixture.id);
   frames.push(frame);
   await fs.writeFile(path.join(output,fixture.id+'.png'),Buffer.from(frame.split(',')[1],'base64'));
   delete dump.canvas.png;
   await fs.writeFile(path.join(output,fixture.id+'.json'),JSON.stringify(dump,null,2));
   rows.push({id:fixture.id,state:dump.status.state,checks:dump.checks});
 }
 if(frames[0]===frames[1])throw new Error('Native mode preview did not change pixels');
 await evaluate('window.mobileBcSetPortrait(window.vanillaScene,5)');
 await expectState(5,'ready');
 if(await evaluate('document.getElementById("MainCanvas").toDataURL()')!==baseline)throw new Error('Try-on changed the source appearance');
 await fs.writeFile(path.join(output,'results.json'),JSON.stringify(rows,null,2));
 console.log('PASS native/Echo try-on pixels, mode changes and source restoration',JSON.stringify(rows));
 return;
}
if (process.env.PORTRAIT_CACHE_PROBE) {
 const first = await evaluate('window.mobileBcPortraitStatus');
 const frame = await evaluate('document.getElementById("MainCanvas").toDataURL()');
 await evaluate('window.warmAsset=Asset;window.warmImages=new Map(DrawImageCache.cache)');
 const timings=[];
 const before=resourceRequests;
 for(let i=0;i<5;i++) {
  await evaluate('window.mobileBcPausePortrait(true);window.mobileBcClearPortrait('+(2+i*2)+')');
  const cleared=await evaluate('({characters:Character.length,scene:MobileBcPortraitDump.capture({includeScene:true}).scene,pixels:MainCanvas.getImageData(0,0,500,1000).data.some(v=>v!==0)})');
  if(cleared.characters!==1||cleared.scene||cleared.pixels)throw new Error('Recycled runtime kept character data');
  await evaluate('window.mobileBcSetPortrait(window.vanillaScene,'+(3+i*2)+');window.mobileBcPausePortrait(false)');
  await expectState(3+i*2,'ready');
  if(await evaluate('document.getElementById("MainCanvas").toDataURL()')!==frame)throw new Error('Warm renderer changed pixels');
  if(!await evaluate('window.warmAsset===Asset&&[...DrawImageCache.cache].some(([k,v])=>window.warmImages.get(k)===v)'))throw new Error('Warm renderer rebuilt runtime or all decoded images');
  timings.push((await evaluate('window.mobileBcPortraitStatus')).elapsedMs);
 }
 const result={runtimeReadyMs:first.runtimeReadyMs,firstFrameMs:first.elapsedMs,reopenFrameMs:timings,newResourceRequests:resourceRequests-before};
 if(result.newResourceRequests!==0)throw new Error('Warm reopen refetched resources: '+JSON.stringify(result));
 await fs.writeFile('_local-artifacts/portrait-warm-probe.json',JSON.stringify(result,null,2));
 console.log('PASS warm reopen: same pixels, no extra requests, no retained snapshot',JSON.stringify(result));
 return;
}

if (process.env.ECHO_SWEEP_OUT) {
 const output = path.resolve(process.env.ECHO_SWEEP_OUT);
 await fs.mkdir(output); // Preserve earlier audit evidence.
 const start = Number(process.env.ECHO_SWEEP_START || 0);
 const count = Number(process.env.ECHO_SWEEP_COUNT || pack.catalog.assets.length);
 if (!Number.isInteger(start) || start < 0 || start >= pack.catalog.assets.length || !Number.isInteger(count) || count < 1) throw new Error('Invalid sweep range');
 const selected = process.env.ECHO_SWEEP_ASSETS
  ? JSON.parse(await fs.readFile(process.env.ECHO_SWEEP_ASSETS, 'utf8')) : null;
 if (selected && (!Array.isArray(selected) || !selected.length || selected.some(key =>
  typeof key !== 'string' || !pack.catalog.assets.some(record => `${record.group}/${record.definition.Name}` === key)))) {
  throw new Error('Invalid sweep asset selection');
 }
 const records = pack.catalog.assets.slice(start, start + count)
  .filter(record => !selected || selected.includes(`${record.group}/${record.definition.Name}`));
 if (!records.length) throw new Error('No assets in the selected sweep range');
 const pose = process.env.ECHO_SWEEP_POSE ? JSON.parse(process.env.ECHO_SWEEP_POSE) : ['BaseUpper','BaseLower'];
 const knownPoses = await evaluate('PoseFemale3DCG.map(pose=>pose.Name)');
 if (!Array.isArray(pose) || !pose.every(name=>typeof name==='string' && knownPoses.includes(name))) throw new Error('Invalid sweep pose');
 let failures = 0;
 for (let i = 0; i < records.length; i++) {
  const {group,definition} = records[i];
  const revision = i + 2;
  await evaluate(`{
   const item = ${JSON.stringify({group,name:definition.Name,color:definition.DefaultColor ?? 'Default',property:{}})};
   const scene = JSON.parse(JSON.stringify(window.vanillaScene));
   scene.activePose = ${JSON.stringify(pose)};
   scene.appearance = scene.appearance.filter(i=>i.group!==item.group).concat(item);
   window.mobileBcSetPortrait(scene,${revision});
  }`);
  for(let poll=0;poll<380;poll++) {
   const status=await evaluate('window.mobileBcPortraitStatus');
   if(status.revision===revision && status.state!=='loading') break;
   await new Promise(resolve=>setTimeout(resolve,250));
  }
  const report=JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture({includeScene:true}))'));
  const index = pack.catalog.assets.indexOf(records[i]);
  const result={index,group,name:definition.Name,pose,status:report.status,checks:report.checks,
   unavailable:report.images.filter(image=>image.state!=='loaded')};
  await fs.appendFile(path.join(output,'results.jsonl'),JSON.stringify(result)+'\n');
  console.log('SWEEP',JSON.stringify(result));
  if(result.status.revision!==revision || result.status.state!=='ready' || !result.checks.hasLayout || !result.checks.hasPixels || result.unavailable.length) {
   failures++;
   await fs.writeFile(path.join(output,`${index}.json`),JSON.stringify(report,null,2));
  }
 }
 console.log('SWEEP COMPLETE',output);
 if (failures) throw new Error(`${failures}/${records.length} portraits failed; see saved dumps`);
 return;
}
// A WebView can initialize with a zero-height layout viewport even after its visible bounds grow.
// The canvas must retain its intrinsic 1:2 ratio rather than inherit that zero percentage height.
await evaluate('document.documentElement.style.height="0px"');
const portraitBounds = await evaluate('document.getElementById("MainCanvas").getBoundingClientRect().toJSON()');
if (portraitBounds.width <= 0 || Math.abs(portraitBounds.height - portraitBounds.width * 2) > 1) {
 throw new Error('Portrait collapsed in a zero-height document: '+JSON.stringify(portraitBounds));
}
await evaluate('document.documentElement.style.removeProperty("height")');
console.log('PASS portrait keeps visible intrinsic dimensions in a zero-height document');
const initialDump = JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture())'));
const bodyImages = initialDump.images.filter(image => /\/Body(?:Upper|Lower)\//.test(image.url));
if (!bodyImages.some(image => image.url.startsWith('/echo/')) || bodyImages.some(image => echo.images[image.url])) {
 throw new Error('Echo native body image overrides were not applied');
}
console.log('PASS native body identities resolve to pinned Echo replacement images');
if (initialDump.format !== 1 || initialDump.scene || !initialDump.checks.hasLayout || !initialDump.checks.hasPixels || initialDump.checks.imagesFailed) throw new Error('Invalid ready portrait dump');
if (!initialDump.events.some(event => event.stage === 'runtime-ready')) throw new Error('Missing renderer timeline');
const sceneDump = JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture({includeScene:true}))'));
if ('memberNumber' in sceneDump.scene || JSON.stringify(sceneDump.scene.appearance) !== JSON.stringify((await evaluate('window.probeScene')).appearance)) throw new Error('Dump scene changed appearance or included account identity');
await evaluate('document.querySelector("canvas").style.height="0px"');
const collapsedDump = JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture())'));
if (collapsedDump.checks.hasLayout || !collapsedDump.checks.hasPixels) throw new Error('Dump did not distinguish invisible layout from empty pixels');
await evaluate('document.querySelector("canvas").style.removeProperty("height")');
await fs.mkdir(path.join(root, 'dump-fixture'), { recursive: true });
await fs.writeFile(path.join(root, 'dump-fixture/dump.json'), JSON.stringify(sceneDump));
console.log('PASS dump captures timeline and replay scene, distinguishes zero layout, and excludes account identity by default');
const dumpOutput = path.join(root, `dump-cli-${Date.now()}`);
await new Promise((resolve, reject) => {
 const child = spawn(process.execPath, ['scripts/dump-portrait.mjs', '--port', '18764', '--out', dumpOutput], { windowsHide: true, stdio: 'inherit' });
 child.on('error', reject);
 child.on('exit', code => code === 0 ? resolve() : reject(new Error(`Dump CLI exited ${code}`)));
});
const exportedDump = JSON.parse(await fs.readFile(path.join(dumpOutput, 'dump.json'), 'utf8'));
if (exportedDump.scene || !exportedDump.checks.hasPixels || (await fs.stat(path.join(dumpOutput, 'canvas.png'))).size === 0) throw new Error('Dump CLI did not export a usable diagnostic capture');
console.log('PASS dump CLI exports JSON and PNG through real DevTools');
const before=await evaluate('document.getElementById("MainCanvas").toDataURL()');
await evaluate('window.probeScene.appearance.find(i=>i.group==="HairFront").color="#4A6EB0";window.mobileBcSetPortrait(window.probeScene,2)');
await expectState(2,'ready');
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')===before)throw new Error('Color did not change frame');
console.log('PASS color changes rendered pixels');
const beforePose=await evaluate('document.getElementById("MainCanvas").toDataURL()');
await evaluate('window.probeScene.activePose=["Kneel","BackBoxTie"];window.mobileBcSetPortrait(window.probeScene,3)');
await expectState(3,'ready');
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')===beforePose)throw new Error('Pose did not change frame');
console.log('PASS pose changes rendered pixels');
await evaluate('window.mobileBcSetPortrait({...window.probeScene,appearance:[...window.probeScene.appearance,{group:"Cloth",name:"UnknownModDress",property:{}}]},4)');
await expectState(4,'partial');
if(!await evaluate('document.getElementById("MainCanvas").getContext("2d").getImageData(0,0,500,1000).data.some(v=>v!==0)'))throw new Error('Mixed appearance lost its supported layers');
if(!await evaluate('window.mobileBcPortraitStatus.omitted.some(i=>i.name==="UnknownModDress")'))throw new Error('Missing omitted-item report');
console.log('PASS unknown item preserves known layers and reports partial appearance');
await evaluate('window.mobileBcPausePortrait(true);window.mobileBcSetPortrait(window.probeScene,5)');
await expectState(5,'loading');
await new Promise(r=>setTimeout(r,500));
await expectState(5,'loading');
await evaluate('window.mobileBcPausePortrait(false)');
await expectState(5,'ready');
console.log('PASS pause and resume');
await evaluate('window.probeScene.activePose=["BaseUpper","BaseLower"];window.mobileBcSetPortrait(window.probeScene,6);window.probeScene.appearance.find(i=>i.group==="Eyes").property.Expression="Closed";window.mobileBcSetPortrait(window.probeScene,7)');
await expectState(7,'ready');
console.log('PASS rapid updates retain latest revision');
await evaluate(`window.probeScene.activePose=['BaseUpper','BaseLower'];
window.probeScene.appearance=window.probeScene.appearance.filter(i=>!['Cloth','ClothLower'].includes(i.group));
window.probeScene.appearance.push({group:'Cloth',name:'花园连衣裙',color:['Default','Default','Default','#303030','Default'],property:{}},
{group:'Hat_笨笨蛋Luzi',name:'女巫帽子_Luzi',property:{TypeRecord:{z:1}}},
{group:'Glasses',name:'医用眼罩左',property:{TypeRecord:{typed:1}}});
window.mobileBcSetPortrait(window.probeScene,8);`);
await expectState(8,'ready');
console.log('PASS Echo dress, mask, mirrored hat and old asset name');
const beforeEcho=await evaluate('document.getElementById("MainCanvas").toDataURL()');
await evaluate(`window.probeScene.appearance.find(i=>i.group==='Cloth').color[3]='#4A6EB0';window.mobileBcSetPortrait(window.probeScene,9);`);
await expectState(9,'ready');
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')===beforeEcho)throw new Error('Echo color did not change pixels');
console.log('PASS Echo color changes rendered pixels');
const beforeType=await evaluate('document.getElementById("MainCanvas").toDataURL()');
await evaluate(`window.probeScene.appearance.find(i=>i.group==='Glasses').property.TypeRecord.typed=2;window.mobileBcSetPortrait(window.probeScene,10);`);
await expectState(10,'ready');
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')===beforeType)throw new Error('Echo type did not change pixels');
console.log('PASS Echo type changes rendered pixels');
await evaluate(`window.probeScene.appearance.push({group:'新前发_Luzi',name:'前发1',color:'#4A6EB0',property:{}});window.mobileBcSetPortrait(window.probeScene,11);`);
await expectState(11,'ready');
if(![...requests].some(p=>p.includes('LuziArmMask/')))throw new Error('Echo arm mask was not loaded');
if(![...requests].some(p=>p.includes('/女巫帽子_')))throw new Error('Echo mirror did not use its source images');
if(![...requests].some(p=>p.includes('/前发1_')))throw new Error('Echo hair was not loaded');
console.log('PASS Echo masks, mirrored resources and hair overlay');
console.log('RESULT',JSON.stringify(await call('Runtime.evaluate',{expression:'JSON.stringify({status:document.body.dataset.status, detail:document.body.dataset.detail, assets:typeof Asset!=="undefined"?Asset.length:0, characters:typeof Character!=="undefined"?Character.length:0})',returnByValue:true})));
const screenshot=await call('Page.captureScreenshot');
await fs.writeFile(path.join(root,'screenshot.png'),Buffer.from(screenshot.data,'base64'));
await evaluate(`window.mobileBcPausePortrait(true);window.probeScene.activePose=['Kneel','BackBoxTie'];window.mobileBcSetPortrait(window.probeScene,12);`);
await expectState(12,'loading');
await evaluate('window.mobileBcPausePortrait(false)');
await expectState(12,'ready');
await evaluate(`window.probeScene.appearance.push({group:'Cloth_笨笨蛋Luzi',name:'CollegeOutfit1',color:'#CC6688',property:{Opacity:0.3}});window.mobileBcSetPortrait(window.probeScene,13);`);
await expectState(13,'ready');
console.log('PASS Echo pose, pause/resume and simultaneous official clothing in extra slot');
await evaluate(`window.mobileBcSetPortrait({...window.probeScene,appearance:[...window.probeScene.appearance,{group:'Cloth',name:'乳胶衣',property:{}}]},14);`);
await expectState(14,'partial');
await evaluate('window.mobileBcSetPortrait(window.vanillaScene,15)');
await expectState(15,'ready');
const vanillaFrame=await evaluate('document.getElementById("MainCanvas").toDataURL()');
console.log('PASS unlisted Echo item gives partial preview and a later vanilla snapshot recovers');
if(await evaluate('window.mobileBcPortraitStatus.omitted.length')!==0)throw new Error('Stale omission report');
await evaluate(`window.mobileBcSetPortrait({...window.vanillaScene,appearance:[{group:'Cloth',name:'UnknownOnly',property:{}}]},16);`);
await expectState(16,'unsupported');
if(await evaluate('document.getElementById("MainCanvas").getContext("2d").getImageData(0,0,500,1000).data.some(v=>v!==0)'))throw new Error('All-unknown appearance left stale pixels');
await evaluate(`window.sideEffects=[];
for(const name of Object.keys(window).filter(name=>/^Assets.*ScriptDraw/.test(name)))window[name]=()=>{window.sideEffects.push(name);throw new Error('ScriptDraw was called');};
window.ServerSend=()=>{window.sideEffects.push('ServerSend');throw new Error('Network side effect');};
window.ChatRoomCharacterUpdate=()=>{window.sideEffects.push('CharacterUpdate');throw new Error('Character side effect');};
window.staticScene=JSON.parse(JSON.stringify(window.vanillaScene));
window.staticScene.appearance.push({group:'Wings',name:'SteampunkWings',color:'Default',property:{TypeRecord:{typed:1}}},{group:'ItemMisc',name:'贴贴',property:{}});
window.staticInput=JSON.stringify(window.staticScene);
window.mobileBcSetPortrait(window.staticScene,17);`);
await expectState(17,'ready');
const frozen=await evaluate('document.getElementById("MainCanvas").toDataURL()');
if(frozen===vanillaFrame)throw new Error('Animated wings did not add visible pixels');
const frozenScreenshot=await call('Page.captureScreenshot');
await fs.writeFile(path.join(root,'static-wings.png'),Buffer.from(frozenScreenshot.data,'base64'));
await new Promise(r=>setTimeout(r,750));
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')!==frozen)throw new Error('Static wings kept animating');
if(await evaluate('JSON.stringify(window.staticScene)')!==await evaluate('window.staticInput'))throw new Error('Rendering mutated its snapshot');
if(await evaluate('window.sideEffects.length')!==0)throw new Error('Preview executed a game callback');
console.log('PASS animated wings freeze at first frame, Echo cuddle marker is invisible, no gameplay callbacks');
await evaluate(`const asset=Asset.find(a=>a.DynamicScriptDraw&&!a.DynamicBeforeDraw&&!a.DynamicAfterDraw&&a.Visible);
if(!asset)throw new Error('Missing plain-layer scripted fixture');
window.staticScene.appearance.push({group:asset.Group.Name,name:asset.Name,color:'Default',property:{}});window.mobileBcSetPortrait(window.staticScene,18);`);
await expectState(18,'ready');
if(await evaluate('window.sideEffects.length')!==0)throw new Error('Plain layers executed a game callback');
console.log('PASS scripted item with plain image layers renders without ScriptDraw');

let typeRevision = 19;
async function renderTypes(items, activePose = ['BaseUpper', 'BaseLower'], expectedState = 'ready') {
 const revision = typeRevision++;
 await evaluate(`{ window.typeScene=JSON.parse(JSON.stringify(window.vanillaScene));
 const items=${JSON.stringify(items)};
 window.typeScene.appearance=window.typeScene.appearance.filter(i=>!items.some(item=>item.group===i.group)).concat(items);
 window.typeScene.activePose=${JSON.stringify(activePose)};
 window.typeInput=JSON.stringify(window.typeScene);
 window.mobileBcSetPortrait(window.typeScene,${revision}); }`);
 await expectState(revision,expectedState);
 if(await evaluate('JSON.stringify(window.typeScene)')!==await evaluate('window.typeInput'))throw new Error('Type rendering mutated its input');
 if(await evaluate('window.sideEffects.length')!==0)throw new Error('Type rendering executed gameplay');
 return {
  frame: await evaluate('document.getElementById("MainCanvas").toDataURL()'),
  layers: await evaluate(`[...Character[1].AppearanceLayers,...Character[1].AppearanceMasks].filter(l=>${JSON.stringify(items)}.some(i=>i.group===l.Asset.Group.Name&&i.name===l.Asset.Name)).map(l=>l.Name)`),
 };
}
function expectLayers(actual, expected) {
 if(JSON.stringify([...actual].sort())!==JSON.stringify([...expected].sort()))throw new Error(`Wrong type layers: ${JSON.stringify({actual,expected})}`);
}
const opaqueBody = await renderTypes([]);
await evaluate("window.previousDecodedImages=new Map(DrawImageCache.cache)");
await renderTypes([]);
if(!await evaluate("[...DrawImageCache.cache].some(([key,image])=>window.previousDecodedImages.get(key)===image&&image.isLoaded())"))throw new Error('Decoded images not reused');
const cachedImagePath=await evaluate("new URL([...DrawImageCache.cache.values()].find(i=>i.isLoaded()).url,location.href).pathname.slice(1).replace('R133/BondageClub/','')");
await evaluate('DrawImageCache.clear()');
blockedImages.add(cachedImagePath);
await renderTypes([],undefined,'partial');
blockedImages.clear();
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Failed image persisted or cached image changed pixels');
console.log('PASS decoded image reuse and failed image retry');

await renderTypes([{group:'BodyUpper',name:'Normal',color:['Default','Default'],property:{}}]);
const normalizedBodyColor=await evaluate("Character[1].Appearance.find(i=>i.Asset.Group.Name==='BodyUpper').Color");
if(JSON.stringify(normalizedBodyColor)!==JSON.stringify(['White','White']))throw new Error('Default palette nested in color slots');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Default color normalization changed later render');
console.log('PASS default palette fills individual color slots without nested arrays');

const shockDevice=TypeRecord=>({group:'ItemLegs',name:'电击器',property:{TypeRecord,Luzi_ManualShock:1,Luzi_ShockEndTime:9999999999999}});
for(const a of [0,1])for(const p of [0,1])for(const u of [0,1])for(const d of [0,1])for(const r of [0,1]) {
 const result=await renderTypes([shockDevice({a,p,u,d,r})]);
 for(const [name,shown]of [['电击肛塞',a],['阴部',p],['大腿内侧',u],['小腹',d]])if(result.layers.includes(name)!==Boolean(shown))throw new Error('Shock device module layer incorrect: '+name);
 const glow=await evaluate(`AssetGet('Female3DCG','ItemLegs','电击器').Layer.find(l=>l.Name==='闪光').HasImage`);
 if(glow!==false||await evaluate(`[...DrawImageCache.cache.keys()].some(url=>decodeURIComponent(url).includes('电击器')&&decodeURIComponent(url).endsWith('_闪光.png'))`))throw new Error('Shock device did not keep initial light phase');
}
const shockFrame=await renderTypes([shockDevice({a:1,p:1,u:1,d:1,r:1})]);
if((await renderTypes([shockDevice({a:1,p:1,u:1,d:1,r:1})])).frame!==shockFrame.frame)throw new Error('Shock device frame changed without scene change');
for(const size of ['Small','Normal','Large','XLarge'])for(const pose of ['BaseLower','Kneel','KneelingSpread','Spread','LegsClosed','Hogtied','AllFours']) {
 await renderTypes([shockDevice({a:1,p:1,u:1,d:1,r:1}),{group:'BodyLower',name:size,color:'White',property:{}}],['Hogtied','AllFours'].includes(pose)?[pose]:['BaseUpper',pose]);
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Shock device removal did not restore outfit');
console.log('PASS shock device 32 module combinations, initial light phase, four sizes, seven poses and removal without gameplay');
for(const group of ['Cloth','ClothOuter'])for(const typed of [0,1])for(const size of ['Small','Normal','Large','XLarge']) {
 for(const pose of ['BaseUpper','BackBoxTie','BackCuffs','BackElbowTouch','OverTheHead','Yoked','TapedHands','Hogtied','AllFours']) {
  const result=await renderTypes([{group,name:'敞夹克',property:{TypeRecord:{typed}}},{group:'BodyUpper',name:size,color:'White',property:{}}],['Hogtied','AllFours'].includes(pose)?[pose]:[pose,'BaseLower']);
  const back=result.layers.filter(name=>name?.endsWith('_D1B'));
  if(back.length!==(['BackBoxTie','BackCuffs','BackElbowTouch'].includes(pose)?2:0))throw new Error('Jacket back sleeves use wrong pose: '+pose);
 }
}
for(const name of ['敞夹克','大衣','白大褂'])for(const group of ['Cloth','ClothOuter']) {
 await renderTypes([{group,name,property:{}}],['TapedHands','BaseLower']);
 const imageGroup=pack.catalog.assets.find(a=>a.group===group&&a.definition.Name===name).definition.DynamicGroupName||group;
 const alias=pack.catalog.imageMappings[`Assets/Female3DCG/${imageGroup}/TapedHands/${name}_ArmMaskH.png`];
 if(alias!=='Assets/Female3DCG/LuziArmMask/TapedHands/ArmMaskH.png')throw new Error('Missing taped hand mask alias: '+group+'/'+name);
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Jacket removal did not restore outfit');
console.log('PASS jacket back sleeves in both groups, both styles, four sizes and nine poses');
for(const group of ['ItemMisc','ItemHandheld'])for(const name of ['拉紧的牵绳','拉紧的链子']) {
 for(const pose of [['BaseUpper','BaseLower'],['Yoked','BaseLower'],['Hogtied'],['AllFours']]) {
  const baseline=await renderTypes([],pose);
  const marker=await renderTypes([{group,name,property:{}}],pose);
  if(marker.frame!==baseline.frame||marker.layers.length)throw new Error('Invisible leash marker changed portrait: '+group+'/'+name);
 }
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Leash marker removal changed portrait');
console.log('PASS invisible leash and chain markers in both groups and four poses without gameplay');
const appearanceTool = (Hide=[],TypeRecord={h:0,hn:0,c:0}) => ({group:'外观工具',name:'兼容工具',property:{Hide,TypeRecord}});
if((await renderTypes([appearanceTool()])).frame!==opaqueBody.frame)throw new Error('Inactive appearance tool changed outfit');
// Echo evaluates this module before setup registers its additional groups.
const nativeGroupNames=Object.keys(JSON.parse(await fs.readFile(path.join(appAssets,'echo/source-index.json'),'utf8')).nativeAssets);
const clothingGroups=await evaluate(`AssetFemale3DCG.filter(g=>g.Clothing&&${JSON.stringify(nativeGroupNames)}.includes(g.Group)).map(g=>g.Group)`);
const recordedClothingGroups=pack.catalog.assets.find(a=>a.group==='外观工具'&&a.definition.Name==='兼容工具').extended.Modules.find(m=>m.Key==='h').Options[1].Property.Hide;
if(JSON.stringify(recordedClothingGroups)!==JSON.stringify(clothingGroups))throw new Error('Appearance tool clothing groups differ from native definitions');
for(const hidden of [['Cloth'],['ClothLower'],['Nipples'],clothingGroups]) {
 const result=await renderTypes([appearanceTool(hidden)]);
 if(await evaluate(`Character[1].AppearanceLayers.some(l=>${JSON.stringify(hidden)}.includes(l.Asset.Group.Name))`))throw new Error('Appearance tool retained hidden layers');
 if(hidden.includes('Cloth')&&result.frame===opaqueBody.frame)throw new Error('Appearance tool did not change clothing pixels');
 if((await renderTypes([appearanceTool(hidden)])).frame!==result.frame)throw new Error('Appearance tool repeated frame changed');
}
for(const pose of [['BaseUpper','BaseLower'],['BaseUpper','Kneel'],['Yoked','BaseLower']]) {
 const expected=await renderTypes([],pose);
 if((await renderTypes([appearanceTool([],{h:0,hn:0,c:1})],pose)).frame!==expected.frame)throw new Error('Appearance tool changed saved pose');
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Appearance tool removal did not restore outfit');
console.log('PASS appearance tool saved clothing hides, native group definitions, deterministic frames, saved poses and removal');
const container=(name,TypeRecord,Opacity=1,color)=>({group:'ItemDevices',name,color,property:{TypeRecord,Opacity}});
const luggageFrames=[];
for(const c of [0,1,2,3]) luggageFrames.push((await renderTypes([container('行李箱',{c})],['Hogtied'])).frame);
if(new Set(luggageFrames).size!==4)throw new Error('Luggage filling images did not change');
await evaluate(`window.hardLuggageOriginal=window.AssetsItemDevices硬壳行李箱BeforeDraw;window.hardLuggageOpacity={};
 window.AssetsItemDevices硬壳行李箱BeforeDraw=draw=>{const result=window.hardLuggageOriginal(draw);if(result)window.hardLuggageOpacity[draw.L]=result.Opacity;return result;};`);
for(const pose of [['Hogtied'],['AllFours']])for(const c of [0,1,2,3])for(const s of [0,1,2]) {
 await evaluate('window.hardLuggageOpacity={}');
 const result=await renderTypes([container('硬壳行李箱',{c,s})],pose);
 if(result.layers.includes('衣物堆')!==(c>0))throw new Error('Hard luggage filling layer incorrect');
 const opacity=await evaluate('window.hardLuggageOpacity');
 if(opacity.箱盖!==[0.8,0.9,1][s]||(c>0&&opacity.衣物堆!==(c===3?0.7:1)))throw new Error('Hard luggage layer opacity incorrect');
}
await evaluate('window.AssetsItemDevices硬壳行李箱BeforeDraw=window.hardLuggageOriginal');
for(const pose of [['AllFours'],['Hogtied']])for(const s of [0,1,2,3])for(const c of [0,1,2])for(const m of [0,1,2])for(const b of [0,1]) {
 const result=await renderTypes([container('宠物箱',{s,c,m,b})],pose);
 for(const [layer,shown]of [['门',s>0],['锁',s>0],['锁链',c>0],['坐垫',m>0],['遮光布',b===1]])if(result.layers.includes(layer)!==shown)throw new Error('Pet crate module layer incorrect: '+layer);
}
for(const [name,types,pose]of [['行李箱',{c:1},['Hogtied']],['硬壳行李箱',{c:2,s:1},['Hogtied']],['宠物箱',{s:2,c:1,m:1,b:0},['AllFours']]]) {
 const full=await renderTypes([container(name,types)],pose);
 if((await renderTypes([container(name,types)],pose)).frame!==full.frame)throw new Error('Container repeat frame changed');
 const transparent=await renderTypes([container(name,types,0.3)],pose);
 // Hard-shell layers retain upstream minimum opacity and module-specific overrides.
 if((transparent.frame===full.frame)!==(name==='硬壳行李箱'))throw new Error('Container transparency disagrees with upstream: '+name);
 const red=await renderTypes([container(name,types,1,'#FF0000')],pose);
 if((await renderTypes([container(name,types,1,'#00FF00')],pose)).frame===red.frame)throw new Error('Container recoloring ignored');
 for(const size of ['Small','Normal','Large','XLarge']) await renderTypes([container(name,types),...['BodyUpper','BodyLower'].map(group=>({group,name:size,color:'White',property:{}}))],pose);
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Container removal did not restore original outfit');
console.log('PASS luggage filling, hard-shell lid opacity, all pet crate modules in both poses, transparency, colors, body sizes and removal');
const chocolate=(p,seed={})=>({group:'ItemHandheld',name:'巧克力',property:{TypeRecord:{p},...seed}});
const winningChocolate={LuziRid:29,LuziCid:123}, losingChocolate={LuziRid:1,LuziCid:123};
await evaluate(`window.chocolateOriginal=window.AssetsItemHandheld巧克力BeforeDraw;
 window.AssetsItemHandheld巧克力BeforeDraw=draw=>{const result=window.chocolateOriginal(draw);if(draw.L==='Gt')window.chocolateTicket=result?.Opacity;return result;};`);
const chocolateFrames=[];
for(const p of [0,1,2]) {
 const frames=[];
 for(const seed of [{},losingChocolate,winningChocolate]) {
  await evaluate('window.chocolateTicket=null');
  const result=await renderTypes([chocolate(p,seed)]);
  expectLayers(result.layers,[...(p===0?['Al','Wr']:p===1?['Al']:['Ch','Gt']),'ArmMaskR']);
  if(p===2&&await evaluate('window.chocolateTicket')!==(seed.LuziRid===29?1:0))throw new Error('Chocolate ticket visibility disagrees with saved seed');
  frames.push(result.frame);
 }
 if(frames[0]!==frames[1]||(p===2?frames[1]===frames[2]:frames[1]!==frames[2]))throw new Error('Chocolate packaging or ticket pixels incorrect');
 chocolateFrames.push(frames[2]);
}
if(new Set(chocolateFrames).size!==3)throw new Error('Chocolate packaging did not change');
if((await renderTypes([chocolate(2,winningChocolate)])).frame!==chocolateFrames[2])throw new Error('Chocolate ticket changed on repeat drawing');
for(const pose of [['BaseUpper','Kneel'],['TapedHands','BaseLower'],['Yoked','BaseLower'],['OverTheHead','BaseLower'],['AllFours'],['Hogtied']]) await renderTypes([chocolate(2,winningChocolate)],pose);
for(const size of ['Small','Normal','Large','XLarge']) await renderTypes([chocolate(0),{group:'BodyUpper',name:size,color:'White',property:{}}],['TapedHands','BaseLower']);
await evaluate('window.AssetsItemHandheld巧克力BeforeDraw=window.chocolateOriginal');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Chocolate removal did not restore original outfit');
console.log('PASS chocolate packaging, saved winning/losing seeds, missing seed, deterministic ticket, poses, body sizes and removal');
for(const group of ['ItemTorso','ItemTorso2']) {
 const tray=property=>({group,name:'托盘',property});
 const empty=await renderTypes([tray({})]);
 const cookies=[];
 for(let count=0;count<=12;count++) {
  const result=await renderTypes([tray({Luzi_InventoryType:'曲奇',Luzi_InventoryContent:Array.from({length:count},()=>({Property:{}}))})]);
  cookies.push(result.frame);
 }
 if(new Set(cookies).size!==13||cookies[0]!==empty.frame)throw new Error('Tray cookie count images or empty state incorrect');
 if((await renderTypes([tray({Luzi_InventoryType:'曲奇',Luzi_InventoryContent:Array.from({length:15},()=>({}))})])).frame!==cookies[12])throw new Error('Tray oversized cookie count did not use maximum image');
 const positions=[];
 for(let i=0;i<4;i++) {
  const content=Array.from({length:4},(_,j)=>j===i?{IAsset:'橙汁'}:{});
  positions.push((await renderTypes([tray({Luzi_InventoryType:'饮料',Luzi_InventoryContent:content})])).frame);
 }
 if(new Set(positions).size!==4)throw new Error('Tray drink slot positions collapsed');
 const property={Luzi_InventoryType:'饮料',Luzi_InventoryContent:['空杯','橙汁','可乐','牛奶'].map(IAsset=>({IAsset}))};
 const full=await renderTypes([tray(property)]);
 if((await renderTypes([tray(property)])).frame!==full.frame)throw new Error('Tray cache changed repeat rendering');
 for(const props of [{Luzi_InventoryType:'曲奇'},{Luzi_InventoryType:'饮料',Luzi_InventoryContent:null},{Luzi_InventoryType:'饮料',Luzi_InventoryContent:[{},null,{IAsset:'Unknown'},{IAsset:'__proto__'}]}]) {
  if((await renderTypes([tray(props)])).frame!==empty.frame)throw new Error('Tray invalid or empty content retained stale food');
 }
 for(const pose of [['BaseUpper','Kneel'],['Yoked','BaseLower'],['TapedHands','BaseLower'],['AllFours'],['Hogtied']]) await renderTypes([tray(property)],pose);
 for(const size of ['Small','Normal','Large','XLarge']) await renderTypes([tray(property),{group:'BodyUpper',name:size,color:'White',property:{}}]);
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Tray removal did not restore original outfit');
console.log('PASS tray both torso groups, all cookie counts, four drink slots, empty/invalid contents, repeated frames, poses and body sizes');
const drinks=['空杯','橙汁','可乐','牛奶'];
const drinkFrames=[];
for(const typed of [0,1,2,3]) {
 let frame;
 for(const v of [0,1]) {
  const result=await renderTypes([{group:'ItemHandheld',name:'杯饮',property:{TypeRecord:{typed,v}}}]);
  expectLayers(result.layers,[drinks[typed],'ArmMaskR']);
  frame??=result.frame;
  if(frame!==result.frame)throw new Error('Drink volume gameplay setting changed its picture');
 }
 drinkFrames.push(frame);
 if(!requests.has(`Icons/Luzi_GlassDrink_${drinks[typed]}.png`))throw new Error('Drink did not load its pinned shared image');
}
if(new Set(drinkFrames).size!==4)throw new Error('Drink content pictures did not change');
for(const pose of [['TapedHands','BaseLower'],['BaseUpper','Kneel'],['Yoked','BaseLower'],['AllFours'],['Hogtied']]) await renderTypes([{group:'ItemHandheld',name:'杯饮',property:{TypeRecord:{typed:1,v:0}}}],pose);
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Drink removal did not restore native arm');
console.log('PASS glass drink four shared images, volume modes, arm mask, poses, immutable snapshots and removal');
const rightMaskItems=pack.catalog.assets.filter(a=>a.definition.Layer?.some(l=>l.Name==='ArmMaskR'));
for(const record of rightMaskItems)for(const size of ['Small','Normal','Large','XLarge']) {
 const mask=record.definition.Layer.find(l=>l.Name==='ArmMaskR');
 if(mask.PoseMapping.TapedHands!=='')throw new Error('Right arm mask requests nonexistent pose texture');
 await renderTypes([{group:record.group,name:record.definition.Name,property:{}},{group:'BodyUpper',name:size,color:'White',property:{}}],['TapedHands','BaseLower']);
}
console.log('PASS shared right arm masks across all registered users and four body sizes in TapedHands');
await evaluate(`window.fortuneScene=window.vanillaScene;window.vanillaScene=JSON.parse(JSON.stringify(window.vanillaScene));
 window.vanillaScene.appearance=window.vanillaScene.appearance.filter(i=>!['Cloth','ClothLower','Panties'].includes(i.group));`);
const fortune=(s=0,o=0,t=0,color)=>({group:'ItemPelvis',name:'幸运贞操带',color,property:{TypeRecord:{s,o,t},WornTime:123,OrgasmCount:4,LastStruggleTime:456}});
let fortuneFrame;
for(const s of [0,1,2,3])for(const o of [0,1,2])for(const t of [0,1,2]) {
 const result=await renderTypes([fortune(s,o,t)]);
 expectLayers(result.layers,['底色','色调','反射','高光','Lock']);
 fortuneFrame??=result.frame;
 if(result.frame!==fortuneFrame)throw new Error('Fortune belt gameplay modules changed static layers');
}
for(const pose of [['BaseUpper','Kneel'],['BaseUpper','Spread'],['Yoked','BaseLower'],['AllFours'],['Hogtied']]) await renderTypes([fortune()],pose);
const fortuneRed=await renderTypes([fortune(0,0,0,'#FF0000')]);
if((await renderTypes([fortune(0,0,0,'#00FF00')])).frame===fortuneRed.frame)throw new Error('Fortune belt layer colors ignored');
await evaluate('window.vanillaScene=window.fortuneScene');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Fortune belt removal did not restore original outfit');
console.log('PASS fortune belt all thirty-six modules, four static layers, poses, colors, immutable statistics and removal');
const androidBody={group:'动物身体_Luzi',name:'生化人体',property:{}};
await evaluate(`window.androidOriginal=window.Assets动物身体_Luzi生化人体AfterDraw;window.androidFrames={};
 window.Assets动物身体_Luzi生化人体AfterDraw=draw=>window.androidOriginal({...draw,
  drawCanvas:(canvas,...args)=>{window.androidFrames[draw.L]=canvas.toDataURL();draw.drawCanvas(canvas,...args);}
 });`);
for(const size of ['Small','Normal','Large','XLarge'])for(const pose of [['BaseUpper','BaseLower'],['BaseUpper','Kneel'],['BaseUpper','LegsClosed'],['BaseUpper','Spread'],['BaseUpper','KneelingSpread'],['Yoked','BaseLower'],['TapedHands','BaseLower']]) {
 const items=[androidBody,...['BodyUpper','BodyLower'].map(group=>({group,name:size,color:'White',property:{}}))];
 await evaluate('window.androidFrames={}');
 const result=await renderTypes(items,pose);
 const frames=await evaluate('androidFrames');
 if(['底部','上身遮罩','下身遮罩'].some(l=>!frames[l])||new Set(Object.values(frames)).size!==3)throw new Error('Android body partial composition missing');
 if(await evaluate('Character[1].AppearanceLayers.some(l=>["BodyUpper","BodyLower","Nipples"].includes(l.Asset.Group.Name))'))throw new Error('Android body did not replace native body');
 if((await renderTypes(items,pose)).frame!==result.frame)throw new Error('Android body repeat composition changed');
}
for(const pose of [['AllFours'],['Hogtied']]) {
 await evaluate('window.androidFrames={}');
 await renderTypes([androidBody],pose);
 if(await evaluate('Object.keys(androidFrames).length')!==0)throw new Error('Android body drew in unsupported full-body pose');
}
const androidSkin=await renderTypes([androidBody,{group:'BodyUpper',name:'Small',color:'#AA3366',property:{}}]);
if((await renderTypes([androidBody,{group:'BodyUpper',name:'Small',color:'#33AA66',property:{}}])).frame===androidSkin.frame)throw new Error('Android body ignored native skin color');
const androidColor=await renderTypes([{...androidBody,color:'#AA3366'}]);
if((await renderTypes([{...androidBody,color:'#33AA66'}])).frame===androidColor.frame)throw new Error('Android body ignored layer color');
await evaluate('window.Assets动物身体_Luzi生化人体AfterDraw=window.androidOriginal');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Android body removal did not restore original body');
console.log('PASS android body four sizes, upper and lower poses, independent masks, repeated composition, colors, hidden poses and removal');
await evaluate(`window.ghostOriginal=window.Assets动物身体_Luzi幽灵人形AfterDraw;window.ghostFrames={};
 window.Assets动物身体_Luzi幽灵人形AfterDraw=draw=>window.ghostOriginal({...draw,
  drawCanvas:(canvas,...args)=>{(window.ghostFrames[draw.L]??={}).normal=canvas.toDataURL();draw.drawCanvas(canvas,...args);},
  drawCanvasBlink:(canvas,...args)=>{(window.ghostFrames[draw.L]??={}).blink=canvas.toDataURL();draw.drawCanvasBlink(canvas,...args);}
 });`);
const ghostItem=(h=0,f=0,le=0,et=0,Opacity=1,color)=>({group:'动物身体_Luzi',name:'幽灵人形',color,property:{TypeRecord:{h,f,le,et},Opacity,
 Hide:[...(h===3?pack.catalog.assets.find(a=>a.definition.Name==='幽灵人形').extended.Modules[2].Options[3].Property.Hide:[]),...(et===1?['TailStraps','HairAccessory2']:[])]}});
const ghostModes=[];
const ghostMissing=['','TapedHands/'].map(pose=>`Assets/Female3DCG/动物身体_Luzi/${pose}幽灵人形_Small_HandMask.png`);
for(const url of ghostMissing)blockedImages.add(url);
async function renderGhost(items,pose=['BaseUpper','BaseLower']) {
 const mask=pack.catalog.assets.find(a=>a.definition.Name==='幽灵人形').definition.Layer.find(l=>l.Name==='HandMask');
 const partial=items[0].property.TypeRecord.le===1&&!pose.some(p=>mask.PoseMapping[p]==='Hide');
 const result=await renderTypes(items,pose,partial?'partial':'ready');
 if(partial) {
  const report=await evaluate('MobileBcPortraitDump.capture()');
  const failed=report.images.filter(image=>image.state!=='loaded');
  if(!report.checks.hasPixels||failed.length!==1||!ghostMissing.some(url=>decodeURIComponent(failed[0].url).endsWith(url)))throw new Error('Unexpected ghost missing image');
 }
 return result;
}
for(const h of [0,1,2,3])for(const f of [0,1])for(const le of [0,1])for(const et of [0,1]) {
 await evaluate('window.ghostFrames={}');
 const result=await renderGhost([ghostItem(h,f,le,et)]);
 const frames=await evaluate('ghostFrames');
 const body=h<2?'Body':h===2?'FullBody':'FullBody2';
 if(!frames[body] || result.frame===opaqueBody.frame)throw new Error('Ghost body composition missing');
 const eye=h<2?'Eyes':body;
 if(!frames[eye] || frames[eye].normal===frames[eye].blink)throw new Error('Ghost normal/blink frames share content');
 await evaluate('window.ghostFrames={}');
 if((await renderGhost([ghostItem(h,f,le,et)])).frame!==result.frame || await evaluate('JSON.stringify(ghostFrames)')!==JSON.stringify(frames))throw new Error('Ghost compositing state leaked between frames');
 if(et===0)ghostModes.push(result.frame);
}
if(new Set(ghostModes).size<8)throw new Error('Ghost mode/fade/glow controls ignored');
for(const pose of [['BaseUpper','Kneel'],['Yoked','BaseLower'],['TapedHands','BaseLower'],['AllFours'],['Hogtied']]) await renderGhost([ghostItem(2,1,1,0)],pose);
const ghostOpaque=await renderGhost([ghostItem()]);
if((await renderGhost([ghostItem(0,0,0,0,0.5)])).frame!==ghostOpaque.frame)throw new Error('Ghost opacity outside upstream bounds was accepted');
const ghostRed=await renderGhost([ghostItem(0,0,1,0,1,'#FF0000')]);
if((await renderGhost([ghostItem(0,0,1,0,1,'#00FF00')])).frame===ghostRed.frame)throw new Error('Ghost eye glow color ignored');
await evaluate('window.Assets动物身体_Luzi幽灵人形AfterDraw=window.ghostOriginal');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Ghost removal did not restore body');
console.log('PASS ghost body all thirty-two modes, separate blink buffers, repeat drawing, poses, opacity, eye color and removal');
for(const url of ghostMissing)blockedImages.delete(url);
const doll={group:'动物身体_Luzi',name:'人偶',property:{}};
const dollParts=['BodyUpper','BodyLower','HandsLeft','HandsRight','ArmsLeft','ArmsRight'];
const missingDollHands=['White','Asian','Black'].map(color=>`Assets/Female3DCG/动物身体_Luzi/TapedHands/人偶_${color}_手.png`)
 .filter(url=>!echo.images[url]&&!pack.catalog.imageMappings[url]);
for(const url of missingDollHands)blockedImages.add(url);
for(const size of ['Small','Normal','Large','XLarge'])for(const color of ['White','Asian','Black'])for(const pose of [['BaseUpper','BaseLower'],['Hogtied']]) {
 const result=await renderTypes([doll,...['BodyUpper','BodyLower'].map(group=>({group,name:size,color,property:{}}))],pose);
 if(result.frame===opaqueBody.frame || await evaluate(`Character[1].AppearanceLayers.some(l=>${JSON.stringify(dollParts)}.includes(l.Asset.Group.Name))`))throw new Error('Doll did not replace native body parts');
}
for(const pose of [['Yoked','BaseLower'],['OverTheHead','BaseLower'],['BackBoxTie','BaseLower'],['BackElbowTouch','BaseLower'],['BackCuffs','BaseLower'],['TapedHands','BaseLower'],['BaseUpper','LegsClosed'],['BaseUpper','Kneel'],['BaseUpper','KneelingSpread'],['BaseUpper','Spread'],['AllFours'],['Hogtied']]) {
 for(const size of ['Small','Normal','Large','XLarge'])for(const color of ['White','Asian','Black','#AABBCC']) {
  const missing=`Assets/Female3DCG/动物身体_Luzi/TapedHands/人偶_${color.startsWith('#')?'White':color}_手.png`;
  const partial=pose[0]==='TapedHands'&&missingDollHands.includes(missing);
  await renderTypes([doll,...['BodyUpper','BodyLower'].map(group=>({group,name:size,color,property:{}}))],pose,partial?'partial':'ready');
  if(partial) {
   const report=await evaluate('MobileBcPortraitDump.capture()');
   const failed=report.images.filter(image=>image.state!=='loaded');
   if(!report.checks.hasPixels || failed.length!==1 || !decodeURIComponent(failed[0].url).endsWith(missing))throw new Error('Doll partial result hid another missing image');
  }
 }
}
for(const url of missingDollHands)blockedImages.delete(url);
await evaluate(`window.dollOriginal=window.Assets动物身体_Luzi人偶BeforeDraw;window.dollOpacity={};
 window.Assets动物身体_Luzi人偶BeforeDraw=draw=>{const result=window.dollOriginal(draw);window.dollOpacity[draw.L]=result?.Opacity??1;return result;};`);
for(const [group,layer] of [['BodyUpper','上半身'],['BodyLower','下半身'],['HandsLeft','手']]) {
 await evaluate('window.dollOpacity={}');
 await renderTypes([doll,{group:'Cloth',name:'CollegeOutfit1',property:{Hide:[group]}}]);
 if(!await evaluate(`dollOpacity[${JSON.stringify(layer)}]===0`))throw new Error('Doll ignored another item body hiding');
}
await evaluate('window.Assets动物身体_Luzi人偶BeforeDraw=window.dollOriginal');
if((await renderTypes([{...doll,name:'人偶_Luzi'}])).frame!==(await renderTypes([doll])).frame)throw new Error('Doll legacy alias changed appearance');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Doll removal did not restore original body');
console.log('PASS doll four body sizes, skin colors, arm and leg poses, external hiding, alias and removal; pinned TapedHands gap stays partial');
await evaluate(`window.robotBefore=window.AssetsItemNeckRestraints监控机器人BeforeDraw;
 window.robotAfter=window.AssetsItemNeckRestraints监控机器人AfterDraw;window.robotFrames={};window.robotOpacity={};
 window.AssetsItemNeckRestraints监控机器人BeforeDraw=draw=>{
  const result=window.robotBefore(draw);window.robotOpacity[draw.L]=result?.Opacity??1;return result;
 };
 window.AssetsItemNeckRestraints监控机器人AfterDraw=draw=>window.robotAfter({...draw,drawCanvas:(canvas,...args)=>{
  window.robotFrames[draw.L]={frame:canvas.toDataURL(),args};draw.drawCanvas(canvas,...args);
 }});`);
const robotItem=(typed,leashed,color)=>({group:'ItemNeckRestraints',name:'监控机器人',color,property:{TypeRecord:{typed},Effect:leashed?['IsLeashed']:[]}});
for(const pose of [['BaseUpper','BaseLower'],['BaseUpper','Kneel'],['Hogtied']]) {
 const modes=[];
 for(const typed of [0,1,2])for(const leashed of [false,true]) {
  await evaluate('window.robotFrames={};window.robotOpacity={}');
  const result=await renderTypes([robotItem(typed,leashed)],pose);
  if(!await evaluate('robotFrames["绳子"] && robotFrames["眼睛"]'))throw new Error('Robot procedural layers missing');
  if(typed===1 && !await evaluate(`robotOpacity['跟随模式']===${leashed?0:1} && robotOpacity['跟随模式_抓住']===${leashed?1:0}`))throw new Error('Robot follow/leashed layer visibility mismatch');
  const pixels=await evaluate('JSON.stringify(robotFrames)');
  await evaluate('window.robotFrames={};window.robotOpacity={}');
  if((await renderTypes([robotItem(typed,leashed)],pose)).frame!==result.frame || await evaluate('JSON.stringify(robotFrames)')!==pixels)throw new Error('Robot fixed gaze or rope changed');
  modes.push(result.frame);
 }
 if(new Set(modes).size!==4)throw new Error('Robot mode/leash state did not change portrait');
}
const robotColors=[];
for(const color of ['#FF0000','#00FF00']) {
 await evaluate('window.robotFrames={}');await renderTypes([robotItem(0,false,color)]);
 robotColors.push(await evaluate('robotFrames'));
}
for(const layer of ['绳子','眼睛'])if(robotColors[0][layer].frame===robotColors[1][layer].frame)throw new Error('Robot custom color ignored: '+layer);
await evaluate('window.AssetsItemNeckRestraints监控机器人BeforeDraw=window.robotBefore;window.AssetsItemNeckRestraints监控机器人AfterDraw=window.robotAfter');
console.log('PASS robot modes, leash visibility, rope and eye colors, poses and fixed gaze');
for(const group of ['ItemTorso','ItemTorso2']) {
 const key=`Assets${group}尿袋AfterDraw`;
 await evaluate(`window.liquidOriginal=window[${JSON.stringify(key)}];window.liquidFrame=null;
 window[${JSON.stringify(key)}]=draw=>window.liquidOriginal({...draw,drawCanvas:(canvas,...args)=>{
  window.liquidFrame=canvas.toDataURL();draw.drawCanvas(canvas,...args);
 }});`);
 for(const lower of ['BaseLower','LegsClosed','KneelingSpread','Kneel','Spread']) {
  const levels=new Map();
  for(const Liquid of [0,0.5,1,-1,2,undefined])for(const Auto of [false,true]) {
   await evaluate('window.liquidFrame=null');
   await renderTypes([{group,name:'尿袋',property:{Liquid,Auto}}],['BaseUpper',lower]);
   const frame=await evaluate('liquidFrame');
   if(!frame)throw new Error('Liquid bag canvas missing');
   const level=Math.max(0,Math.min(1,Liquid??0));
   if(levels.has(level) && levels.get(level)!==frame)throw new Error('Liquid bag clamping or Auto changed saved liquid');
   levels.set(level,frame);
  }
  if(new Set(levels.values()).size!==3)throw new Error('Liquid bag levels did not change pixels');
 }
 for(const pose of [['Hogtied'],['AllFours']]) {
  await evaluate('window.liquidFrame=null');
  await renderTypes([{group,name:'尿袋',property:{Liquid:1,Auto:true}}],pose);
  if(await evaluate('liquidFrame')!==null)throw new Error('Liquid bag ignored hidden pose');
 }
 await evaluate(`window[${JSON.stringify(key)}]=window.liquidOriginal`);
}
console.log('PASS liquid bag mirrored groups, five leg poses, clamped levels, Auto stability and hidden poses');
await evaluate(`window.shieldOriginal=window.AssetsItemVulva赛博护盾AfterDraw;window.shieldFrames=[];
 window.AssetsItemVulva赛博护盾AfterDraw=draw=>window.shieldOriginal({...draw,drawCanvas:(canvas,...args)=>{
  window.shieldFrames=window.shieldFrames.filter(frame=>frame.layer!==draw.L);
  window.shieldFrames.push({layer:draw.L,frame:canvas.toDataURL(),args});
  draw.drawCanvas(canvas,...args);
 }});`);
const shieldItem=(f,s=0,i=0,o=0,color)=>({group:'ItemVulva',name:'赛博护盾',color,property:{TypeRecord:{f,s,i,o},Intensity:i-1}});
const shieldPixels=[];
for(const f of [0,1])for(const s of [0,1])for(const i of [0,1,2,3,4])for(const o of [0,1,2]) {
 await evaluate('window.shieldFrames=[]');
 await renderTypes([shieldItem(f,s,i,o)]);
 const drawn=await evaluate('shieldFrames');
 if(JSON.stringify(drawn.map(x=>x.layer))!==JSON.stringify(['Light','Bloom']) || drawn.some(x=>JSON.stringify(x.args)!=='[200,1160]'))throw new Error('Cyber shield masked light layers missing');
 const signature=JSON.stringify(drawn);
 if(shieldPixels[f] && shieldPixels[f]!==signature)throw new Error('Cyber shield nonvisual option changed fixed light');
 shieldPixels[f]=signature;
}
if(shieldPixels[0]===shieldPixels[1])throw new Error('Cyber shield color flow mode had no effect');
for(const color of [['Default','#AE3131','#000000','#FFFFFF'],['Default','#AE3131','#FF0000','#00FF00']]) {
 await evaluate('window.shieldFrames=[]');
 const first=await renderTypes([shieldItem(0,0,0,0,color)]);
 const pixels=await evaluate('JSON.stringify(shieldFrames)');
 if(pixels===shieldPixels[0])throw new Error('Cyber shield custom colors ignored');
 await evaluate('window.shieldFrames=[]');
 if((await renderTypes([shieldItem(0,0,0,0,color)])).frame!==first.frame || await evaluate('JSON.stringify(shieldFrames)')!==pixels)throw new Error('Cyber shield random phase changed fixed frame');
}
for(const pose of [['BaseUpper','Kneel'],['BaseUpper','KneelingSpread'],['Hogtied'],['AllFours']]) {
 await renderTypes([shieldItem(0)],pose);
}
await evaluate('window.AssetsItemVulva赛博护盾AfterDraw=window.shieldOriginal');
console.log('PASS cyber shield sixty configurations, masked lights, custom colors, poses and fixed phase');
await evaluate(`window.prisonOriginal=window.AssetsItemDevices单监BeforeDraw;window.prisonCalls=[];
 window.AssetsItemDevices单监BeforeDraw=draw=>{
  const result=window.prisonOriginal(draw);
  if(draw.L==='A_柱子伸缩'||draw.L==='A_阳具')window.prisonCalls.push((result?.Y??draw.Y)-draw.Y);
  return result;
 };`);
for(const [o,pose] of ['BaseLower','LegsClosed','Spread'].entries()) {
 const frames=[];
 for(const d of [0,1,2,3,4]) {
  await evaluate('window.prisonCalls=[]');
  const property=await evaluate(`Object.assign({},...ExtendedItemGetData(MobileBcEcho.asset('ItemDevices','单监'),ExtendedArchetype.MODULAR).modules.map((m,i)=>m.Options[${JSON.stringify([o,d])}[i]].Property))`);
  const item={group:'ItemDevices',name:'单监',property:{...property,TypeRecord:{o,d}}};
  const result=await renderTypes([item],['BaseUpper',pose]);
  if(await evaluate('Character[1].PoseMapping.BodyLower')!==pose)throw new Error('Prison leg pose mismatch');
  const delta=d===0?0:d===1?-100:-60;
  if(!await evaluate(`prisonCalls.length>0&&prisonCalls.every(y=>y===${delta})`))throw new Error('Prison piston offset missing');
  if(result.frame===opaqueBody.frame)throw new Error('Prison did not add visible pixels');
  frames.push(result.frame);
  if((await renderTypes([item],['BaseUpper',pose])).frame!==result.frame)throw new Error('Prison fixed frame changed');
 }
 if(new Set(frames.slice(0,3)).size!==3 || frames[2]!==frames[3] || frames[3]!==frames[4])throw new Error('Prison piston frame mismatch');
}
await evaluate('window.AssetsItemDevices单监BeforeDraw=window.prisonOriginal');
console.log('PASS one-bar prison all fifteen configurations, leg poses, piston offsets and fixed frames');
const ghostRecords=pack.catalog.assets.filter(a=>a.definition.Name==='鬼手');
if(ghostRecords.length!==11)throw new Error('Ghost hand groups missing');
for(const record of ghostRecords) {
 await renderTypes([{group:record.group,name:'鬼手',property:{}}],['Yoked','BaseLower']);
}
for(const size of ['Small','Normal','Large','XLarge']) {
 await renderTypes([{group:'BodyUpper',name:size,color:'White',property:{}},{group:'ItemBreast',name:'鬼手',property:{}}]);
}
for(const [typed,pose] of ['Yoked','OverTheHead','BackBoxTie','BackElbowTouch'].entries()) {
 // Native option parsing also derives AllowActivePose; use the actual saved-property shape.
 const property=await evaluate(`ExtendedItemGetData(MobileBcEcho.asset('ItemArms','鬼手'),ExtendedArchetype.TYPED).options[${typed}].Property`);
 await renderTypes([{group:'ItemArms',name:'鬼手',property:{...property,TypeRecord:{typed}}}],[pose,'BaseLower']);
 if(await evaluate('Character[1].PoseMapping.BodyUpper')!==pose)throw new Error('Ghost hand pose did not match saved property');
 const images=await evaluate('MobileBcPortraitDump.capture().images.map(i=>decodeURIComponent(i.url))');
 const prefix=`/ItemArms/${pose}/鬼手_`;
 const back=pose==='BackElbowTouch'?'/ItemArms/BackElbowTouch/鬼手A_B2.png':prefix+'A_B2.png';
 if(!images.some(url=>url.endsWith(back)) ||
    images.some(url=>url.endsWith(prefix+'A_B1.png'))!==(pose!=='BackElbowTouch'))throw new Error('Ghost hand pose images do not match mapping');
}
for(const alias of pack.catalog.aliases.filter(a=>a.name==='鬼手')) {
 const item=name=>({group:alias.group,name,property:{}});
 const current=await renderTypes([item('鬼手')],['Yoked','BaseLower']);
 if((await renderTypes([item(alias.oldName)],['Yoked','BaseLower'])).frame!==current.frame)throw new Error('Ghost hand legacy alias changed portrait');
}
console.log('PASS ghost hand eleven groups, arm poses and legacy outfit aliases');
for(const record of pack.catalog.assets.filter(a=>['EFGlass','EFMask'].includes(a.definition.Name))) {
 const name=record.definition.Name, group=record.group;
 for(const v of [0,1,2])for(const m of group==='ItemHead'?[0,1,2,3,4]:[0]) {
  const item=f=>({group,name,property:{TypeRecord:{v,m,f}}});
  const frozen=await renderTypes([item(0)]);
  const steady=await renderTypes([item(1)]);
  for(const result of [frozen,steady]) {
   for(const layer of ['light1','light2'])if(!result.layers.includes(layer))throw new Error('EvilFall light layer missing');
   if(result.layers.includes('cover1')!==(v===1)||result.layers.includes('cover2')!==(v===2))throw new Error('EvilFall opacity layer mismatch');
   if(result.layers.includes('effect')!==(name==='EFGlass'&&group==='ItemHead'&&m>0))throw new Error('EvilFall effect layer mismatch');
  }
  if(frozen.frame===steady.frame)throw new Error('EvilFall light mode did not change pixels');
  if((await renderTypes([item(0)])).frame!==frozen.frame)throw new Error('EvilFall frozen frame changed');
 }
}
console.log('PASS EvilFall seven groups, opacity/effect options and deterministic light modes without game callbacks');
for(const pose of [['BaseUpper','BaseLower'],['BaseUpper','Kneel'],['BaseUpper','KneelingSpread'],['Hogtied'],['AllFours']]) {
 for(const e of [0,1]) {
  const box=(t,Text)=>({group:'ItemDevices',name:'纸箱',property:{TypeRecord:{e,t},Text}});
  const plain=await renderTypes([box(0,'')],pose);
  const labeled=await renderTypes([box(1,'Fragile')],pose);
  if(!labeled.layers.includes('B')||!labeled.layers.includes('F')||!labeled.layers.includes('Text'))throw new Error('Cardboard box layers missing');
  if(plain.frame===labeled.frame)throw new Error('Cardboard box text missing');
  if((await renderTypes([box(1,'Handle')],pose)).frame===labeled.frame)throw new Error('Cardboard box text did not update');
  if((await renderTypes([box(0,'Fragile')],pose)).frame!==plain.frame)throw new Error('Disabled cardboard text remained visible');
 }
}
console.log('PASS cardboard eye options, five poses and text enable/update/disable');
const collarLayers=['后Rim','后Mat','中Rim','中Mat','前Rim','前Mat'];
for(const pose of [['BaseUpper','BaseLower'],['Yoked','Kneel'],['Hogtied'],['AllFours']]) {
 const item = color => ({group:'ItemNeckAccessories',name:'伊丽莎白圈',color,property:{}});
 const first=await renderTypes([item(['#224466','#88AACC'])],pose);
 const recolored=await renderTypes([item(['#AA4422','#FFCC88'])],pose);
 expectLayers(first.layers,collarLayers);
 expectLayers(recolored.layers,collarLayers);
 if(first.frame===recolored.frame)throw new Error('Collar colors did not affect portrait');
}
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Collar removal did not restore portrait');
console.log('PASS Elizabethan collar six layers, colors, poses and removal without activity callbacks');
await evaluate(`{
 window.rulerOriginal=window.AssetsBodyMarkings刻度尺AfterDraw;
 window.rulerCanvases={};
 window.AssetsBodyMarkings刻度尺AfterDraw=function(draw){
  return window.rulerOriginal({...draw,drawCanvas:(canvas,...args)=>{
   window.rulerCanvases[draw.L]=canvas.toDataURL();
   return draw.drawCanvas(canvas,...args);
  }});
 };
}`);
const rulerItem = property => ({group:'BodyMarkings',name:'刻度尺',color:['#493F3F','#493F3F'],property});
const rulerEmpty = await renderTypes([rulerItem({Text:'',Text2:'',Text3:'',Text4:''})]);
expectLayers(rulerEmpty.layers,['Lines','Text']);
const rulerBlankCanvases = await evaluate('rulerCanvases');
if(!rulerBlankCanvases.Lines || !rulerBlankCanvases.Text || rulerBlankCanvases.Lines===rulerBlankCanvases.Text)throw new Error('Ruler procedural lines missing');
await evaluate('rulerCanvases={}');
const rulerText = await renderTypes([rulerItem({Text:'One',Text2:'Two',Text3:'Three',Text4:'Four'})]);
expectLayers(rulerText.layers,['Lines','Text']);
const rulerFilledCanvases = await evaluate('rulerCanvases');
if(rulerFilledCanvases.Text===rulerBlankCanvases.Text || rulerFilledCanvases.Lines!==rulerBlankCanvases.Lines)throw new Error('Ruler text canvas did not update independently');
await evaluate('rulerCanvases={}');
if((await renderTypes([rulerItem({Text:'',Text2:'',Text3:'',Text4:''})])).frame!==rulerEmpty.frame)throw new Error('Ruler text persisted after clearing');
if(await evaluate('rulerCanvases.Text')!==rulerBlankCanvases.Text)throw new Error('Ruler text canvas was not cleared');
await evaluate('window.AssetsBodyMarkings刻度尺AfterDraw=window.rulerOriginal');
console.log('PASS ruler procedural lines and four text fields, clearing and input isolation');
for (const group of ['ItemMisc','ItemHandheld']) {
 const record = pack.catalog.assets.find(a=>a.group===group && a.definition.Name==='玩偶');
 if(!record || record.extended.ScriptHooks)throw new Error('Plushie static configuration missing');
 const layers = record.definition.Layer;
 const selected = process.env.ECHO_PLUSHIE_ALL ? layers : record.extended.Modules.flatMap(module => {
  const options = layers.filter(layer=>Object.hasOwn(layer.AllowTypes,module.Key));
  return [...new Set([options[0],options.at(-1)])];
 });
 const empty = Object.fromEntries(record.extended.Modules.map(module=>[module.Key,0]));
 expectLayers((await renderTypes([{group,name:'玩偶',property:{TypeRecord:empty}}])).layers,[]);
 for (const layer of selected) {
  const result=await renderTypes([{group,name:'玩偶',property:{TypeRecord:{...empty,...layer.AllowTypes}}}]);
  expectLayers(result.layers,[layer.Name]);
 }
 const special=layers.find(layer=>layer.PoseMapping?.Yoked==='TapedHands');
 if(!special)throw new Error('Missing plushie pose fixture');
 for(const pose of [['Yoked','BaseLower'],['Hogtied'],['AllFours']]) {
  expectLayers((await renderTypes([{group,name:'玩偶',property:{TypeRecord:{...empty,...special.AllowTypes}}}],pose)).layers,[special.Name]);
 }
}
console.log('PASS plushie room boundaries, empty selection, both slots and special poses without editor callbacks');
if(process.env.ECHO_PLUSHIE_ALL)return;
for (const pose of ['BaseLower', 'Kneel', 'KneelingSpread']) {
 for (const suspended of [false, true]) for (const mirror of [0, 1]) {
  const gown = await renderTypes([{group:'Cloth',name:'长裙',property:{
   TypeRecord:{M:mirror},Effect:suspended?['Suspended']:[],
  }}], ['BaseUpper', pose]);
  if (!gown.layers.includes('1')) throw new Error('Evening gown skirt layer missing');
 }
}
console.log('PASS evening gown standing, kneeling, suspension and mirror variants without gameplay');
for (const pose of ['BaseUpper','Yoked']) for (const typed of [0,1]) {
 const laptop = await renderTypes([{group:'ItemHandheld',name:'笔记本电脑',property:{TypeRecord:{typed}}}], [pose,'BaseLower']);
 if (!laptop.layers.includes('main')) throw new Error('Laptop main layer missing');
}
const savedLanguage = await evaluate('TranslationLanguage');
for (const language of ['CN','TW','EN','DE']) {
 await evaluate(`TranslationLanguage=${JSON.stringify(language)}`);
 for (const typed of [0,1,2,3,4]) await renderTypes([{group:'ItemMisc',name:'斩标',property:{TypeRecord:{typed}}}]);
}
await evaluate(`TranslationLanguage=${JSON.stringify(savedLanguage)}`);
for (const pose of [['BaseUpper','BaseLower'], ['AllFours'], ['Hogtied']]) for (const t of [0,1]) {
 const item = Text => ({group:'ItemDevices',name:'木狗屋',property:{TypeRecord:{d:0,c:0,t},Text,Text2:'Second'}});
 const first = await renderTypes([item('Home')],pose);
 const second = await renderTypes([item('Changed')],pose);
 if (first.frame === second.frame) throw new Error('Dog house text did not update');
 if (!first.layers.includes(pose.length === 1 ? 'Text_F' : 'Text')) throw new Error('Dog house text layer missing');
}
console.log('PASS laptop poses, localized sign variants and dog house text in standing/prone poses');
for (const name of ['大号拉珠','肛鞭']) for (const group of ['ItemMouth','ItemHandheld']) {
 await renderTypes([{group,name,property:{}}]);
}
let previousBeadsFrame;
for (let InsertedBeads=1;InsertedBeads<=9;InsertedBeads++) {
 const result = await renderTypes([{group:'ItemButt',name:'大号拉珠',property:{InsertedBeads,TypeRecord:{typed:InsertedBeads-1}}}]);
 if (result.frame===previousBeadsFrame) throw new Error('Bead count did not change visible position');
 previousBeadsFrame=result.frame;
}
for (const typed of [0,1]) await renderTypes([{group:'ItemButt',name:'肛鞭',property:{TypeRecord:{typed}}}]);
for (const g of [0,1,2,3,4]) for (const l of [0,1]) {
 await renderTypes([{group:'ItemDevices',name:'乳胶带床',property:{TypeRecord:{g,l}}}]);
}
console.log('PASS grouped props, nine bead positions and latex bed cover/sleeping-bag variants');
for (const [group,name,types] of [
 ['ItemDevices','垃圾桶',[0,1,2]], ['ItemDevices','巨型玩偶',[0]],
 ['ItemDevices','拳击袋',[0,1]], ['ItemDevices','独角兽玩偶',[0,1]],
 ['ItemDevices','麻袋',[0,1]], ['ItemHood','麻袋',[0,1]],
]) {
 for (const typed of types) for (const Opacity of [0,0.5,1]) {
  await renderTypes([{group,name,property:{TypeRecord:{typed},Opacity}}],['BaseUpper','Kneel']);
 }
 const item = {group,name,property:{TypeRecord:{typed:0},Opacity:0}};
 const masked = await renderTypes([item],['BaseUpper','Kneel']);
 await evaluate(`{
  const asset=AssetGet('Female3DCG',${JSON.stringify(group)},${JSON.stringify(name)});
  window.fullMaskBackup=asset.Layer.filter(layer=>layer.Alpha.length).map(layer=>({layer,alpha:layer.Alpha}));
  if(!window.fullMaskBackup.length)throw new Error('Missing full-body alpha masks');
  const expected=AssetGroup.map(g=>g.Name).filter(g=>g!==asset.Group.Name).sort();
  for(const {alpha} of window.fullMaskBackup)for(const mask of alpha) {
   if(JSON.stringify([...mask.Group].sort())!==JSON.stringify(expected))throw new Error('Wrong full-mask groups');
  }
  for(const {layer} of window.fullMaskBackup)layer.Alpha=[];
 }`);
 const unmasked = await renderTypes([item],['BaseUpper','Kneel']);
 await evaluate('for(const {layer,alpha} of window.fullMaskBackup)layer.Alpha=alpha');
 if(masked.frame===unmasked.frame)throw new Error('Full mask did not affect visible body: '+group+'/'+name);
 const restored = await renderTypes([item],['BaseUpper','Kneel']);
 if(masked.frame!==restored.frame)throw new Error('Full mask did not restore deterministically');
}
console.log('PASS full-body mask groups, opacity/type variants, clipping pixels and restoration');
for (const group of ['ItemTorso','ItemTorso2']) {
 for (const typed of [0,1]) {
  const reins = await renderTypes([{group,name:'缰绳',property:{TypeRecord:{typed}}}]);
  if(reins.layers.includes('绳子') !== (typed===1)) throw new Error('Reins option selected wrong layers');
 }
 for (const lower of ['BaseLower','LegsClosed','Kneel','KneelingSpread']) for (const typed of [0,1,2,3]) {
  const suit = await renderTypes([{group,name:'拘束套装',property:{TypeRecord:{typed}}}],['BackElbowTouch',lower]);
  for(const [index,layer] of ['乳胶衣','透视紧身衣','紧身衣'].entries()) {
   if(suit.layers.includes(layer)!==(typed===index+1))throw new Error('Restraint suit selected wrong clothing');
  }
 }
}
for (const group of ['ItemDevices','ItemArms']) for (const o of [0,1]) for (const t of [0,1]) for (const g of [0,1]) {
 await renderTypes([{group,name:'充气式拘束袋',property:{TypeRecord:{o,t,g},Opacity:0.5}}]);
}
console.log('PASS mirrored reins, restraint clothing options/poses and inflatable bag modules');
for(const group of ['ItemTorso','ItemTorso2']) {
 for(const d of [0,1,2])for(const s of [0,1])for(const h of [0,1,2])for(const m of [0,1])for(const f of [0,1]) {
  const result = await renderTypes([{group,name:'触手服',property:{TypeRecord:{d,s,h,m,f},Intensity:-1}}]);
  for(const [layer,visible] of [['触手服',d===0],['触手服开',d!==0],['触手',d===2],['触手背后',d===2],
   ['上衣',s===1],['触手服手套',h!==0],['触手服嘴套',m===1],['触手服脚套',f===1]]) {
   if(result.layers.includes(layer)!==visible)throw new Error('Tentacle suit layer mismatch: '+group+'/'+layer);
  }
 }
 const item = Intensity=>({group,name:'触手服',property:{TypeRecord:{d:2,s:1,h:1,m:1,f:1},Intensity}});
 await evaluate(`{
  const key=${JSON.stringify('Assets' + group + '触手服BeforeDraw')};
  window.tentacleOriginal=window[key];window.tentacleCalls=[];
  window[key]=function(draw){
   const result=window.tentacleOriginal(draw);
   if(draw.L==='触手'||draw.L==='触手背后') {
    window.tentacleCalls.push({delta:result.Y-draw.Y,intensity:draw.Property?.Intensity});
    if(window.tentacleCalls.length>64)window.tentacleCalls.shift();
   }
   return result;
  };
 }`);
 // The base skirt can occlude this layer; verify the callback invoked by the drawing engine.
 await renderTypes([item(-1)]);
 if(!await evaluate('tentacleCalls.length>0 && tentacleCalls.every(c=>c.delta===0 && c.intensity===-1)'))throw new Error('Inactive tentacle draw offset changed');
 await evaluate('tentacleCalls=[]');
 const active = await renderTypes([item(0)]);
 if(!await evaluate('tentacleCalls.length>0 && tentacleCalls.every(c=>c.delta===30 && c.intensity===0)'))throw new Error('Active tentacle draw offset missing');
 for(const Intensity of [1,2,3]) {
  if((await renderTypes([item(Intensity)])).frame!==active.frame)throw new Error('Tentacle frame is not deterministic');
 }
 for(const pose of [['BaseUpper','Kneel'],['BaseUpper','KneelingSpread'],['Yoked','BaseLower'],['Hogtied'],['AllFours']]) {
  await renderTypes([item(2)],pose);
 }
 await evaluate(`window[${JSON.stringify('Assets' + group + '触手服BeforeDraw')}]=window.tentacleOriginal`);
}
console.log('PASS tentacle suit 144 module combinations, mirrored groups, poses and deterministic frozen frame');
const poseTool = await renderTypes([{group:'外观工具',name:'自动闭腿',property:{}}]);
if(poseTool.frame!==opaqueBody.frame)throw new Error('Invisible pose tool changed the supplied portrait');
const transparentBody = await renderTypes([{group:'动物身体_Luzi',name:'透明身体',property:{}}]);
if(opaqueBody.frame===transparentBody.frame)throw new Error('Transparent body did not hide body pixels');
if(await evaluate('Character[1].AppearanceLayers.some(l=>["BodyUpper","BodyLower"].includes(l.Asset.Group.Name))'))throw new Error('Transparent body retained body layers');
if((await renderTypes([])).frame!==opaqueBody.frame)throw new Error('Transparent body removal did not restore body');
for(const pose of [['BaseUpper','BaseLower'],['Kneel'],['AllFours']]) {
 const petSuit = await renderTypes([{group:'ItemArms',name:'乳胶宠物拘束服',property:{}}],pose);
 if(!petSuit.layers.includes('本体'))throw new Error('Legacy restraint suit did not draw its main layer');
}
console.log('PASS legacy restraint suit poses and image-free transparent body hide/restore');
const hoodVisibleHair = await renderTypes([{group:'ItemHood',name:'乳胶头套',property:{TypeRecord:{l:0,F:0,B:0,AC:0,P:0}}}]);
const hoodHiddenHair = await renderTypes([{group:'ItemHood',name:'乳胶头套',property:{TypeRecord:{l:0,F:1,B:1,AC:1,P:0}}}]);
if(hoodVisibleHair.frame===hoodHiddenHair.frame)throw new Error('Latex hood hair visibility options did not change pixels');
const noseFrames=[];
for(let typed=0;typed<6;typed++) {
 const result=await renderTypes([{group:'ItemNose',name:'Luzi_NoseHook',property:{TypeRecord:{typed}}}]);
 noseFrames.push(result.frame);
 if(!await evaluate('Character[1].AppearanceLayers.some(l=>l.Asset.Group.Name==="ItemNose"&&l.Asset.Name==="LuziNoseHook"&&l.Name==="Hook")'))throw new Error('Nose hook alias failed to load layers');
}
if(new Set(noseFrames).size!==6)throw new Error('Nose hook options did not produce six distinct frames');
await renderTypes([{group:'ItemHandheld',name:'警棍',property:{}}]);
console.log('PASS latex hood hair options, six nose-hook styles and baton');
const hoodFrames=[];
for(const Text of ['TOY','DRONE','']) {
 const result=await renderTypes([{group:'ItemHood',name:'狗机仆头套',property:{Text,TypeRecord:{t:0}}}]);
 hoodFrames.push(result.frame);
}
if(new Set(hoodFrames).size!==3)throw new Error('Drone hood text changes did not update pixels');
const hiddenHood=await renderTypes([{group:'ItemHood',name:'狗机仆头套',property:{Text:'HIDDEN',TypeRecord:{t:1}}}]);
if(hiddenHood.frame!==hoodFrames[2])throw new Error('Drone hood text toggle retained text');
console.log('PASS drone hood text edit, empty text and hidden text');
for(const Expression of [undefined,'Daydream','Dizzy']) {
 await renderTypes(['左眼_Luzi','右眼_Luzi'].map(group=>({group,name:'眼睛3微调',property:{Expression}})));
}
await renderTypes([{group:'Luzi_HairAccessory3_1',name:'金属发卡',property:{}}]);
for(const typed of [0,1]) {
 await renderTypes([{group:'Cloth',name:'锦云绣雪旗袍',property:{TypeRecord:{typed}}}]);
 await renderTypes(['Suit','SuitLower'].map(group=>({group,name:'乳胶衣',property:{TypeRecord:{typed}}})));
}
await renderTypes(['SocksLeft','SocksRight'].map(group=>({group,name:'丝袜3',property:{}})));
console.log('PASS requested outfit records, mirrored hairpin and special eye expressions');
for (const [group, name] of [['额外头发_Luzi','呆毛'], ['ClothLower','两侧镂空瑜伽裤'],
 ['ClothLower','裙子2'], ['Cloth','长旗袍'], ['Bra','乳贴1'], ['HairAccessory1','天线'],
 ['Suit','时韵S'], ['Suit','时韵B'], ['Shoes','洞洞鞋'], ['Shoes','拖鞋'], ['Shoes','厚拖'],
 ['Shoes','灰姑娘'], ['Shoes','玛丽珍皮鞋'], ['ItemArms','书包'], ['Mask','时尚口罩']]) {
 const result = await renderTypes([{group,name,color:'Default',property:{}}]);
 if (!result.layers.length) throw new Error(`Recovered Echo component has no layers: ${group}/${name}`);
}
console.log('PASS recovered Echo clothing with pure dialog and position helpers');
await renderTypes([]);
await evaluate('window.maskBaseline = document.getElementById("MainCanvas").getContext("2d").getImageData(0,0,500,1000).data; void 0');
for (const group of ['SocksLeft', 'SocksRight']) {
 const result = await renderTypes([{group,name:'猫袜',color:'Default',property:{TypeRecord:{typed:0}}}]);
 if (!result.layers.includes('Mask猫袜')) throw new Error('Missing split-sock mask');
 const changes = await evaluate(`(() => {
  const pixels = document.getElementById('MainCanvas').getContext('2d').getImageData(0,0,500,1000).data;
  const halves = [0,0];
  for(let y=500;y<1000;y++)for(let x=0;x<500;x++) {
   const i=(y*500+x)*4;
   if([0,1,2,3].some(c=>pixels[i+c]!==window.maskBaseline[i+c])) halves[x<250?0:1]++;
  }
  return halves;
 })()`);
 const visible = group === 'SocksLeft' ? 1 : 0;
 if (changes[visible] === 0 || changes[1-visible] !== 0) throw new Error(`Wrong sock masking: ${group}: ${changes}`);
}
for (const name of ['踝链A','踝链B']) {
 const frames = [];
 for(const lr of [0,1,2]) {
  const result = await renderTypes([{group:'Jewelry',name,color:'Default',property:{TypeRecord:{lr,s:0,o:0}}}]);
  frames.push(result.frame);
 }
 if (new Set(frames).size !== 3) throw new Error(`Anklet sides did not change: ${name}`);
}
await renderTypes([{group:'SocksLeft',name:'绷带',color:'Default',property:{}}]);
console.log('PASS local half-mask clips only the opposite sock; anklet side switches change pixels');
await evaluate(`window.markingBaseline = window.vanillaScene.appearance;
 window.vanillaScene.appearance = window.markingBaseline.filter(i=>!['Cloth','ClothLower','Bra','Suit','SuitLower'].includes(i.group));`);
const biteOff = await renderTypes([{group:'BodyMarkings',name:'咬痕',property:{TypeRecord:{FB:0}}}]);
const biteOn = await renderTypes([{group:'BodyMarkings',name:'咬痕',property:{TypeRecord:{FB:1}}}]);
if (biteOff.frame === biteOn.frame || !biteOn.layers.includes('FB') || biteOff.layers.includes('FB')) throw new Error('Bite-mark position switch failed: ' + JSON.stringify({sameFrame:biteOff.frame===biteOn.frame,off:biteOff.layers,on:biteOn.layers}));
const iconFrames = [];
for(const a of [0,1]) {
 const result = await renderTypes([{group:'BodyMarkings',name:'标志纹饰',property:{TypeRecord:{p:0,a,b:0,c:0,d:0,e:0,f:0,g:0,h:0,i:0,j:0}}}]);
 iconFrames.push(result.frame);
}
if(iconFrames[0] === iconFrames[1]) throw new Error('Icon marking position mask did not change pixels');
const bodyTextFrames = [];
for(const Text1 of ['FIRST','SECOND']) {
 const result = await renderTypes([{group:'BodyMarkings2_Luzi',name:'身体论文',color:'#000000',property:{TypeRecord:{a:1},Text1,Text2:'line two',Text3:'line three'}}]);
 bodyTextFrames.push(result.frame);
}
if(bodyTextFrames[0] === bodyTextFrames[1]) throw new Error('Body text changes did not change pixels');
const lyingBody = await renderTypes([],['AllFours']);
const hiddenBodyText = await renderTypes([{group:'BodyMarkings2_Luzi',name:'身体论文',property:{TypeRecord:{a:1},Text1:'HIDDEN'}}],['AllFours']);
if(lyingBody.frame !== hiddenBodyText.frame) throw new Error('Body text should be hidden on all fours');
if(await evaluate('Boolean(AssetFemale3DCGExtended.BodyMarkings?.["身体论文"])')) throw new Error('Mirror-only text configuration leaked into the original group');
console.log('PASS body text draws without image assets, updates text and supports hidden poses');
await evaluate('window.vanillaScene.appearance = window.markingBaseline; void 0');
console.log('PASS bite-mark layers and icon marking masks change visible pixels without editor callbacks');
await evaluate(`window.sportsBaseline = window.vanillaScene.appearance;
 window.vanillaScene.appearance = window.sportsBaseline.filter(i=>!['Cloth','Bra'].includes(i.group));`);
for (const group of ['Cloth','Bra']) {
 const sportsFrames = [];
 for (const t of [0,1]) {
  const result = await renderTypes([{group,name:'运动套装top',color:['#DDDDDD','#1C1C1C','#222222'],property:{Text:'TEST',TypeRecord:{m:0,t}}}]);
  sportsFrames.push(result.frame);
 }
 if(sportsFrames[0] === sportsFrames[1]) throw new Error(`Sport top text switch did not change pixels: ${group}`);
}
await evaluate('window.vanillaScene.appearance = window.sportsBaseline; void 0');
console.log('PASS sports top draws custom text in both appearance slots without game callbacks');
const hairBase = await renderTypes([]);
for(const name of ['渐变叠加','双渐变叠加']) {
 const enabled = await renderTypes([{group:'额外头发_Luzi',name,color:['#FF0088','#FF0088'],property:{TypeRecord:{f:0,b:0},upperBound:0,gradientSize:100,rotation:180}}]);
 const disabled = await renderTypes([{group:'额外头发_Luzi',name,color:['#FF0088','#FF0088'],property:{TypeRecord:{f:1,b:1}}}]);
 if(enabled.frame === hairBase.frame || disabled.frame !== hairBase.frame) throw new Error(`Hair gradient toggle failed: ${name}`);
}
console.log('PASS single/double hair gradient visibly draws and disabling both layers restores the original frame');
const collarFrames = [];
for(const typed of [0,1,2]) {
 const result = await renderTypes([{group:'ClothAccessory',name:'交领右衽',color:'Default',property:{TypeRecord:{typed}}}]);
 collarFrames.push(result.frame);
}
if(new Set(collarFrames).size !== 3) throw new Error('Cross-collar extension styles did not change pixels');
console.log('PASS cross-collar WebGL extensions render three distinct styles');
const stockingFrames = [];
await evaluate(`window.stockingBaseline = window.vanillaScene.appearance;
 window.vanillaScene.appearance = window.stockingBaseline.filter(i=>!['Cloth','ClothLower','Bra','Panties'].includes(i.group));`);
for(const m of [0,1,2,3,4]) {
 const result = await renderTypes([{group:'Suit',name:'马油袜',property:{TypeRecord:{m,h:0,b:0}}}]);
 stockingFrames.push(result.frame);
}
if(new Set(stockingFrames).size !== 5) throw new Error('Glossy stocking cut modes did not change pixels: ' + stockingFrames.map(frame=>stockingFrames.indexOf(frame)));
await renderTypes([{group:'Suit',name:'马油袜',property:{TypeRecord:{m:2,h:2,b:1}}}],['BaseLower','OverTheHead']);
for(const t of [0,1]) await renderTypes([{group:'SuitLower',name:'马油袜下',property:{TypeRecord:{t}}}]);
await evaluate('window.vanillaScene.appearance = window.stockingBaseline; void 0');
console.log('PASS all five glossy stocking cut masks, raised arms, chest opening and toe options');
await renderTypes([{group:'BodyMarkings',name:'脚趾甲',property:{}}]);
await renderTypes([{group:'BodyMarkings',name:'脚趾甲',property:{}}],['BaseUpper','Spread']);
await renderTypes([{group:'ClothAccessory',name:'围裙',property:{}}]);
const standingHandles = await renderTypes([{group:'ClothAccessory',name:'把手',property:{TypeRecord:{typed:0}}}]);
const lyingHandles = await renderTypes([{group:'ClothAccessory',name:'把手',property:{TypeRecord:{typed:0}}}],['AllFours']);
if(standingHandles.frame === lyingHandles.frame || !requests.has('Assets/Female3DCG/ClothAccessory/AllFours/把手_af_base.png')) throw new Error('Doll handles lost the all-fours layers');
console.log('PASS reviewed toe-size, apron-mask and pose-only handle data repairs');
const weaponLayers = [['法杖1','法杖2'],['剑'],['弩'],['斧'],['羽毛笔_r','羽毛笔_s'],['胜利剑'],['玛恩纳'],['斩龙剑']];
const weapon = (t,s) => ({group:'ItemHandheld',name:'武器组合',color:'Default',property:{TypeRecord:{t,s},...(s===0?{OverridePriority:4}:{})}});
const weaponFrames = new Map();
for(let t=0;t<8;t++) {
 for(let s=0;s<2;s++) {
  const result=await renderTypes([weapon(t,s)]);
  expectLayers(result.layers,[...weaponLayers[t],...(s===1&&t!==2?['ArmMaskR']:[])]);
  weaponFrames.set(`${t},${s}`,result.frame);
  if(t>0&&s===1&&result.frame===weaponFrames.get(`${t},0`))throw new Error(`Weapon ${t} did not change when drawn`);
 }
}
if(new Set(weaponFrames.values()).size<8)throw new Error('Weapon type switches did not produce distinct frames');
const restoredWeapon=await renderTypes([weapon(1,1)]);
if(restoredWeapon.frame!==weaponFrames.get('1,1'))throw new Error('Repeated modular selection left stale layers');
await fs.writeFile(path.join(root,'modular-weapon.png'),Buffer.from((await call('Page.captureScreenshot')).data,'base64'));
const posedWeapon=await renderTypes([weapon(1,1)],['Kneel','BackBoxTie']);
if(posedWeapon.layers.includes('ArmMaskR'))throw new Error('Hidden pose kept the arm mask');
if(posedWeapon.frame===restoredWeapon.frame)throw new Error('Modular item ignored pose change');
const sparse=weapon(1,0);delete sparse.property.TypeRecord.s;
if((await renderTypes([sparse])).frame!==weaponFrames.get('1,0'))throw new Error('Missing module did not use option zero');
const missingType=weapon(0,1);delete missingType.property.TypeRecord.t;
const defaultType=await renderTypes([missingType]);
expectLayers(defaultType.layers,['法杖1','法杖2','ArmMaskR']);
if(defaultType.frame!==weaponFrames.get('0,1'))throw new Error('Missing module hid default layers or conditional mask');
const emptyType=weapon(0,0);delete emptyType.property.TypeRecord;
if((await renderTypes([emptyType])).frame!==weaponFrames.get('0,0'))throw new Error('Missing record did not render default modules');
console.log('PASS all 16 weapon type/sheathe combinations, conditional masks, pose, sparse defaults and repeated switches');

const clip = typed => ({group:'HairAccessory3',name:'心型发卡',property:{TypeRecord:{typed}}});
const clipFrames=[];
for(let typed=0;typed<3;typed++) {
 const result=await renderTypes([clip(typed)]);
 expectLayers(result.layers,typed===0?['左夹子','左心']:typed===1?['右夹子','右心']:['左夹子','左心','右夹子','右心']);
 clipFrames.push(result.frame);
}
if(new Set(clipFrames).size!==3)throw new Error('Left/right/both clips did not change pixels');
if((await renderTypes([{group:'HairAccessory3',name:'心型发卡'}])).frame!==clipFrames[0])throw new Error('Missing typed property hid the default clip');
const mirroredClip={...clip(2),group:'HairAccessory3_笨笨蛋Luzi'};
await renderTypes([mirroredClip]);
if(![...requests].some(p=>p.startsWith('Assets/Female3DCG/HairAccessory1/心型发卡_')))throw new Error('Clip did not use inherited image group');
const bow = typed => ({group:'ClothAccessory',name:'蝴蝶结装饰',property:{TypeRecord:{typed}}});
const bowFrames=[];
for(let typed=0;typed<3;typed++) {
 const result=await renderTypes([bow(typed)]);
 if(result.layers.length!==(typed===0?10:5))throw new Error('Wrong bow layer count');
 if(typed>0&&!result.layers.every(n=>n.startsWith(typed===1?'右_':'左_')))throw new Error('Bow mirror orientation is wrong');
 bowFrames.push(result.frame);
}
if(new Set(bowFrames).size!==3)throw new Error('Bow options did not change pixels');
console.log('PASS typed left/right/both clips and bows, inherited images and Echo extra slot');

const tattoo = TypeRecord => ({group:'BodyMarkings',name:'大纹身',property:{TypeRecord}});
for(const [record,layers] of [[{},[]],[{Te:1},['部落左锁骨']],[{Fa:1},['右腿花','右腿花_花']],[{Te:1,Fa:1},['部落左锁骨','右腿花','右腿花_花']]]) {
 expectLayers((await renderTypes([tattoo(record)])).layers,layers);
}
await renderTypes([weapon(3,1),clip(2),bow(0),tattoo({Te:1,Fa:1})]);
await fs.writeFile(path.join(root,'modular-combined.png'),Buffer.from((await call('Page.captureScreenshot')).data,'base64'));
if((await renderTypes([])).frame!==vanillaFrame)throw new Error('Removing modular items left stale pixels');
console.log('PASS independent tattoo modules, simultaneous multi-item combinations, immutable snapshots and clean removal');
await evaluate(`window.coatCalls=[];
for(const group of ['ClothOuter','Cloth_笨笨蛋Luzi']) for(const name of ['白大褂','大衣']) {
 const key='Assets'+group+name+'BeforeDraw';const original=window[key];
 if(typeof original!=='function')throw new Error('Missing supplement hook: '+key);
 window[key]=(...args)=>{const result=original(...args);window.coatCalls.push({group,name,layer:args[0].L,result});return result;};
}`);
for(const [name,key] of [['白大褂','typed'],['大衣','d']]) {
 const coat = mode => ({group:'ClothOuter',name,color:'Default',property:{TypeRecord:{[key]:mode}}});
 const normal = await renderTypes([coat(1)]);
 const forced = await renderTypes([coat(2)]);
 if(normal.frame===forced.frame)throw new Error(`${name} disguise did not change pixels`);
 await evaluate('window.coatCalls=[]');
 const restrained={group:'ItemArms',name:'HempRope',color:'Default',property:{TypeRecord:{typed:0},Effect:['Block'],SetPose:['BackBoxTie'],AllowActivePose:['BackBoxTie']}};
 await renderTypes([coat(0),restrained],['BackBoxTie','BaseLower']);
 const autoApplied=await evaluate(`window.coatCalls.some(c=>c.name===${JSON.stringify(name)}&&${key==='typed'?"c.result?.LayerType==='r'":"c.layer==='A3'&&c.result?.Opacity===1"})`);
 if(!autoApplied)throw new Error(`${name} auto disguise did not follow restraint/pose`);
 const mirror={...coat(2),group:'Cloth_笨笨蛋Luzi'};
 await renderTypes([mirror]);
 const restored=await renderTypes([coat(1)]);
 if(restored.frame!==normal.frame)throw new Error(`${name} left stale disguise state`);
}
await renderTypes([{group:'ClothOuter',name:'白大褂',property:{TypeRecord:{typed:2}}}]);
await fs.writeFile(path.join(root,'supplement-coat.png'),Buffer.from((await call('Page.captureScreenshot')).data,'base64'));
if((await renderTypes([])).frame!==vanillaFrame)throw new Error('Supplement removal left stale pixels');
console.log('PASS supplement coats: normal/forced/automatic disguise, mirrored slots, repeated switches and no gameplay side effects');
await evaluate(`window.dressCalls=[];
for(const group of ['Cloth','Cloth_笨笨蛋Luzi']) {
 const key='Assets'+group+'白色礼服BeforeDraw';const original=window[key];
 if(typeof original!=='function')throw new Error('Missing dress hook: '+key);
 window[key]=(...args)=>{const result=original(...args);window.dressCalls.push({group,layer:args[0].L,result});return result;};
}`);
const dress={group:'Cloth',name:'白色礼服',color:'Default',property:{TypeRecord:{hn:0}}};
const suspendedMarker={group:'ItemMisc',name:'贴贴',property:{Effect:['Suspended']}};
const kneelingDress=await renderTypes([dress],['BaseUpper','Kneel']);
await evaluate('window.dressCalls=[]');
const suspendedDress=await renderTypes([dress,suspendedMarker],['BaseUpper','Kneel']);
if(!await evaluate(`['g_3','d_3'].every(layer=>window.dressCalls.some(c=>c.group==='Cloth'&&c.layer===layer&&c.result?.Pose===''))`)) {
 throw new Error('Suspended dress did not override both main layers');
}
if(kneelingDress.frame===suspendedDress.frame)throw new Error('Dress suspension did not change pixels');
if((await renderTypes([dress],['BaseUpper','Kneel'])).frame!==kneelingDress.frame)throw new Error('Dress retained suspended pose after effect removal');
await evaluate('window.dressCalls=[]');
await renderTypes([{...dress,group:'Cloth_笨笨蛋Luzi'},suspendedMarker],['BaseUpper','Kneel']);
if(!await evaluate(`window.dressCalls.some(c=>c.group==='Cloth_笨笨蛋Luzi'&&c.result?.Pose==='')`))throw new Error('Mirrored dress lost supplement hook');
const standingDress=await renderTypes([dress]);
for(const group of ['ClothAccessory','长袖子_Luzi']) {
 const scarf={group,name:'白色礼服丝巾',color:'Default',property:{}};
 const combined=await renderTypes([dress,scarf]);
 if(combined.frame===standingDress.frame)throw new Error('Dress scarf did not add visible pixels: '+group);
 if(!combined.layers.includes('前d'))throw new Error('Missing scarf layer: '+group);
}
await fs.writeFile(path.join(root,'supplement-dress.png'),Buffer.from((await call('Page.captureScreenshot')).data,'base64'));
if((await renderTypes([])).frame!==vanillaFrame)throw new Error('Dress or scarf removal left stale pixels');
console.log('PASS supplement dress: suspended pose overrides, effect removal, mirrored hook, both static scarf slots and clean removal');

// Exercise real cache failures without depending on upstream outages.
const bodySize=await evaluate('window.vanillaScene.appearance.find(i=>i.group==="BodyUpper").name');
const missingLayer=`echo/${echo.revision}/Assets/Female3DCG/Cloth/白色礼服_${bodySize}_d_3.png`;
await evaluate('DrawImageCache.clear()');
blockedImages.add(missingLayer);
const partialDress=await renderTypes([dress],undefined,'partial');
if(partialDress.frame===standingDress.frame)throw new Error('Missing layer did not change pixels');
if(!partialDress.layers.includes('g_3'))throw new Error('Missing image removed the whole dress');
if(!await evaluate('MainCanvas.getImageData(0,0,500,1000).data.some(v=>v!==0)'))throw new Error('Missing layer blanked the portrait');
if(!await evaluate('window.mobileBcPortraitStatus.missingImages===1&&window.mobileBcPortraitStatus.omitted.length===0'))throw new Error('Missing image was not reported separately from omitted items');
blockedImages.clear();
if((await renderTypes([dress])).frame!==standingDress.frame)throw new Error('Recovered resource did not restore the complete frame');
if((await renderTypes([])).frame!==vanillaFrame)throw new Error('Removal after missing image failed to recover');
console.log('PASS individual image 404 keeps other layers; recovered resources and removal restore ready');

const catalog=pack.catalog;
const dressLayers=catalog.assets.find(a=>a.portraitAdapter==='echo-white-dress').definition.Layer;
for(const name of ['FlatSmall','FlatMedium']) {
 for(const layer of dressLayers) {
  const resource=`Assets/Female3DCG/Cloth/白色礼服_${name}_${layer.Name}.png`;
  if(!echo.images[resource]&&!catalog.imageMappings[resource])blockedImages.add(resource);
 }
 const body={group:'BodyUpper',name,color:'White',property:{}};
 await renderTypes([body,dress],undefined,'partial');
 if(!await evaluate('window.mobileBcPortraitStatus.missingImages>0'))throw new Error('Flat body did not report missing dress images');
 if(!await evaluate(`[...DrawImageCache.cache.values()].some(i=>i.isFailing()&&i.url.includes(${JSON.stringify('白色礼服_'+name)}))`))throw new Error('Expected missing dress fixture was not exercised');
 if((await renderTypes([dress])).frame!==standingDress.frame)throw new Error('Body change did not recover after missing dress images');
 await renderTypes([body,dress],undefined,'partial');
 // The baseline BC shirt/bra also lack flat-body images; removing the dress
 // must still allow a new partial frame instead of locking the whole renderer.
 await renderTypes([body],undefined,'partial');
 if((await renderTypes([])).frame!==vanillaFrame)throw new Error('Original BC image failures prevented recovery');
}
blockedImages.clear();
console.log('PASS FlatSmall/FlatMedium missing dress images, body switch and dress removal recover without reloading');

await evaluate('DrawImageCache.clear()');
const timeoutRevision=typeRevision++;
await evaluate(`window.heldImageLoads=[];window.originalFetch=window.fetch;window.originalNow=performance.now.bind(performance);window.clockOffset=0;
window.fetch=(input,options)=>{
 const url=new URL(typeof input==='string'?input:input.url,document.baseURI);
 if(decodeURIComponent(url.pathname)===${JSON.stringify('/'+missingLayer)})return new Promise((resolve,reject)=>{
  window.heldImageLoads.push(()=>window.originalFetch(input,options).then(resolve,reject));
 });
 return window.originalFetch(input,options);
};
Object.defineProperty(performance,'now',{configurable:true,value:()=>window.originalNow()+window.clockOffset});
window.timeoutScene=JSON.parse(JSON.stringify(window.vanillaScene));
window.timeoutScene.appearance=window.timeoutScene.appearance.filter(i=>i.group!=='Cloth').concat(${JSON.stringify(dress)});
window.timeoutInput=JSON.stringify(window.timeoutScene);
window.mobileBcSetPortrait(window.timeoutScene,${timeoutRevision});`);
for(let attempt=0;attempt<200;attempt++) {
 if(await evaluate('window.heldImageLoads.length===1&&[...DrawImageCache.cache.values()].filter(i=>!i.isDoneLoading()).length===1'))break;
 if(attempt===199)throw new Error('Timeout fixture did not finish loading its other images');
 await new Promise(r=>setTimeout(r,50));
}
await evaluate('window.clockOffset=90001');
await expectState(timeoutRevision,'partial');
if(!await evaluate('window.heldImageLoads.length===1&&window.mobileBcPortraitStatus.missingImages===1'))throw new Error('Timeout did not preserve a partial frame');
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')!==partialDress.frame)throw new Error('Timeout discarded available layers');
if(!await evaluate('JSON.stringify(window.timeoutScene)===window.timeoutInput'))throw new Error('Timeout mutated its snapshot');
await evaluate('window.clockOffset=0;window.fetch=window.originalFetch;delete performance.now;');
if((await renderTypes([dress])).frame!==standingDress.frame)throw new Error('Timeout poisoned the next snapshot');
await call('Runtime.evaluate',{expression:'Promise.all(window.heldImageLoads.map(release=>release()))',awaitPromise:true});
await new Promise(r=>setTimeout(r,100));
if(await evaluate('document.getElementById("MainCanvas").toDataURL()')!==standingDress.frame)throw new Error('Late image changed the newer snapshot');
if(!await evaluate('window.mobileBcPortraitStatus.state==="ready"'))throw new Error('Late image changed the newer status');
console.log('PASS image timeout shows available layers; next snapshot recovers; late completion cannot replace it');

const badSnapshotRevision=typeRevision++;
await evaluate(`window.dressBeforeDraw=window.AssetsCloth白色礼服BeforeDraw;
window.AssetsCloth白色礼服BeforeDraw=()=>{throw new Error('Injected drawing failure');};
window.mobileBcSetPortrait(window.timeoutScene,${badSnapshotRevision});`);
await expectState(badSnapshotRevision,'failed');
await evaluate('window.AssetsCloth白色礼服BeforeDraw=window.dressBeforeDraw;');
if((await renderTypes([])).frame!==vanillaFrame)throw new Error('A drawing exception permanently disabled the renderer');
console.log('PASS drawing exception stops the bad snapshot; a subsequent valid snapshot recovers');
const recoveredDump = JSON.parse(await evaluate('JSON.stringify(MobileBcPortraitDump.capture())'));
if (recoveredDump.events.length > 64 || !recoveredDump.events.some(event => event.stage === 'error')) throw new Error('Dump error timeline missing or unbounded');

blockedImages.add('Scripts/Asset.js');
await call('Page.navigate',{url:'http://127.0.0.1:18763/portrait/index.html'});
await expectState(0,'failed');
await evaluate('window.mobileBcSetPortrait({assetFamily:"Female3DCG",appearance:[],activePose:[]},1)');
await expectState(1,'failed');
blockedImages.clear();
console.log('PASS missing core script still requires runtime reinitialization');
}
try { await main(); }
finally {
  if(closeBrowser)await Promise.race([closeBrowser(),new Promise(r=>setTimeout(r,2000))]);
  socket?.close();server.closeAllConnections();server.close();chrome?.kill();
}
