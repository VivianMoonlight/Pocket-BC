// Usage: node generate-wardrobe-r129.cjs <R129/BondageClub> [output-directory]
// Runs the native catalog builders offline. Extended item callbacks and descriptions are not needed
// for these fields. The fixture preserves every group/asset and the original CopyConfig order,
// projecting only wardrobe metadata and base effects from Female3DCG.js.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = path.resolve(process.argv[2]);
const output = path.resolve(process.argv[3] || __dirname);
const warnings = [];
const context = vm.createContext({ console: { log() {}, warn: (...args) => warnings.push(args), error: (...args) => warnings.push(args) } });
for (const file of ["Scripts/Common.js", "Scripts/Pose.js", "Scripts/Asset.js", "Assets/Female3DCG/Female3DCG.js"]) {
    vm.runInContext(fs.readFileSync(path.join(source, file), "utf8"), context, { filename: file, timeout: 10000 });
}
const fixture = vm.runInContext(`(() => {
    const pick = (object, fields) => Object.fromEntries(fields.filter(field => field in object).map(field => [field, object[field]]));
    const groupFields = ["Group", "Category", "Clothing", "AllowNone", "MirrorGroup", "Color", "BodyCosplay", "Underwear", "AllowCustomize", "AllowColorize", "Default", "Effect"];
    const assetFields = ["Name", "Value", "InventoryID", "BuyGroup", "CopyConfig", "AvailableLocations", "Enable", "BodyCosplay", "AllowColorize", "DefaultColor", "Effect", "AllowEffect"];
    const layerFields = ["Name", "AllowColorize", "CopyLayerColor", "TextureMask"];
    return AssetFemale3DCG.map(group => ({ ...pick(group, groupFields), Asset: group.Asset.map(asset => {
        if (typeof asset === "string") return asset;
        const result = pick(asset, assetFields);
        if (asset.Layer) result.Layer = asset.Layer.map(layer => pick(layer, layerFields));
        return result;
    }) }));
})()`, context);

// AssetLoadAll pose initialization, followed by AssetLoad's three native passes without its
// final AssetLoadDescription call (which would request translation files).
vm.runInContext(`
Pose = PoseFemale3DCG;
Pose.forEach(p => PoseRecord[p.Name] = p);
const assetDefsParsed = CommonFromEntries(AssetFemale3DCG.map(g => [g.Group, CommonFromEntries(g.Asset.map(a =>
    typeof a === "string" ? [a, { Name: a }] : [a.Name, a]
))]));
for (const [groupName, assetDefs] of CommonEntries(assetDefsParsed)) {
    for (const [assetName, assetDef] of Object.entries(assetDefs)) {
        const resolved = AssetResolveCopyConfig.AssetDefinition(assetDef, groupName, assetDefsParsed);
        if (resolved == null) delete assetDefs[assetName];
        else assetDefs[assetName] = resolved;
    }
}
for (const groupDef of AssetFemale3DCG) {
    const group = AssetGroupAdd("Female3DCG", groupDef);
    for (const assetDef of Object.values(assetDefsParsed[group.Name])) AssetAdd(group, assetDef, undefined, groupDef);
}`, context, { timeout: 10000 });
if (warnings.length) throw new Error(JSON.stringify(warnings));
const rows = vm.runInContext(`[
    ...AssetGroup.map(g => ["G", g.Name, g.Category, g.Clothing, g.AllowNone, g.MirrorGroup || "<null>", g.Asset[0]?.Name ?? "<null>", g.ColorSchema.join(","), g.DefaultColor, g.BodyCosplay, g.Underwear, g.AllowCustomize, g.AllowColorize, g.IsDefault, [...new Set(g.Effect)].join(",")]),
    ...Asset.map(a => ["A", a.Group.Name, a.Name, a.Value, a.BuyGroup ?? "<null>", a.AvailableLocations.join(","), a.Enable, a.BodyCosplay, a.DefaultColor.join(","), a.InventoryID ?? "<null>", [...new Set(a.Effect)].join(",")]),
]`, context);
if (rows.length !== 85 + 2411) throw new Error(`Unexpected catalog size: ${rows.length}`);
fs.writeFileSync(path.join(output, "WardrobeCatalog-R129.js"), "// Native R129 wardrobe metadata projection; regenerate with generate-wardrobe-r129.cjs.\nvar AssetFemale3DCG = " + JSON.stringify(fixture, null, 2) + ";\n");
fs.writeFileSync(path.join(output, "WardrobeMetadata-R129.tsv"), "# Native AssetGroupAdd/AssetAdd oracle: 85 groups, 2411 assets.\n" + rows.map(row => row.join("\t")).join("\n") + "\n");
console.log(JSON.stringify({ groups: 85, assets: 2411, warnings: warnings.length }));
