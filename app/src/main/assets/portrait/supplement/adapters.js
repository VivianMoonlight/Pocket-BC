"use strict";

// Echo drawing adaptations: CC BY-NC-SA 4.0, see ../echo/NOTICE.md.
// Packaged code only. Hooks return drawing overrides and never update a character.
var MobileBcSupplement = (() => {
    let manifest = null;
    const beforeDraw = {
        "echo-hard-luggage": (_data,_original,{L,Property}) => {
            if(L==='箱盖')return {Opacity:Property?.TypeRecord?.s===0?0.8:Property?.TypeRecord?.s===1?0.9:1};
            if(L==='衣物堆')return {Opacity:Property?.TypeRecord?.c===3?0.7:1};
        },
        "echo-chocolate": (_data, _original, {L,Property}) => {
            if(L!=='Gt')return;
            if(!Property.LuziRid||!Property.LuziCid)return {Opacity:0};
            let hash=0x811c9dc5;
            for(const value of [Property.LuziRid,Property.LuziCid,0x9e3779b9]) {
                hash^=Number(value)|0;
                hash=Math.imul(hash,0x01000193);
            }
            return {Opacity:(hash>>>0)%100<5?1:0};
        },
        "echo-doll-body": (_data, _original, { C, L }) => {
            const group = {上半身:'BodyUpper',下半身:'BodyLower',手:'HandsLeft'}[L];
            if (group && C.DrawAppearance.some(item=>item.Asset.Name!=='人偶' &&
                !CharacterAppearanceItemIsHidden(item.Asset.Name,item.Asset.Group.Name) &&
                (item.Asset.Hide?.includes(group) || item.Property?.Hide?.includes(group)))) return {Opacity:0};
        },
        "echo-surveillance-robot": MobileBcRobotBeforeDraw,
        "echo-one-bar-prison": (_data, _original, { L, Y, Property }) => {
            if (L !== "A_柱子伸缩" && L !== "A_阳具") return;
            const d = Property?.TypeRecord?.d;
            if (!d) return;
            if (d === 1) return { Y: Y - 100 };
            // At t=0 the moving piston is 60 pixels above its bottom position.
            return { Y: Y - ([0, 1, 0.2, 0.5, 0.8, 1.2][d] ? 60 : 0) };
        },
        "echo-tentacle-suit": (_data, _original, { L, Y, Property }) => {
            if (L !== "触手" && L !== "触手背后") return;
            const mask = L === "触手" ? { AlphaMasks: [[240,400,6,118],[246,400,8,114],[254,400,6,118]] } : {};
            const intensity = Property?.Intensity;
            if (intensity === undefined || intensity < 0) return { Y, ...mask };
            // The portrait freezes the reviewed animation at t=0; no vibrator update runs.
            const frequency = [0.2,0.6,1.2,1.8][intensity];
            const ratio = frequency ? (Math.cos(0 * frequency) + 1) / 2 : 0;
            return { Y: Y + Math.round(ratio * 30), ...mask };
        },
        "echo-large-beads": (_data, _original, { Y, Property }) => ({
            Y: Y - ((Property.InsertedBeads || 1) - 1) * 44,
            AlphaMasks: [[220, 0, 60, 512]],
        }),
        "echo-laptop": (_data, _original, { Pose }) => {
            if (Pose === "Yoked") return { LayerType: "" };
        },
        "echo-marking-sign": (_data, _original, { LayerType, L }) => {
            if (L === "文字") return { LayerType: `${LayerType}_${['CN', 'TW'].includes(TranslationLanguage) ? 'CN' : 'EN'}` };
        },
        "echo-evening-gown": (_data, _original, { C, Pose }) => {
            if (C.HasEffect("Suspended")) {
                return { Pose: Pose === "Kneel" || Pose === "KneelingSpread" ? "" : Pose };
            }
        },
        "echo-icon-markings": (_data, _original, { L, Property, Pose }, modules) => {
            const lying = Pose === "Hogtied" || Pose === "AllFours";
            const layer = L.endsWith("_1") ? 1 : 2;
            return { AlphaMasks: modules.filter(m => m.LuziAlpha &&
                ((lying && !m.LuziShowWhenLying) ||
                    (m.LuziLayerC === layer && Property.TypeRecord[m.Key] === 0)))
                .map(m => m.LuziAlpha) };
        },
        "echo-mary-jane": (_data, _original, { L, Y, Property }) => {
            if (L === "鞋底" && Property?.TypeRecord?.b === 1) return { Y: Y - 4 };
        },
        "echo-lab-coat": (_data, _original, { C, Property, Pose }) => {
            const type = Property?.TypeRecord?.typed;
            if (type === 0 && Pose !== "BackCuffs") {
                if (!C.AllowedActivePose.includes("BaseUpper") && C.IsRestrained()) {
                    return { LayerType: "r", Pose: null };
                }
            } else if (type === 2) {
                return { LayerType: "r", Pose: null };
            }
        },
        "echo-wool-coat": (_data, _original, { C, Property, Pose, L }) => {
            if (Property?.TypeRecord?.d !== 0) return;
            const disguised = !C.AllowedActivePose.includes("BaseUpper") && Pose !== "BackCuffs" && C.IsRestrained();
            if (L === "A2") return { Opacity: disguised ? 0 : 1 };
            if (L === "A3") return { Opacity: disguised ? 1 : 0 };
        },
        "echo-white-dress": (_data, _original, { C, L, Pose }) => {
            if ((L === "g_3" || L === "d_3") && C.HasEffect("Suspended")) {
                return { Pose: Pose === "Kneel" ? "" : Pose };
            }
        },
    };

    const afterDraw = {
        "echo-evilfall-glass": MobileBcEvilFallDraw,
        "echo-cyber-shield": MobileBcCyberShieldDraw,
        "echo-liquid-bag": MobileBcLiquidBagDraw,
        "echo-tray": MobileBcTrayDraw,
        "echo-ghost-body": MobileBcBodyDrawing.ghost,
        "echo-android-body": MobileBcBodyDrawing.android,
        "echo-surveillance-robot": MobileBcRobotAfterDraw,
        "echo-evilfall-mask": MobileBcEvilFallDraw,
        "echo-cardboard-box": MobileBcCardboardDraw,
        "echo-ruler": MobileBcRulerDraw,
        "echo-dog-house": (data, _original, { C, A, CA, X, Y, Property, AlphaMasks, L, Color, drawCanvas, drawCanvasBlink }) => {
            if (L !== "Text" && L !== "Text_F") return;
            const canvas = AnimationGenerateTempCanvas(C, A, 148, 93);
            const context = canvas.getContext("2d");
            TextItem.Init(data, C, CA, false, false);
            const config = { fontSize: 22, fontFamily: data.font, color: Color, textAlign: "center", width: 148 };
            if ((Property.TypeRecord?.t ?? 0) === 0) {
                DynamicDrawText(CA.Property.Text, context, 74, 46.5, { ...config, fontSize: 30 });
            } else {
                DynamicDrawText(CA.Property.Text, context, 74, 32.5, config);
                DynamicDrawText(CA.Property.Text2, context, 74, 60.5, config);
            }
            drawCanvas(canvas, X + 176, Y + 131, AlphaMasks);
            drawCanvasBlink(canvas, X + 176, Y + 131, AlphaMasks);
        },
        "echo-drone-hood": (data, _original, draw) => {
            const { C, A, Color, Property, X, Y, AlphaMasks, drawCanvas, drawCanvasBlink, L } = draw;
            if (L !== "text" || Property?.TypeRecord?.t !== 0 || !Property.Text) return;
            const canvas = AnimationGenerateTempCanvas(C, A, 80, 48);
            const context = canvas.getContext("2d");
            if (!context) return;
            context.textAlign = "center";
            context.textBaseline = "middle";
            DynamicDrawTextArc(Property.Text, context, 40, 24, {
                fontSize: 48, fontFamily: data.font, width: 80, color: Color, radius: 200,
            });
            drawCanvas(canvas, X - 40, Y + 8, AlphaMasks);
            drawCanvasBlink(canvas, X - 40, Y + 8, AlphaMasks);
        },
        "echo-cross-collar": (_data, _original, draw) => {
            const { C, A, X, Y, Color, Opacity, AlphaMasks, drawCanvas, drawCanvasBlink, PersistentData, L, Pose, G, GroupName, LayerType } = draw;
            if (L !== 'A3' && L !== 'A4') return;
            const layer = A.Layer.find(layer => layer.Name === L);
            const pose = Pose === '' ? '' : layer.PoseMapping[Pose];
            const segment = !pose || pose === PoseType.HIDE || pose === PoseType.DEFAULT ? '' : `${pose}/`;
            const url = `Assets/${A.Group.Family}/${GroupName}/${segment}${[A.Name,G,LayerType,L].filter(Boolean).join('_')}.png`;
            const image = DrawGetImage(url);
            if (!image) return;
            const data = PersistentData();
            data.canvas ??= AnimationGenerateTempCanvas(C, A, 500, 1000);
            data.renderer ??= new MobileBcGLImageRenderer(data.canvas);
            data.mask ??= new MobileBcPartsMask(AnimationGenerateTempCanvas(C, A, 500, 1000),
                ['Cloth','ClothAccessory','Bra','BodyUpper'], ['ArmsLeft','AnkletRight','HandsLeft','HandsRight']);
            data.mask.draw(C);
            data.renderer.clearRect(0, 0, 500, 1000);
            data.renderer.drawImage(image, X, Y - CanvasUpperOverflow, {
                color:MobileBcGLImageRenderer.BCColorToGLColor(Color), colorAlpha:Opacity, alphaTex:data.mask.result,
            });
            drawCanvas(data.canvas, 0, CanvasUpperOverflow, AlphaMasks);
            drawCanvasBlink(data.canvas, 0, CanvasUpperOverflow, AlphaMasks);
        },
        "echo-gradient": MobileBcGradientDraw,
        "echo-double-gradient": MobileBcGradientDraw,
        "echo-body-text": (data, _original, { C, A, CA, X, Y, drawCanvas, drawCanvasBlink, AlphaMasks, L, Color }) => {
            if (L !== "Text") return;
            const canvas = AnimationGenerateTempCanvas(C, A, 500, 500);
            TextItem.Init(data, C, CA, false, false);
            const context = canvas.getContext("2d");
            const rows = [[-105,60],[-50,55],[0,48],[50,60],[105,70]];
            for (let row = 0; row < rows.length; row++) for (let column = 0; column < 3; column++) {
                const [y, width] = rows[row];
                const position = row * 3 + column;
                for (let line = 0; line < 3; line++) {
                    DynamicDrawText(CA.Property[`Text${position * 3 + line + 1}`] || "", context,
                        250 + (column - 1) * width, 250 + y + (line - 1) * 10,
                        { fontSize:10, fontFamily:data.font, color:Color, width:500, textAlign:['left','center','right'][column] });
                }
            }
            drawCanvas(canvas, X, Y, AlphaMasks);
            drawCanvasBlink(canvas, X, Y, AlphaMasks);
        },
        "echo-sports-top": (data, _original, draw) => {
            const { C, A, Color, Property, X, Y, G, AlphaMasks, drawCanvas, drawCanvasBlink, L } = draw;
            if (L !== "text" || Property?.TypeRecord?.t !== 0) return;
            const sizes = { Small: [45, 8, 400], Normal: [60, 5, 300], Large: [70, 3, 250], XLarge: [80, 5, 200] };
            const [width, offset, radius] = sizes[G] ?? sizes.Normal;
            const canvas = AnimationGenerateTempCanvas(C, A, width, 48);
            const context = canvas.getContext("2d");
            if (!context) return;
            context.textAlign = "center";
            context.textBaseline = "middle";
            DynamicDrawTextArc(Property.Text ?? "", context, width / 2, 24, {
                fontSize: 48, fontFamily: data.font, width, color: Color, radius,
            });
            drawCanvas(canvas, X - width / 2, Y + offset, AlphaMasks);
            drawCanvasBlink(canvas, X - width / 2, Y + offset, AlphaMasks);
        },
    };
    function hookOwner(extended, entry) {
        return (entry.hookPaths ?? [entry.hookPath ?? []]).map(path => path.reduce((value, key) => value?.[key], extended));
    }
    function hasScriptHooks(value) {
        return value !== null && typeof value === 'object' &&
            (Object.hasOwn(value, 'ScriptHooks') || Object.values(value).some(hasScriptHooks));
    }
    function checkBinding(record, entry) {
        if (!entry || entry.source !== "echo" || !["BeforeDraw", "AfterDraw", "BeforeAfterDraw"].includes(entry.mode) ||
            entry.id !== record.portraitAdapter || entry.name !== record.definition.Name ||
            !entry.groups.includes(record.group) || !record.extended || hasScriptHooks(record.extended) || hookOwner(record.extended, entry).some(owner => !owner)) {
            throw new Error(`Invalid portrait adapter binding: ${record.portraitAdapter}`);
        }
    }

    async function prepare(scripts, catalog) {
        manifest = null;
        const response = await fetch("/portrait/supplement/manifest.json");
        if (!response.ok) throw new Error("Missing portrait supplement manifest");
        const next = await response.json();
        if (next.format !== 1 || next.version !== 30 || next.version !== catalog.supplementVersion ||
            next.bcVersion !== GameVersion || next.echoRevision !== catalog.revision) {
            throw new Error("Portrait supplement version mismatch");
        }
        if (!Array.isArray(next.adapters) || next.adapters.length !== new Set([...Object.keys(beforeDraw),...Object.keys(afterDraw)]).size + 1) {
            throw new Error("Incomplete portrait supplement adapters");
        }
        const ids = new Set();
        const bindings = new Map();
        for (const entry of next.adapters) {
            if ((entry.id === 'echo-tentacle-suit' ? entry.frozenFrame !== true : entry.frozenFrame !== undefined)) {
                throw new Error('Invalid portrait supplement frozen adapter');
            }
            const echo = entry.source === "echo";
            if (ids.has(entry.id) || !(echo
                ? (entry.mode === "BeforeAfterDraw" && entry.id === 'echo-surveillance-robot' && entry.hookPath === undefined && entry.hookPaths === undefined) ||
                    (entry.mode === "BeforeDraw" && Object.hasOwn(beforeDraw, entry.id) && !Object.hasOwn(afterDraw,entry.id) && entry.hookPath === undefined && entry.hookPaths === undefined) ||
                    (entry.mode === "AfterDraw" && Object.hasOwn(afterDraw, entry.id) &&
                        ((['echo-gradient','echo-double-gradient','echo-cross-collar','echo-ruler','echo-evilfall-glass','echo-evilfall-mask','echo-cyber-shield','echo-liquid-bag','echo-ghost-body','echo-android-body','echo-tray'].includes(entry.id) && entry.hookPaths === undefined && JSON.stringify(entry.hookPath) === '[]') ||
                        (entry.id === 'echo-sports-top' && entry.hookPaths === undefined && JSON.stringify(entry.hookPath) === JSON.stringify(['Modules', 1, 'Options', 0, 'ArchetypeConfig'])) ||
                        (entry.id === 'echo-cardboard-box' && entry.hookPaths === undefined && JSON.stringify(entry.hookPath) === JSON.stringify(['Modules', 1, 'Options', 1, 'ArchetypeConfig'])) ||
                        (entry.id === 'echo-drone-hood' && entry.hookPaths === undefined && JSON.stringify(entry.hookPath) === JSON.stringify(['Modules', 0, 'Options', 0, 'ArchetypeConfig'])) ||
                        (entry.id === 'echo-dog-house' && entry.hookPath === undefined && JSON.stringify(entry.hookPaths) === JSON.stringify([0,1].map(i=>['Modules',2,'Options',i,'ArchetypeConfig']))) ||
                        (entry.id === 'echo-body-text' && entry.hookPath === undefined && JSON.stringify(entry.hookPaths) === JSON.stringify(Array.from({length:15},(_,i)=>['Modules',i,'Options',1,'ArchetypeConfig'])))))
                : entry.source === "bc" && entry.mode === "frozen-frame" && entry.id === "bc-steampunk-wings") ||
                typeof entry.path !== "string" || !entry.path.endsWith(".js") ||
                entry.path.split("/").some(p => !p || p === "..") || entry.path.includes("\\") ||
                !(echo ? /^[a-f0-9]{40}$/ : /^[a-f0-9]{64}$/).test(entry.sourceHash) ||
                typeof entry.name !== "string" || !entry.name || !Array.isArray(entry.groups) || !entry.groups.length) {
                throw new Error(`Invalid portrait supplement entry: ${entry.id}`);
            }
            ids.add(entry.id);
            if (entry.source === "bc" && !scripts.some(s => s.path === entry.path && s.sha256 === entry.sourceHash)) {
                throw new Error(`Portrait supplement source mismatch: ${entry.id}`);
            }
            if (echo && !catalog.sources.includes(entry.path)) {
                throw new Error(`Missing portrait supplement source: ${entry.id}`);
            }
            for (const group of entry.groups) {
                const key = JSON.stringify([group, entry.name]);
                if (typeof group !== "string" || !group || bindings.has(key)) {
                    throw new Error(`Duplicate or invalid portrait supplement binding: ${entry.id}`);
                }
                bindings.set(key, entry);
            }
        }
        const seen = new Set();
        for (const record of catalog.assets) {
            const key = JSON.stringify([record.group, record.definition.Name]);
            const entry = bindings.get(key);
            if (entry || record.portraitAdapter) {
                checkBinding(record, entry);
                if (seen.has(key)) throw new Error(`Duplicate portrait adapter asset: ${key}`);
                seen.add(key);
            }
        }
        for (const [key, entry] of bindings) {
            if (entry.source === "echo" && !seen.has(key)) throw new Error(`Missing portrait adapter asset: ${key}`);
        }
        manifest = next;
    }

    function extended(record) {
        if (!record.portraitAdapter) return record.extended;
        const entry = manifest?.adapters.find(a => a.id === record.portraitAdapter);
        checkBinding(record, entry);
        if (entry.mode === 'BeforeAfterDraw') {
            return { ...record.extended, ScriptHooks: { BeforeDraw: beforeDraw[entry.id], AfterDraw: afterDraw[entry.id] } };
        }
        if (entry.mode === 'AfterDraw') {
            const extended = JSON.parse(JSON.stringify(record.extended));
            for (const owner of hookOwner(extended, entry)) owner.ScriptHooks = { AfterDraw: afterDraw[entry.id] };
            return extended;
        }
        return { ...record.extended, ScriptHooks: {
            BeforeDraw: (data, original, draw) => beforeDraw[entry.id](data, original, draw, record.extended.Modules),
        } };
    }

    function allowsFrozenFrame(asset) {
        return manifest?.adapters.some(a => (a.mode === "frozen-frame" || a.id === 'echo-tentacle-suit') && a.name === asset.Name &&
            a.groups.includes(asset.DynamicGroupName)) === true;
    }

    return { prepare, extended, allowsFrozenFrame };
})();
