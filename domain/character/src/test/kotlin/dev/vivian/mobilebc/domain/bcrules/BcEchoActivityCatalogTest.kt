package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcEchoLeashRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcEchoActivityCatalogTest {
    @Test
    fun englishLocalizationChangesPresentationWithoutChangingProtocolNames() {
        val rules = BcEchoActivityCatalog.localizedRules("EN")
        val enterArms = checkNotNull(rules.firstOrNull { it.name == "钻进怀里" })
        val carry = checkNotNull(rules.firstOrNull { it.name == "扛起" })

        assertEquals("钻进怀里", enterArms.name)
        assertEquals("Get In Arms", enterArms.displayName)
        assertEquals(
            "SourceCharacter snuggles into DestinationCharacter arms.",
            enterArms.chatText,
        )
        assertEquals("扛起", carry.name)
        assertEquals("Carry on the Back", carry.displayName)
        assertEquals("扛起来的麻袋", (carry.effects.single() as BcActivityEffect.EchoPairedItem).name)
    }

    @Test
    fun echoRulesCarryUpstreamChatFallbackTemplates() {
        val wingPat = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "翅膀轻拍" })
        val headTilt = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "歪头" })
        val footMassage = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "脚按摩" })
        val tentaclePoke = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "触手戳" })
        val penisNuzzle = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "阴茎摩擦" })

        assertEquals(
            "SourceCharacter用翅膀轻轻拍了拍TargetCharacter的手臂。",
            wingPat.chatTextFor("ItemArms", isSelfTarget = false),
        )
        assertEquals("SourceCharacter歪头.", headTilt.chatTextFor("ItemHead", isSelfTarget = true))
        assertEquals(
            "SourceCharacter用脚轻柔地按摩着DestinationCharacter的小腹。",
            footMassage.chatTextFor("ItemPelvis", isSelfTarget = false),
        )
        assertEquals(
            "SourceCharacter用触手轻轻地戳了戳DestinationCharacter脸.",
            tentaclePoke.chatTextFor("ItemMouth", isSelfTarget = true),
        )
        assertEquals(
            "SourceCharacter用阴茎摩擦TargetCharacter的手臂.",
            penisNuzzle.chatTextFor("ItemArms", isSelfTarget = false),
        )
    }

    @Test
    fun snapshotKeepsSelfAndOtherTargetsSeparate() {
        val selfOnly = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "歪头" })
        val otherOnly = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "翅膀轻拍" })

        assertTrue(selfOnly.targetGroups.isEmpty())
        assertEquals(setOf("ItemNeck", "ItemHead"), selfOnly.selfTargetGroups)
        assertEquals(setOf("ItemArms"), otherOnly.targetGroups)
        assertTrue(otherOnly.selfTargetGroups.isEmpty())
    }

    @Test
    fun snapshotOnlyPublishesCuratedReliableActions() {
        assertEquals(151, BcEchoActivityCatalog.rules.size)
        assertEquals(BcEchoActivityCatalog.rules.size, BcEchoActivityCatalog.rules.map { it.name }.distinct().size)
        assertTrue(BcEchoActivityCatalog.rules.all { it.targetGroups.isNotEmpty() || it.selfTargetGroups.isNotEmpty() })
        assertTrue(BcEchoActivityCatalog.rules.all { it.source == BcActivitySource.Echo })
        assertFalse(BcEchoActivityCatalog.rules.any { "Luzi_CharacterViewWithinReach" in it.prerequisites })
        assertFalse(BcEchoActivityCatalog.rules.any { "Luzi_IsLeashingTarget" in it.prerequisites })
        assertFalse(BcEchoActivityCatalog.rules.any { "Luzi_LeashedBy" in it.prerequisites })
        assertTrue(
            BcEchoActivityCatalog.rules.flatMap { it.prerequisites }.all(RELIABLE_PREREQUISITES::contains),
        )
    }

    private companion object {
        val RELIABLE_PREREQUISITES = setOf(
            "HasVagina",
            "IsGagged",
            "Luzi_ActedZoneNaked",
            "Luzi_CanWalk",
            "Luzi_Female",
            "Luzi_HasBreast",
            "Luzi_HasCatTail",
            "Luzi_HasKennel",
            "Luzi_HasPawMittens",
            "Luzi_HasPenis",
            "Luzi_HasPetSuit",
            "Luzi_HasSword",
            "Luzi_HasTail",
            "Luzi_HasTentacles",
            "Luzi_HasWings",
            "Luzi_Has鱼鱼尾",
            "Luzi_IsStanding",
            "Luzi_IsKneeling",
            "Luzi_IsAllFours",
            "Luzi_PrivateExposed",
            "Luzi_TargetHasBreast",
            "Luzi_TargetHasCatTail",
            "Luzi_TargetHasItemVulva",
            "Luzi_TargetHasPawMittens",
            "Luzi_TargetHasTentacles",
            "Luzi_TargetKneelOrAllFours",
            "Luzi_TargetLeashedOrCanBeLeashed",
            "MoveHead",
            "TargetKneeling",
            "TargetZoneNaked",
            "UseArms",
            "UseFeet",
            "UseHands",
            "UseMouth",
            "UseTongue",
            "CanKneel",
            "CanBaseLower",
            "CanKneelingSpread",
            "CanAllFours",
        )
    }

    @Test
    fun poseAndExpressionEffectActionsAreExplicitAndSelfOnly() {
        val effectRules = BcEchoActivityCatalog.rules.filter { rule ->
            rule.effects.any { it is BcActivityEffect.Pose || it is BcActivityEffect.Expression }
        }

        assertEquals(11, effectRules.size)
        assertTrue(effectRules.all { it.targetGroups.isEmpty() })
        assertTrue(effectRules.all { it.selfTargetGroups.isNotEmpty() })
        assertEquals(
            setOf("跪下", "站起来", "跪着张开腿", "跪着并拢腿", "四肢着地", "起身跪下", "宠物服趴下", "宠物服跪立", "张开嘴", "闭上嘴", "闭上眼睛"),
            effectRules.map { it.name }.toSet(),
        )
    }

    @Test
    fun cuddleActionsCarryEchoPairingDirectionsAndFallbackText() {
        val enterArms = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "钻进怀里" })
        val holdInArms = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "抱入怀中" })
        val expectedGroups = setOf("ItemTorso", "ItemTorso2", "ItemArms")
        val expectedRequirements = listOf(
            BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Actor, "ItemMisc"),
            BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Target, "ItemMisc"),
        )

        assertEquals(expectedGroups, enterArms.targetGroups)
        assertEquals(expectedGroups, holdInArms.targetGroups)
        assertEquals(setOf("Luzi_CanWalk"), enterArms.prerequisites)
        assertEquals(setOf("Luzi_CanWalk"), holdInArms.prerequisites)
        assertEquals(expectedRequirements, enterArms.stateRequirements)
        assertEquals(expectedRequirements, holdInArms.stateRequirements)
        assertEquals("SourceCharacter钻进了DestinationCharacter怀里.", enterArms.chatText)
        assertEquals("SourceCharacter把TargetCharacter抱进了怀中.", holdInArms.chatText)
        assertEquals(
            BcActivityEffect.EchoPairedItem(
                group = "ItemMisc",
                name = "贴贴",
                actorRelation = BcEchoDrawRelation.PreviousCharacter,
                actorLeash = BcEchoLeashRole.Lead,
            ),
            enterArms.effects.single(),
        )
        assertEquals(
            BcActivityEffect.EchoPairedItem(
                group = "ItemMisc",
                name = "贴贴",
                actorRelation = BcEchoDrawRelation.NextCharacter,
                actorLeash = BcEchoLeashRole.Follow,
            ),
            holdInArms.effects.single(),
        )
    }

    @Test
    fun carryAndTrolleyActionsRequireExactTargetDevices() {
        val carry = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "扛起" })
        val trolley = checkNotNull(BcEchoActivityCatalog.rules.firstOrNull { it.name == "手推车" })

        assertEquals("背起", carry.displayName)
        assertEquals(setOf("ItemTorso"), carry.targetGroups)
        assertEquals("SourceCharacter将TargetCharacter背了起来.", carry.chatText)
        assertEquals(
            listOf(
                BcActivityStateRequirement.GroupEmptyOrContainsAny(
                    BcActivityParticipant.Actor,
                    "ItemMisc",
                    setOf("扛起来的麻袋"),
                ),
                BcActivityStateRequirement.GroupContainsAny(
                    BcActivityParticipant.Target,
                    "ItemDevices",
                    setOf("BurlapSack"),
                ),
            ),
            carry.stateRequirements,
        )
        assertEquals(
            BcActivityEffect.EchoPairedItem(
                group = "ItemMisc",
                name = "扛起来的麻袋",
                actorRelation = BcEchoDrawRelation.PreviousCharacter,
                actorLeash = BcEchoLeashRole.Lead,
                targetGroup = "ItemDevices",
                targetName = "BurlapSack",
                targetRequiresAllowItem = false,
            ),
            carry.effects.single(),
        )

        assertEquals("抓住推车", trolley.displayName)
        assertEquals(setOf("UseHands", "Luzi_TargetLeashedOrCanBeLeashed"), trolley.prerequisites)
        assertEquals("SourceCharacter抓住了TargetCharacter的推车.", trolley.chatText)
        assertEquals(
            listOf(
                BcActivityStateRequirement.GroupEmptyOrContainsAny(
                    BcActivityParticipant.Actor,
                    "ItemMisc",
                    setOf("抓住推车"),
                ),
                BcActivityStateRequirement.GroupContainsAny(
                    BcActivityParticipant.Target,
                    "ItemDevices",
                    setOf("Trolley"),
                ),
            ),
            trolley.stateRequirements,
        )
        assertEquals(
            BcActivityEffect.EchoPairedItem(
                group = "ItemMisc",
                name = "抓住推车",
                actorRelation = BcEchoDrawRelation.NextCharacter,
                actorLeash = BcEchoLeashRole.Lead,
                targetGroup = "ItemDevices",
                targetName = "Trolley",
            ),
            trolley.effects.single(),
        )
    }
}
