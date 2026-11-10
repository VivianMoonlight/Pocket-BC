// ==UserScript==
// @name         BC R133 Craft Recovery
// @namespace    local.bc.craft-recovery
// @version      1.0.0
// @description  Inspect and restore missing crafts from the JSON import preview.
// @match        https://www.bondageprojects.elementfx.com/R133/BondageClub/*
// @match        https://bondageprojects.elementfx.com/R133/BondageClub/*
// @match        https://www.bondage-europe.com/R133/BondageClub/*
// @match        https://www.bondage-asia.com/R133/BondageClub/*
// @grant        none
// @sandbox      raw
// @run-at       document-idle
// ==/UserScript==

(() => {
    "use strict";
    const clone = value => JSON.parse(JSON.stringify(value));
    const equal = (a, b) => {
        if (a === b) return true;
        if (!a || !b || typeof a !== "object" || typeof b !== "object") return false;
        const keys = Object.keys(a).filter(k => a[k] !== undefined);
        return keys.length === Object.keys(b).filter(k => b[k] !== undefined).length
            && keys.every(k => equal(a[k], b[k]));
    };

    window.BCCraftRecovery = ({ apply = false, item = "ModularChastityBelt" } = {}) => {
        if (typeof Player === "undefined" || !Array.isArray(Player.Crafting)
            || typeof CraftingJSON === "undefined" || !Array.isArray(CraftingJSON._craftListCache)) {
            throw new Error("Open the JSON import preview and select the original backup first.");
        }
        const planned = [], report = [];
        CraftingJSON._craftListCache.forEach((entry, slot) => {
            if (entry?.craft?.Item !== item) return;
            const craft = clone(entry.craft);
            const row = { slot: slot + 1, name: craft.Name, result: "" };
            report.push(row);
            if (slot >= 200) { row.result = "Outside native 200 slots"; return; }
            if (Player.Crafting.some(c => c?.Item === item && c.Name === craft.Name)) {
                row.result = "Already present"; return;
            }
            if (Player.Crafting[slot] != null) { row.result = "Occupied; untouched"; return; }
            try {
                const original = clone(craft);
                const status = CraftingValidate(craft, null, false, true);
                if (status !== 2 || !equal(original, craft) || craft.Disabled) {
                    row.result = `Validation rejected or changed data (status ${status})`; return;
                }
                const encoded = CraftingSerialize(craft);
                if (typeof encoded !== "string" || !encoded) throw new Error("Empty serialization");
                const restored = CraftingDeserialize(encoded);
                if (!restored || CraftingValidate(restored, null, false, true) !== 2 || restored.Disabled) {
                    throw new Error("Read-back validation failed");
                }
                for (const key of ["Item", "Name", "Description", "Color", "Lock", "Private", "TypeRecord", "Effects"]) {
                    if (!equal(craft[key], restored[key])) throw new Error(`Read-back changed ${key}`);
                }
                if (CraftingSerialize(restored) !== encoded) throw new Error("Unstable save/read-back");
                planned.push({ slot, craft });
                row.result = "Ready";
            } catch (error) { row.result = String(error.message ?? error); }
        });
        console.table(report);
        if (!apply || !planned.length) return { applied: 0, report };

        const previous = Player.Crafting;
        const backup = CraftingJSON.encode(previous);
        const url = URL.createObjectURL(new Blob([JSON.stringify(backup, null, 2)], { type: "application/json" }));
        const link = document.createElement("a");
        link.href = url;
        link.download = `craft-before-recovery-${Date.now()}.json`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 30000);
        const updated = previous.slice();
        for (const { slot, craft } of planned) updated[slot] = craft;
        Player.Crafting = updated;
        try { CraftingSaveServer(); }
        catch (error) { Player.Crafting = previous; throw error; }
        console.info("Recovery queued for saving. Close the import preview using Cancel, then reopen the item selector. Server persistence is not yet verified.");
        return { applied: planned.length, report };
    };
})();
