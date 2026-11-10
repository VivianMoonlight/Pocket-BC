package dev.vivian.mobilebc.domain.bcspeech

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import kotlin.math.floor
import kotlin.math.max

data class BcSpeechState(
    val gagIntensity: Double,
    val stutterIntensity: Double,
    val babyTalk: Boolean,
)

enum class BcSpeechStateUnavailableReason {
    UnsupportedAssetFamily,
    CatalogUnavailable,
    CatalogVersionMismatch,
    UnknownAsset,
    UnsupportedAssetMetadata,
    InvalidItemProperties,
    InvalidCraftProperties,
    InvalidArousalSettings,
}

sealed interface BcSpeechStateResolution {
    data class Available(val state: BcSpeechState) : BcSpeechStateResolution
    data class Unavailable(val reasons: Set<BcSpeechStateUnavailableReason>) : BcSpeechStateResolution
}

/** R129 CharacterGetEffects, InventoryCraftCount, and SpeechTransform*Intensity semantics. */
fun resolveBcSpeechState(
    character: BcCharacterSnapshot,
    rules: BcRuleCatalog?,
    expectedBcVersion: String?,
): BcSpeechStateResolution {
    val unavailable = linkedSetOf<BcSpeechStateUnavailableReason>()
    if (character.assetFamily != "Female3DCG") unavailable += BcSpeechStateUnavailableReason.UnsupportedAssetFamily
    if (rules == null || rules.assets.isEmpty()) unavailable += BcSpeechStateUnavailableReason.CatalogUnavailable
    val version = expectedBcVersion?.normalizedBcVersionOrNull()
    if (version == null || rules?.version?.normalizedBcVersionOrNull() != version) {
        unavailable += BcSpeechStateUnavailableReason.CatalogVersionMismatch
    }
    if (unavailable.isNotEmpty()) return BcSpeechStateResolution.Unavailable(unavailable)
    requireNotNull(rules)

    val settings = readSpeechObject(character.arousalSettingsJson)
    if (settings == null) unavailable += BcSpeechStateUnavailableReason.InvalidArousalSettings
    // Both the player and online characters normalize these preferences through ValidationApplyRecord.
    val stutterMode = settings?.get("AffectStutter")?.speechString()
        ?.takeIf { it in setOf("None", "Arousal", "Vibration", "All") } ?: "All"
    val baselineProgress = settings?.get("Progress")?.speechNumber()
        ?.takeIf { it % 1.0 == 0.0 && it in 0.0..100.0 } ?: 0.0
    // ArousalSync replaces Progress, including a missing value; the old settings JSON is not current.
    val liveArousal = character.arousal
    val progress = if (liveArousal != null) liveArousal.progress?.takeIf { it.isFinite() } ?: 0.0 else baselineProgress
    var stutter = if (stutterMode == "Arousal" || stutterMode == "All") floor(progress / 20.0) else 0.0
    var gag = 0.0
    var babyTalk = false
    character.appearance.forEach { item ->
        val asset = rules.asset(item.group, item.name)
        if (asset == null) {
            unavailable += BcSpeechStateUnavailableReason.UnknownAsset
            return@forEach
        }
        if (asset.unsupportedSpeechMetadata.any { it == "Effect" || it == "CopyConfig" }) {
            unavailable += BcSpeechStateUnavailableReason.UnsupportedAssetMetadata
        }
        val property = readSpeechObject(item.propertyJson)
        if (property == null) {
            unavailable += BcSpeechStateUnavailableReason.InvalidItemProperties
            return@forEach
        }
        val rawEffect = property["Effect"]
        // CharacterGetEffects ignores non-array Property.Effect and unions arrays with Asset.Effect.
        val propertyEffects = if (rawEffect?.startsWith("[") == true) rawEffect.speechStrings() else emptySet()
        if (propertyEffects == null) {
            unavailable += BcSpeechStateUnavailableReason.InvalidItemProperties
            return@forEach
        }
        val effects = asset.effects + propertyEffects
        if (item.group in speechGagGroups) gag += effects.sumOf { speechGagLevels[it] ?: 0.0 }
        if ("RegressedTalk" in effects) babyTalk = true
        if ("Egged" in effects && (stutterMode == "Vibration" || stutterMode == "All")) {
            val rawIntensity = property["Intensity"]?.takeUnless { it == "null" }
            // R129 AssetAdd does not put an Intensity field on the runtime asset.
            val intensity = rawIntensity?.speechNumber()
            if (rawIntensity != null && intensity == null) {
                unavailable += BcSpeechStateUnavailableReason.InvalidItemProperties
            } else if (intensity != null) {
                stutter = max(stutter, intensity)
            }
        }
        val craft = readSpeechObject(item.craftJson)
        if (craft == null) {
            unavailable += BcSpeechStateUnavailableReason.InvalidCraftProperties
            return@forEach
        }
        val craftSize = craftSpeechSize(craft, asset)
        if (craftSize == null) unavailable += BcSpeechStateUnavailableReason.InvalidCraftProperties
        else gag += craftSize * 2.0
    }
    return if (unavailable.isEmpty()) {
        BcSpeechStateResolution.Available(BcSpeechState(gag, stutter, babyTalk))
    } else {
        BcSpeechStateResolution.Unavailable(unavailable)
    }
}

private fun craftSpeechSize(craft: Map<String, String>, asset: BcAssetRule): Double? {
    val effects = readSpeechObject(craft["Effects"])?.toMutableMap() ?: return null
    val legacy = craft["Property"]?.speechString()
    // CraftingValidate migrates a legacy property only if its Effects entry is absent/falsy.
    val legacyValue = effects[legacy]
    val legacyFalsy = legacyValue == null || legacyValue == "null" || legacyValue == "false" ||
        legacyValue.speechNumber() == 0.0 || legacyValue.speechString() == ""
    if (legacy in setOf("Large", "Small") && legacyFalsy) effects[requireNotNull(legacy)] = "1"
    if ("Large" !in effects && "Small" !in effects) return 0.0
    if ("AllowEffect" in asset.unsupportedSpeechMetadata) return null
    // Native validation can repair Name/Item from the actual asset without removing its effects.
    // Valid craft Effects have at most two entries, bounded integer stacks, and no size conflict.
    // Other malformed craft candidates need the native sanitizer and are not executed here.
    if (effects.size > 2 || effects.keys.any { it !in speechCraftEffects }) return null
    if (effects.any { (name, raw) ->
            val amount = raw.speechNumber()
            val maximum = if (name in setOf("Painful", "Comfy", "Decoy")) 1 else 2
            amount == null || amount % 1.0 != 0.0 || amount !in 1.0..maximum.toDouble()
        }) return null
    if ("Large" in effects && "Small" in effects) return null
    // Large/Small are only valid for assets with a native or permitted gag effect.
    if ((asset.effects + asset.allowedEffects).none { it in speechGagLevels }) return 0.0
    return (effects["Large"]?.speechNumber() ?: 0.0) - (effects["Small"]?.speechNumber() ?: 0.0)
}

private val speechGagGroups = setOf("ItemMouth", "ItemMouth2", "ItemMouth3", "ItemHead", "ItemHood", "ItemNeck", "ItemDevices")
private val speechGagLevels = mapOf(
    "GagTotal4" to 20.0, "GagTotal3" to 16.0, "GagTotal2" to 12.0, "GagTotal" to 8.0,
    "GagVeryHeavy" to 7.0, "GagHeavy" to 6.0, "GagMedium" to 5.0, "GagNormal" to 4.0,
    "GagEasy" to 3.0, "GagLight" to 2.0, "GagVeryLight" to 1.0,
)
private val speechCraftEffects = setOf(
    "Large", "Small", "Thick", "Thin", "Secure", "Loose", "Decoy", "Malleable", "Rigid", "Simple", "Puzzling",
    "Painful", "Comfy", "Strong", "Flexible", "Nimble", "Arousing", "Dull", "Edging", "Heavy", "Light",
)

private fun String.speechString(): String? = BcItemPropertyJson.readStringValue(this)
private fun String.speechNumber(): Double? = takeIf { speechJsonNumber.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }
private val speechJsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private fun String.speechStrings(): Set<String>? {
    val values = runCatching { SpeechJsonReader(this).arrayValues() }.getOrNull() ?: return null
    val effects = values.map { it.speechString() ?: return null }
    return effects.toSet()
}

private fun readSpeechObject(raw: String?): Map<String, String>? {
    if (raw == null || raw.trim() == "null") return emptyMap()
    return runCatching { SpeechJsonReader(raw).objectEntries() }.getOrNull()
}

/** Validates incoming JSON while retaining only the top-level values needed for speech. */
private class SpeechJsonReader(private val source: String) {
    private var index = 0

    fun objectEntries(): Map<String, String> {
        val entries = readObject(0)
        whitespace()
        require(index == source.length)
        return entries
    }

    fun arrayValues(): List<String> {
        val entries = readArray(0)
        whitespace()
        require(index == source.length)
        return entries
    }

    private fun readObject(depth: Int): Map<String, String> {
        take('{')
        val entries = linkedMapOf<String, String>()
        whitespace()
        if (peek('}')) { index++; return entries }
        while (true) {
            whitespace()
            val key = readString().speechString() ?: error("Invalid key")
            take(':')
            entries[key] = readValue(depth + 1)
            whitespace()
            if (peek('}')) { index++; return entries }
            take(',')
        }
    }

    private fun readArray(depth: Int): List<String> {
        take('[')
        val values = mutableListOf<String>()
        whitespace()
        if (peek(']')) { index++; return values }
        while (true) {
            values += readValue(depth + 1)
            whitespace()
            if (peek(']')) { index++; return values }
            take(',')
        }
    }

    private fun readValue(depth: Int): String {
        require(depth <= 64)
        whitespace()
        val start = index
        when (source.getOrNull(index)) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> readString()
            else -> {
                while (index < source.length && source[index] !in ",]} \t\r\n") index++
                val value = source.substring(start, index)
                require(value in setOf("true", "false", "null") || speechJsonNumber.matches(value))
            }
        }
        return source.substring(start, index)
    }

    private fun readString(): String {
        val start = index
        take('"')
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return source.substring(start, index)
                '\\' -> when (source.getOrNull(index++)) {
                    'u' -> {
                        require(index + 4 <= source.length && source.substring(index, index + 4).all { it in "0123456789abcdefABCDEF" })
                        index += 4
                    }
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    else -> error("Invalid escape")
                }
                else -> require(char.code >= 32)
            }
        }
        error("Unclosed string")
    }

    private fun take(char: Char) {
        whitespace()
        require(source.getOrNull(index) == char)
        index++
    }

    private fun peek(char: Char): Boolean = source.getOrNull(index) == char
    private fun whitespace() { while (source.getOrNull(index) in listOf(' ', '\t', '\r', '\n')) index++ }
}
