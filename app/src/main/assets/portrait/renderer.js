"use strict";

// BC's drawing dependencies use these host values. Game.js, Server.js and Import.js
// are intentionally absent: no game loop, login, socket or account synchronization.
var GameVersion = "R133";
var BrowserStorageCache = null;
function ServerPlayerIsInChatRoom() { return false; }

(() => {
    let initialized = false;
    let pending = null;
    let character = null;
    let frameId = null;
    let paused = false;
    let revision = 0;
    let startedAt = 0;
    let fatal = false;
    let omitted = [];
    let requestedAt = 0;
    const runtimeStartedAt = performance.now();
    let runtimeReadyMs = null;
    let statusPort = null;
    let echoHeightAdjustment = 0;
    window.addEventListener("message", event => {
        if (event.data !== "mobilebc-portrait-status" || event.ports.length !== 1) return;
        statusPort?.close();
        statusPort = event.ports[0];
        statusPort.postMessage(JSON.stringify(window.mobileBcPortraitStatus));
    });
    window.mobileBcPortraitStatus = { revision: 0, state: "loading" };

    function status(state, missingImages = 0) {
        window.mobileBcPortraitStatus = { revision, state, omitted, missingImages, runtimeReadyMs,
            elapsedMs: requestedAt ? Math.round(performance.now() - requestedAt) : null };
        MobileBcPortraitDump.state(window.mobileBcPortraitStatus);
        statusPort?.postMessage(JSON.stringify(window.mobileBcPortraitStatus));
    }

    function fail(error) {
        MobileBcPortraitDump.record("error", { message: String(error) });
        // A broken runtime cannot initialize safely, but a bad appearance must
        // not prevent the next snapshot from using an already initialized engine.
        fatal = !initialized;
        if (frameId !== null) cancelAnimationFrame(frameId);
        frameId = null;
        document.getElementById("MainCanvas").getContext("2d").clearRect(0, 0, 500, 1000);
        status("failed");
        console.error("Portrait renderer:", error);
    }

    window.addEventListener("error", event => fail(event.error || event.message));
    window.addEventListener("unhandledrejection", event => fail(event.reason));

    function schedule() {
        if (!paused && !fatal && character && frameId === null) frameId = requestAnimationFrame(draw);
    }

    function portraitFrameGeometry(C) {
        return { x: 0, width: 500, sourceY: 0, sourceHeight: 1000,
            destY: 0, destHeight: 1000, inverted: CharacterAppearsInverted(C) };
    }

    // The native overlay reads the exact same geometry used for the frame.
    window.mobileBcPortraitGeometry = () => character ? portraitFrameGeometry(character) : null;
    function draw() {
        frameId = null;
        try {
            if (!character || paused) return;
            if (character.MustDraw) CharacterLoadCanvas(character);
            const images = [...DrawImageCache.cache.values()];
            const timedOut = performance.now() - startedAt > 90000;
            if (!timedOut && images.some(image => !image.isDoneLoading())) {
                schedule();
                return;
            }
            // Rebuild once more after image decoding, before exposing the finished frame.
            // Echo image aliases do not match BC's cache-to-character name lookup.
            // Rebuild explicitly after decoding so aliased layers and masks appear.
            CharacterLoadCanvas(character);
            const unavailable = [...DrawImageCache.cache.values()].filter(image => !image.isLoaded());
            if (!timedOut && unavailable.some(image => !image.isDoneLoading())) {
                schedule();
                return;
            }
            MainCanvas.clearRect(0, 0, 500, 1000);
            // Use BC's unshifted character canvas at a consistent scale. Asset and
            // layer transforms remain in that canvas; room height placement does not.
            const geometry = portraitFrameGeometry(character);
            DrawImageEx(character.Canvas, MainCanvas, geometry.x, geometry.destY, {
                SourcePos: [0, CanvasUpperOverflow + geometry.sourceY, 500, geometry.sourceHeight],
                Width: geometry.width,
                Height: geometry.destHeight,
                Invert: geometry.inverted,
                Mirror: geometry.inverted,
            });
            // BC skips unavailable images per layer. Keep that partial canvas;
            // missing resources must not poison subsequent appearance snapshots.
            status(omitted.length || unavailable.length ? "partial" : "ready", unavailable.length);
        } catch (error) { fail(error); }
    }

    function drawingProperty(asset, property) {
        const result = { ...property };
        if (asset.Archetype !== ExtendedArchetype.TYPED && asset.Archetype !== ExtendedArchetype.MODULAR) return result;
        const data = ExtendedItemGetData(asset, asset.Archetype);
        if (!data) return result;
        // Layer visibility requires explicit keys, even for option zero. Complete
        // only missing selections on a local copy; keep the received properties.
        const keys = data.archetype === ExtendedArchetype.MODULAR ? data.modules.map(module => module.Key) : [data.name];
        return { ...result, TypeRecord: { ...Object.fromEntries(keys.map(key => [key, 0])), ...property?.TypeRecord } };
    }

    // LSCG's older offsets replace the final BeforeDraw coordinates, rather
    // than asset origins. In particular they must not inherit body-style or
    // fixed-position offsets a second time.
    function legacyLayerCoordinates(C, asset, layerName, groupName, property) {
        if (!C || C.IsGhosted()) return null;
        const index = asset.Layer.findIndex(layer => (layer.Name ?? "") === layerName);
        const legacy = property?.LayerOverrides?.[index];
        const left = legacy?.DrawingLeft?.[""], top = legacy?.DrawingTop?.[""];
        const hasX = typeof left === "number" && Number.isFinite(left) && left !== 0;
        const hasY = typeof top === "number" && Number.isFinite(top) && top !== 0;
        if (!hasX && !hasY) return null;
        let x = left, y = top;
        for (const pose of C.DrawPose) {
            const move = PoseRecord[pose]?.MovePosition?.find(position => position.Group === groupName);
            if (move) { if (hasX) x += move.X; if (hasY) y += move.Y; }
        }
        return { ...(hasX ? { X: x } : {}), ...(hasY ? { Y: y + CanvasUpperOverflow } : {}) };
    }

    function retainDecodedImages() {
        // Retain at most 32 MiB between snapshots. Failed/in-flight entries must
        // be retried and cannot influence the next character's loading status.
        let retainedBytes = 0;
        if (typeof DrawImageCache === "undefined") return;
        const images = [...DrawImageCache.cache.entries()].sort((a, b) => b[1].lastUsed - a[1].lastUsed);
        for (const [key, image] of images) {
            const bytes = image.width * image.height * 4;
            if (image.isLoaded() && retainedBytes + bytes <= 32 * 1024 * 1024) {
                retainedBytes += bytes;
            } else {
                DrawImageCache.delete(key);
            }
        }
    }

    function apply(snapshot) {
        MobileBcPortraitDump.record("apply", { revision });
        if (frameId !== null) cancelAnimationFrame(frameId);
        frameId = null;
        if (character) AnimationPurge(character, true);
        character = null;
        MainCanvas.clearRect(0, 0, 500, 1000);
        retainDecodedImages();
        Character.splice(1);
        CharacterNextId = 1;
        omitted = [];
        echoHeightAdjustment = 0;
        if (snapshot.assetFamily !== "Female3DCG" || !snapshot.appearance.length) {
            status("unsupported");
            return;
        }
        const appearance = [];
        for (const entry of snapshot.appearance) {
            if (entry.group === "额外身高_Luzi") {
                const name = entry.name.replace(/_Luzi$/, "");
                const match = /^身高(加|减)(10|20|30|40)cm$/.exec(name);
                if (match) {
                    echoHeightAdjustment = (match[1] === "加" ? 1 : -1) * Number(match[2]) / 500;
                    continue;
                }
            }
            const asset = MobileBcEcho.asset(entry.group, entry.name);
            // Only audited supplement entries may combine drawing hooks with a
            // dormant ScriptDraw callback. The animation loop remains disabled.
            const frozenCallback = asset && MobileBcSupplement.allowsFrozenFrame(asset);
            if (!asset ||
                (asset.DynamicScriptDraw && (asset.DynamicBeforeDraw || asset.DynamicAfterDraw) && !frozenCallback) ||
                (asset.DynamicBeforeDraw && typeof window[`Assets${asset.Group.Name}${asset.Name}BeforeDraw`] !== "function") ||
                (asset.DynamicAfterDraw && typeof window[`Assets${asset.Group.Name}${asset.Name}AfterDraw`] !== "function")) {
                omitted.push({ group: entry.group, name: entry.name });
                continue;
            }
            const defaultColor = asset.DefaultColor ?? asset.Group.ColorSchema[0];
            const normalizeColor = (value, index) => value == null || (value === "Default" && !asset.Group.ColorSchema.includes("Default"))
                ? (index !== undefined && Array.isArray(defaultColor)
                    ? defaultColor[index] ?? defaultColor[0] ?? asset.Group.ColorSchema[0] : defaultColor) : value;
            appearance.push({ Asset: asset, Color: Array.isArray(entry.color)
                ? entry.color.map(normalizeColor) : normalizeColor(entry.color), Property: drawingProperty(asset, entry.property) });
        }
        if (!appearance.some(item => item.Asset.Visible)) {
            status("unsupported");
            return;
        }
        character = CharacterCreate("Female3DCG", CharacterType.SIMPLE, "mobile-portrait");
        character.Appearance = appearance;
        character.ActivePose = snapshot.activePose;
        // The native client owns interactions. Dynamic ScriptDraw callbacks may perform
        // gameplay work, so the preview only renders still appearances.
        character.RunScripts = false;
        CharacterLoadEffect(character);
        PoseRefresh(character);
        CharacterLoadCanvas(character);
        startedAt = performance.now();
        schedule();
    }

    window.mobileBcSetPortrait = (snapshot, nextRevision) => {
        requestedAt = performance.now();
        revision = nextRevision;
        pending = snapshot;
        MobileBcPortraitDump.input(snapshot, revision);
        omitted = [];
        if (fatal) { status("failed"); return; }
        status("loading");
        if (initialized) {
            try { apply(snapshot); } catch (error) { fail(error); }
        }
    };
    window.mobileBcClearPortrait = nextRevision => {
        revision = nextRevision;
        pending = null;
        requestedAt = 0;
        if (frameId !== null) cancelAnimationFrame(frameId);
        frameId = null;
        if (character) AnimationPurge(character, true);
        character = null;
        if (typeof Character !== "undefined") {
            Character.splice(1);
            CharacterNextId = 1;
        }
        document.getElementById("MainCanvas").getContext("2d").clearRect(0, 0, 500, 1000);
        retainDecodedImages();
        omitted = [];
        MobileBcPortraitDump.clear();
        status("unsupported");
    };
    window.mobileBcPausePortrait = value => {
        paused = value;
        if (paused && frameId !== null) {
            cancelAnimationFrame(frameId);
            frameId = null;
        } else if (!paused) {
            startedAt = performance.now();
            schedule();
        }
    };

    async function initialize() {
        MobileBcPortraitDump.record("scripts-loading");
        const response = await fetch("/portrait/scripts.json");
        if (!response.ok) throw new Error("Missing renderer manifest");
        const scripts = await response.json();
        // Fetch concurrently while preserving classic-script execution order.
        await Promise.all(scripts.map(({ path }) => new Promise((resolve, reject) => {
            const script = document.createElement("script");
            script.async = false;
            script.src = path;
            script.onload = resolve;
            script.onerror = () => reject(new Error(`Cannot load ${path}`));
            document.head.appendChild(script);
        })));
        if (fatal) return;
        MobileBcPortraitDump.record("scripts-ready", { count: scripts.length });
        Pose = PoseFemale3DCG;
        Pose.forEach(pose => { PoseRecord[pose.Name] = pose; });
        DrawLoad();
        const drawingCoordinates = CommonDrawComputeDrawingCoordinates;
        CommonDrawComputeDrawingCoordinates = (C, asset, layer, groupName, property) => {
            const result = drawingCoordinates(C, asset, layer, groupName, property);
            // Texture-mask coordinates and real BeforeDraw callbacks follow their
            // original paths; the latter are adjusted after the callback below.
            if (layer.TextureMask || asset.DynamicBeforeDraw) return result;
            return { ...result, ...legacyLayerCoordinates(C, asset, layer.Name ?? "", groupName, property) };
        };
        const callDynamic = CommonCallFunctionByNameWarn;
        CommonCallFunctionByNameWarn = (name, ...args) => {
            const result = callDynamic(name, ...args);
            const data = args[0];
            if (!/^Assets.+BeforeDraw$/i.test(name) || !data?.CA?.Asset) return result;
            const layerName = (data.L ?? "").trim().replace(/^_/, "");
            const legacy = legacyLayerCoordinates(data.C, data.CA.Asset, layerName, data.GroupName, data.Property);
            return legacy ? { ...result, ...legacy } : result;
        };
        // Match R133's WebGL layer transform on the 2D portrait canvas. CommonDraw
        // already adds translation to x/y; GLDrawImage applies it once more.
        const drawImageCanvas = DrawImageCanvas;
        DrawImageCanvas = (source, canvas, x, y, options) => {
            const sx = options?.ScaleX ?? 1, sy = options?.ScaleY ?? 1;
            const tx = options?.TranslationX ?? 0, ty = options?.TranslationY ?? 0;
            const mirror = options?.Mirror ?? false, invert = options?.Invert ?? false;
            if (sx === 1 && sy === 1 && tx === 0 && ty === 0 && !mirror && !invert)
                return drawImageCanvas(source, canvas, x, y, options);
            const image = typeof source === "string" ? DrawGetImage(source) : source;
            if (!image) return false;
            const width = image.width, height = image.height;
            const angle = (options?.Rotation ?? 0) * Math.PI / 180;
            const dx = mirror ? -width : 0, dy = invert ? -height : 0;
            const targetX = (mirror ? 500 - x : x) + tx * (mirror ? 3 : 1) + width / 2 +
                Math.cos(angle) * dx - Math.sin(angle) * dy - width * sx / 2;
            const targetY = (invert ? CanvasDrawHeight - y + 550 : y) + ty + height / 2 +
                Math.sin(angle) * dx + Math.cos(angle) * dy - height * sy / 2;
            // Native GL resolves masks against the pre-transform position, then
            // transforms the masked texture. Preserve that origin in DrawImageEx.
            const shiftX = targetX - x, shiftY = targetY - y;
            const masks = options?.AlphaMasks?.map(([mx, my, w, h]) => [mx + shiftX, my + shiftY, w, h]);
            const textures = options?.TextureAlphaMask?.map(mask => ({ ...mask, X: mask.X + shiftX, Y: mask.Y + shiftY }));
            return drawImageCanvas(image, canvas, targetX, targetY,
                { ...options, AlphaMasks: masks, TextureAlphaMask: textures, Width: width * sx, Height: height * sy });
        };
        const getHeight = CharacterGetHeight;
        CharacterGetHeight = target => {
            const value = getHeight(target);
            return target === character ? Math.max(0.01, value + echoHeightAdjustment) : value;
        };
        await MobileBcEcho.prepare(scripts);
        MobileBcPortraitDump.record("echo-ready");
        // AssetLoadAll also initializes shop, crafting and gameplay. Only build the catalog.
        AssetLoad(AssetFemale3DCG, "Female3DCG", AssetFemale3DCGExtended);
        CurrentScreen = "InformationSheet";
        CurrentModule = "Character";
        Player = CharacterCreate("Female3DCG", CharacterType.SIMPLE, "mobile-viewer");
        Player.GraphicsSettings = {};
        Player.OnlineSettings = { DisableAnimations: true };
        initialized = true;
        runtimeReadyMs = Math.round(performance.now() - runtimeStartedAt);
        MobileBcPortraitDump.record("runtime-ready");
        if (pending) apply(pending);
    }
    initialize().catch(fail);
})();
