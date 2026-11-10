"use strict";
// Reviewed Echo body compositing (CC BY-NC-SA 4.0); see ../echo/NOTICE.md.
// Keep blink buffers separate, reset compositing state, and restore the host canvas on failure.
var MobileBcBodyDrawing = (() => {
const Type = {groups: groups => groups};
const Tools = {getAssetURL: MobileBcAssetImageUrl};
const ImageMapTools = {assetLayer:(group,name,pose)=>`Assets/Female3DCG/${group}/${pose?pose+"/":""}${name}.png`};
/**
 * @typedef {object} PartialDrawCanvasCache
 * @property {HTMLCanvasElement} [DrawCanvas]
 * @property {HTMLCanvasElement} [Canvas]
 * @property {HTMLCanvasElement} [CanvasBlink]
 */

/**
 * @typedef {object} AdjustCanvasAlphaCache
 * @property {HTMLCanvasElement} [AdjustedCanvas]
 */

/**
 * 通用缓存数据容器基类
 * @template {object} T
 */
class CacheData {
    constructor() {
        /** @type {Record<string, T>} */
        this.storage = {};
    }

    /**
     * @param {string} key
     * @returns {T}
     */
    get(key) {
        return (this.storage[key] ??= /** @type {T} */ ({}));
    }
}

/**
 * @extends {CacheData<PartialDrawCanvasCache>}
 */
class PartialDrawCanvasCacheData extends CacheData {}

/**
 * @extends {CacheData<AdjustCanvasAlphaCache>}
 */
class AdjustedCanvasCacheData extends CacheData {}

/** @type {(canvas: HTMLCanvasElement) => void} */
const clear = (canvas) => {
    const ctx = canvas.getContext("2d");
    ctx.globalCompositeOperation = "source-over";
    ctx.globalAlpha = 1;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
};

/**
 * 绘制其他身体部位
 * @param {Character} C
 * @param {Asset} TempCanvasAsset
 * @param {CustomGroupName[]} DrawGroupNames
 * @param {PartialDrawCanvasCache} cache
 * @param {(arg: {Canvas: HTMLCanvasElement, CanvasBlink: HTMLCanvasElement}) => void} [beforeDraw]
 */
function partialDraw(C, TempCanvasAsset, DrawGroupNames, cache, beforeDraw) {
    cache.DrawCanvas ??= AnimationGenerateTempCanvas(C, TempCanvasAsset, CanvasDrawWidth, CanvasDrawHeight);
    cache.Canvas ??= AnimationGenerateTempCanvas(C, TempCanvasAsset, CanvasDrawWidth, CanvasDrawHeight);
    cache.CanvasBlink ??= AnimationGenerateTempCanvas(C, TempCanvasAsset, CanvasDrawWidth, CanvasDrawHeight);

    const oldDrawCanvas = GLDrawCanvas;
    GLDrawCanvas = cache.DrawCanvas;
    try {

    /** @type {Character} */
    const copyC = {
        ...C,
        CharacterID: "npc-partial-draw",
        Appearance: C.Appearance.filter((a) => DrawGroupNames.includes(a.Asset.Group.Name)),
    };

    copyC.Canvas = cache.Canvas;
    copyC.CanvasBlink = cache.CanvasBlink;

    clear(copyC.Canvas);
    clear(copyC.CanvasBlink);

    if (beforeDraw) beforeDraw({ Canvas: copyC.Canvas, CanvasBlink: copyC.CanvasBlink });

    copyC.DrawAppearance = AppearanceItemParse(CharacterAppearanceStringify(copyC));
    copyC.AppearanceLayers = CharacterAppearanceSortLayers(copyC);
    copyC.Appearance = C.Appearance;

    CharacterAppearanceBuildCanvas(copyC);

    return { Canvas: copyC.Canvas, CanvasBlink: copyC.CanvasBlink };
    } finally { GLDrawCanvas = oldDrawCanvas; }
}

/**
 * 调整 Canvas 的透明度
 * @param {Character} c
 * @param {Asset} asset
 * @param {HTMLCanvasElement} canvas - 原始 Canvas
 * @param {number} alphaMultiplier - 透明度乘数（0 到 1）
 * @param {AdjustCanvasAlphaCache} cache - 用于存储调整后的 Canvas
 * @returns {HTMLCanvasElement} - 调整后的 Canvas
 */
function adjustCanvasAlpha(c, asset, canvas, alphaMultiplier, cache) {
    // 创建一个新的 Canvas
    cache.AdjustedCanvas ??= AnimationGenerateTempCanvas(c, asset, canvas.width, canvas.height);

    clear(cache.AdjustedCanvas);

    const context = cache.AdjustedCanvas.getContext("2d");

    // 设置透明度并绘制原始 Canvas
    context.globalAlpha = alphaMultiplier;
    context.drawImage(canvas, 0, 0);

    return cache.AdjustedCanvas;
}

const mergingClothes = Type.groups([
    "Bra",
    "Panties",
    "Suit",
    "SuitLower",
    "Bra_笨笨蛋Luzi",
    "Panties_笨笨蛋Luzi",
    "Suit_笨笨蛋Luzi",
    "SuitLower_笨笨蛋Luzi",
    "Socks",
    "SocksLeft",
    "SocksRight",
    "Corset",
    "Garters",
]);

/** @type {(AssetLayerDefinition & {PGroups:CustomGroupName[], PGShow?:boolean})[]} */
const baseBodyLayers = [
    {
        Name: "HairBack",
        PGroups: ["HairBack", "新后发_Luzi"],
        AllowTypes: { h: 0 },
        Priority: 5,
    },
    {
        Name: "Head",
        PGroups: ["Head"],
        AllowTypes: { h: 0 },
        Priority: 7,
    },
    {
        Name: "HairFront",
        PGroups: ["HairFront", "新前发_Luzi"],
        AllowTypes: { h: 0 },
        Priority: 52,
    },
    {
        Name: "Head2",
        PGroups: ["HairBack", "新后发_Luzi", "Head", "HairFront", "新前发_Luzi"],
        AllowTypes: { h: 1 },
        Priority: 7,
    },
    {
        Name: "Body",
        PGroups: ["BodyUpper", "BodyLower", "ArmsLeft", "ArmsRight"],
        AllowTypes: { h: [0, 1] },
        Priority: 7,
    },
    {
        Name: "Eyes",
        PGroups: ["Eyebrows", "Eyes", "右眼_Luzi", "Eyes2", "左眼_Luzi"],
        AllowTypes: { h: [0, 1] },
        Priority: 9,
    },
    {
        Name: "Hands",
        PGroups: ["HandsLeft", "HandsRight"],
        Priority: 27,
    },
    {
        Name: "Mouth",
        PGroups: ["Mouth"],
        AllowTypes: { h: [0, 1] },
        Priority: 10,
    },
    {
        Name: "Nipples",
        PGroups: ["Nipples"],
        AllowTypes: { h: [0, 1] },
        Priority: 11,
    },
    {
        Name: "Pussy",
        PGroups: ["Pussy"],
        AllowTypes: { h: [0, 1] },
        Priority: 12,
    },
    {
        Name: "Ears",
        PGroups: ["HairAccessory2"],
        PGShow: true,
        AllowTypes: { et: 1 },
        Priority: 53,
    },
    {
        Name: "Tail",
        PGroups: ["TailStraps"],
        PGShow: true,
        AllowTypes: { et: 1 },
        Priority: 4,
    },
];

const fullGroups = baseBodyLayers
    .filter((l) => !l.PGShow)
    .map((l) => l.PGroups)
    .flat();

const noHandsGroups = fullGroups.filter((g) => !["HandsLeft", "HandsRight"].includes(g));

const bodyLayers = baseBodyLayers.concat([
    {
        Name: "FullBody",
        PGroups: noHandsGroups,
        AllowTypes: { h: 2 },
        Priority: 12,
    },
    {
        Name: "FullBody2",
        PGroups: [...noHandsGroups, ...mergingClothes],
        AllowTypes: { h: 3 },
        Priority: 15,
    },
]);

const bodyLayerNames = new Map(bodyLayers.map((layer) => [layer.Name, layer]));

/**
 * @param {string} layerName
 * @returns {layerName is string}
 */
function isBodyLayer(layerName) {
    return bodyLayerNames.has(layerName);
}

/**
 * @param {HTMLCanvasElement} canvas
 * @param {string} color
 */
function clippingMask(canvas, color) {
    const ctx = canvas.getContext("2d");
    ctx.globalCompositeOperation = "source-in";
    ctx.fillStyle = color;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
}

/**
 * @typedef {AnimationPersistentData & {adjustedCache: AdjustedCanvasCacheData, partialCache: PartialDrawCanvasCacheData}} PersistentDataType
 */

/** @type {ExtendedItemScriptHookCallbacks.AfterDraw<ModularItemData, PersistentDataType>} */
function afterDraw(mData, originalFunction, drawData) {
    const { C, A, X, Y, Color, drawCanvas, drawCanvasBlink, AlphaMasks, L, Property, PersistentData } = drawData;
    const data = PersistentData();
    data.adjustedCache ??= new AdjustedCanvasCacheData();
    data.partialCache ??= new PartialDrawCanvasCacheData();

    const layerDef = bodyLayerNames.get(L);
    if (layerDef && isBodyLayer(L)) {
        const partialDrawCacheKey = `Luzi_PartialDraw_${L}`;
        const partialCache = data.partialCache.get(partialDrawCacheKey);
        const adjustedDrawCacheKey = `Luzi_AdjustedDraw_${L}`;
        const adjustedCache = data.adjustedCache.get(adjustedDrawCacheKey);

        const pChara = { ...C, DrawAppearance: C.DrawAppearance.filter((a) => a.Asset.Name !== A.Name) };
        const groups = (layerDef.PGroups || [])
            .map((g) => C.Appearance.find((a) => a.Asset.Group.Name === g))
            .filter(Boolean)
            .filter((a) => CharacterAppearanceVisible(pChara, a.Asset.Name, a.Asset.Group.Name))
            .map((a) => a.Asset.Group.Name);

        if (groups.length === 0) return;

        const { Canvas, CanvasBlink } = partialDraw(C, A, groups, partialCache);
        const adjustedCanvas = adjustCanvasAlpha(C, A, Canvas, 0.5, adjustedCache);
        const adjustedCanvasBlink = adjustCanvasAlpha(C, A, CanvasBlink, 0.5, data.adjustedCache.get(adjustedDrawCacheKey + "_Blink"));

        if (L === "Body" || L === "FullBody" || L === "FullBody2") {
            const ctx = adjustedCanvas.getContext("2d");
            const ctxBlink = adjustedCanvasBlink.getContext("2d");
            if (C.PoseMapping.BodyUpper === "BaseUpper" || C.PoseMapping.BodyUpper === "TapedHands") {
                const bodySize = C.Appearance.find((a) => a.Asset.Group.Name === "BodyUpper");
                if (bodySize) {
                    for (const g of Type.groups(["HandsLeft", "HandsRight"])) {
                        const armMask = ImageMapTools.assetLayer(
                            g,
                            `${bodySize.Asset.Name}_Asian`,
                            C.PoseMapping.BodyUpper === "TapedHands" ? "TapedHands" : undefined
                        );
                        DrawImageEx(armMask, ctx, X, Y, { BlendingMode: "destination-out" });
                        DrawImageEx(armMask, ctxBlink, X, Y, { BlendingMode: "destination-out" });
                    }
                }
            }
            if (Property.TypeRecord?.f === 1) {
                const mask = Tools.getAssetURL(drawData, "Fade");
                DrawImageEx(mask, adjustedCanvas.getContext("2d"), X, Y, { BlendingMode: "destination-out" });
                DrawImageEx(mask, adjustedCanvasBlink.getContext("2d"), X, Y, { BlendingMode: "destination-out" });
            }
        }

        drawCanvas(adjustedCanvas, 0, 0, AlphaMasks);
        drawCanvasBlink(adjustedCanvasBlink, 0, 0, AlphaMasks);
    }
    if (Property.TypeRecord?.le === 1 && L === "发光眼睛") {
        const partialDrawCacheKey = `Luzi_PartialDraw_${L}`;
        const partialCache = data.partialCache.get(partialDrawCacheKey);

        const { Canvas, CanvasBlink } = partialDraw(C, A, ["Eyes", "右眼_Luzi", "Eyes2", "左眼_Luzi"], partialCache);

        clippingMask(Canvas, Color);
        clippingMask(CanvasBlink, Color);

        drawCanvas(Canvas, 0, 0, AlphaMasks);
        drawCanvasBlink(CanvasBlink, 0, 0, AlphaMasks);
    }
}


const androidLayers = {
    上身遮罩: {partial:['BodyUpper'],mask:'身体遮罩',blend:'destination-out'},
    下身遮罩: {partial:['BodyLower'],mask:'身体遮罩',blend:'destination-out'},
    底部: {partial:['BodyUpper','BodyLower'],mask:'底部遮罩',blend:'destination-in'},
};
function androidBodyDraw(_data,_original,draw) {
    const config=androidLayers[draw.L];
    if(!config)return;
    const data=draw.PersistentData();
    data.drawCache ??= new PartialDrawCanvasCacheData();
    const cache=data.drawCache.get('Luzi_PartialDraw_'+config.partial.join('_'));
    const {Canvas,CanvasBlink}=partialDraw(draw.C,draw.A,config.partial,cache);
    const image=DrawGetImage(MobileBcAssetImageUrl(draw,config.mask));
    if(!image)return;
    for(const canvas of [Canvas,CanvasBlink]) DrawImageEx(image,canvas.getContext('2d'),draw.X,draw.Y,{BlendingMode:config.blend});
    draw.drawCanvas(Canvas,0,0,draw.AlphaMasks);
    draw.drawCanvasBlink(CanvasBlink,0,0,draw.AlphaMasks);
}
return {ghost:afterDraw,android:androidBodyDraw};
})();
