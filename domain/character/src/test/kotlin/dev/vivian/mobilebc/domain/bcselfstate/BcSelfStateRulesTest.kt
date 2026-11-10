package dev.vivian.mobilebc.domain.bcselfstate

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcPoseRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSelfStateRulesTest {
    private val rules = BcSelfStateRules(serverTimeMillis = { 1_000L })

    @Test
    fun projectionRequiresAuthenticatedOnlineAccountEvenWithCachedSelfAndRoom() {
        val snapshot = snapshot()
        listOf(
            snapshot.copy(state = BcSessionState.Connected),
            snapshot.copy(connectionHealth = BcConnectionHealth.ConnectedUnverified),
            snapshot.copy(desiredOnline = false),
            snapshot.copy(account = null),
        ).forEach { cached ->
            assertEquals(BcSelfStateUnavailableReason.Offline, rules.projection(cached, catalog()).unavailableReason)
        }
    }

    @Test
    fun projectionRequiresCurrentRoomAndSelfMatchingAuthenticatedMember() {
        val snapshot = snapshot()
        assertEquals(BcSelfStateUnavailableReason.NotInRoom, rules.projection(snapshot.copy(currentRoom = null), catalog()).unavailableReason)
        listOf(self().copy(isSelf = false), self().copy(memberNumber = 20)).forEach { character ->
            assertEquals(
                BcSelfStateUnavailableReason.SelfUnavailable,
                rules.projection(snapshot.copy(roomCharacters = listOf(character)), catalog()).unavailableReason,
            )
        }
    }

    @Test
    fun unsupportedAssetFamilyHasNoEditableOptions() {
        val projection = rules.projection(snapshot(self().copy(assetFamily = "Male3DCG")), catalog())
        assertEquals(BcSelfStateUnavailableReason.UnsupportedAssetFamily, projection.unavailableReason)
        assertTrue(projection.poseGroups.isEmpty())
        assertTrue(projection.expressionGroups.isEmpty())
    }

    @Test
    fun missingPoseMetadataOrMismatchedVersionKeepsCatalogPreparing() {
        val snapshot = snapshot()
        listOf(null, catalog().copy(poses = emptyMap()), catalog().copy(version = "R128")).forEach { metadata ->
            assertEquals(BcSelfStateUnavailableReason.CatalogPreparing, rules.projection(snapshot, metadata).unavailableReason)
        }
        assertNull(rules.projection(snapshot.copy(bcVersion = "r129"), catalog()).unavailableReason)
    }

    @Test
    fun malformedSnapshotVersionRejectsWithoutThrowing() {
        listOf("", "129", "R129-invalid").forEach { version ->
            val snapshot = snapshot().copy(bcVersion = version)
            assertEquals(BcSelfStateUnavailableReason.CatalogPreparing, rules.projection(snapshot, catalog()).unavailableReason)
            assertEquals(BcSelfStateUnavailableReason.CatalogPreparing, rules.rejection(request(BcSelfStateChange.Pose("Kneel")), snapshot))
        }
    }

    @Test
    fun staleEditorCannotTargetAnotherCharacterOrAccountGeneration() {
        val snapshot = snapshot()
        val request = request(BcSelfStateChange.Pose("Kneel"))
        assertEquals(BcSelfStateUnavailableReason.AccountChanged, rules.rejection(request.copy(targetMemberNumber = 20), snapshot))
        assertEquals(
            BcSelfStateUnavailableReason.AccountChanged,
            rules.rejection(request, snapshot.copy(relationships = snapshot.relationships.copy(accountGeneration = 8L))),
        )
    }

    @Test
    fun sameNamedRoomRejoinInvalidatesEditor() {
        val request = request(BcSelfStateChange.Pose("Kneel"))
        assertEquals(BcSelfStateUnavailableReason.RoomChanged, rules.rejection(request, snapshot().copy(roomPresenceEpoch = 4L)))
        assertEquals(
            BcSelfStateUnavailableReason.RoomChanged,
            rules.rejection(request, snapshot().copy(currentRoom = room("Other"))),
        )
    }

    @Test
    fun menuPoseMergePreservesOtherCategoryAndStripsTransientFullItemAndUnknownPoses() {
        val character = self().copy(activePose = listOf("Kneel", "Yoked", "TransientUpper", "AllFours", "Hogtied", "Unknown"))
        assertEquals(listOf("Yoked", "BaseLower"), rules.poseUpdate(character, catalog(), BcSelfStateChange.Pose("BaseLower")))
        assertEquals(listOf("Kneel", "OverTheHead"), rules.poseUpdate(character, catalog(), BcSelfStateChange.Pose("OverTheHead")))
    }

    @Test
    fun duplicateActivePoseCategoryUsesLastValueForCurrentSelectionAndOtherCategoryPreservation() {
        val character = self().copy(activePose = listOf("Yoked", "OverTheHead", "Kneel"))
        assertEquals(
            listOf("OverTheHead", "BaseLower"),
            rules.poseUpdate(character, catalog(), BcSelfStateChange.Pose("BaseLower")),
        )
        val upper = rules.projection(snapshot(character), catalog()).poseGroups.single { it.category == "BodyUpper" }
        assertEquals("OverTheHead", upper.currentName)
        assertTrue(upper.options.single { it.name == "OverTheHead" }.selected)
        assertFalse(upper.options.single { it.name == "Yoked" }.selected)
    }

    @Test
    fun fullPoseReplacesBothCategoriesAndCanSwitchBackToOneCategory() {
        val character = self().copy(activePose = listOf("Yoked", "Kneel"))
        assertEquals(listOf("AllFours"), rules.poseUpdate(character, catalog(), BcSelfStateChange.Pose("AllFours")))
        assertEquals(
            listOf("BaseUpper"),
            rules.poseUpdate(character.copy(activePose = listOf("AllFours")), catalog(), BcSelfStateChange.Pose("BaseUpper")),
        )
    }

    @Test
    fun onlyMenuOrTransientMenuPosesAreSelectable() {
        assertEquals(BcSelfStateUnavailableReason.InvalidPose, rejection(BcSelfStateChange.Pose("Hogtied")))
        assertEquals(BcSelfStateUnavailableReason.InvalidPose, rejection(BcSelfStateChange.Pose("Missing")))
        assertNull(rejection(BcSelfStateChange.Pose("TransientUpper")))
        assertFalse(rules.projection(snapshot(), catalog()).poseGroups.flatMap { it.options }.any { it.name == "Hogtied" })
    }

    @Test
    fun perItemAllowedPosesIntersectWithinCategoryAndBlockIncompatibleFullPose() {
        val metadata = catalog(
            BcAssetRule("ItemArms", "Rope", allowActivePose = setOf("Yoked", "OverTheHead")),
            BcAssetRule("ItemHands", "Mittens", allowActivePose = setOf("Yoked")),
        )
        val character = self(BcCharacterItem("ItemArms", "Rope"), BcCharacterItem("ItemHands", "Mittens"))
        assertNull(rejection(BcSelfStateChange.Pose("Yoked"), character, metadata))
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rejection(BcSelfStateChange.Pose("OverTheHead"), character, metadata))
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rejection(BcSelfStateChange.Pose("AllFours"), character, metadata))
        assertNull(rejection(BcSelfStateChange.Pose("Kneel"), character, metadata))
    }

    @Test
    fun dynamicAllowedPosePropertyReplacesAssetConstraintIncludingEmptyOverride() {
        val metadata = catalog(BcAssetRule("ItemLegs", "Rope", allowActivePose = setOf("Kneel")))
        val overridden = self(BcCharacterItem("ItemLegs", "Rope", propertyJson = "{\"AllowActivePose\":[\"LegsClosed\"]}"))
        assertNull(rejection(BcSelfStateChange.Pose("LegsClosed"), overridden, metadata))
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rejection(BcSelfStateChange.Pose("Kneel"), overridden, metadata))
        val cleared = self(BcCharacterItem("ItemLegs", "Rope", propertyJson = "{\"AllowActivePose\":[]}"))
        assertNull(rejection(BcSelfStateChange.Pose("BaseLower"), cleared, metadata))
        assertNull(rejection(BcSelfStateChange.Pose("AllFours"), cleared, metadata))
    }

    @Test
    fun forcedSetPoseBecomesCurrentWhenActivePoseDoesNotPassWornItemConstraint() {
        val metadata = catalog(BcAssetRule("ItemLegs", "Rope", allowActivePose = setOf("Kneel"), setPose = setOf("Kneel")))
        val character = self(BcCharacterItem("ItemLegs", "Rope")).copy(activePose = listOf("BaseLower", "Yoked"))
        val projection = rules.projection(snapshot(character), metadata)
        assertEquals("Kneel", projection.poseGroups.single { it.category == "BodyLower" }.currentName)
        assertEquals("Yoked", projection.poseGroups.single { it.category == "BodyUpper" }.currentName)
        assertTrue(projection.poseGroups.single { it.category == "BodyLower" }.options.single { it.name == "Kneel" }.selected)
    }

    @Test
    fun parsedSetPoseOnlyAssetUsesBcNormalizedConstraintAndAllowsDerivedFullPose() {
        val metadata = BcRuleCatalogParser().parse(
            version = "R129",
            female3dJs = """
                var PoseFemale3DCG = [
                    { Name: "BaseUpper", Category: "BodyUpper", AllowMenu: true },
                    { Name: "BaseLower", Category: "BodyLower", AllowMenu: true },
                    { Name: "Kneel", Category: "BodyLower", AllowMenu: true },
                    { Name: "Hogtied", Category: "BodyFull" },
                    { Name: "AllFours", Category: "BodyFull", AllowMenuTransient: true },
                ];
                var AssetFemale3DCG = [
                    { Group: "ItemLegs", Asset: [{ Name: "Rope", SetPose: ["Kneel"] }] },
                ];
            """.trimIndent(),
        )
        val character = self(BcCharacterItem("ItemLegs", "Rope")).copy(activePose = listOf("BaseLower"))
        assertEquals("Kneel", rules.projection(snapshot(character), metadata).poseGroups.single { it.category == "BodyLower" }.currentName)
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rejection(BcSelfStateChange.Pose("BaseLower"), character, metadata))
        assertNull(rejection(BcSelfStateChange.Pose("AllFours"), character, metadata))
    }

    @Test
    fun dynamicSetPoseOverridesAssetAndEmptyAllowPoseSuppressesForcedPose() {
        val metadata = catalog(BcAssetRule("ItemLegs", "Rope", allowActivePose = setOf("Kneel", "LegsClosed"), setPose = setOf("Kneel")))
        val dynamic = self(BcCharacterItem("ItemLegs", "Rope", propertyJson = "{\"SetPose\":[\"LegsClosed\"]}"))
        assertEquals("LegsClosed", rules.projection(snapshot(dynamic), metadata).poseGroups.single { it.category == "BodyLower" }.currentName)
        val cleared = self(BcCharacterItem("ItemLegs", "Rope", propertyJson = "{\"AllowActivePose\":[],\"SetPose\":[\"Kneel\"]}"))
        assertEquals("BaseLower", rules.projection(snapshot(cleared), metadata).poseGroups.single { it.category == "BodyLower" }.currentName)
    }

    @Test
    fun fullPoseProjectionDoesNotSelectDefaultUpperAndLowerAtSameTime() {
        val projection = rules.projection(snapshot(self().copy(activePose = listOf("AllFours", "Kneel", "Yoked"))), catalog())
        assertEquals("AllFours", projection.poseGroups.single { it.category == "BodyFull" }.currentName)
        assertNull(projection.poseGroups.single { it.category == "BodyLower" }.currentName)
        assertNull(projection.poseGroups.single { it.category == "BodyUpper" }.currentName)
    }

    @Test
    fun resetPoseSendsBothDefaultsAndHonorsCurrentItemRestriction() {
        assertEquals(listOf("BaseLower", "BaseUpper"), rules.poseUpdate(self(), catalog(), BcSelfStateChange.ResetPose))
        val metadata = catalog(BcAssetRule("ItemLegs", "Rope", allowActivePose = setOf("Kneel")))
        val character = self(BcCharacterItem("ItemLegs", "Rope"))
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rejection(BcSelfStateChange.ResetPose, character, metadata))
        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem, rules.projection(snapshot(character), metadata).resetPoseReason)
    }

    @Test
    fun expressionGroupOptionsFollowMetadataAndPussyRequiresPenis() {
        val character = expressionSelf()
        val projection = rules.projection(snapshot(character), catalog())
        assertEquals(listOf(null, "Happy", "Sad"), projection.expressionGroups.single { it.group == "Eyes" }.options.map { it.name })
        assertFalse(projection.expressionGroups.any { it.group == "Eyes2" || it.group == "Pussy" })
        assertEquals(BcSelfStateUnavailableReason.InvalidExpression, rejection(BcSelfStateChange.Expression("Eyes", "Missing"), character))
        assertEquals(BcSelfStateUnavailableReason.InvalidExpression, rejection(BcSelfStateChange.Expression("Eyes2", "Happy"), character))
        assertEquals(BcSelfStateUnavailableReason.InvalidExpression, rejection(BcSelfStateChange.Expression("Pussy", "Erect"), character))
    }

    @Test
    fun assetExpressionRestrictionOverridesGroupWhileDynamicPropertyOverridesAsset() {
        val metadata = catalog(BcAssetRule("Mouth", "Default", allowExpression = listOf("Open")))
        val character = expressionSelf()
        assertEquals(BcSelfStateUnavailableReason.ExpressionRestrictedByItem, rejection(BcSelfStateChange.Expression("Mouth", "Smile"), character, metadata))
        assertNull(rejection(BcSelfStateChange.Expression("Mouth", "Open"), character, metadata))
        val dynamic = character.withProperty("Mouth", "{\"AllowExpression\":[\"Smile\"]}")
        assertNull(rejection(BcSelfStateChange.Expression("Mouth", "Smile"), dynamic, metadata))
        assertEquals(BcSelfStateUnavailableReason.ExpressionRestrictedByItem, rejection(BcSelfStateChange.Expression("Mouth", "Open"), dynamic, metadata))
    }

    @Test
    fun emptyDynamicExpressionListDisablesNamedOptionsAndStillAllowsDefaultRestore() {
        val metadata = catalog(BcAssetRule("Mouth", "Default", allowExpression = listOf("Open")))
        val character = expressionSelf().withProperty("Mouth", "{\"AllowExpression\":[]}")
        assertEquals(BcSelfStateUnavailableReason.ExpressionRestrictedByItem, rejection(BcSelfStateChange.Expression("Mouth", "Open"), character, metadata))
        assertNull(rejection(BcSelfStateChange.Expression("Mouth", null), character, metadata))
    }

    @Test
    fun combinedEyesChecksFixedRightEyeRestrictionAndMissingRightEye() {
        val metadata = catalog(BcAssetRule("Eyes2", "Default", allowExpression = listOf("Sad")))
        assertEquals(BcSelfStateUnavailableReason.ExpressionRestrictedByItem, rejection(BcSelfStateChange.Expression("Eyes", "Happy"), expressionSelf(), metadata))
        assertNull(rejection(BcSelfStateChange.Expression("Eyes", "Sad"), expressionSelf(), metadata))
        val missing = expressionSelf().copy(appearance = expressionSelf().appearance.filterNot { it.group == "Eyes2" })
        assertEquals(BcSelfStateUnavailableReason.ExpressionItemMissing, rejection(BcSelfStateChange.Expression("Eyes", "Happy"), missing))
    }

    @Test
    fun differentEyesAreShownWithoutClaimingCombinedOptionIsSelected() {
        val character = expressionSelf().withProperty("Eyes", "{\"Expression\":\"Happy\"}").withProperty("Eyes2", "{\"Expression\":\"Sad\"}")
        val eyes = rules.projection(snapshot(character), catalog()).expressionGroups.single { it.group == "Eyes" }
        assertEquals("Happy", eyes.currentName)
        assertEquals("Sad", eyes.secondaryCurrentName)
        assertTrue(eyes.hasDifferentEyes)
        assertTrue(eyes.options.none { it.selected })
    }

    @Test
    fun combinedEyesCommandsCarrySameFinalAppearanceAndPreserveItemData() {
        val unrelated = BcCharacterItem("ItemArms", "Rope", propertyJson = "{\"Effect\":[\"Block\"]}")
        val left = BcCharacterItem("Eyes", "Default", colorJson = "[\"#123456\"]", difficulty = 4,
            propertyJson = "{\"Expression\":\"Sad\",\"TypeRecord\":{\"a\":2},\"Custom\":{\"keep\":true}}", craftJson = "{\"Name\":\"Keepsake\"}")
        val character = expressionSelf().copy(appearance = listOf(left, BcCharacterItem("Eyes2", "Default"), unrelated))
        val commands = rules.expressionUpdates(character, catalog(), BcSelfStateChange.Expression("Eyes", "Happy"))
        assertEquals(listOf("Eyes", "Eyes2"), commands.map { it.group })
        assertEquals(listOf("Happy", "Happy"), commands.map { it.name })
        assertEquals(commands[0].appearance, commands[1].appearance)
        val appearance = checkNotNull(commands[0].appearance)
        assertEquals(unrelated, appearance.single { it.group == "ItemArms" })
        val changedLeft = appearance.single { it.group == "Eyes" }
        assertEquals(left.colorJson, changedLeft.colorJson)
        assertEquals(left.difficulty, changedLeft.difficulty)
        assertEquals(left.craftJson, changedLeft.craftJson)
        assertEquals("{\"keep\":true}", BcItemPropertyJson.readPropertyValue(changedLeft.propertyJson, "Custom"))
        assertEquals(mapOf("a" to 2), BcItemPropertyJson.readTypeRecord(changedLeft.propertyJson))
        listOf("Eyes", "Eyes2").forEach { group ->
            assertEquals("\"Happy\"", BcItemPropertyJson.readPropertyValue(appearance.single { it.group == group }.propertyJson, "Expression"))
        }
    }

    @Test
    fun resetExpressionsIncludesEveryEditableGroupAndBothEyes() {
        val character = expressionSelf().withProperty("Mouth", "{\"Expression\":\"Open\",\"Custom\":42}")
        val commands = rules.expressionUpdates(character, catalog(), BcSelfStateChange.ResetExpressions)
        assertEquals(listOf("Eyes", "Eyes2", "Mouth"), commands.map { it.group })
        assertTrue(commands.all { it.name == null })
        val appearance = checkNotNull(commands.first().appearance)
        assertEquals("42", BcItemPropertyJson.readPropertyValue(appearance.single { it.group == "Mouth" }.propertyJson, "Custom"))
        commands.forEach { command ->
            assertEquals("null", BcItemPropertyJson.readPropertyValue(appearance.single { it.group == command.group }.propertyJson, "Expression"))
        }
    }

    @Test
    fun resetExpressionsTouchesOnlyPresentExpressionCapableItemsAndResetsRightEyeIndependently() {
        val character = self(
            BcCharacterItem("Eyes2", "Default", propertyJson = "{\"Expression\":\"Sad\"}"),
            BcCharacterItem("Mouth", "Default", propertyJson = "{\"Expression\":\"Open\"}"),
            BcCharacterItem("ItemArms", "Rope", propertyJson = "{\"Expression\":\"Unknown\"}"),
        )
        assertNull(rejection(BcSelfStateChange.ResetExpressions, character))
        assertNull(rules.projection(snapshot(character), catalog()).resetExpressionsReason)
        val commands = rules.expressionUpdates(character, catalog(), BcSelfStateChange.ResetExpressions)
        assertEquals(setOf("Eyes2", "Mouth"), commands.map { it.group }.toSet())
        val appearance = checkNotNull(commands.first().appearance)
        assertEquals("\"Unknown\"", BcItemPropertyJson.readPropertyValue(appearance.single { it.group == "ItemArms" }.propertyJson, "Expression"))
    }

    @Test
    fun defaultPenisExpressionStillEnforcesAlignedLimpPrerequisite() {
        val metadata = catalog().let { it.copy(groups = it.groups + (
            "Pussy" to BcGroupRule("Pussy", allowExpression = listOf(null, "Erect"), expressionPrerequisites = listOf("CanBeLimp", "CanHaveErection"))
        )) }
        val character = penisSelf().copy(appearance = penisSelf().appearance + BcCharacterItem("ItemVulva", "Device", propertyJson = "{\"Effect\":[\"ForcedErection\"]}"))
        assertEquals(BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet, rejection(BcSelfStateChange.Expression("Pussy", null), character, metadata))
        assertEquals(BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet, rejection(BcSelfStateChange.ResetExpressions, character, metadata))
    }

    @Test
    fun limpPrerequisiteRejectsForcedErectionFromAssetOrDynamicEffect() {
        val metadata = penisCatalog(BcAssetRule("ItemVulva", "Device", effects = setOf("ForcedErection")))
        val character = penisSelf()
        assertNull(rejection(BcSelfStateChange.Expression("Pussy", "Limp"), character, metadata))
        val staticBlocked = character.copy(appearance = character.appearance + BcCharacterItem("ItemVulva", "Device"))
        assertEquals(BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet, rejection(BcSelfStateChange.Expression("Pussy", "Limp"), staticBlocked, metadata))
        val dynamicBlocked = character.copy(appearance = character.appearance + BcCharacterItem("ItemVulva", "Other", propertyJson = "{\"Effect\":[\"ForcedErection\"]}"))
        assertEquals(BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet, rejection(BcSelfStateChange.Expression("Pussy", "Limp"), dynamicBlocked, metadata))
    }

    @Test
    fun erectionPrerequisiteRejectsChasteButNotBreastChaste() {
        val metadata = penisCatalog()
        val character = penisSelf()
        assertNull(rejection(BcSelfStateChange.Expression("Pussy", "Erect"), character, metadata))
        val chaste = character.copy(appearance = character.appearance + BcCharacterItem("ItemVulva", "Device", propertyJson = "{\"Effect\":[\"Chaste\"]}"))
        assertEquals(BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet, rejection(BcSelfStateChange.Expression("Pussy", "Erect"), chaste, metadata))
        val breastChaste = character.copy(appearance = character.appearance + BcCharacterItem("ItemBreast", "Device", propertyJson = "{\"Effect\":[\"BreastChaste\"]}"))
        assertNull(rejection(BcSelfStateChange.Expression("Pussy", "Erect"), breastChaste, metadata))
    }

    @Test
    fun dynamicPrerequisiteOverrideIncludingEmptyArrayWinsOverAsset() {
        val metadata = penisCatalog(BcAssetRule("Pussy", "Penis", allowExpression = listOf("Limp", "Erect"), expressionPrerequisites = listOf("Unknown", "Unknown")))
        val character = penisSelf().withProperty("Pussy", "{\"ExpressionPrerequisite\":[\"CanBeLimp\",\"CanHaveErection\"]}")
        assertNull(rejection(BcSelfStateChange.Expression("Pussy", "Erect"), character, metadata))
        val cleared = character.withProperty("Pussy", "{\"ExpressionPrerequisite\":[]}")
        assertNull(rejection(BcSelfStateChange.Expression("Pussy", "Limp"), cleared, metadata))
    }

    @Test
    fun malformedDynamicExpressionMetadataFailsClosed() {
        listOf(
            "{\"AllowExpression\":true}",
            "{\"AllowExpression\":[12]}",
            "{\"ExpressionPrerequisite\":{\"Happy\":\"Unknown\"}}",
            "{\"ExpressionPrerequisite\":[false]}",
        ).forEach { property ->
            val character = expressionSelf().withProperty("Eyes2", property)
            assertEquals(BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite, rejection(BcSelfStateChange.Expression("Eyes", "Happy"), character))
        }
    }

    @Test
    fun unsupportedStaticMetadataOrUnknownPrerequisiteFailsClosed() {
        val assetUnsupported = catalog(BcAssetRule("Eyes2", "Default", unsupportedExpressionMetadata = setOf("ExpressionPrerequisite")))
        assertEquals(BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite, rejection(BcSelfStateChange.Expression("Eyes", null), expressionSelf(), assetUnsupported))
        val groupUnsupported = catalog().let { it.copy(groups = it.groups + ("Eyes2" to checkNotNull(it.group("Eyes2")).copy(unsupportedExpressionMetadata = setOf("AllowExpression")))) }
        assertEquals(BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite, rejection(BcSelfStateChange.Expression("Eyes", "Happy"), expressionSelf(), groupUnsupported))
        val unknown = catalog(BcAssetRule("Mouth", "Default", allowExpression = listOf("Smile"), expressionPrerequisites = listOf("Unknown")))
        assertEquals(BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite, rejection(BcSelfStateChange.Expression("Mouth", "Smile"), expressionSelf(), unknown))
    }

    @Test
    fun knownDynamicExpressionMetadataOverridesUnsupportedStaticFallback() {
        val metadata = catalog(BcAssetRule("Eyes2", "Default", unsupportedExpressionMetadata = setOf("AllowExpression", "ExpressionPrerequisite")))
        val character = expressionSelf().withProperty("Eyes2", "{\"AllowExpression\":[\"Happy\"],\"ExpressionPrerequisite\":[]}")
        assertNull(rejection(BcSelfStateChange.Expression("Eyes", "Happy"), character, metadata))
        assertEquals(BcSelfStateUnavailableReason.ExpressionRestrictedByItem, rejection(BcSelfStateChange.Expression("Eyes", "Sad"), character, metadata))
    }

    @Test
    fun ownerPoseBlockUsesPrivateCurrentLogAndNeverGuessesFromCharacterRawData() {
        val owned = self().copy(ownership = BcCharacterOwnership("Owner", 20, stage = 1), rawDataJson = "{\"Log\":[]}")
        val snapshot = snapshot(owned).copy(roomCharacters = listOf(owned, self().copy(memberNumber = 20, isSelf = false)))
        val request = request(BcSelfStateChange.Pose("Kneel"))
        assertEquals(BcSelfStateUnavailableReason.OwnerRulesUnavailable, rules.rejection(request, snapshot.copy(selfRuleLog = null)))
        assertEquals(
            BcSelfStateUnavailableReason.PoseBlockedByOwner,
            rules.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule")))),
        )
        assertNull(rules.rejection(request, snapshot.copy(selfRuleLog = emptyList())))
    }

    @Test
    fun ownerBlockRequiresOwnerPresentFullOwnershipCorrectGroupAndActiveExpiry() {
        val owned = self().copy(ownership = BcCharacterOwnership("Owner", 20, stage = 1))
        val owner = self().copy(memberNumber = 20, isSelf = false)
        val snapshot = snapshot(owned).copy(roomCharacters = listOf(owned, owner))
        val request = request(BcSelfStateChange.ResetPose)
        assertNull(rules.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "Other")))))
        assertNull(rules.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule", expiresAtMillis = 999L)))))
        assertEquals(BcSelfStateUnavailableReason.PoseBlockedByOwner, rules.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule", expiresAtMillis = 1_000L)))))
        assertNull(rules.rejection(request, snapshot.copy(roomCharacters = listOf(owned), selfRuleLog = null)))
        assertNull(rules.rejection(request, snapshot.copy(roomCharacters = listOf(owned.copy(ownership = owned.ownership?.copy(stage = 0)), owner), selfRuleLog = null)))
    }

    @Test
    fun timedOwnerRuleRequiresTrustedServerClockWhilePermanentAndUnrelatedRulesDoNot() {
        val withoutClock = BcSelfStateRules(serverTimeMillis = { null })
        val owned = self().copy(ownership = BcCharacterOwnership("Owner", 20, stage = 1))
        val snapshot = snapshot(owned).copy(roomCharacters = listOf(owned, self().copy(memberNumber = 20, isSelf = false)))
        val request = request(BcSelfStateChange.Pose("Kneel"))
        assertEquals(
            BcSelfStateUnavailableReason.OwnerRulesUnavailable,
            withoutClock.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule", expiresAtMillis = 999L)))),
        )
        assertEquals(
            BcSelfStateUnavailableReason.PoseBlockedByOwner,
            withoutClock.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule")))),
        )
        assertNull(withoutClock.rejection(request, snapshot.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("OtherRule", "OwnerRule", expiresAtMillis = 999L)))))
    }

    private fun rejection(
        change: BcSelfStateChange,
        character: BcCharacterSnapshot = self(),
        metadata: BcRuleCatalog = catalog(),
    ): BcSelfStateUnavailableReason? = rules.rejection(request(change, metadata), snapshot(character))

    private fun request(change: BcSelfStateChange, metadata: BcRuleCatalog = catalog()) = BcSelfStateRequest(
        targetMemberNumber = 10,
        roomName = BcRoomName("Test Room"),
        roomPresenceEpoch = 3L,
        accountGeneration = 7L,
        change = change,
        rules = metadata,
    )

    private fun snapshot(character: BcCharacterSnapshot = self()) = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true,
        bcVersion = "R129",
        account = BcAccount(10, "Self", "self"),
        currentRoom = room("Test Room"),
        roomPresenceEpoch = 3L,
        roomCharacters = listOf(character),
        relationships = BcRelationshipSnapshot(accountMemberNumber = 10, accountGeneration = 7L),
    )

    private fun room(name: String) = BcRoom(BcRoomName(name), "", 1, 10, false, null)

    private fun self(vararg appearance: BcCharacterItem) = BcCharacterSnapshot(
        id = "10", memberNumber = 10, name = "Self", displayName = "Self", isSelf = true, appearance = appearance.toList(),
    )

    private fun expressionSelf() = self(
        BcCharacterItem("Eyes", "Default"),
        BcCharacterItem("Eyes2", "Default"),
        BcCharacterItem("Mouth", "Default"),
    )

    private fun penisSelf() = expressionSelf().copy(
        body = BcCharacterBodyState(hasVagina = false, hasPenis = true),
        appearance = expressionSelf().appearance + BcCharacterItem("Pussy", "Penis"),
    )

    private fun BcCharacterSnapshot.withProperty(group: String, property: String) = copy(
        appearance = appearance.map { if (it.group == group) it.copy(propertyJson = property) else it },
    )

    private fun penisCatalog(vararg assets: BcAssetRule): BcRuleCatalog = catalog(*assets).let { metadata ->
        metadata.copy(groups = metadata.groups + (
            "Pussy" to BcGroupRule("Pussy", allowExpression = listOf("Limp", "Erect"), expressionPrerequisites = listOf("CanBeLimp", "CanHaveErection"))
        ))
    }

    private fun catalog(vararg assets: BcAssetRule) = BcRuleCatalog(
        version = "R129",
        groups = listOf(
            BcGroupRule("Eyes", allowExpression = listOf("Happy", "Sad")),
            BcGroupRule("Eyes2", allowExpression = listOf("Happy", "Sad")),
            BcGroupRule("Mouth", allowExpression = listOf("Smile", "Open")),
            BcGroupRule("Pussy", allowExpression = listOf("Limp", "Erect")),
        ).associateBy { it.name },
        activities = emptyMap(),
        assets = assets.associateBy { BcAssetKey(it.group, it.name) },
        poses = listOf(
            BcPoseRule("BaseUpper", "BodyUpper", allowMenu = true),
            BcPoseRule("Yoked", "BodyUpper", allowMenu = true),
            BcPoseRule("OverTheHead", "BodyUpper", allowMenu = true),
            BcPoseRule("BaseLower", "BodyLower", allowMenu = true),
            BcPoseRule("Kneel", "BodyLower", allowMenu = true),
            BcPoseRule("LegsClosed", "BodyLower", allowMenu = true),
            BcPoseRule("AllFours", "BodyFull", allowMenu = true),
            BcPoseRule("TransientUpper", "BodyUpper", allowMenuTransient = true),
            BcPoseRule("Hogtied", "BodyLower"),
        ).associateBy { it.name },
    )
}
