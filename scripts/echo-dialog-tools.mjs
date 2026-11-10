// Build-time ports of the pinned utils Tools/dialogs.js pure translation builders.
export const DialogTools = {
    modularSetMessage: (key, fields, generator) => Object.fromEntries(fields.map((field,index)=>[`Set${key}${index}`,generator(field,index)])),
    dialogGenerator(configs, param, init = {}) {
        for (const config of configs) {
            const {Name, Key, Options} = config;
            const {Select, Module} = param.module(config);
            const prefixes = (Array.isArray(param.groups) ? param.groups : ['']).flatMap(group =>
                (Array.isArray(param.itemNames) ? param.itemNames : ['']).map(name => group + name));
            const write = (key, value) => {
                for (const prefix of prefixes) if (!init[prefix + key]) init[prefix + key] = value;
            };
            write('SelectBase', param.selectBase);
            write('Select' + Name, Select);
            write('Module' + Name, Module);
            Options.forEach((option, index) => {
                const {Option, Set} = param.option(option, index, config);
                write(`Option${Key}${index}`, Option);
                write(`Set${Key}${index}`, Set);
            });
        }
        return init;
    },
    repeatEntries: (...entries) => Object.fromEntries(entries.flatMap(([keys, value]) => [keys].flat().map(key => [key, value]))),
    combine: (...translations) => translations.reduce((combined, current) => Object.fromEntries(
        Object.entries(current).map(([language, entries]) => [language, { ...combined[language], ...entries }])
    ), { CN: {}, EN: {} }),
    showHide({ moduleName, key, moduleText, fullText, reverse }) {
        const show = reverse ? 1 : 0, hide = reverse ? 0 : 1;
        const cn = fullText?.CN || moduleText.CN, en = fullText?.EN || moduleText.EN;
        return {
            CN: {
                [`Module${moduleName}`]: `显示/隐藏${moduleText.CN}`,
                [`Select${moduleName}`]: `设置显示/隐藏${cn}`,
                [`Option${key}${show}`]: '显示', [`Option${key}${hide}`]: '隐藏',
                [`Set${key}${show}`]: `SourceCharacter使DestinationCharacterAssetName会露出${cn}`,
                [`Set${key}${hide}`]: `SourceCharacter使DestinationCharacterAssetName会隐藏${cn}`,
            },
            EN: {
                [`Module${moduleName}`]: `Show/Hide ${moduleText.EN}`,
                [`Select${moduleName}`]: `Set Show/Hide ${en}`,
                [`Option${key}${show}`]: 'Show', [`Option${key}${hide}`]: 'Hide',
                [`Set${key}${show}`]: `SourceCharacter makes DestinationCharacter AssetName reveal the ${en}.`,
                [`Set${key}${hide}`]: `SourceCharacter makes DestinationCharacter AssetName hide the ${en}.`,
            },
        };
    },
    autoItemStrings(strings, config, sets = {
        CN: (from, entries) => `SourceCharacter将DestinationCharacterAssetName设置为${entries[from]}`,
        EN: (from, entries) => `SourceCharacter sets DestinationCharacter AssetName to ${entries[from]}`,
    }) {
        // Preserve upstream key direction, including its modular form.
        const keys = config.Archetype === 'modular'
            ? config.Modules.flatMap(m => m.Options.map((_, i) => [`Set${m.Key}${i}`, `Option${m.Key}${i}`]))
            : config.Archetype === 'typed' ? config.Options.map(o => [o.Name, `Set${o.Name}`]) : [];
        return Object.fromEntries(Object.entries(strings).map(([language, entries]) => {
            const result = { ...entries };
            if (language in sets) for (const [from, to] of keys) {
                result[to] = sets[language](from, entries) || sets.CN(from, strings.CN);
            }
            return [language, result];
        }));
    },
};
