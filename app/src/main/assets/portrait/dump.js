"use strict";

// A bounded, local diagnostic record. Capture is read-only and never sends character updates.
var MobileBcPortraitDump = (() => {
    const started = performance.now();
    const events = [];
    let scene = null;
    let lastState = null;
    function record(stage, details = {}) {
        events.push({ ms: Math.round(performance.now() - started), stage, ...details });
        if (events.length > 64) events.shift();
    }
    function input(snapshot, revision) {
        scene = JSON.parse(JSON.stringify({ assetFamily: snapshot.assetFamily, activePose: snapshot.activePose, appearance: snapshot.appearance }));
        lastState = null;
        record("input", { revision, items: snapshot.appearance.length });
    }
    function state(value) {
        const key = `${value.revision}:${value.state}:${value.missingImages}`;
        if (key !== lastState) {
            record("state", { ...value });
            lastState = key;
        }
    }
    function capture({ includeScene = false, includeCanvas = false } = {}) {
        const canvas = document.getElementById("MainCanvas");
        const rect = element => {
            const { x, y, width, height } = element.getBoundingClientRect();
            return { x, y, width, height };
        };
        const images = typeof DrawImageCache === "undefined" ? [] : [...DrawImageCache.cache.values()].map(image => ({
            url: image.url, state: image.state,
        }));
        let opaquePixels = 0;
        let pixelError = null;
        try {
            const pixels = canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data;
            for (let i = 3; i < pixels.length; i += 4) if (pixels[i]) opaquePixels++;
        } catch (error) { pixelError = String(error); }
        const bounds = rect(canvas);
        const report = {
            format: 1,
            capturedAt: new Date().toISOString(),
            environment: {
                userAgent: navigator.userAgent,
                bcVersion: typeof GameVersion === "undefined" ? null : GameVersion,
                echoRevision: typeof MobileBcEcho === "undefined" ? null : MobileBcEcho.revision,
            },
            status: window.mobileBcPortraitStatus,
            events: [...events],
            layout: { viewport: { width: innerWidth, height: innerHeight }, html: rect(document.documentElement), body: rect(document.body), canvas: bounds },
            canvas: { width: canvas.width, height: canvas.height, opaquePixels, pixelError },
            images,
            checks: {
                hasLayout: bounds.width > 0 && bounds.height > 0,
                hasPixels: pixelError === null && opaquePixels > 0,
                imagesLoaded: images.filter(image => image.state === "loaded").length,
                imagesFailed: images.filter(image => image.state === "failed").length,
                imagesPending: images.filter(image => image.state !== "loaded" && image.state !== "failed").length,
            },
        };
        if (includeScene && scene) report.scene = JSON.parse(JSON.stringify(scene));
        if (includeCanvas) {
            try { report.canvas.png = canvas.toDataURL("image/png"); }
            catch (error) { report.canvas.exportError = String(error); }
        }
        return report;
    }
    record("page");
    function clear() {
        scene = null;
        lastState = null;
        events.length = 0;
        record("cleared");
    }
    return { record, input, state, capture, clear };
})();
