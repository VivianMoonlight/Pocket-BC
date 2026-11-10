"use strict";

function MobileBcTrayDraw(_data, _original, draw) {
    const {L,Property,C,A,X,Y,AlphaMasks,drawCanvas,drawCanvasBlink,PersistentData}=draw;
    const content=Property.Luzi_InventoryContent;
    if(!Array.isArray(content))return;
    const data=PersistentData();
    if(L==='曲奇'&&Property.Luzi_InventoryType==='曲奇') {
        if(content.length===0)return;
        const count=Math.min(content.length,12).toString().padStart(2,'0');
        const canvas=data.cookiesCanvas??=AnimationGenerateTempCanvas(C,A,160,40);
        const ctx=canvas.getContext('2d');
        ctx.clearRect(0,0,canvas.width,canvas.height);
        const image=DrawGetImage(MobileBcAssetImageUrl(draw,'曲奇'+count));
        if(!image)return;
        DrawImageEx(image,ctx,0,0);
        drawCanvas(canvas,X,Y,AlphaMasks);
        drawCanvasBlink(canvas,X,Y,AlphaMasks);
    } else if(L==='饮料'&&Property.Luzi_InventoryType==='饮料') {
        const canvas=data.drinksCanvas??=AnimationGenerateTempCanvas(C,A,500,70);
        const ctx=canvas.getContext('2d');
        ctx.clearRect(0,0,canvas.width,canvas.height);
        for(let i=0;i<4;i++) {
            const name=content[i]?.IAsset;
            if(!['空杯','橙汁','可乐','牛奶'].includes(name))continue;
            const image=DrawGetImage(MobileBcAssetImageUrl(draw,'饮料-'+name));
            if(image)DrawImageEx(image,ctx,170+i*40,0);
        }
        drawCanvas(canvas,X,Y,AlphaMasks);
        drawCanvasBlink(canvas,X,Y,AlphaMasks);
    }
}

// Reviewed Echo drawing helpers (CC BY-NC-SA 4.0); see ../echo/NOTICE.md.
/**
 * 按身体分组生成一张遮罩画布。
 *
 * 画布分两遍绘制：
 * 1. `groups` 中的图层用 `source-over` 正常绘制，作为遮罩内容；
 * 2. `rGroups` 中的图层用 `destination-out` 擦除，在遮罩上挖出镂空。
 *
 * 结果作为 `alphaTex` 传给自定义绘制（如 `GLImageRenderer.drawImage`），
 * 从而让衣物遮挡或穿透指定的身体部件。
 */
class MobileBcPartsMask {
    /**
     * 一次绘制所需的图层列表，每项为 [图片地址, X 偏移, Y 偏移]。
     * 顺序即绘制顺序，因此数组内容与顺序共同决定遮罩外观。
     * @typedef {[string,number,number][]} PartialDrawState
     */

    /**
     * @param {HTMLCanvasElement} canvas 用于绘制遮罩的画布，尺寸需与角色画布一致
     * @param {CustomGroupName[]} groups 参与绘制的分组（遮罩内容）
     * @param {CustomGroupName[]} [rGroups] 需要从遮罩中擦除的分组（镂空）
     */
    constructor(canvas, groups, rGroups) {
        /**
         * 遮罩画布
         * @private
         * @type {HTMLCanvasElement}
         */
        this._canvas = canvas;

        /**
         * 遮罩内容分组
         * @private
         * @type {CustomGroupName[]}
         */
        this._groups = groups;

        /**
         * 擦除分组
         * @private
         * @type {CustomGroupName[]}
         */
        this._rGroups = rGroups ?? [];

        /**
         * 上一次成功绘制的遮罩内容状态，用于跳过无变化的重复绘制；
         * 为 `undefined` 表示上次未绘制完成（或画布内容失效），需要重绘。
         * @private
         * @type {PartialDrawState | undefined}
         */
        this._state = undefined;

        /**
         * 上一次成功绘制的擦除状态，含义同 `_state`
         * @private
         * @type {PartialDrawState | undefined}
         */
        this._rState = undefined;
    }

    /**
     * 计算指定分组当前应绘制的图层列表。
     *
     * 按游戏自身的规则解析姿势、绘制坐标、图层类型、颜色后缀与父组前缀，
     * 拼出与 `DrawCharacter` 一致的图片地址。
     * @private
     * @param {Character} C
     * @param {CustomGroupName[]} groups
     * @return {PartialDrawState}
     */
    _calState(C, groups) {
        /** @type {PartialDrawState} */
        const state = [];

        /** @type {AssetLayer[]} */
        const layers = groups.flatMap((g) => C.AppearanceLayers.filter((l) => l.Asset.Group.Name === g));
        for (const layer of layers) {
            // 该图层当前没有可见图片，无需绘制
            if (!layer.HasImage) continue;

            const asset = layer.Asset;
            const group = asset.Group;
            const item = C.Appearance.find((i) => i.Asset === asset);
            const pose = CommonDrawResolveAssetPose(C, layer);
            const groupName = asset.DynamicGroupName;
            const { X, Y } = CommonDrawComputeDrawingCoordinates(C, asset, layer, groupName, item.Property);

            const typeRecord = item.Property?.TypeRecord || {};
            const layerType = layer.CreateLayerTypes.map((k) => `${k}${typeRecord[k] || 0}`).join("");
            const layerSegment = layer.Name || "";

            const layerColor = CommonDrawResolveLayerColor(C, item, layer, groupName);

            const parentGroupName = layer.ParentGroup[pose] ?? layer.ParentGroup[PoseType.DEFAULT];
            const parentAssetName = parentGroupName
                ? C.Appearance.find((i) => i.Asset.Group.Name === parentGroupName)?.Asset.Name || ""
                : "";

            const poseSegment = ((p) =>
                /** @type {string[]}*/ ([PoseType.HIDE, PoseType.DEFAULT]).includes(p) ? null : p)(
                pose === "" ? "" : layer.PoseMapping[pose]
            );

            const shouldColorize = layer.AllowColorize && layerColor && layerColor.startsWith("#");
            const colorSegment = (() => {
                if (shouldColorize) {
                    return layer.ColorSuffix ? layer.ColorSuffix.HEX_COLOR : "";
                } else {
                    if (layerColor && layerColor !== "Default" && !layerColor.startsWith("#")) {
                        return layerColor;
                    }
                }
            })();

            const baseURL = AssetBaseURL(C, group, groupName, poseSegment, layer, layerType, asset);

            const layerURL = `${[asset.Name, parentAssetName, layerType, colorSegment, layerSegment].filter(Boolean).join("_")}.png`;
            state.push([`${baseURL}${layerURL}`, X, Y]);
        }
        return state;
    }

    /**
     * 比较两次状态是否完全一致（图片地址与坐标都相同），用于跳过无变化的绘制。
     * @private
     * @param {PartialDrawState | undefined} s1
     * @param {PartialDrawState | undefined} s2
     */
    _stateCompare(s1, s2) {
        if (!s1 || !s2) return false;
        if (s1.length !== s2.length) return false;
        for (let i = 0; i < s1.length; i++) {
            const [url1, x1, y1] = s1[i];
            const [url2, x2, y2] = s2[i];
            if (url1 !== url2 || x1 !== x2 || y1 !== y2) return false;
        }
        return true;
    }

    /**
     * 遮罩画布本身，通常作为 `alphaTex` 交给后续绘制使用。
     * @return {HTMLCanvasElement}
     */
    get result() {
        return this._canvas;
    }

    /**
     * 将一组图层绘制到画布上。
     *
     * R133 起 `DrawGetImage` 在图片加载完成前返回 `undefined`：此时跳过该图，
     * 并返回 `undefined` 表示这次绘制不完整，调用方不记录状态、留待下次重试。
     * @private
     * @param {PartialDrawState} state
     * @param {GlobalCompositeOperation} operation
     * @param {boolean} [clear] 是否先清空画布
     * @returns {PartialDrawState | undefined} 绘制完成时返回 state，否则返回 undefined
     */
    _render(state, operation, clear = false) {
        const ctx = this._canvas.getContext("2d");
        if (!ctx) return undefined;

        if (clear) ctx.clearRect(0, 0, this._canvas.width, this._canvas.height);
        ctx.globalCompositeOperation = operation;

        const complete = state.reduce((complete, [url, x, y]) => {
            const img = DrawGetImage(url);
            if (!img) return false;
            ctx.drawImage(img, x, y - CanvasUpperOverflow);
            return complete;
        }, true);

        return complete ? state : undefined;
    }

    /**
     * 按角色当前外观更新遮罩画布。
     *
     * 两遍绘制都只在状态变化或 `C.MustDraw` 时执行：
     * 先整体重绘遮罩内容，再用 `destination-out` 擦出镂空。
     * @param {Character} C
     */
    draw(C) {
        const newState = this._calState(C, this._groups);
        if (C.MustDraw || !this._state || !this._stateCompare(newState, this._state)) {
            // 画布内容整体重画，之前的擦除结果一并作废
            this._rState = undefined;
            this._state = this._render(newState, "source-over", true);
        }

        const nrState = this._calState(C, this._rGroups);
        if (C.MustDraw || !this._rState || !this._stateCompare(nrState, this._rState)) {
            this._rState = this._render(nrState, "destination-out");
        }
    }
}

function MobileBcGradientDraw(mdata, originalFunction, drawData) {
    const { C, A, Color, Property, drawCanvas, drawCanvasBlink, AlphaMasks, L, PersistentData } = drawData;
    if (L !== "Front" && L !== "Back") return;

    const typeRecord = Property?.TypeRecord || {};

    if (L === "Front" && typeRecord.f !== 0) return;
    if (L === "Back" && typeRecord.b !== 0) return;

    const c = Color === "Default" ? "#FF8888" : Color;

    const data = PersistentData();

    const upper = Property.upperBound ?? 125;
    const size = Property.gradientSize ?? 300;

    /** @type {CustomGroupName[]} */
    const frontGroups = ["HairFront", "新前发_Luzi"];
    /** @type {CustomGroupName[]} */
    const backGroups = ["HairBack", "新后发_Luzi"];

    data.front ??= { mask: null, canvas: null };
    data.back ??= { mask: null, canvas: null };

    const layerData = L === "Front" ? data.front : L === "Back" ? data.back : null;
    const groups = L === "Front" ? frontGroups : L === "Back" ? backGroups : null;

    if (!layerData || !groups) return;

    layerData.mask = new MobileBcPartsMask(AnimationGenerateTempCanvas(C, A, 500, 1000), groups);
    layerData.mask.draw(C);
    layerData.canvas ??= AnimationGenerateTempCanvas(C, A, 500, 1000);

    const ctx = layerData.canvas.getContext("2d");
    ctx.clearRect(0, 0, layerData.canvas.width, layerData.canvas.height);

    ctx.save();

    const rotation = (((Property.rotation ?? 180) - 180) * Math.PI) / 180;
    ctx.translate(250, upper);
    ctx.rotate(rotation);

    const gradient = ctx.createLinearGradient(0, 0, 0, size);
    gradient.addColorStop(0, `${c}00`);
    gradient.addColorStop(1, `${c}FF`);

    ctx.fillStyle = gradient;
    ctx.fillRect(-1200, 0, 2400, size);
    ctx.fillStyle = `${c}FF`;
    ctx.fillRect(-1200, size - 1, 2400, 1200);

    if (A.Name === "双渐变叠加") {
        const gradient = ctx.createLinearGradient(0, 0, 0, -size);
        gradient.addColorStop(0, `${c}00`);
        gradient.addColorStop(1, `${c}FF`);

        ctx.fillStyle = gradient;
        ctx.fillRect(-1200, -size, 2400, size);
        ctx.fillStyle = `${c}FF`;
        ctx.fillRect(-1200, -size - 1199, 2400, 1200);
    }

    ctx.translate(-250, -upper);
    ctx.restore();
    ctx.globalCompositeOperation = "destination-in";
    ctx.drawImage(layerData.mask.result, 0, 0);
    ctx.globalCompositeOperation = "source-over";

    drawCanvas(layerData.canvas, 0, CanvasUpperOverflow, AlphaMasks);
    drawCanvasBlink(layerData.canvas, 0, CanvasUpperOverflow, AlphaMasks);
}

function MobileBcRulerDraw(
    data,
    originalFunction,
    { C, A, X, Y, Property, AlphaMasks, L, Color, drawCanvas, drawCanvasBlink }
) {
    const tempCanvs = AnimationGenerateTempCanvas(C, A, 200, 120);
    const ctx = tempCanvs.getContext("2d");
    if (!ctx) return;

    if (L === "Lines") {
        ctx.strokeStyle = Color;

        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(100, 13);
        ctx.bezierCurveTo(98, 23, 103, 43, 100, 60);
        ctx.lineTo(100, 85);
        ctx.stroke();

        ctx.lineWidth = 1.5;
        ctx.beginPath();
        [20, 40, 60, 80].forEach((y, idx) => {
            ctx.moveTo(93 + idx, y);
            ctx.bezierCurveTo(97, y + (idx % 3) - 1, 103, y + 1 - (idx % 3), 107 - idx, y);
        });
        ctx.stroke();

        ctx.fillStyle = Color;
        ctx.beginPath();
        ctx.moveTo(100, 13);
        ctx.arc(100, 13, 1, 0, Math.PI * 2);
        ctx.arc(100, 85, 1, 0, Math.PI * 2);
        [20, 40, 60, 80].forEach((y, idx) => {
            ctx.moveTo(93 + idx, y);
            ctx.arc(93 + idx, y, 0.75, 0, Math.PI * 2);
            ctx.arc(107 - idx, y, 0.75, 0, Math.PI * 2);
        });
        ctx.fill();
    }
    if (L === "Text") {
        //
        ctx.font = "cursive 10px Satisfy";
        ctx.fillStyle = Color;

        ctx.textAlign = "left";
        ctx.textBaseline = "bottom";

        const props = /**@type {any}*/ (Property);

        /** @type {DynamicDrawOptions} */
        const config = {
            fontFamily: data.font,
            fontSize: 12,
            color: Color,
            textAlign: "left",
            textBaseline: "bottom",
        };

        if (props) {
            if (props.Text) DynamicDrawText(props.Text, ctx, 105, 20, config);
            if (props.Text2) DynamicDrawText(props.Text2, ctx, 105, 40, config);
            if (props.Text3) DynamicDrawText(props.Text3, ctx, 105, 60, config);
            if (props.Text4) DynamicDrawText(props.Text4, ctx, 105, 80, config);
        }
    }

    drawCanvas(tempCanvs, X, Y, AlphaMasks);
    drawCanvasBlink(tempCanvs, X, Y, AlphaMasks);
}

function MobileBcRobotBeforeDraw(_data, _original, { C, L, Property }) {
    if (Property?.TypeRecord?.typed !== 1) return;
    if ((L === '跟随模式' && C.HasEffect('IsLeashed')) ||
        (L === '跟随模式_抓住' && !C.HasEffect('IsLeashed'))) return { Opacity: 0 };
}

function MobileBcRobotAfterDraw(_data, _original, draw) {
    const { C, A, CA, L, X, Y, Color, GroupName, AlphaMasks, PersistentData, drawCanvas, drawCanvasBlink } = draw;
    const data = PersistentData();
    if (L === '绳子') {
        const layer = A.Layer.find(layer=>layer.Name===L);
        const { fixedYOffset } = CommonDrawComputeDrawingCoordinates(C,A,layer,GroupName);
        const canvas = (data.ropeCanvas ??= AnimationGenerateTempCanvas(C,A,500,1000+CanvasUpperOverflow));
        const ctx = canvas.getContext('2d');
        const startX=250, startY=225+CanvasUpperOverflow, endX=420, endY=230+fixedYOffset+CanvasUpperOverflow;
        const lightLayer=A.Layer.find(layer=>layer.Name==='绳子光芒');
        if (!lightLayer) return;
        const LightColor=CommonDrawResolveLayerColor(C,CA,lightLayer,GroupName);
        const state={endY,Color,LightColor};
        if (!data.ropeState || Object.keys(state).some(key=>data.ropeState[key]!==state[key])) {
            ctx.clearRect(0,0,canvas.width,canvas.height);
            ctx.strokeStyle=Color;
            ctx.lineWidth=5;
            ctx.shadowBlur=10;
            ctx.shadowColor=LightColor;
            ctx.beginPath();
            ctx.moveTo(startX,startY);
            ctx.quadraticCurveTo((startX+endX)/2,Math.max(startY,endY)+50,endX,endY);
            ctx.stroke();
            ctx.beginPath();
            ctx.arc(startX,startY,ctx.lineWidth/2,0,Math.PI*2);
            ctx.arc(endX,endY,ctx.lineWidth/2,0,Math.PI*2);
            ctx.fillStyle=Color;
            ctx.fill();
        }
        data.ropeState=state;
        drawCanvas(canvas,0,0,AlphaMasks);
        drawCanvasBlink(canvas,0,0,AlphaMasks);
    } else if (L === '眼睛') {
        const canvas=(data.eyeCanvas ??= AnimationGenerateTempCanvas(C,A,150,230));
        const renderer=(data.eyeRenderer ??= new MobileBcGLImageRenderer(canvas));
        const image=DrawGetImage(MobileBcAssetImageUrl(draw));
        if (!image) return;
        const color=MobileBcGLImageRenderer.BCColorToGLColor(Color);
        renderer.clearRect(0,0,canvas.width,canvas.height);
        // Freeze the upstream gaze at its initial centered offset.
        renderer.drawImage(image,0,0,{color});
        drawCanvas(canvas,X,Y);
        drawCanvasBlink(canvas,X,Y);
    }
}

function MobileBcAssetImageUrl({ A, L, Pose, G, GroupName, LayerType }, name = L) {
    const layer = A.Layer.find(layer=>layer.Name===L);
    const pose = Pose === '' ? '' : layer.PoseMapping[Pose];
    const segment = !pose || pose===PoseType.HIDE || pose===PoseType.DEFAULT ? '' : `${pose}/`;
    return `Assets/${A.Group.Family}/${GroupName}/${segment}${[A.Name,G,LayerType,name].filter(Boolean).join('_')}.png`;
}

function MobileBcLiquidBagDraw(_data, _original, draw) {
    const { C, A, X, Y, Property, drawCanvas, drawCanvasBlink, AlphaMasks, L } = draw;
    if (L !== '尿') return;
    const canvas = AnimationGenerateTempCanvas(C,A,500,1000);
    const ctx = canvas.getContext('2d');
    const liquid = Math.max(0,Math.min(1,Property.Liquid ?? 0));
    DrawImageEx(MobileBcAssetImageUrl(draw),ctx,0,(1-liquid)*72);
    DrawImageEx(MobileBcAssetImageUrl(draw,'遮罩'),ctx,0,0,{BlendingMode:'destination-in'});
    drawCanvas(canvas,X,Y,AlphaMasks);
    drawCanvasBlink(canvas,X,Y,AlphaMasks);
}

function MobileBcCyberShieldDraw(_data, _original, draw) {
    const { L, Color, PersistentData, Property, C, A, X, Y, Pose, G, GroupName, LayerType, drawCanvas, drawCanvasBlink } = draw;
    const data = PersistentData();
    if (L === 'LightColor1' || L === 'LightColor2') { data[L] = Color; return; }
    if (!['Light','Bloom'].includes(L) || !data.LightColor1 || !data.LightColor2) return;
    const image = DrawGetImage(MobileBcAssetImageUrl(draw));
    if (!image) return;
    const canvas = (data.canvas ??= AnimationGenerateTempCanvas(C,A,100,100));
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0,0,canvas.width,canvas.height);
    // Freeze time and the upstream random phase at zero, giving the midpoint color.
    ctx.fillStyle = Property.TypeRecord?.f === 0
        ? new MobileBcColorTools().interpolateColor(data.LightColor1,data.LightColor2,0.5) : data.LightColor1;
    ctx.fillRect(0,0,canvas.width,canvas.height);
    DrawImageEx(image,ctx,X-200,Y-CanvasUpperOverflow-460,{BlendingMode:'destination-in'});
    drawCanvas(canvas,200,460+CanvasUpperOverflow);
    drawCanvasBlink(canvas,200,460+CanvasUpperOverflow);
}

function MobileBcEvilFallDraw(_data, _original, draw) {
    const { C, A, X, Y, L, Color, Property, PersistentData, drawCanvas, drawCanvasBlink, AlphaMasks, Pose, G, GroupName, LayerType } = draw;
    const phases = A.Name === 'EFGlass' ? {effect:0, light2:0.7, light1:1.4} : {light2:0, light1:1.4};
    if (!Object.hasOwn(phases,L)) return;
    const data = PersistentData();
    const canvas = (data.canvas ??= AnimationGenerateTempCanvas(C,A,180,50));
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0,0,canvas.width,canvas.height);
    const image = DrawGetImage(MobileBcAssetImageUrl(draw));
    if (!image) return;
    // Freeze the upstream light animation at t=0, including its per-character phase.
    const alpha = Math.sin(C.MemberNumber * Math.PI * 2 * 0.2 + (phases[L] || 0.1)) * 0.3 + 0.7;
    ctx.fillStyle = Property.TypeRecord.f === 0 ? `${Color}${alpha.toString(16).slice(2,4)}` : `${Color}`;
    ctx.fillRect(0,0,canvas.width,canvas.height);
    DrawImageEx(image,ctx,0,0,{BlendingMode:'destination-in'});
    drawCanvas(canvas,X,Y,AlphaMasks);
    drawCanvasBlink(canvas,X,Y,AlphaMasks);
}

function MobileBcCardboardDraw(
    data,
    originalFunction,
    { C, A, CA, X, Y, Property, AlphaMasks, L, Color, drawCanvas, drawCanvasBlink }
) {
    const Width = 200;
    const Height = 150;

    if (L === "Text" && Property?.TypeRecord?.t === 1) {
        const TempCanvas = AnimationGenerateTempCanvas(C, A, Width, Height);

        TextItem.Init(data, C, CA, false, false);

        /** @type {DynamicDrawOptions} */
        const config = {
            fontSize: 32,
            fontFamily: data.font,
            color: Color,
            textAlign: "center",
            width: Width / 2,
        };
        const ctx = TempCanvas.getContext("2d");
        ctx.rotate(Math.PI / 6);
        DynamicDrawText(CA.Property.Text, ctx, Width / 2, Height / 2 - 14, config);

        drawCanvas(TempCanvas, X + 150, Y + 120, AlphaMasks);
        drawCanvasBlink(TempCanvas, X + 150, Y + 120, AlphaMasks);
    }
}

class MobileBcColorTools {
    /**
     * @param {string} hex
     * @returns {{r: number, g: number, b: number} | null}
     */
    hexToRgb(hex) {
        const match = hex.replace("#", "").match(/.{1,2}/g);
        if (!match) return null;
        return {
            r: parseInt(match[0], 16),
            g: parseInt(match[1], 16),
            b: parseInt(match[2], 16),
        };
    }

    /**
     * @param {{r: number, g: number, b: number}} rgb
     * @return {string}
     */
    rgbToHex({ r, g, b }) {
        return `#${[r, g, b]
            .map((x) => {
                const hex = x.toString(16);
                return hex.length === 1 ? `0${hex}` : hex;
            })
            .join("")}`;
    }

    /**
     * @param {string} color1
     * @param {string} color2
     * @param {number} t
     * @returns {string}
     */
    interpolateColor(color1, color2, t) {
        const c1 = this.hexToRgb(color1);
        const c2 = this.hexToRgb(color2);
        if (!c1 || !c2) return color1;
        const r = Math.round(c1.r + (c2.r - c1.r) * t);
        const g = Math.round(c1.g + (c2.g - c1.g) * t);
        const b = Math.round(c1.b + (c2.b - c1.b) * t);
        return this.rgbToHex({ r, g, b });
    }
}
