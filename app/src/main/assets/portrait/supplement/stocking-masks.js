"use strict";

// Pinned Echo oil-stocking mask composition, CC BY-NC-SA 4.0; see ../echo/NOTICE.md.
function MobileBcStockingMaskRecipe(url) {
    const match = /^Assets\/Female3DCG\/Suit\/(?:(Yoked|OverTheHead|BackBoxTie|BackCuffs|BackElbowTouch)\/)?马油袜_(Small|Normal|Large|XLarge)_m([0-4])_gmask\.png$/.exec(url);
    if (!match) return null;
    const [, pose = '', size, type] = match;
    const source = (layer, sourcePose = pose) => `Assets/Female3DCG/Suit/${sourcePose ? sourcePose + '/' : ''}马油袜_${size}_${layer}.png`;
    const images = [];
    if (type === '1' || type === '2') images.push(source('m1_mmask', ''));
    if (type === '2' || type === '3') {
        if (pose !== 'BackElbowTouch') images.push(source('a1_mmask'));
        if (!['BackBoxTie','BackElbowTouch'].includes(pose)) images.push(source('a2_mmask'));
    }
    if (type === '4') images.push(source('c_mmask', ''));
    return { fill: type === '0', images };
}

function MobileBcInstallStockingMasks(catalog, cache) {
    if (!catalog.sources.includes('src/components/衣服和装饰/衣服/全身连体/油光袜.js')) return () => null;
    const waiting = new Set();
    const changed = cache._imageStateDidChange.bind(cache);
    cache._imageStateDidChange = image => {
        changed(image);
        for (const check of [...waiting]) check();
    };
    // Reuse BC's decoding, loaded/failed states, disposal and generation checks.
    // Only the byte source differs; no network request is made for a generated mask.
    class StockingMask extends CachedImage {
        constructor(url, recipe) { super(cache, url); this.recipe = recipe; this.cancel = null; }
        _fetch() {
            return new Promise((resolve, reject) => {
                const images = this.recipe.images.map(url => cache.get(url));
                const finish = error => {
                    waiting.delete(check);
                    this.cancel = null;
                    if (error) { reject(error); return; }
                    try {
                        const canvas = document.createElement('canvas');
                        canvas.width = 500; canvas.height = 1000;
                        const context = canvas.getContext('2d');
                        if (this.recipe.fill) { context.fillStyle = 'black'; context.fillRect(0,0,500,1000); }
                        for (const image of images) context.drawImage(image.data,0,0);
                        canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Cannot encode stocking mask')));
                    } catch (error) { reject(error); }
                };
                const check = () => {
                    if (images.some(image => image.isFailing())) finish(new Error('Stocking mask source unavailable'));
                    else if (images.every(image => image.isLoaded())) finish();
                };
                this.cancel = () => finish(new Error('Stocking mask unloaded'));
                waiting.add(check);
                check();
            });
        }
        unload() { this.cancel?.(); super.unload(); }
    }
    const get = cache.get.bind(cache);
    return url => {
        const recipe = MobileBcStockingMaskRecipe(url);
        if (!recipe) return null;
        if (!cache.has(url)) cache.cache.set(url, new StockingMask(url, recipe));
        return get(url);
    };
}
