package dev.vivian.mobilebc.domain.bcspeech

import dev.vivian.mobilebc.core.model.BcCharacterArousal
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSpeechStateTest {
    @Test
    fun stacksSevenGagGroupsButDeduplicatesWithinEachItemAndIgnoresOtherGroups() {
        val character = character(
            appearance = listOf("ItemMouth", "ItemMouth2", "ItemMouth3", "ItemHead", "ItemHood", "ItemNeck", "ItemDevices", "ItemArms")
                .map { BcCharacterItem(it, "Normal", propertyJson = """{"Effect":["GagLight","GagNormal","GagNormal"]}""") },
        )
        assertEquals(42.0, state(character).gagIntensity, 0.0)
    }

    @Test
    fun inheritedAssetEffectsRemainPresentWhenPropertyEffectIsExplicitlyEmpty() {
        assertEquals(4.0, state(character(listOf(BcCharacterItem("ItemMouth", "Inherited", propertyJson = """{"Effect":[]}""")))).gagIntensity, 0.0)
        assertEquals(0.0, state(character(listOf(BcCharacterItem("ItemMouth", "Empty")))).gagIntensity, 0.0)
    }

    @Test
    fun legacyCraftSizeMigratesWithoutOverwritingTruthyModernStackAndCountsOutsideGagGroups() {
        val character = character(listOf(
            BcCharacterItem("ItemArms", "Normal", craftJson = """{"Name":"A","Item":"Normal","Property":"Large"}"""),
            BcCharacterItem("ItemMouth", "Normal", craftJson = """{"Name":"B","Item":"Normal","Property":"Large","Effects":{"Large":2}}"""),
            BcCharacterItem("ItemMouth2", "Normal", craftJson = """{"Name":"C","Item":"Normal","Effects":{"Small":1}}"""),
        ))
        // Mouth+Mouth2 base effects = 4; (Large 1+2 - Small 1)*2 = 4.
        assertEquals(8.0, state(character).gagIntensity, 0.0)
    }

    @Test
    fun sizeCraftUsesAssetAllowedEffectsAndNeverPropertyOnlyGagEffectForEligibility() {
        val craft = """{"Name":"Size","Item":"Allowed","Effects":{"Large":1}}"""
        assertEquals(2.0, state(character(listOf(BcCharacterItem("ItemArms", "Allowed", craftJson = craft)))).gagIntensity, 0.0)
        assertEquals(4.0, state(character(listOf(BcCharacterItem("ItemMouth", "Empty", propertyJson = """{"Effect":["GagNormal"]}""", craftJson = craft)))).gagIntensity, 0.0)
    }

    @Test
    fun babyTalkUsesEffectsFromAnyAppearanceItemAndPropertyUnion() {
        assertTrue(state(character(listOf(BcCharacterItem("ItemArms", "Normal", propertyJson = """{"Effect":["RegressedTalk"]}""")))).babyTalk)
        assertTrue(state(character(listOf(BcCharacterItem("ItemArms", "Baby", propertyJson = """{"Effect":[]}""")))).babyTalk)
    }

    @Test
    fun liveArousalOverridesBaselineProgressAndMissingLiveProgressDoesNotReviveOldValue() {
        val baseline = character(settings = """{"AffectStutter":"Arousal","Progress":100}""")
        assertEquals(5.0, state(baseline).stutterIntensity, 0.0)
        assertEquals(1.0, state(baseline.copy(arousal = BcCharacterArousal(progress = 39.0))).stutterIntensity, 0.0)
        assertEquals(0.0, state(baseline.copy(arousal = BcCharacterArousal())).stutterIntensity, 0.0)
    }

    @Test
    fun vibrationUsesMaxEggedItemIntensityWithNativePreferenceModes() {
        val appearance = listOf(
            BcCharacterItem("ItemArms", "Egg", propertyJson = """{"Intensity":3}"""),
            BcCharacterItem("ItemNeck", "Normal", propertyJson = """{"Effect":["Egged"],"Intensity":2}"""),
            BcCharacterItem("ItemHead", "Empty", propertyJson = """{"Effect":["Vibrating"],"Intensity":9}"""),
        )
        assertEquals(3.0, state(character(appearance, """{"AffectStutter":"Vibration","Progress":100}""")).stutterIntensity, 0.0)
        assertEquals(5.0, state(character(appearance, """{"AffectStutter":"All","Progress":100}""")).stutterIntensity, 0.0)
        assertEquals(0.0, state(character(appearance, """{"AffectStutter":"None","Progress":100}""")).stutterIntensity, 0.0)
        assertEquals(0.0, state(character(listOf(BcCharacterItem("ItemArms", "Egg")))).stutterIntensity, 0.0)
    }

    @Test
    fun absentAndInvalidPreferencesUseNativeDefaultsRatherThanTurningOffVibrationStutter() {
        val appearance = listOf(BcCharacterItem("ItemArms", "Egg", propertyJson = """{"Intensity":2}"""))
        assertEquals(2.0, state(character(appearance)).stutterIntensity, 0.0)
        assertEquals(2.0, state(character(appearance, """{"AffectStutter":"FutureMode","Progress":"100"}""")).stutterIntensity, 0.0)
    }

    @Test
    fun unknownMetadataVersionFamilyAndMalformedJsonNeverYieldPartialTransformState() {
        fun unavailable(character: BcCharacterSnapshot, version: String? = "R129") =
            resolveBcSpeechState(character, catalog, version) as BcSpeechStateResolution.Unavailable
        assertTrue(BcSpeechStateUnavailableReason.UnknownAsset in unavailable(character(listOf(BcCharacterItem("ItemArms", "Missing")))).reasons)
        assertTrue(BcSpeechStateUnavailableReason.UnsupportedAssetFamily in unavailable(character().copy(assetFamily = "Custom")).reasons)
        assertTrue(BcSpeechStateUnavailableReason.CatalogVersionMismatch in unavailable(character(), "R128").reasons)
        assertTrue(BcSpeechStateUnavailableReason.InvalidItemProperties in unavailable(character(listOf(BcCharacterItem("ItemArms", "Normal", propertyJson = """{"Effect":["RegressedTalk"], BROKEN}""")))).reasons)
        assertTrue(BcSpeechStateUnavailableReason.UnsupportedAssetMetadata in unavailable(character(listOf(BcCharacterItem("ItemArms", "Dynamic")))).reasons)
    }

    @Test
    fun unsupportedCraftSizeSchemasAreExplicitlyUnavailable() {
        listOf(
            """{"Effects":{"Large":1.5}}""",
            """{"Property":"Large","Effects":{"Large":"2"}}""",
            """{"Effects":{"Large":1,"Small":1}}""",
            """{"Effects":{"Large":1,"Future":1}}""",
        ).forEach { properties ->
            val craft = properties.dropLast(1) + """, "Name":"A","Item":"Normal"}"""
            val resolved = resolveBcSpeechState(character(listOf(BcCharacterItem("ItemArms", "Normal", craftJson = craft))), catalog, "R129")
            assertTrue(craft, resolved is BcSpeechStateResolution.Unavailable)
        }
    }

    @Test
    fun nonArrayPropertyEffectsAreIgnoredAsInCharacterGetEffects() {
        assertEquals(2.0, state(character(listOf(BcCharacterItem("ItemMouth", "Normal", propertyJson = """{"Effect":"GagTotal4"}""")))).gagIntensity, 0.0)
    }

    @Test
    fun modularGagCraftUsesEffectsFromUnselectedNativeOptions() {
        val rules = BcRuleCatalogParser().parse("R129", """
            var AssetFemale3DCG = [{ Group: "ItemMouth", Asset: [{ Name: "Panel", Effect: ["BlockMouth"] }] }];
        """.trimIndent(), """
            var AssetFemale3DCGExtended = { ItemMouth: { Panel: {
                Archetype: ExtendedArchetype.MODULAR,
                Modules: [{ Name: "Gag", Key: "g", Options: [{}, { Property: { Effect: ["GagMedium"] } }] }],
            } } };
        """.trimIndent())
        val character = character(listOf(BcCharacterItem("ItemMouth", "Panel", propertyJson = """{"Effect":["GagVeryLight"]}""", craftJson = """{"Property":"Large"}""")))
        val state = (resolveBcSpeechState(character, rules, "R129") as BcSpeechStateResolution.Available).state
        assertEquals(3.0, state.gagIntensity, 0.0)
    }

    private fun state(character: BcCharacterSnapshot): BcSpeechState =
        (resolveBcSpeechState(character, catalog, "R129") as BcSpeechStateResolution.Available).state

    private fun character(appearance: List<BcCharacterItem> = emptyList(), settings: String? = null) = BcCharacterSnapshot(
        id = "1", memberNumber = 1, name = "Self", displayName = "Self", isSelf = true,
        appearance = appearance, arousalSettingsJson = settings,
    )

    private val catalog = BcRuleCatalogParser().parse("R129", """
        const E = /** @type {const} */ ({ GagLight: "GagLight", Egged: "Egged", RegressedTalk: "RegressedTalk" });
        var AssetFemale3DCG = [
            ${listOf("ItemMouth", "ItemMouth2", "ItemMouth3", "ItemHead", "ItemHood", "ItemNeck", "ItemDevices", "ItemArms").joinToString(",") { group -> """
                { Group: "$group", Effect: ["GagNormal"], Asset: [
                    { Name: "Normal", Effect: [E.GagLight] },
                    { Name: "Inherited" },
                    { Name: "Empty", Effect: [] },
                    { Name: "Allowed", Effect: [], AllowEffect: ["GagHeavy"] },
                    { Name: "Baby", Effect: [E.RegressedTalk] },
                    { Name: "Egg", Effect: [E.Egged], Intensity: 99 },
                    { Name: "Dynamic", Effect: [computeEffect()] },
                ] }
            """ }}
        ];
    """.trimIndent())
}
