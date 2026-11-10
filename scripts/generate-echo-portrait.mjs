// Build-time extraction only. The APK contains data, never Echo's game/session hooks.
// Run with Node 22+: node --experimental-vm-modules scripts/generate-echo-portrait.mjs SOURCE TREE BC_RESOURCES
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { DialogTools } from './echo-dialog-tools.mjs';
import { monadic } from './echo-monadic.mjs';

const [sourceRoot, treeFile, bcRoot] = process.argv.slice(2);
if (!bcRoot) throw new Error('Expected Echo source directory, GitHub tree JSON and pinned R133 resources');
const tree = JSON.parse(await fs.readFile(treeFile, 'utf8'));
const revision = tree.sha;
if (!/^[a-f0-9]{40}$/.test(revision) || tree.truncated) throw new Error('Wrong or incomplete Echo tree');
const output = 'app/src/main/assets/portrait/echo';
const gitHash = bytes => createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
async function source(name) {
    const bytes = await fs.readFile(path.join(sourceRoot, name));
    if (gitHash(bytes) !== tree.tree.find(entry => entry.path === name)?.sha) throw new Error(`Source hash mismatch: ${name}`);
    return bytes.toString('utf8');
}
const bcManifest = JSON.parse(await fs.readFile('app/src/main/assets/portrait/scripts.json', 'utf8'));
const supplementText = await fs.readFile('app/src/main/assets/portrait/supplement/manifest.json', 'utf8');
const supplement = JSON.parse(supplementText);
const drawingCode = await fs.readFile('app/src/main/assets/portrait/supplement/drawing.js','utf8');
const colorStart = drawingCode.indexOf('class MobileBcColorTools {');
if (colorStart < 0) throw new Error('Missing pinned color helper');
const ColorTools = vm.runInNewContext(drawingCode.slice(colorStart,drawingCode.indexOf('\n}',colorStart)+2)+'\nnew MobileBcColorTools()');
if (supplement.format !== 1 || supplement.bcVersion !== 'R133') {
    throw new Error('Portrait supplement version mismatch');
}
for (const adapter of supplement.adapters) {
    const expected = adapter.source === 'echo' ? tree.tree.find(e => e.path === adapter.path)?.sha
        : bcManifest.find(e => e.path === adapter.path)?.sha256;
    if (!expected || adapter.sourceHash !== expected) throw new Error(`Supplement source mismatch: ${adapter.id}`);
}
// A new upstream revision may reuse unchanged reviewed behavior, never new hook code.
supplement.echoRevision = revision;
async function bcSource(name) {
    const bytes = await fs.readFile(path.join(bcRoot, name));
    if (createHash('sha256').update(bytes).digest('hex') !== bcManifest.find(entry => entry.path === name)?.sha256) {
        throw new Error(`BC hash mismatch: ${name}`);
    }
    return bytes.toString('utf8');
}
const definitions = await bcSource('Assets/Female3DCG.js');
const effectStart = definitions.indexOf('const E =');
const effects = vm.runInNewContext(definitions.slice(effectStart, definitions.indexOf('\n});', effectStart) + 4) + '\nE');
const nativeStart = definitions.indexOf('var AssetFemale3DCG = [');
const nativeDefinitions = definitions.slice(nativeStart, definitions.indexOf('\n];', nativeStart) + 3);
// Match top-level group headers in the pinned source, excluding asset overrides.
const groupImageSources = new Map([...definitions.matchAll(/^\t\tGroup: "([^"]+)",([\s\S]*?)^\t\tAsset: \[/gm)]
    .map(([, group, header]) => [group, header.match(/^\t\tDynamicGroupName: "([^"]+)"/m)?.[1] || group]));
const nativeAssets = {};
const nativeGroups = [...nativeDefinitions.matchAll(/^\t\tGroup: "([^"]+)",/gm)];
for (let i = 0; i < nativeGroups.length; i++) {
    const body = nativeDefinitions.slice(nativeGroups[i].index, nativeGroups[i + 1]?.index);
    nativeAssets[nativeGroups[i][1]] = [...body.matchAll(/^\t{4}Name: "([^"]+)"|^\t{3}"([^"]+)"/gm)].map(m => m[1] || m[2]);
    const inline = body.match(/^\t\tAsset: \[([^\n]*)\]/m);
    if (inline) nativeAssets[nativeGroups[i][1]].push(...[...inline[1].matchAll(/"([^"]*)"/g)].map(m => m[1]));
}
function imageGroup(group, definition, groups) {
    return definition.DynamicGroupName || groups.find(g => g.Group === group)?.DynamicGroupName || groupImageSources.get(group) || group;
}

// Inclusion is based on extractable portrait data; retain upstream license notices in the pack.
const groupFiles = ['src/components/功能调整/身体区域.js', 'src/components/功能调整/复制身体区域.js'];
// Audited data-only props outside the clothing directory.
const foodComponent = 'src/components/束缚与玩具/手持物/食物.js';
// Previously reviewed mixed components remain hash-bound when updating on-device.
const reviewedComponents = {
    'src/components/束缚与玩具/刺激/电击器.js': 'e1217bca00dd913c88903fe1e1d4dfc64a529060',
    'src/components/束缚与玩具/互动辅助/拉紧的牵绳.js': '1d28f1caaa790cad985f20ca131c24009fad4185',
    'src/components/工具/外观工具.js': '6f0c71a53e75a53e579a983b94b3bbb0a209c5b4',
    'src/components/束缚与玩具/包装/行李箱.js': '9a24230beae04da17fc7e963a720adbeb4760625',
    'src/components/束缚与玩具/包装/硬壳行李箱.js': 'a12a5478a8a7ba45124b0df5f2554805015e22d9',
    'src/components/束缚与玩具/包装/宠物箱.js': '07da530c09c5d1eb2214492adb566416f1afdd23',
    'src/components/束缚与玩具/手持物/玻璃杯饮料.js': 'fdd440c878ac7a9b57c72e27e8606730847ca8f4',
    'src/components/束缚与玩具/幸运贞操带.js': '9e69603ed46ab19fc22cc2090b9977a61e465781',
    'src/components/套装/落落/发饰.js': '5c94a8d9db3e2058c3a9924c45a58ebf5753cced',
    'src/components/衣服和装饰/化妆美发/新眼睛.js': 'aad5549703d798cd995add27ae4386473af0bb2f',
    'src/components/衣服和装饰/衣服/中式/锦云绣雪旗袍.js': '75ce89978a149db3110b65a34ee85444c130241d',
    'src/components/衣服和装饰/衣服/全身连体/乳胶衣.js': '2c66a35ddedc9b06e48100fb525765a51f90c373',
    'src/components/衣服和装饰/饰品/袜子/丝袜3.js': 'fc0391ddf58a836674e89d7959310648b91a245f',
};
const propFiles = ['src/components/束缚与玩具/手持物/武器组合.js', foodComponent, ...Object.keys(reviewedComponents)];
const assetSourceRoots = ['衣服和装饰/', '束缚与玩具/', '家具及摆设/', '套装/', '工具/', '后背.js'].map(p => `src/components/${p}`);
const candidates = tree.tree.filter(entry => entry.type === 'blob' && entry.path.endsWith('.js') &&
    assetSourceRoots.some(root => entry.path.startsWith(root))).map(entry => entry.path).sort();
const catalog = { revision, supplementVersion: supplement.version, groups: [], mirrors: [], assets: [], aliases: [], imageMappings: {}, sources: [] };
const rejected = [];
const omittedAssets = [];
const stockingComponent = 'src/components/衣服和装饰/衣服/全身连体/油光袜.js';
const collarComponent = 'src/components/束缚与玩具/装饰物/伊丽莎白圈.js';
const dollComponent = 'src/components/衣服和装饰/替换身体/人偶.js';
const androidBodyComponent = 'src/components/衣服和装饰/替换身体/生化人体.js';
const rawBodyHooks = {
    [dollComponent]: {name:'人偶',hook:'BeforeDraw'},
    [androidBodyComponent]: {name:'生化人体',hook:'AfterDraw'},
};
// Reviewed data repairs for missing resource paths in the pinned definitions.
const repairedSources = {
    'src/components/套装/世界/敞开夹克.js': 'a236ddd7ae8d7042c2e07ca2f31c018d5704d1c0',
    'src/components/束缚与玩具/托盘.js': 'f4a261a93da3a72536adb93629eac079da2429ba',
    'src/components/衣服和装饰/替换身体/人偶.js': '6cd2b56b5627e569c15e4d1de73753d495ed1e69',
    'src/components/束缚与玩具/尿袋.js': '3720d258ea196187e3e3ee3dea2ec2340da2830c',
    'src/components/束缚与玩具/手脚拘束/鬼手.js': 'ba4c6172c00551e03d9c4ae52c868cd3695c00ca',
    'src/components/束缚与玩具/全包.js': 'f9db69ef3133f370f810581fdd3e00e2d4fbb3cd',
    'src/components/衣服和装饰/化妆美发/脚趾甲.js': '930d1bef50aa83ef05e51ce9d4bb9558ad2c2fd2',
    'src/components/衣服和装饰/衣服/女仆与围裙/围裙.js': '875cd535c7782cd04e013eef9e78f993315a7893',
    'src/components/衣服和装饰/饰品/玩偶把手.js': 'd7894c83741bef016854962e156a851df8454e7f',
};
// These exact reviewed sources use only equipment-editor callbacks, with no
// portrait drawing hooks. Changed sources must be reviewed again before pruning.
const editorOnlyComponents = {
    'src/components/衣服和装饰/饰品/手持物/玩偶.js': '2e1ac2b396f85a2f549f071c4916e801a9db06f7',
    'src/components/衣服和装饰/化妆美发/咬痕.js': '65ca57d334818556101e6619e22def3d89a5c29d',
    'src/components/衣服和装饰/衣服/全身连体/乳胶衣.js': '2c66a35ddedc9b06e48100fb525765a51f90c373',
};
// Pinned utils src/Tools/index.js: item groups are a fixed list, not every custom group.
const ItemGroups = [
    "ItemFeet",
    "ItemLegs",
    "ItemVulva",
    "ItemVulvaPiercings",
    "ItemButt",
    "ItemPelvis",
    "ItemTorso",
    "ItemTorso2",
    "ItemNipples",
    "ItemNipplesPiercings",
    "ItemBreast",
    "ItemArms",
    "ItemHands",
    "ItemHandheld",
    "ItemNeck",
    "ItemNeckAccessories",
    "ItemNeckRestraints",
    "ItemMouth",
    "ItemMouth2",
    "ItemMouth3",
    "ItemHead",
    "ItemNose",
    "ItemHood",
    "ItemEars",
    "ItemMisc",
    "ItemDevices",
    "ItemAddon",
    "ItemBoots",
];
const actionOnlyComponents = {
    "src/components/束缚与玩具/刺激/大号拉珠.js": "db833076ded79f7b57f7d9bda044cd888c57c3d1",
    "src/components/束缚与玩具/刺激/肛鞭.js": "2d961b5955242c1f76d744ea55cf3bde79f11247"
};
const fullMaskComponent = 'src/components/功能调整/全身遮罩.js';
if (tree.tree.find(e => e.path === fullMaskComponent)?.sha !== 'f8103ccb189fb666085623886b67868a6326b36b') throw new Error('FullMask helper requires review');
await source(fullMaskComponent);
const fullMaskWork = [];
const opacityEditorComponents = {
    "src/components/束缚与玩具/手脚拘束/充气式拘束袋.js": "c0dfa2a40c940b65d3c84f0bdf4b2d8dc8460002",
    "src/components/家具及摆设/垃圾桶.js": "e40248cb71ed3b247f96bfbe8bf996953baed7f1",
    "src/components/家具及摆设/巨型玩偶.js": "2e337edf17a284ecc8df05940f6d43867ce4bce0",
    "src/components/家具及摆设/拳击袋.js": "d63d5412bb569e77c4bb9d521a037f87c7c03294",
    "src/components/家具及摆设/独角兽玩偶.js": "0ad6964165042d9a5f685e682e5966065549baa4"
};
const components = {};
const names = { revision, groups: [], assets: [] };

// Each component is evaluated independently. Apart from manifest-bound drawing ports,
// unsupported imports or callbacks reject the whole component; helpers are never stubbed silently.
async function extract(component) {
    if (reviewedComponents[component] && tree.tree.find(e => e.path === component)?.sha !== reviewedComponents[component]) {
        throw new Error('Reviewed component requires review');
    }
    if (component === foodComponent && tree.tree.find(e => e.path === component)?.sha !== 'a36621709980bf648fcf270f2edcaac8f82a3c7b') {
        throw new Error('Food asset selection requires review');
    }
    const result = { groups: [], mirrors: [], assets: [], aliases: [], imageMappings: {}, names: { groups: [], assets: [] } };
    const fullMasks = [];
    const modules = new Map();
    const context = vm.createContext({ E: new Proxy(effects, {
        get(target, key) {
            if (!Object.hasOwn(target, key)) throw new Error(`Unsupported effect constant: ${String(key)}`);
            return target[key];
        },
    }) });
    for (const [file, constants] of [
        ['Assets/Female3DCG.js', ['AssetUpperOverflowAlpha', 'AssetLowerOverflowAlpha']],
        ['Scripts/Pose.js', ['PoseAllKneeling', 'PoseAllStanding']],
        ['Scripts/TypedItem.js', ['TypedItemChatSetting']],
        ['Scripts/ExtendedItem.js', ['ExtendedXYClothes', 'ExtendedXY']],
        ['Scripts/Common.js', ['CommonChatTags']],
        ['Screens/Character/Appearance/Appearance.js', ['CanvasUpperOverflow', 'CanvasLowerOverflow']],
    ]) {
        const code = await bcSource(file);
        for (const constant of constants) {
            const start = code.indexOf(`const ${constant} =`);
            if (start < 0) throw new Error(`Missing pinned constant: ${constant}`);
            vm.runInContext(code.slice(start, code.indexOf(';', start) + 1), context);
        }
    }
    if (Object.hasOwn(opacityEditorComponents, component)) {
        const code = await bcSource('Scripts/Property.js');
        for (const name of ['PropertyOpacityInit','PropertyOpacityLoad','PropertyOpacityDraw','PropertyOpacityExit']) {
            const start = code.indexOf('function ' + name + '(');
            if (start < 0) throw new Error('Missing pinned opacity callback');
            vm.runInContext(code.slice(start, code.indexOf('\n}',start)+2), context);
        }
    }
    for (const [file, constant] of [['Scripts/Asset.js', 'PoseType'], ['Scripts/ExtendedItem.js', 'ExtendedArchetype']]) {
        const code = await bcSource(file);
        const start = code.indexOf(`const ${constant} =`);
        vm.runInContext(code.slice(start, code.indexOf('\n});', start) + 4), context);
    }
    const modular = await bcSource('Scripts/ModularItem.js');
    const chatStart = modular.indexOf('const ModularItemChatSetting =');
    vm.runInContext(modular.slice(chatStart, modular.indexOf('\n};', chatStart) + 3), context);
    // Only the pose-mapping constant is needed from the official asset definitions.
    const poseStart = definitions.indexOf('const AssetPoseMapping =');
    const poseEnd = definitions.indexOf('\n});', poseStart) + 4;
    vm.runInContext(definitions.slice(poseStart, poseEnd), context);
    const posesStart = definitions.indexOf('var PoseFemale3DCG =');
    vm.runInContext(definitions.slice(posesStart, definitions.indexOf('\n];', posesStart) + 3), context);
    const list = value => Array.isArray(value) ? value : [value];
    const manager = {
        addCustomAssetString(dialog) {
            // Reviewed editor/action text has no role in the portrait canvas.
            if (component !== 'src/components/束缚与玩具/尿袋.js' ||
                Object.values(dialog).some(entries=>!entries || typeof entries!=='object' ||
                    Object.values(entries).some(value=>typeof value!=='string'))) {
                throw new Error('Unreviewed custom dialog');
            }
        },
        addGroupedAssets(groups, translations = {}, extended = {}) {
            for (const [group, assets] of Object.entries(groups)) for (const definition of assets) {
                const translation = Object.fromEntries(Object.entries(translations).flatMap(([language, values]) =>
                    values[group]?.[definition.Name] === undefined ? [] : [[language, values[group][definition.Name]]]));
                this.addAssetWithConfig(group, definition, { translation, extended: extended[group]?.[definition.Name] });
            }
        },
        addGroupedAssetsWithConfig(groups, translations = {}, layerNames = {}) {
            for (const [group, assets] of Object.entries(groups)) for (const definition of assets) {
                const pick = entries => Object.fromEntries(Object.entries(entries).flatMap(([language, values]) =>
                    values[group]?.[definition.Name] === undefined ? [] : [[language, values[group][definition.Name]]]));
                this.addAssetWithConfig(group, definition, { translation: pick(translations), layerNames: pick(layerNames) });
            }
        },
        addAsset(group, definition, extended, translation, noMirror = false) {
            this.addAssetWithConfig(group, definition, { extended, translation, noMirror });
        },
        addGroup(definition, translation) { result.groups.push(definition); result.names.groups.push({ group: definition.Group, translation }); },
        addCopyGroup(group, from, translation, overrides = {}) {
            result.names.groups.push({ group, translation });
            result.mirrors.push({ group, from, overrides, clearHide: Object.hasOwn(overrides, 'Hide') && overrides.Hide === undefined });
        },
        addAssetWithConfig(groups, definition, config = {}) {
            if (definition === undefined && Array.isArray(groups)) {
                for (const args of groups) this.addAssetWithConfig(...args);
                return;
            }
            if (Array.isArray(definition)) {
                for (const [asset, settings] of definition) this.addAssetWithConfig(groups, asset, settings);
            } else for (const group of new Set(list(groups).flatMap(group => !config.noMirror && ['ItemTorso', 'ItemTorso2'].includes(group)
                ? ['ItemTorso', 'ItemTorso2'] : [group]))) {
                result.assets.push({ group, definition, extended: config.extended, ...(config.noMirror ? { noMirror: true } : {}) });
                result.names.assets.push({ group, name: definition.Name, translation: config.translation });
            }
        },
        addImageMapping(mapping) {
            // Editor thumbnails and option icons are never requested by the portrait renderer.
            for (const [key, value] of Object.entries(mapping)) {
                if (key.startsWith('Assets/Female3DCG/') && !key.includes('/Preview/')) result.imageMappings[key] = value;
            }
        },
    };
    const fixup = (groups, oldName, name) => {
        for (const group of new Set(list(groups).flatMap(group => group === 'ItemTorso' ? ['ItemTorso','ItemTorso2'] : [group]))) result.aliases.push({ group, oldName, name });
    };
    const imageMapTools = {
        assetLayer: (group, name, pose) => `Assets/Female3DCG/${group}/${pose ? pose + '/' : ''}${name}.png`,
        assetPreview: (group, name) => `Assets/Female3DCG/${group}/Preview/${name}.png`,
        assetOption: (group, name, option) => `Screens/Inventory/${group}/${name}/${option}.png`,
        // Pinned utils Tools/imageMapTools.js: body-size aliases preserve pose and type suffixes.
        mirrorBodyTypeLayer(groups, asset, from, targets, config) {
            const mappings = {};
            for (const layer of asset.Layer ?? []) {
                if (!['BodyUpper', 'BodyLower'].includes(layer.ParentGroup || asset.ParentGroup)) continue;
                const poseMap = layer.InheritPoseMappingFields ? { ...asset.PoseMapping, ...layer.PoseMapping }
                    : layer.PoseMapping ?? asset.PoseMapping ?? {};
                const poses = ['', ...new Set(context.PoseFemale3DCG.map(p => poseMap[p.Name]).filter(p => p && p !== 'Hide').map(p => `${p}/`))];
                const key = layer.CreateLayerTypes?.[0];
                let types = [];
                if (key && layer.AllowTypes) {
                    types = [...new Set(list(layer.AllowTypes).map(value => value[key]).filter(Boolean).flat())].map(value => `${key}${value}`);
                } else if (key && config?.Archetype === 'modular') {
                    types = config.Modules.filter(module => module.Key === key).flatMap(module => module.Options.map((_, i) => `${key}${i}`));
                } else if (key === 'typed' && config?.Archetype === 'typed') {
                    types = config.Options.map((_, i) => `typed${i}`);
                }
                if (!types.length) types = [''];
                for (const group of list(groups)) for (const pose of poses) for (const type of types) {
                    const file = size => `Assets/Female3DCG/${group}/${pose}${[asset.Name, size, type, layer.Name].filter(Boolean).join('_')}.png`;
                    for (const target of targets) mappings[file(target)] = file(from);
                }
            }
            return mappings;
        },
    };
    const tools = {
        makeButtonGroup(itemCount) {
            const perPage = Math.min(itemCount, 18);
            return {
                elementData: Array.from({length: itemCount}, (_, idx) => ({
                    position: [1135 + 250 * ((idx % perPage) % 3), 450 + 75 * Math.floor((idx % perPage) / 3)],
                    drawImage: false,
                })),
                itemsPerPage: perPage,
            };
        },
        AllItemGroups: (excepts = []) => ItemGroups.filter(name => !excepts.includes(name)),
        CommonChatTags: () => ['SourceCharacter', 'TargetCharacter', 'DestinationCharacter', 'AssetName'],
        topLeftBuilder(base, ...poses) {
            const result = { Left: { '': base.Left }, Top: { '': base.Top } };
            for (const [pose, position] of poses) for (const key of ['Left', 'Top']) {
                if (position[key] !== undefined) result[key][pose] = position[key];
            }
            return result;
        },
    };
    const fullMask = { push(groups, asset, layers) {
        for (const group of list(groups)) fullMasks.push({group, asset, layers});
    } };
    const synthetic = {
        '../功能调整/全身遮罩': { FullMask: fullMask },
        '../../功能调整/全身遮罩': { FullMask: fullMask },
        '@sugarch/bc-mod-utility': { PathTools: { emptyImage: 'Assets/Female3DCG/MobileBc/Empty.png' } },
        '@local/AssetManager': { AssetManager: manager },
        '@local/lib/fixups': {
            customFixup(rule) {
                if (Object.keys(rule).sort().join() !== 'New,Old' ||
                    [rule.Old,rule.New].some(item => !item || Object.keys(item).sort().join() !== 'Group,Name' ||
                        typeof item.Group !== 'string' || typeof item.Name !== 'string') || rule.Old.Group !== rule.New.Group) {
                    throw new Error('Unsupported custom migration');
                }
                result.aliases.push({group:rule.Old.Group,oldName:rule.Old.Name,name:rule.New.Name});
            },
            groupFixup: fixup,
            luziSuffixFixups: (groups, name) => fixup(groups, `${name}_Luzi`, name),
            luziPrefixFixups: (groups, name, oldName) => fixup(groups, oldName || `Luzi_${name}`, name),
        },
        // These two methods only configure UI previews and the known visibility alias.
        './身体组调整': { GroupConfig: { forceCharaPreview() {}, spHideAs() {} } },
        '@mod-utils/Tools': { ImageMapTools: imageMapTools, DialogTools, ColorTools, Tools: tools },
        '@mod-utils/index': { Tools: tools },
        '@mod-utils/Tools/imageMapTools': { ImageMapTools: imageMapTools },
    };
    let collarActivityHooks = 0;
    if (component === collarComponent) {
        if (tree.tree.find(e => e.path === component)?.sha !== '99e1163d4f8ea4a1b28697ef63a60385671f7619') {
            throw new Error('Collar activity source requires review');
        }
        // This pinned callback only restricts game activities; it never draws the collar.
        synthetic['@sugarch/bc-mod-hook-manager'] = { HookManager: {
            hookFunction(name, priority, callback) {
                if (name !== 'ActivityCheckPrerequisites' || priority !== 0 || typeof callback !== 'function') {
                    throw new Error('Unexpected collar activity hook');
                }
                collarActivityHooks++;
            },
        } };
    }
    async function module(name, referringModule) {
        if (name === './metaDraw' && ['src/components/衣服和装饰/替换身体/幽灵.js',androidBodyComponent].includes(referringModule?.identifier)) name = '@portrait/bodyDraw';
        if(name==='@mod-utils/monadic'&&component===androidBodyComponent) synthetic[name]={monadic};
        if (name === './type' && referringModule?.identifier === '@local/lib/lscg') name = '@local/lib/type';
        if (modules.has(name)) return modules.get(name);
        let instance;
        if (synthetic[name]) {
            const exports = synthetic[name];
            instance = new vm.SyntheticModule(Object.keys(exports), function () {
                for (const [key, value] of Object.entries(exports)) this.setExport(key, value);
            }, { context, identifier: name });
        } else {
            let code;
            if (name === 'src/components/束缚与玩具/刺激/电击器.js') {
                code = await source(name);
                code = 'import { AssetManager } from "@local/AssetManager"; import { DialogTools, Tools } from "@mod-utils/Tools"; import { PoseMapTool } from "@local/lib/generator"; import { PostPass } from "@local/lib/pass"; import { luziSuffixFixups } from "@local/lib/fixups";\n' +
                    code.slice(code.indexOf('const asset ='));
                // A portrait keeps the initial light phase and never runs shock timers or room events.
                code = code.replace('ScriptHooks: dialog.createHooks({ BeforeDraw: beforeDraw, ScriptDraw: scriptDraw }),', '')
                    .replace('{ Name: "闪光" }', '{ Name: "闪光", HasImage: false }');
                code += '\nAssetManager.addImageMapping({"Assets/Female3DCG/ItemLegs/LegsClosed/电击器_Normal_本体.png":"Assets/Female3DCG/ItemLegs/LegsClosed/电击器_Normal_电击器.png"});';
            } else if (name === 'src/components/束缚与玩具/互动辅助/拉紧的牵绳.js') {
                code = await source(name);
                // These invisible markers only alter room validation, not portrait drawing.
                code = code.replace('import { CustomValidate } from "@local/lib/load";', '')
                    .replace(/    for \(const g of groups\) \{\s+for \(const a of assetName\) \{\s+CustomValidate.remove\(g, a\);\s+\}\s+\}/, '');
            } else if (name === 'src/components/工具/外观工具.js') {
                context.AssetFemale3DCG = [...definitions.matchAll(/^\t\tGroup: "([^"]+)",([\s\S]*?)^\t\tAsset: \[/gm)]
                    .map(([, Group, header]) => ({ Group, Clothing: /^\t\tClothing: true,/m.test(header) }));
                code = await source(name);
                code = 'import { AssetManager } from "@local/AssetManager";\n' +
                    code.slice(code.indexOf('const groups ='), code.indexOf('function toggleHide(')) +
                    code.slice(code.indexOf('const extended ='));
                // Saved Hide and pose data drive portraits; editing and auto-pose callbacks change game state.
                code = code.replace('    ScriptHooks: { ScriptDraw: scriptDraw },\n', '')
                    .replace('                        ScriptHooks: itemDialog.createHooks(),\n', '');
            } else if (['行李箱','硬壳行李箱','宠物箱'].some(asset=>name===`src/components/束缚与玩具/包装/${asset}.js`)) {
                code=await source(name);
                const before=code.includes('function beforeDraw(') ? code.slice(code.indexOf('function beforeDraw('),code.indexOf('const assets =')) : '';
                // Single-character portraits retain item data/mappings, without room pairing or grab controls.
                code='import { DialogTools, ImageMapTools, Tools } from "@mod-utils/Tools"; import { AssetManager } from "@local/AssetManager";\n'+before+
                    code.slice(code.indexOf('const assets ='),code.indexOf('    SharedCenterModifier.addModifier('))+'}\n';
                code=code.replace('ScriptHooks: dialog.createHooks(),','').replace('ScriptHooks: dialog.createHooks({ BeforeDraw: beforeDraw }),','ScriptHooks: {BeforeDraw:beforeDraw},');
            } else if (name === 'src/components/束缚与玩具/手持物/玻璃杯饮料.js') {
                code = await source(name);
                code = code.replace('import { ActivityEvents } from "@sugarch/bc-event-handler";','');
                // Drinking changes game state, not the supplied portrait snapshot.
                code = code.slice(0,code.indexOf('ActivityEvents.on(')) + code.slice(code.indexOf('export default function'));
            } else if (name === '@local/lib/generator/drinks') {
                const file='src/lib/generator/drinks.js';
                if(tree.tree.find(e=>e.path===file)?.sha!=='12e04c2d7159988421e956993e018a429c685d23') throw new Error('Drink image mappings require review');
                code=await source(file);
                // The renderer owns image loading/redraw; retain the pure mappings.
                code='import { AssetManager } from "@local/AssetManager";\n'+code.slice(code.indexOf('const drinkList ='),code.indexOf('const hook ='));
            } else if (name === 'src/components/束缚与玩具/幸运贞操带.js') {
                // The reviewed hooks only update game statistics and draw the editor.
                // Keep the exact asset/modules/text, without constructing event watchers.
                code = await source(name);
                const data = code.slice(code.indexOf('const asset ='), code.indexOf('const tamperArea ='));
                const hooks = '    ScriptHooks: {\n        ScriptDraw: scriptDraw,\n        Draw: dialogDrawHook,\n    },\n';
                const normalized = data.replaceAll('\r\n','\n');
                if (!normalized.includes(hooks)) throw new Error('Fortune belt callbacks changed');
                code = 'import { AssetManager } from "@local/AssetManager"; import { Tools } from "@mod-utils/Tools";\n' +
                    normalized.replace(hooks,'') + code.slice(code.indexOf('const custom_dialogs ='),code.indexOf('export default function')) +
                    '\nexport default function () { AssetManager.addAssetWithConfig("ItemPelvis",asset,{layerNames,translation,extended,assetStrings}); }';
            } else if (name === stockingComponent) {
                if (tree.tree.find(e => e.path === name)?.sha !== 'd9db1e0d888f5fef297eaf28b9ecc4c1e472fe57') throw new Error('Stocking mask source requires review');
                code = await source(name);
                code = 'import { AssetManager } from "@local/AssetManager"; import { PoseMapTool } from "@local/lib/generator"; import { Layer } from "@local/lib/type";\n' +
                    code.slice(code.indexOf('const asset ='), code.indexOf('const armPMap =')) +
                    '\nexport default function () { AssetManager.addAssetWithConfig(asset); }';
            } else if (name === '@local/lib/generator') {
                code = 'export { PoseMapTool } from "@portrait/pose"; export { ArmMaskTool } from "@portrait/arm"; export { ExtendedTools } from "@portrait/extended"; export { SockLRTool } from "@portrait/sock";';
            } else if (name === '@local/lib/draw') {
                code = 'export { createAfterDrawProcess } from "@portrait/afterDraw"; export { PartsMask } from "@local/lib/draw/partsMask"; export { GLImageRenderer } from "@portrait/gl";';
                if (Object.hasOwn(rawBodyHooks,component)) code += 'export { registerDrawHook } from "@portrait/registerDraw";';
            } else if (name === '@portrait/registerDraw') {
                const file='src/lib/draw/index.js';
                if (tree.tree.find(e=>e.path===file)?.sha!=='69bd7b84130ab017e3f6dd7d94ceeee3fc6ad08d') throw new Error('Raw drawing registration requires review');
                code=await source(file);
                code='const Logger={warn(message){throw new Error(message);}};\n'+code.slice(code.indexOf('export function registerDrawHook('));
            } else if (name === '@local/lib/load') {
                code = 'export { createAfterDrawProcess } from "@portrait/afterDraw";';
            } else if (name === '@portrait/sock') {
                // The upstream canvas is a fixed opaque 250x1000 rectangle. Preserve
                // its layer construction, serving the identical mask from the APK.
                code = await source('src/lib/generator/sockLR.js');
                code = 'import { AssetManager } from "@local/AssetManager"; import { ImageMapTools } from "@mod-utils/Tools";\n' +
                    'const vp = { map(keys) { AssetManager.addImageMapping(Object.fromEntries([keys].flat().map(key => [key, "Assets/Female3DCG/MobileBc/HalfBlack.png"]))); } };\n' +
                    code.slice(code.indexOf('const topLeft ='));
            } else if (name === '@portrait/arm') {
                // Keep only upstream's pure layer/mapping construction. Image preloading
                // and cache hooks belong to the game loop and are unnecessary here.
                if(tree.tree.find(e=>e.path==='src/lib/generator/armMask.js')?.sha!=='ca200729f8348e50d56894d654616ec331d209ea') throw new Error('Arm mask pose repair requires review');
                code = await source('src/lib/generator/armMask.js');
                code = code.slice(code.indexOf('const bodySizes'), code.indexOf('const hook ='));
                code = code.replace(/for \(const size of bodySizes\) \{[\s\S]*?\n\}\n/, '');
                // Right masks explicitly have no pose variant in nameRecord and the pinned tree.
                code = code.replace('TapedHands: "TapedHands",', 'TapedHands: mode === "Right" ? "" : "TapedHands",');
                // Hand masks have a pinned TapedHands texture, but upstream omits its alias.
                const handMapping = 'ret[ImageMapTools.assetLayer(group, `${assetName}_${Name}`)] = ImageMapTools.assetLayer(argMaskGroup, Name);';
                code = code.replace(handMapping, handMapping + '\nret[ImageMapTools.assetLayer(group, `${assetName}_${Name}`, "TapedHands")] = maskTexPath(Name, undefined, "TapedHands");');
                code = 'import { AssetManager } from "@local/AssetManager"; import { ImageMapTools } from "@mod-utils/Tools";\n' +
                    code + '\nexport const ArmMaskTool = { createArmMaskForCloth, createArmMaskForGroupedCloth };';
            } else {
                const helpers = { '@portrait/pose': 'src/lib/generator/poseMapTool.js',
                    '@portrait/extended': 'src/lib/generator/extendedTools.js',
                    '@local/lib/type': 'src/lib/type.js', '@local/lib/pass': 'src/lib/pass.js', '@local/lib/lscg': 'src/lib/lscg.js',
                    '@local/lib/itemDialog': 'src/lib/itemDialog.js', '@portrait/afterDraw': 'src/lib/draw/afterDraw.js',
                    '@local/lib/draw/partsMask': 'src/lib/draw/partsMask.js', '@portrait/gl': 'src/lib/draw/draw.js',
                    '@portrait/bodyDraw': 'src/components/衣服和装饰/替换身体/metaDraw.js' };
                const file = name === component ? component : helpers[name];
                if (!file) throw new Error(`Unsupported dependency: ${name}`);
                code = await source(file);
            }
            instance = new vm.SourceTextModule(code, { context, identifier: name });
        }
        modules.set(name, instance);
        return instance;
    }
    const entry = await module(component);
    await entry.link(module);
    await entry.evaluate({ timeout: 1000 });
    modules.get('@local/lib/pass')?.namespace.PostPass.run();
    entry.namespace.default();
    if (Object.hasOwn(rawBodyHooks,component)) {
        const {name,hook}=rawBodyHooks[component];
        const record=result.assets.find(r=>r.group==='动物身体_Luzi' && r.definition.Name===name);
        const callback=context[`Assets动物身体_Luzi${name}${hook}`];
        if (result.assets.length!==1 || !record || record.extended || typeof callback!=='function' ||
            record.definition[`Dynamic${hook}`]!==true) throw new Error('Body raw drawing binding changed');
        record.extended={Archetype:'noarch',ScriptHooks:{[hook]:(_data,_original,draw)=>callback(draw)}};
        record.definition={...record.definition};
        delete record.definition[`Dynamic${hook}`];
    }
    if (component === collarComponent && collarActivityHooks !== 1) throw new Error('Missing collar activity hook');
    if (!result.assets.length && !groupFiles.includes(component)) throw new Error('No assets registered');
    if (Object.hasOwn(repairedSources, component)) {
        if (tree.tree.find(e => e.path === component)?.sha !== repairedSources[component]) throw new Error('Repaired source changed; review required');
        for (const record of result.assets) {
            if (record.definition.Name === '敞夹克') {
                // Back sleeves exist only in the three explicitly mapped behind-the-back poses.
                for (const layer of record.definition.Layer.filter(layer => layer.Name.endsWith('_D1B'))) {
                    layer.PoseMapping = { ...layer.PoseMapping, AllFours: 'Hide', Hogtied: 'Hide', TapedHands: 'Hide' };
                }
            }
            if (record.definition.Name === '托盘') {
                // Only the belt hides in full-body poses; other layers use the supplied root images.
                record.definition = { ...record.definition, PoseMapping: {} };
            }
            if (record.definition.Name === '脚趾甲' && record.group === 'BodyMarkings') {
                record.definition = { ...record.definition, ParentGroup: 'BodyLower' };
            }
            if (record.definition.Name === '把手') {
                for (const layer of record.definition.Layer.filter(layer => layer.Name.startsWith('af_'))) {
                    layer.PoseMapping = { '': 'Hide', ...Object.fromEntries(context.PoseFemale3DCG.map(pose => [pose.Name, 'Hide'])), AllFours: 'AllFours' };
                }
            }
        }
        if (component.endsWith('/围裙.js')) {
            for (const [key, value] of Object.entries(result.imageMappings)) {
                result.imageMappings[key.replace('/Cloth/', '/ClothAccessory/')] = value;
            }
        }
        if (component.endsWith('/全包.js')) {
            result.imageMappings['Assets/Female3DCG/ItemTorso/全包毛毯_Small_后.png'] = 'Assets/Female3DCG/ItemTorso/全包毛毯_Small_后 - 副本.png';
        }
        if (component.endsWith('/鬼手.js')) {
            result.imageMappings['Assets/Female3DCG/ItemArms/BackElbowTouch/鬼手_A_B2.png'] = 'Assets/Female3DCG/ItemArms/BackElbowTouch/鬼手A_B2.png';
        }
        if (component.endsWith('/尿袋.js')) {
            for (const layer of ['尿','袋','管','遮罩']) {
                result.imageMappings[`Assets/Female3DCG/ItemTorso/BaseLower/尿袋_${layer}.png`] = `Assets/Female3DCG/ItemTorso/尿袋_${layer}.png`;
            }
        }
        if (component === dollComponent) {
            for (const size of ['Small','Normal','Large','XLarge']) for (const color of ['White','Asian','Black']) {
                const prefix=`Assets/Female3DCG/动物身体_Luzi/Hogtied/人偶_${size}_${color}`;
                result.imageMappings[`${prefix}_上半身.png`]=`${prefix}_全身.png`;
            }
        }
    }
    if (Object.hasOwn(opacityEditorComponents, component)) {
        if (tree.tree.find(e => e.path === component)?.sha !== opacityEditorComponents[component]) throw new Error('Opacity editor source requires review');
        for (const record of result.assets) {
            const hooks = record.extended?.ScriptHooks;
            if (!hooks || Object.keys(hooks).sort().join() !== 'Draw,Exit,Init,Load' ||
                Object.entries(hooks).some(([key, hook]) => hook !== context['PropertyOpacity' + key])) throw new Error('Unexpected opacity editor hooks');
            record.extended = { ...record.extended };
            delete record.extended.ScriptHooks;
        }
    }
    if (Object.hasOwn(editorOnlyComponents, component)) {
        if (tree.tree.find(e => e.path === component)?.sha !== editorOnlyComponents[component]) {
            throw new Error('Editor-only source changed; callback review required');
        }
        for (const record of result.assets) {
            const hooks = record.extended?.ScriptHooks;
            const expectedHooks = component.endsWith('/手持物/玩偶.js') ? 'Click' : 'Click,Draw';
            if (!hooks || Object.keys(hooks).sort().join() !== expectedHooks ||
                Object.values(hooks).some(hook => typeof hook !== 'function')) {
                throw new Error('Unexpected editor-only hooks');
            }
            record.extended = { ...record.extended };
            delete record.extended.ScriptHooks;
        }
    }
    if (Object.hasOwn(actionOnlyComponents, component)) {
        if (tree.tree.find(e => e.path === component)?.sha !== actionOnlyComponents[component]) throw new Error('Action-only source changed; review required');
        const record = result.assets.find(record => record.group === 'ItemButt');
        const hooks = record?.extended?.ScriptHooks;
        if (typeof hooks?.PublishAction !== 'function') throw new Error('Missing reviewed action callback');
        delete hooks.PublishAction;
        if (!Object.keys(hooks).length) delete record.extended.ScriptHooks;
    }
    for (const adapter of supplement.adapters.filter(a => a.source === 'echo' && a.path === component)) {
        const records = result.assets.filter(r => r.definition.Name === adapter.name && adapter.groups.includes(r.group));
        if (!['BeforeDraw', 'AfterDraw', 'BeforeAfterDraw'].includes(adapter.mode) || records.length !== adapter.groups.length ||
            new Set(records.map(r => r.group)).size !== adapter.groups.length) {
            throw new Error(`Unsupported supplement binding: ${adapter.id}`);
        }
        for (const record of records) {
            const clone = value => Array.isArray(value) ? value.map(clone) : value && typeof value === 'object'
                ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)])) : value;
            record.extended = clone(record.extended);
            if (adapter.id === 'echo-tentacle-suit') {
                const option = record.extended.Modules?.[0]?.Options?.[2]?.ArchetypeConfig;
                if (option?.Archetype !== 'vibrating' || Object.keys(option.ScriptHooks ?? {}).join() !== 'ScriptDraw' ||
                    typeof option.ScriptHooks.ScriptDraw !== 'function') throw new Error('Tentacle animation hook changed');
                delete option.ScriptHooks;
            }
            for (const hookPath of adapter.hookPaths ?? [adapter.hookPath ?? []]) {
                const owner = hookPath.reduce((value, key) => value?.[key], record.extended);
                const hooks = owner?.ScriptHooks;
                if (adapter.id === 'echo-chocolate') {
                    if(Object.keys(hooks ?? {}).sort().join()!=='BeforeDraw,Click,Draw,ScriptDraw') throw new Error('Chocolate hooks changed');
                    for(const key of ['Click','Draw','ScriptDraw']) delete hooks[key];
                }
                if (adapter.id === 'echo-tray') {
                    if(Object.keys(hooks ?? {}).sort().join()!=='AfterDraw,Click,Draw') throw new Error('Tray hooks changed');
                    delete hooks.Click;
                    delete hooks.Draw;
                }
                if (adapter.id === 'echo-surveillance-robot') {
                    if (Object.keys(hooks ?? {}).sort().join() !== 'AfterDraw,BeforeDraw,ScriptDraw' || typeof hooks.ScriptDraw !== 'function') {
                        throw new Error('Surveillance robot hooks changed');
                    }
                    delete hooks.ScriptDraw;
                }
                if (adapter.id === 'echo-liquid-bag') {
                    if (Object.keys(hooks ?? {}).sort().join() !== 'AfterDraw,Click,Draw,ScriptDraw' ||
                        ['Click','Draw','ScriptDraw'].some(key=>typeof hooks[key] !== 'function')) {
                        throw new Error('Liquid bag hooks changed');
                    }
                    for (const key of ['Click','Draw','ScriptDraw']) delete hooks[key];
                }
                if (adapter.id === 'echo-one-bar-prison') {
                    if (Object.keys(hooks ?? {}).sort().join() !== 'BeforeDraw,ScriptDraw' || typeof hooks.ScriptDraw !== 'function') {
                        throw new Error('One-bar prison drawing hooks changed');
                    }
                    delete hooks.ScriptDraw;
                }
                if (['echo-evilfall-glass','echo-evilfall-mask','echo-cyber-shield'].includes(adapter.id)) {
                    if (Object.keys(hooks ?? {}).sort().join() !== 'AfterDraw,ScriptDraw' || typeof hooks.ScriptDraw !== 'function') {
                        throw new Error(`Animated light drawing hooks changed: ${adapter.id}`);
                    }
                    delete hooks.ScriptDraw;
                }
                if (['echo-gradient','echo-double-gradient'].includes(adapter.id)) {
                    if (Object.keys(hooks ?? {}).sort().join() !== 'AfterDraw,Click,Draw,Exit,Load') throw new Error('Gradient hooks changed');
                    for (const key of ['Click','Draw','Exit','Load']) delete hooks[key];
                }
                const modes = adapter.mode === 'BeforeAfterDraw' ? ['AfterDraw','BeforeDraw'] : [adapter.mode];
                if (!adapter.groups.includes(record.group) || record.definition.Name !== adapter.name ||
                    !hooks || Object.keys(hooks).sort().join() !== modes.join() || modes.some(mode=>typeof hooks[mode] !== 'function')) {
                    throw new Error(`Supplement hook mismatch: ${adapter.id}`);
                }
                // Replace only the reviewed callback with an identifier for packaged code.
                // Every other function still fails the data-only extraction below.
                delete owner.ScriptHooks;
            }
            if (adapter.id === 'echo-large-beads') {
                if (record.definition.DynamicBeforeDraw !== true) throw new Error('Missing bead drawing flag');
                record.definition = { ...record.definition };
                delete record.definition.DynamicBeforeDraw;
            }
            if (adapter.id === 'echo-surveillance-robot') {
                record.definition = { ...record.definition };
                for (const key of ['DynamicBeforeDraw','DynamicAfterDraw','DynamicScriptDraw']) {
                    if (record.definition[key] !== true) throw new Error('Robot drawing flag changed');
                    delete record.definition[key];
                }
            }
            if (adapter.id === 'echo-body-text') {
                if (record.definition.DynamicAfterDraw !== true) throw new Error('Missing body text draw flag');
                record.definition = { ...record.definition };
                delete record.definition.DynamicAfterDraw;
            }
            record.portraitAdapter = adapter.id;
        }
    }
    JSON.stringify(result, (_key, value) => {
        if (typeof value === 'function') throw new Error('Runtime callback');
        return value;
    });
    for (const { definition, extended } of result.assets) {
        if (definition.DynamicScriptDraw || definition.DynamicBeforeDraw || definition.DynamicAfterDraw || definition.CopyConfig) {
            throw new Error('Requires runtime drawing or copy configuration');
        }
        if (definition.Extended && !extended) throw new Error('Missing extended configuration');
    }
    const missingImages = [];
    for (const { group, definition } of result.assets) {
        const prefix = `resources/Assets/Female3DCG/${imageGroup(group, definition, result.groups)}/`;
        const mapped = Object.entries(result.imageMappings).some(([key,target]) =>
            ('resources/'+key).startsWith(prefix) && (path.basename(key,'.png')===definition.Name || path.basename(key).startsWith(definition.Name+'_')) &&
            tree.tree.some(entry=>entry.type==='blob' && entry.path==='resources/'+target));
        if (definition.Visible !== false && !(definition.Layer?.length && definition.Layer.every(layer => layer.HasImage === false)) && !tree.tree.some(entry => entry.path.startsWith(prefix) &&
            entry.path.endsWith('.png') && !entry.path.includes('/Preview/') &&
            (path.basename(entry.path, '.png') === definition.Name || path.basename(entry.path).startsWith(definition.Name + '_'))) && !mapped) {
            missingImages.push({group, name:definition.Name});
        }
    }
    if (missingImages.length) {
        const missing = (group,name) => missingImages.some(record=>record.group===group && record.name===name);
        result.assets = result.assets.filter(record=>!missing(record.group,record.definition.Name));
        result.names.assets = result.names.assets.filter(record=>!missing(record.group,record.name));
        result.aliases = result.aliases.filter(record=>!missing(record.group,record.name));
        if (!result.assets.length) throw new Error('No pinned images for ' + missingImages.map(r=>r.group+'/'+r.name).join(', '));
    }
    return { result, fullMasks: fullMasks.filter(work => !missingImages.some(record => record.group === work.group && record.name === work.asset)), missingImages };
}

for (const component of new Set([...groupFiles, ...candidates, ...propFiles])) {
    try {
        const { result, fullMasks, missingImages } = await extract(component);
        omittedAssets.push(...missingImages.map(record=>({component,...record,reason:"No pinned images"})));
        fullMaskWork.push(...fullMasks.map(work => ({...work, records:result.assets})));
        components[component] = { sha: tree.tree.find(e => e.path === component).sha, data: result };
        for (const field of ['groups', 'mirrors', 'assets', 'aliases']) catalog[field].push(...result[field]);
        Object.assign(catalog.imageMappings, result.imageMappings);
        catalog.sources.push(component);
        names.groups.push(...result.names.groups);
        names.assets.push(...result.names.assets);
    } catch (error) {
        if (component === stockingComponent || component === collarComponent || Object.hasOwn(repairedSources, component) || Object.hasOwn(editorOnlyComponents, component) || Object.hasOwn(actionOnlyComponents, component) || Object.hasOwn(opacityEditorComponents, component) || groupFiles.includes(component) || propFiles.includes(component) ||
            supplement.adapters.some(a => a.source === 'echo' && a.path === component)) throw error;
        rejected.push({ component, reason: error.message });
        components[component] = { sha: tree.tree.find(e => e.path === component).sha, reason: error.message };
    }
}
// These pinned Echo interaction markers have Visible:false and no drawing layers.
// Keep their asset identity without importing validation, leash or gameplay hooks.
const autoCloseComponent = 'src/components/工具/自动闭腿.js';
for (const [component, group, names] of [
    ['src/components/束缚与玩具/互动辅助/贴贴.js', 'ItemMisc', ['贴贴']],
    ['src/components/束缚与玩具/互动辅助/扛起来的麻袋.js', 'ItemMisc', ['扛起来的麻袋']],
    ['src/components/束缚与玩具/互动辅助/抓住.js', 'ItemMisc', ['抓住推车', '抓住行李箱', '抓住硬壳行李箱', '抓住宠物箱']],
    [autoCloseComponent, '外观工具', ['自动闭腿']],
]) {
    if (component === autoCloseComponent && tree.tree.find(e => e.path === component)?.sha !== 'f36f9d0e5595c1ab174e91bdebe6d0446ef7de01') {
        throw new Error('Invisible pose tool requires review');
    }
    await source(component);
    catalog.sources.push(component);
    const records = names.map(Name => ({ group, definition: {
        Name, Random: false, Visible: false, Value: -1, ...(Name === '贴贴' ? { Effect: ['Leash'] } : {}),
    } }));
    catalog.assets.push(...records);
    components[component] = { sha: tree.tree.find(e => e.path === component).sha,
        data: { groups: [], mirrors: [], aliases: [], imageMappings: {}, assets: records, names: { groups: [], assets: [] } } };
    const rejectedIndex = rejected.findIndex(entry => entry.component === component);
    if (rejectedIndex >= 0) rejected.splice(rejectedIndex, 1);
}
// Resolve after all groups exist, then keep only ordinary alpha-mask data in the pack.
const allGroups = [...new Set([...Object.keys(nativeAssets), ...catalog.groups.map(g=>g.Group), ...catalog.mirrors.map(m=>m.group)])];
for (const {group, asset, layers, records} of fullMaskWork) {
    const record = records.find(r=>r.group===group && r.definition.Name===asset);
    if (!record) throw new Error('FullMask asset is missing: ' + group + '/' + asset);
    record.definition = structuredClone(record.definition);
    for (const layer of record.definition.Layer ?? []) {
        if (layers && !layers.includes(layer.Name)) continue;
        for (const alpha of layer.Alpha ?? []) alpha.Group = allGroups.filter(name=>name!==group);
    }
}
// Echo also replaces the images of existing BC assets without changing their names.
const prefixes = catalog.assets.map(({ group, definition }) => [imageGroup(group, definition, catalog.groups), definition.Name]);
for (const [group, names] of Object.entries(nativeAssets)) {
    for (const name of names) prefixes.push([groupImageSources.get(group) || group, name]);
}
for (const group of catalog.groups) for (const asset of group.Asset) prefixes.push([group.Group, asset.Name]);
const images = {};
const mappedImages = new Set(Object.values(catalog.imageMappings));
for (const entry of tree.tree) {
    const resource = entry.path.slice('resources/'.length);
    if (entry.type !== 'blob' || !(entry.path.startsWith('resources/Assets/Female3DCG/') || (entry.path.startsWith('resources/Icons/') && mappedImages.has(resource))) || !entry.path.endsWith('.png') || entry.path.includes('/Preview/')) continue;
    const group = resource.split('/')[2];
    const filename = path.basename(resource, '.png');
    if (mappedImages.has(resource) || group === 'LuziArmMask' || prefixes.some(([g, name]) => g === group && (name === '' || filename === name || filename.startsWith(name + '_')))) {
        images[resource] = entry.sha;
    }
}
catalog.images = Object.keys(images);
// Use the same package/catalog contract as the renderer before writing any output.
// This context only evaluates our packaged adapter code; it performs no network IO.
const supplementContext = vm.createContext({ GameVersion: 'R133',
    fetch: async () => ({ ok: true, json: async () => supplement }) });
vm.runInContext(await fs.readFile('app/src/main/assets/portrait/supplement/drawing.js', 'utf8'), supplementContext);
vm.runInContext(await fs.readFile('app/src/main/assets/portrait/supplement/body-drawing.js', 'utf8'), supplementContext);
vm.runInContext(await fs.readFile('app/src/main/assets/portrait/supplement/adapters.js', 'utf8'), supplementContext);
await supplementContext.MobileBcSupplement.prepare(bcManifest, catalog);
const namesOutput = 'domain/bc-text/src/main/resources/echo';
await fs.mkdir(namesOutput, { recursive: true });
await fs.writeFile(namesOutput + '/names.json', JSON.stringify({ ...names, mirrors: catalog.mirrors.map(({ group, from }) => ({ group, from })), aliases: catalog.aliases }, null, 2) + '\n');
await fs.writeFile(namesOutput + '/LICENSE.txt', await source('LICENSE'));
await fs.writeFile(namesOutput + '/LICENSE-APPENDIX.md', await source('LICENSE-APPENDIX.md'));
if (process.argv.includes('--names-only')) { console.log('Generated Echo names: ' + names.assets.length); process.exit(0); }
await fs.mkdir(output, { recursive: true });
// One record per line keeps the generated catalog reviewable without expanding
// thousands of nested layer properties into a very large diff.
const format = object => '{\n' + Object.entries(object).map(([key, value]) => {
    const encoded = Array.isArray(value) ? '[\n' + value.map(item => '    ' + JSON.stringify(item)).join(',\n') + '\n  ]'
        : value && typeof value === 'object' ? '{\n' + Object.entries(value).map(([k, v]) => '    ' + JSON.stringify(k) + ': ' + JSON.stringify(v)).join(',\n') + '\n  }'
        : JSON.stringify(value);
    return '  ' + JSON.stringify(key) + ': ' + encoded;
}).join(',\n') + '\n}\n';
await fs.writeFile(`${output}/catalog.json`, format(catalog));
await fs.writeFile(`${output}/images.json`, format({ revision, images }));
const dependencies = [fullMaskComponent, 'LICENSE', 'LICENSE-APPENDIX.md', 'src/lib/generator/armMask.js', 'src/lib/generator/sockLR.js', 'src/lib/draw/index.js', 'src/lib/draw/afterDraw.js', 'src/lib/draw/partsMask.js', 'src/lib/draw/draw.js',
    'src/lib/generator/poseMapTool.js', 'src/lib/generator/extendedTools.js', 'src/lib/type.js', 'src/lib/pass.js', 'src/lib/itemDialog.js',
    'src/assetForward.js', 'src/lib/fixups.js', 'src/lib/lscg.js', 'src/lib/generator/drinks.js', 'src/components/衣服和装饰/替换身体/metaDraw.js', 'utils', 'package.json', 'pnpm-lock.yaml'];
await fs.writeFile(`${output}/source-index.json`, format({ format: 1, revision, components,
    dependencies: Object.fromEntries(dependencies.map(p => {
        const sha = tree.tree.find(e => e.path === p)?.sha;
        if (!sha) throw new Error(`Missing extraction dependency: ${p}`);
        return [p, sha];
    })),
    required: [...groupFiles, ...propFiles, stockingComponent, collarComponent, autoCloseComponent, ...Object.keys(repairedSources), ...Object.keys(editorOnlyComponents), ...Object.keys(actionOnlyComponents), ...Object.keys(opacityEditorComponents), ...Object.keys(components).filter(p => p.includes('/互动辅助/')),
        ...supplement.adapters.filter(a => a.source === 'echo').map(a => a.path)],
    excludedNames: [], assetSourceRoots, excludedSourcePrefixes: [], imageGroups: Object.fromEntries(groupImageSources), nativeAssets,
}));
await fs.writeFile(`${output}/LICENSE.txt`, await source('LICENSE'));
await fs.writeFile(`${output}/LICENSE-APPENDIX.md`, await source('LICENSE-APPENDIX.md'));
await fs.writeFile('app/src/main/assets/portrait/supplement/manifest.json',
    supplementText.replace(/("echoRevision"\s*:\s*")[a-f0-9]{40}("\s*,)/, `$1${revision}$2`));
console.log(JSON.stringify({ components: catalog.sources.length, assets: catalog.assets.length, mirrors: catalog.mirrors.length, images: catalog.images.length, rejected, omittedAssets }, null, 2));
