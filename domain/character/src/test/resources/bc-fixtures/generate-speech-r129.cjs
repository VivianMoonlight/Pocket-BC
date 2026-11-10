// Regenerate with node generate-speech-r129.cjs <path to the R129 BondageClub directory>.
// This executes the native source; it does not reimplement the expected transforms.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const sourceRoot = process.argv[2];
if (!sourceRoot) throw new Error("Pass the R129 BondageClub directory");
const context = vm.createContext({ TranslationLanguage: "EN" });
vm.runInContext(fs.readFileSync(path.join(sourceRoot, "Scripts/Speech.js"), "utf8"), context);
const roomSource = fs.readFileSync(path.join(sourceRoot, "Screens/Online/ChatRoom/ChatRoom.js"), "utf8");
const start = roomSource.indexOf("function ChatRoomGenerateChatRoomChatMessage(");
const end = roomSource.indexOf("\n}\n", start) + 2;
if (start < 0 || end < start) throw new Error("Native message generator not found");
vm.runInContext(roomSource.slice(start, end), context);
vm.runInContext(`
    SpeechTransformShouldBabyTalk = C => C.babyTalk;
    SpeechTransformGagGarbleIntensity = C => C.gagIntensity;
    SpeechTransformStutterIntensity = C => C.stutterIntensity;
    DictionaryBuilder = class { sourceCharacter() { return this; } build() { return []; } };
`, context);
const encode = value => Buffer.from(value, "utf8").toString("base64");
const rows = ["# Native R129 Speech.js and ChatRoomGenerateChatRoomChatMessage; operation language baby gag stutter autoClose noSpeechGarble input output effects original (tab separated)"];
function fixture(operation, input, {
    language = "EN", baby = false, gag = 0, stutter = 0, autoClose = true, noSpeechGarble = false,
} = {}) {
    context.TranslationLanguage = language;
    let output;
    let effects = [];
    let original = null;
    if (operation === "baby") output = context.SpeechTransformBabyTalk(input);
    if (operation === "stutter") output = context.SpeechTransformStutter(input, stutter);
    if (operation === "close" || operation === "prepare") {
        context.Player = {
            babyTalk: operation === "prepare" && baby,
            gagIntensity: operation === "prepare" ? gag : 0,
            stutterIntensity: operation === "prepare" ? stutter : 0,
            ChatSettings: { OOCAutoClose: autoClose },
            RestrictionSettings: { NoSpeechGarble: noSpeechGarble },
        };
        const result = context.ChatRoomGenerateChatRoomChatMessage("Chat", input);
        output = result.Content;
        const record = result.Dictionary.find(entry => Object.hasOwn(entry, "Original"));
        original = record?.Original ?? null;
        effects = operation === "prepare"
            ? context.SpeechTransformProcess(context.Player, input, ["babyTalk", "gagGarble", "stutter"]).effects
            : [];
    }
    rows.push([operation, language, baby, gag, stutter, autoClose, noSpeechGarble, encode(input), encode(output), effects.join(","), original == null ? "-" : encode(original)].join("\t"));
}
const samples = [
    "Hello there friend!", "STILL TALKING (OOC text)", "left (First) middle (Last", "(a(b)c", "(",
    "123 \t\nLatin ÉÍñç ëïü İ ß 日本 中文 ЁЇ Єґ 😀", "[brackets] next word", "one-two three\nfour five",
    "(note) after words", "abc (Protected) next (Second) end", "Hello (All OOC", "（全角括号）你好！",
];
for (const language of ["EN", "CN", "JP"]) {
    for (const input of samples) fixture("baby", input, { language });
}
for (const stutter of [0, 1, 3, 5, 10, 2.5]) {
    for (const input of samples) fixture("stutter", input, { stutter });
}
fixture("stutter", Array.from({ length: 500 }, (_, i) => `word${i}`).join(" "), { stutter: 5 });
for (const input of ["(", "(a", "(a)", "text (a", "(a(b)c", "(a) (b", "（a", "(a) (", "a)", "", "(a) text", "(a) (b) text ("]) {
    fixture("close", input);
    fixture("prepare", input, { autoClose: false });
}
for (const language of ["EN", "CN"]) {
    for (const input of samples) {
        for (const noSpeechGarble of [false, true]) {
            fixture("prepare", input, { language, baby: true, gag: 4, stutter: 3, noSpeechGarble });
            fixture("prepare", input, { language, gag: 6, stutter: 2.5, noSpeechGarble, autoClose: false });
        }
    }
}
fs.writeFileSync(path.join(__dirname, "SpeechTransform-R129.tsv"), rows.join("\n") + "\n", "utf8");
console.log(`Generated ${rows.length - 1} native fixtures`);
