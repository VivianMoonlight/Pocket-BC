"use strict";

// Rendering-only adapter for the pinned Echo data snapshot. No mod boot, SDK hooks,
// character mutations, activities or sockets are installed.
var MobileBcEcho = (() => {
    let aliases = new Map();
    let revision = null;

    async function prepare(scripts) {
        const response = await fetch("/portrait/echo/catalog.json");
        if (!response.ok) throw new Error("Missing Echo portrait catalog");
        const catalog = await response.json();
        revision = catalog.revision;
        await MobileBcSupplement.prepare(scripts, catalog);
        const definitions = new Map(AssetFemale3DCG.map(group => [group.Group, group]));
        for (const group of catalog.groups) {
            if (definitions.has(group.Group)) throw new Error(`Duplicate Echo group: ${group.Group}`);
            definitions.set(group.Group, group);
            AssetFemale3DCG.push(group);
        }
        const mirrorNames = new Set(catalog.mirrors.map(m => m.group));
        function addAsset(record) {
            const { group, definition } = record;
            const extended = MobileBcSupplement.extended(record);
            const groupDef = definitions.get(group);
            if (!groupDef) throw new Error(`Unknown Echo group: ${group}`);
            if (groupDef.Asset.some(asset => (typeof asset === "string" ? asset : asset.Name) === definition.Name)) {
                throw new Error(`Echo asset conflicts with BC: ${group}/${definition.Name}`);
            }
            groupDef.Asset.push(definition);
            if (extended) {
                AssetFemale3DCGExtended[group] ??= {};
                AssetFemale3DCGExtended[group][definition.Name] = extended;
            }
        }
        for (const record of catalog.assets.filter(r => !mirrorNames.has(r.group))) addAsset(record);
        for (const { group, from, overrides, clearHide } of catalog.mirrors) {
            const source = definitions.get(from);
            if (!source || definitions.has(group)) throw new Error(`Invalid Echo mirror: ${group}`);
            const privateNames = new Set(catalog.assets.filter(r => r.group === from && r.noMirror).map(r => r.definition.Name));
            const inherited = source.Asset.filter(asset => !privateNames.has(typeof asset === 'string' ? asset : asset.Name));
            const copy = { ...source, ...overrides, Group: group, Default: false, Random: false,
                DynamicGroupName: source.DynamicGroupName || from,
                Asset: overrides.Asset ?? inherited.map(asset => ({ ...(typeof asset === 'string' ? { Name: asset } : asset), Value: 0 })) };
            if (clearHide) delete copy.Hide;
            definitions.set(group, copy);
            AssetFemale3DCG.push(copy);
            AssetFemale3DCGExtended[group] = Object.fromEntries(Object.entries(AssetFemale3DCGExtended[from] ?? {}).filter(([name]) => !privateNames.has(name)));
            for (const asset of copy.Asset) {
                const name = typeof asset === "string" ? asset : asset.Name;
                for (const callback of ["BeforeDraw", "AfterDraw"]) {
                    const original = window[`Assets${from}${name}${callback}`];
                    if (original) window[`Assets${group}${name}${callback}`] = original;
                }
            }
            for (const record of catalog.assets.filter(r => r.group === group)) addAsset(record);
        }
        for (const { group, oldName, name } of catalog.aliases) {
            aliases.set(`${group}/${oldName}`, name);
            for (const mirror of catalog.mirrors.filter(mirror => mirror.from === group)) {
                aliases.set(`${mirror.group}/${oldName}`, name);
            }
        }
        const images = new Set(catalog.images);
        const generatedMask = MobileBcInstallStockingMasks(catalog, DrawImageCache);
        const getImage = DrawImageCache.get.bind(DrawImageCache);
        DrawImageCache.get = (url, ...args) => {
            const generated = generatedMask(url);
            if (generated) return generated;
            const mapped = catalog.imageMappings[url] ?? url;
            const source = mapped === 'Assets/Female3DCG/MobileBc/HalfBlack.png'
                ? '/portrait/supplement/half-black.png' : mapped === 'Assets/Female3DCG/MobileBc/Empty.png'
                ? '/portrait/supplement/empty.png' : images.has(mapped)
                ? `/echo/${catalog.revision}/${mapped.split('/').map(encodeURIComponent).join('/')}` : mapped;
            return getImage(source, ...args);
        };
        // Echo's hair overlays follow the original hair visibility rules without
        // letting their own Hide property hide the overlay itself.
        const visible = CharacterAppearanceVisible;
        const hairGroups = { "新前发_Luzi": "HairFront", "新后发_Luzi": "HairBack" };
        CharacterAppearanceVisible = (character, name, group, ...args) => {
            const source = hairGroups[group];
            if (!source) return visible(character, name, group, ...args);
            const original = character.DrawAppearance;
            try {
                character.DrawAppearance = original.filter(item => item.Asset.Group.Name !== group);
                return visible(character, name, source, ...args);
            } finally { character.DrawAppearance = original; }
        };
    }

    function asset(group, name) {
        return AssetGet("Female3DCG", group, aliases.get(`${group}/${name}`) ?? name);
    }
    return { prepare, asset, get revision() { return revision; } };
})();
