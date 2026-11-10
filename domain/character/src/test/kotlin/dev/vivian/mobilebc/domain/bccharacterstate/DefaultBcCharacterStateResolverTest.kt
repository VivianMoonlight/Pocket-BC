package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterLovership
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBcCharacterStateResolverTest {
    private val resolver = DefaultBcCharacterStateResolver()

    @Test
    fun enclosuresRestrictOtherCharactersButDoNotBlockOwnUnrelatedSlots() {
        val enclosed = BcCharacterSnapshot("1", 1, "Self", "Self", true, appearance = listOf(
            BcCharacterItem("ItemDevices", "Box", propertyJson = """{"Effect":["Enclose"]}""")))
        fun resolve(character: BcCharacterSnapshot) = resolver.resolve(character, BcRuleCatalog.Empty, BcCharacterItemSource.Target)
        assertTrue(resolve(enclosed).isGroupAvailableForItemChange("ItemArms"))
        assertFalse(resolve(enclosed.copy(isSelf = false)).isGroupAvailableForItemChange("ItemArms"))
        assertTrue(resolve(enclosed.copy(isSelf = false)).isGroupAvailableForItemChange("ItemDevices"))
        assertFalse(resolve(enclosed.copy(appearance = enclosed.appearance + BcCharacterItem("ItemPelvis", "Belt",
            propertyJson = """{"Block":["ItemVulva"]}"""))).isGroupAvailableForItemChange("ItemVulva"))
    }

    @Test
    fun nativeCanInteractChecksTheExactBlockEffectIndependentlyOfOtherLimbs() {
        fun state(effect: String): BcCharacterRuleState = resolver.resolve(
            BcCharacterSnapshot("1", 1, "Self", "Self", true,
                appearance = listOf(BcCharacterItem("ItemHands", "Mittens", propertyJson = """{"Effect":["$effect"]}"""))),
            BcRuleCatalog.Empty, BcCharacterItemSource.Actor,
        )
        assertFalse(state("Block").canInteract())
        assertTrue(state("Block").canUseFeet())
        assertTrue(state("Block").canUseMouthOrTongue())
        assertTrue(state("BlockHands").canInteract())
        assertTrue(state("BlockMouth").canInteract())
        assertTrue(state("BlockFeet").canInteract())
        assertTrue(state("CustomBlock").canInteract())
    }

    @Test
    fun derivedCollectionsAreReusedButRebuiltForChangedItems() {
        val item = BcWornItemState(
            item = BcCharacterItem("ItemMouth", "Gag", propertyJson = """{"Effect":["Gag"]}"""),
            assetRule = null,
            source = BcCharacterItemSource.Target,
        )
        val character = BcCharacterSnapshot(
            id = "1", memberNumber = 1, name = "Target", displayName = "Target", isSelf = false,
        )
        val state = BcCharacterRuleState(character, listOf(item))
        org.junit.Assert.assertSame(item.effects, item.effects)
        org.junit.Assert.assertSame(state.effects, state.effects)
        org.junit.Assert.assertSame(state.blocks, state.blocks)
        org.junit.Assert.assertSame(state.activePose, state.activePose)
        assertTrue(state.isGagged())
        assertFalse(state.ignoringGroup("ItemMouth").isGagged())
        assertTrue(state.isGagged())
    }

    @Test
    fun resolveMergesAssetRuleAndPropertyOverlay() {
        val state = resolver.resolve(
            character = BcCharacterSnapshot(
                id = "1",
                memberNumber = 1,
                name = "Target",
                displayName = "Target",
                isSelf = false,
                appearance = listOf(
                    BcCharacterItem(
                        group = "ItemMouth",
                        name = "BallGag",
                        propertyJson = """{"Effect":["MouthOpen"],"Block":["ItemMouth"],"AllowActivity":["Bite"]}""",
                    ),
                ),
            ),
            rules = BcRuleCatalog(
                version = "test",
                groups = emptyMap(),
                activities = emptyMap(),
                assets = mapOf(
                    BcAssetKey("ItemMouth", "BallGag") to BcAssetRule(
                        group = "ItemMouth",
                        name = "BallGag",
                        effects = setOf("Gag"),
                        expose = setOf("ItemMouth"),
                    ),
                ),
            ),
            source = BcCharacterItemSource.Target,
        )

        val item = state.item("ItemMouth")
        assertEquals("BallGag", item?.name)
        assertTrue(item?.effects.orEmpty().contains("Gag"))
        assertTrue(item?.effects.orEmpty().contains("MouthOpen"))
        assertTrue(state.isMouthBlocked())
        assertTrue(item?.supportsNeed("Bite", "Bite", "ItemMouth") == true)
    }

    @Test
    fun bodyPartsUseStructuredBodyStateInsteadOfRawText() {
        val snapshot = BcCharacterSnapshot(
            id = "body",
            memberNumber = 10,
            name = "Body",
            displayName = "Body",
            isSelf = false,
            body = BcCharacterBodyState(
                hasVagina = false,
                hasPenis = true,
                hasBreasts = false,
                hasFlatChest = true,
            ),
            rawDataJson = "HasVagina HasBreasts",
        )

        assertFalse(snapshot.hasBodyPart("vulva"))
        assertTrue(snapshot.hasBodyPart("penis"))
        assertFalse(snapshot.hasBodyPart("breasts"))
        assertTrue(snapshot.hasBodyPart("flatChest"))
    }

    @Test
    fun bodyStateDerivesFromBcAppearanceGroups() {
        val body = BcCharacterBodyState.fromAppearance(
            listOf(
                BcCharacterItem(group = "Pussy", name = "Penis"),
                BcCharacterItem(group = "BodyUpper", name = "FlatSmall"),
            ),
        )

        assertFalse(body.hasVagina)
        assertTrue(body.hasPenis)
        assertFalse(body.hasBreasts)
        assertTrue(body.hasFlatChest)
    }

    @Test
    fun ownerRuleFlagsIgnoreStaleRawCharacterJson() {
        val rawOnly = BcCharacterSnapshot(
            id = "rules",
            memberNumber = 10,
            name = "Rules",
            displayName = "Rules",
            isSelf = false,
            rawDataJson = "{\"OwnerRule\":\"BlockChange\"}",
        )

        assertFalse(rawOnly.hasOwnerRuleFlag("BlockChange"))
        assertTrue(
            rawOnly.copy(
                onlineSharedSettingsJson = "{\"OwnerRule\":\"BlockChange\"}",
            ).hasOwnerRuleFlag("BlockChange"),
        )
    }

    @Test
    fun effectStateUsesBcEffectTokensNotItemNameGuesses() {
        val namedOnly = stateWithItem(
            BcCharacterItem(group = "ItemArms", name = "SuspensionChain"),
            asset = BcAssetRule(group = "ItemArms", name = "SuspensionChain"),
        )
        assertFalse(namedOnly.isSuspended())
        assertFalse(namedOnly.isChained())

        val effected = stateWithItem(
            BcCharacterItem(group = "ItemArms", name = "WoodenFrame"),
            asset = BcAssetRule(
                group = "ItemArms",
                name = "WoodenFrame",
                effects = setOf("OnBed", "Lifted", "Mounted", "IsChained", "Suspended"),
            ),
        )
        assertTrue(effected.isOnBed())
        assertTrue(effected.isLifted())
        assertTrue(effected.isMounted())
        assertTrue(effected.isChained())
        assertTrue(effected.isSuspended())
    }


    @Test
    fun suspendedUsesPoseMappingBodyAddonInsteadOfSubstringMatching() {
        val suspended = resolver.resolve(
            character = BcCharacterSnapshot(
                id = "suspended",
                memberNumber = 44,
                name = "Suspended",
                displayName = "Suspended",
                isSelf = false,
                activePose = listOf("Suspension"),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Target,
        )
        assertTrue(suspended.isSuspended())

        val substringOnly = resolver.resolve(
            character = BcCharacterSnapshot(
                id = "substring",
                memberNumber = 45,
                name = "Substring",
                displayName = "Substring",
                isSelf = false,
                activePose = listOf("SuspensionDecoration"),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Target,
        )
        assertFalse(substringOnly.isSuspended())
    }

    @Test
    fun suspendedUsesWornItemSetPoseLikeBcPoseRefresh() {
        val item = BcCharacterItem(group = "ItemDevices", name = "CeilingSuspension")
        val state = stateWithItem(
            item = item,
            asset = BcAssetRule(
                group = "ItemDevices",
                name = "CeilingSuspension",
                setPose = setOf("Suspension"),
            ),
        )

        assertEquals("Suspension", state.poseState.bodyAddon)
        assertTrue(state.isSuspended())
    }


    @Test
    fun exactEffectStateDoesNotSubstringMatchBcEffects() {
        val state = stateWithItem(
            BcCharacterItem(group = "ItemDevices", name = "DecorativeStateNames"),
            asset = BcAssetRule(
                group = "ItemDevices",
                name = "DecorativeStateNames",
                effects = setOf("NotLiftedDecoration", "UnmountedHarness", "ChainDecoration", "OnBedsideTable"),
            ),
        )

        assertFalse(state.isLifted())
        assertFalse(state.isMounted())
        assertFalse(state.isChained())
        assertFalse(state.isOnBed())
    }

    @Test
    fun kneelingUsesPoseMappingBodyLowerNotSubstringMatching() {
        val kneeling = resolver.resolve(
            character = BcCharacterSnapshot(
                id = "kneeling",
                memberNumber = 50,
                name = "Kneeling",
                displayName = "Kneeling",
                isSelf = false,
                activePose = listOf("KneelingSpread"),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Target,
        )
        assertTrue(kneeling.isKneeling())

        val substringOnly = resolver.resolve(
            character = BcCharacterSnapshot(
                id = "kneel-substring",
                memberNumber = 51,
                name = "KneelSubstring",
                displayName = "KneelSubstring",
                isSelf = false,
                activePose = listOf("KneelingDecoration"),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Target,
        )
        assertFalse(substringOnly.isKneeling())
    }

    @Test
    fun poseAvailabilityUsesAllowedActivePoseMapping() {
        val state = stateWithItem(
            BcCharacterItem(group = "ItemLegs", name = "KneelOnlyDevice"),
            asset = BcAssetRule(
                group = "ItemLegs",
                name = "KneelOnlyDevice",
                allowActivePose = setOf("Kneel"),
                setPose = setOf("Kneel"),
            ),
        )

        assertTrue(state.poseAvailable("Kneel"))
        assertFalse(state.poseAvailable("KneelingSpread"))
        assertFalse(state.poseAvailable("AllFours"))
    }

    @Test
    fun relationshipStateUsesBcFamilyNotLoverAsFamily() {
        val actor = BcCharacterSnapshot(
            id = "actor",
            memberNumber = 100,
            name = "Actor",
            displayName = "Actor",
            isSelf = false,
            ownership = BcCharacterOwnership(name = "Owner", memberNumber = 900),
        )
        val siblingTarget = BcCharacterSnapshot(
            id = "sibling",
            memberNumber = 200,
            name = "Sibling",
            displayName = "Sibling",
            isSelf = false,
            ownerMemberNumber = 900,
        )
        val loverOnlyTarget = BcCharacterSnapshot(
            id = "lover",
            memberNumber = 300,
            name = "Lover",
            displayName = "Lover",
            isSelf = false,
            lovership = listOf(BcCharacterLovership(name = "Actor", memberNumber = 100)),
        )

        assertTrue(actor.isFamilyOf(siblingTarget))
        assertFalse(actor.isFamilyOf(loverOnlyTarget))
        assertTrue(actor.isLoverOf(loverOnlyTarget))
    }

    @Test
    fun relationshipStateDetectsOwnerLoverWhitelistAndBlacklist() {
        val actor = BcCharacterSnapshot(
            id = "1",
            memberNumber = 100,
            name = "Actor",
            displayName = "Actor",
            isSelf = false,
        )
        val target = BcCharacterSnapshot(
            id = "2",
            memberNumber = 200,
            name = "Target",
            displayName = "Target",
            isSelf = false,
            ownerMemberNumber = 100,
            whiteList = listOf(100),
            blackList = listOf(300),
        )

        assertTrue(actor.isOwnerOf(target))
        assertTrue(actor.isFamilyOf(target))
        assertTrue(actor.isWhitelistedBy(target))
        assertFalse(actor.memberNumber in target.blackList)
    }
    private fun stateWithItem(
        item: BcCharacterItem,
        asset: BcAssetRule,
        activePose: List<String> = emptyList(),
    ): BcCharacterRuleState = resolver.resolve(
        character = BcCharacterSnapshot(
            id = "effect",
            memberNumber = 42,
            name = "Effect",
            displayName = "Effect",
            isSelf = false,
            activePose = activePose,
            appearance = listOf(item),
        ),
        rules = BcRuleCatalog(
            version = "effect-test",
            groups = emptyMap(),
            activities = emptyMap(),
            assets = mapOf(BcAssetKey(asset.group, asset.name) to asset),
        ),
        source = BcCharacterItemSource.Target,
    )

}
