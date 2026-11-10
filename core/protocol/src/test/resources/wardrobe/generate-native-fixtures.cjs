// Run with: node generate-native-fixtures.cjs <R129/BondageClub directory>
// Only the unmodified native functions named below are executed.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const nativeRoot = process.argv[2];
if (!nativeRoot) throw new Error("Pass the R129/BondageClub directory as the first argument.");

const context = vm.createContext({
  CommonIsArray: Array.isArray,
  CommonIsObject: value => value !== null && typeof value === "object" && !Array.isArray(value),
  console,
  Asset: [],
});
vm.runInContext(fs.readFileSync(path.join(nativeRoot, "Scripts/lib/LZString.js"), "utf8"), context);

function loadNativeFunction(relativePath, functionName) {
  const source = fs.readFileSync(path.join(nativeRoot, relativePath), "utf8");
  const match = source.match(new RegExp(`^function ${functionName}\\([^]*?^\\}`, "m"));
  if (!match) throw new Error(`Cannot locate ${functionName} in ${relativePath}.`);
  vm.runInContext(match[0], context);
}

loadNativeFunction("Scripts/Character.js", "CharacterCompressWardrobe");
loadNativeFunction("Scripts/Character.js", "CharacterDecompressWardrobe");
loadNativeFunction("Screens/Character/Wardrobe/Wardrobe.js", "WardrobeAssetBundle");
loadNativeFunction("Scripts/Inventory.js", "InventoryDataBuild");
loadNativeFunction("Scripts/Inventory.js", "InventoryLoadCompressedData");

const codeUnits = value => Array.from({ length: value.length }, (_, index) => value.charCodeAt(index));
const sourceAppearance = [
  {
    Asset: { Name: "StarDress", Group: { Name: "Cloth" } },
    Color: ["#FE80AE", "Default", "#abcdef"],
    Property: {
      Expression: "Smile",
      TypeRecord: { button: 1 },
      Future: { label: "星空💫", values: [false, null, { Color: "#123456" }] },
    },
    Craft: { Name: "Personal dress", MemberNumber: 42 },
    Difficulty: 17,
  },
  {
    Asset: { Name: "Eyes1", Group: { Name: "Eyes" } },
    Color: "Default",
    Property: { Expression: "Closed" },
  },
  {
    Asset: { Name: "FrontHair1", Group: { Name: "HairFront" } },
    Color: ["#334455", "#778899"],
    Property: {},
  },
  { Asset: { Name: "Normal", Group: { Name: "BodyUpper" } } },
];
const legacyAppearance = sourceAppearance.map(item => context.WardrobeAssetBundle(item));
const nativeWardrobe = Array.from({ length: 24 }, (_, index) => index === 0 ? legacyAppearance : null);
const compressed = context.CharacterCompressWardrobe(nativeWardrobe);

const legacyWithMetadata = [[
  {
    Name: "Shirt", Group: "Cloth", Color: ["#123456", "Default"],
    Property: { Future: { tags: ["夜", "💫"] } },
    Craft: { Name: "Ignored native metadata" }, Difficulty: 12,
  },
], null, []];
const compressedLegacy = context.CharacterCompressWardrobe(legacyWithMetadata);

const inventoryIds = [1, 101, 102, 103, 104, 1000, 55357];
context.Asset = inventoryIds.map(id => ({ InventoryID: id, Name: `Asset${id}`, Group: { Name: "Cloth" } }));
const inventoryData = context.InventoryDataBuild({ Inventory: context.Asset.map(Asset => ({ Asset })) });
const inventoryTuples = [["Shirt", "Cloth"], ["星空💫", "ClothAccessory"]];
const inventoryCompressed = context.LZString.compressToUTF16(JSON.stringify(inventoryTuples));

const fixture = {
  nativeRelease: "R129",
  functions: [
    "CharacterCompressWardrobe", "CharacterDecompressWardrobe", "WardrobeAssetBundle",
    "InventoryDataBuild", "InventoryLoadCompressedData",
  ],
  savedAppearance: {
    sourceAppearance,
    legacyAppearance,
    compressedCodeUnits: codeUnits(compressed),
    compactWardrobe: JSON.parse(context.LZString.decompressFromUTF16(compressed)),
    decompressedWardrobe: context.CharacterDecompressWardrobe(compressed),
  },
  legacyWithMetadata: {
    legacyWardrobe: legacyWithMetadata,
    compressedCodeUnits: codeUnits(compressedLegacy),
    compactWardrobe: JSON.parse(context.LZString.decompressFromUTF16(compressedLegacy)),
    decompressedWardrobe: context.CharacterDecompressWardrobe(compressedLegacy),
  },
  inventory: {
    dataCodeUnits: codeUnits(inventoryData),
    ids: inventoryIds,
    nativeItems: context.InventoryLoadCompressedData(inventoryData),
    compressedLegacyCodeUnits: codeUnits(inventoryCompressed),
    legacyTuples: inventoryTuples,
  },
};
fs.writeFileSync(path.join(__dirname, "r129-native-wardrobe.json"), JSON.stringify(fixture, null, 2) + "\n");
