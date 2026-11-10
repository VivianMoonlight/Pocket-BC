package dev.vivian.mobilebc.domain.bcwardrobe

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcWardrobeInventoryItem
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcWardrobePlannerTest {
    private val planner = BcWardrobePlanner(serverTimeMillis = { 1_000L })

    @Test
    fun snapshotRequiresVerifiedAuthenticatedSelfAndCurrentRoom() {
        val original = snapshot()
        listOf(
            original.copy(desiredOnline = false),
            original.copy(connectionHealth = BcConnectionHealth.ConnectedUnverified),
            original.copy(state = BcSessionState.LoggingIn),
        ).forEach { assertEquals(BcWardrobeUnavailableReason.Offline, planner.unavailableReason(it, catalog())) }
        assertEquals(BcWardrobeUnavailableReason.NotInRoom, planner.unavailableReason(original.copy(currentRoom = null), catalog()))
        assertEquals(BcWardrobeUnavailableReason.SelfUnavailable, planner.unavailableReason(original.copy(roomCharacters = listOf(self().copy(memberNumber = 20))), catalog()))
        assertEquals(BcWardrobeUnavailableReason.UnsupportedAssetFamily, planner.unavailableReason(snapshot(self().copy(assetFamily = "Other")), catalog()))
    }

    @Test
    fun absentCatalogAndInvalidOrMismatchedVersionDoNotAuthorizeWardrobe() {
        assertEquals(BcWardrobeUnavailableReason.CatalogPreparing, planner.unavailableReason(snapshot(), null))
        listOf("R128", "", "129", "R129-invalid").forEach { version ->
            assertEquals(BcWardrobeUnavailableReason.CatalogPreparing, planner.unavailableReason(snapshot().copy(bcVersion = version), catalog()))
        }
        assertNull(planner.unavailableReason(snapshot().copy(bcVersion = "r129"), catalog()))
    }

    @Test
    fun privateWardrobeMustBelongToCurrentMemberAndExactLogin() {
        val context = context()
        listOf(
            context.copy(wardrobeState = null),
            context.copy(loginSessionId = null),
            context.copy(wardrobeState = wardrobe().copy(memberNumber = 20)),
            context.copy(wardrobeState = wardrobe().copy(selfRuleLogSessionId = "previous-login")),
            context.copy(privateLog = null),
            context.copy(wardrobeState = wardrobe().copy(wardrobeAvailable = false)),
        ).forEach { assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(it, catalog())) }
    }

    @Test
    fun unknownPrivateInputsCannotBecomeKnownEmptyState() {
        val original = wardrobe()
        listOf(
            original.privateInputs.copy(gameAvailable = false),
            original.privateInputs.copy(inventoryAvailable = false),
            original.privateInputs.copy(inventoryItems = null, inventoryIds = null),
        ).forEach { inputs ->
            assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(wardrobe = original.copy(privateInputs = inputs)), catalog()))
        }
        assertNull(planner.unavailableReason(context(), catalog()))
    }

    @Test
    fun relevantNpcRulesRequireCompleteExactPrivateEventData() {
        val logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "OwnerRule")
        val original = wardrobe()
        val npc = self().copy(owner = "NPC-Owner")
        listOf(
            original.privateInputs.copy(privateCharacterAvailable = false),
            original.privateInputs.copy(privateCharacterJson = "[{\"Event\":false}]"),
            original.privateInputs.copy(privateCharacterJson = "[{\"Event\":[{\"Name\":\"PlayerCollaring\",\"Value\":\"unknown\"}]}]"),
        ).forEach { inputs ->
            assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(npc, original.copy(privateInputs = inputs), logs), catalog()))
        }
        val trial = original.copy(privateInputs = original.privateInputs.copy(privateCharacterJson = "[{\"Event\":[{\"Name\":\"EndSubTrial\",\"Value\":1},{\"Name\":\"PlayerCollaring\",\"Value\":0}]}]"))
        assertNull(planner.unavailableReason(context(npc, trial, logs), catalog()))
        val collared = trial.copy(privateInputs = trial.privateInputs.copy(privateCharacterJson = "[{\"Event\":[{\"Name\":\"EndSubTrial\",\"Value\":1},{\"Name\":\"PlayerCollaring\",\"Value\":2}]}]"))
        assertEquals(BcWardrobeUnavailableReason.BlockChangeOwnerRule, planner.unavailableReason(context(npc, collared, logs), catalog()))
    }

    @Test
    fun wardrobeUnlockRequiresExactPrivateRoomLogAndTrustedExpiryClock() {
        assertEquals(BcWardrobeUnavailableReason.WardrobeLocked, planner.unavailableReason(context(logs = emptyList()), catalog()))
        assertEquals(BcWardrobeUnavailableReason.WardrobeLocked, planner.unavailableReason(context(logs = listOf(BcOwnerRuleLogRecord("Wardrobe", "OwnerRule"))), catalog()))
        assertEquals(BcWardrobeUnavailableReason.WardrobeLocked, planner.unavailableReason(context(logs = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom", 999L))), catalog()))
        assertNull(planner.unavailableReason(context(logs = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom", 1_000L))), catalog()))
        assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(logs = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom", 2_000L))).copy(serverTimeMillis = null), catalog()))
        assertNull(planner.unavailableReason(context().copy(serverTimeMillis = null), catalog()))
    }

    @Test
    fun clothingChangeBlocksUseExactLogsAndFirstMatchingNativeEntry() {
        assertEquals(BcWardrobeUnavailableReason.BlockChangeRule, planner.unavailableReason(context(logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "Rule")), catalog()))
        assertNull(planner.unavailableReason(context(logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChangeExtra", "Rule")), catalog()))
        assertNull(planner.unavailableReason(context(logs = unlockedLog() + listOf(BcOwnerRuleLogRecord("BlockChange", "Rule", 999L), BcOwnerRuleLogRecord("BlockChange", "Rule"))), catalog()))
        assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "Rule", 2_000L)).copy(serverTimeMillis = null), catalog()))
    }

    @Test
    fun nativeRestraintEffectsAndClubSlaveCollarPreventChangingClothes() {
        listOf("Freeze", "Block", "BlockWardrobe").forEach { effect ->
            val item = BcCharacterItem("ItemArms", "Rope", propertyJson = "{\"Effect\":[\"$effect\"]}")
            assertEquals(BcWardrobeUnavailableReason.Restrained, planner.unavailableReason(context(self(item)), catalog()))
        }
        assertEquals(BcWardrobeUnavailableReason.ClubSlave, planner.unavailableReason(context(self(BcCharacterItem("ItemNeck", "ClubSlaveCollar"))), catalog()))
        assertNull(planner.unavailableReason(context(self(BcCharacterItem("ItemArms", "Rope", propertyJson = "{\"Effect\":[\"BlockHands\"]}"))), catalog()))
    }

    @Test
    fun ownerBlockChangeDistinguishesTrialAndFullOwnershipAndGgtsLevel() {
        val logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "OwnerRule")
        val owned = self().copy(ownership = BcCharacterOwnership("Owner", 20, stage = 1))
        assertEquals(BcWardrobeUnavailableReason.BlockChangeOwnerRule, planner.unavailableReason(context(owned, logs = logs), catalog()))
        assertNull(planner.unavailableReason(context(owned.copy(ownership = owned.ownership?.copy(stage = 0)), logs = logs), catalog()))
        val ggts = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = "{\"GGTS\":{\"Level\":6}}"))
        assertEquals(BcWardrobeUnavailableReason.BlockChangeOwnerRule, planner.unavailableReason(context(wardrobe = ggts, logs = logs), catalog()))
    }

    @Test
    fun runningRoomGamesBlockWhileKnownAbsentAndStoppedStatesPermit() {
        listOf("LARP", "MagicBattle").forEach { game ->
            val running = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = "{\"$game\":{\"Status\":\"Running\"}}"))
            assertEquals(BcWardrobeUnavailableReason.GameBlocksChange, planner.unavailableReason(context(wardrobe = running).copy(roomGame = game), catalog()))
            val stopped = running.copy(privateInputs = running.privateInputs.copy(gameJson = "{\"$game\":{\"Status\":\"\"}}"))
            assertNull(planner.unavailableReason(context(wardrobe = stopped).copy(roomGame = game), catalog()))
        }
    }

    @Test
    fun malformedPrivateGameStateCannotAuthorizeChange() {
        listOf("{\"GGTS\":false}", "{\"GGTS\":{\"Level\":\"unknown\"}}", "{\"LARP\":{\"Status\":\"Maybe\"}}").forEach { json ->
            val state = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = json))
            assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(wardrobe = state).copy(roomGame = "LARP"), catalog()))
        }
    }

    @Test
    fun asylumIsolationExpiryAndGgtsRestrictionsUseTrustedTime() {
        val isolated = unlockedLog() + BcOwnerRuleLogRecord("Isolated", "Asylum", 1_000L)
        assertEquals(BcWardrobeUnavailableReason.AsylumBlocksChange, planner.unavailableReason(context(logs = isolated), catalog()))
        assertNull(planner.unavailableReason(context(logs = unlockedLog() + BcOwnerRuleLogRecord("Isolated", "Asylum", 999L)), catalog()))
        assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(logs = isolated).copy(serverTimeMillis = null), catalog()))
        val ggts = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = "{\"GGTS\":{\"Level\":6}}"))
        assertEquals(BcWardrobeUnavailableReason.AsylumBlocksChange, planner.unavailableReason(context(wardrobe = ggts).copy(roomSpace = "Asylum"), catalog()))
    }

    @Test
    fun nativeNumericGgtsLevelSupportsDecimalAndExponentForAsylumAndOwnerRules() {
        listOf("6.0", "6e0").forEach { level ->
            val state = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = "{\"GGTS\":{\"Level\":$level}}"))
            assertEquals(level, BcWardrobeUnavailableReason.AsylumBlocksChange, planner.unavailableReason(context(wardrobe = state).copy(roomSpace = "Asylum"), catalog()))
            assertEquals(level, BcWardrobeUnavailableReason.BlockChangeOwnerRule, planner.unavailableReason(context(wardrobe = state, logs = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "OwnerRule")), catalog()))
            val saved = state.copy(slots = listOf(BcWardrobeSlot(0, "Outfit", BcWardrobeSlotStatus.Saved, listOf(BcWardrobeItem("Cloth", "NewDress")))))
            val plan = planner.plan(context(self(BcCharacterItem("Cloth", "OldDress")), saved, unlockedLog() + BcOwnerRuleLogRecord("BlockAppearanceA", "OwnerRule")), 0, catalog())
            assertEquals(level, BcWardrobeEntryReason.BlockAppearanceOwnerRule, plan.entries.single { it.group == "Cloth" }.reason)
        }
    }

    @Test
    fun onlineTrialOwnershipTakesNativePrecedenceOverGgtsFullOwnership() {
        val trial = self().copy(ownership = BcCharacterOwnership("Owner", 20, stage = 0))
        val state = wardrobe().copy(privateInputs = wardrobe().privateInputs.copy(gameJson = "{\"GGTS\":{\"Level\":6}}"))
        assertNull(planner.unavailableReason(context(trial, state, unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "OwnerRule")), catalog()))
    }

    @Test
    fun duplicateCurrentGroupsFailWithoutDroppingAnyOriginalItem() {
        val first = BcCharacterItem("ItemArms", "Rope", colorJson = "\"Green\"", propertyJson = "{\"Custom\":1}")
        val duplicate = first.copy(colorJson = "\"Red\"", craftJson = "{\"Name\":\"Second\"}")
        val character = self(first, duplicate, BcCharacterItem("Cloth", "OldDress"))
        assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, planner.unavailableReason(context(character), catalog()))
        val plan = planner.plan(context(character), 0, catalog())
        assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, plan.unavailableReason)
        assertEquals(character.appearance, plan.appearance)
        assertEquals(listOf(first, duplicate), plan.appearance.filter { it.group == "ItemArms" })
        assertFalse(plan.hasChanges)
    }

    @Test
    fun staleRequestRejectsTargetAccountRoomVisitSlotAndRevision() {
        val request = request()
        assertEquals(BcWardrobeUnavailableReason.AccountChanged, planner.rejection(request.copy(targetMemberNumber = 20), snapshot()))
        assertEquals(BcWardrobeUnavailableReason.AccountChanged, planner.rejection(request.copy(accountGeneration = 6), snapshot()))
        assertEquals(BcWardrobeUnavailableReason.RoomChanged, planner.rejection(request, snapshot().copy(roomPresenceEpoch = 4)))
        assertEquals(BcWardrobeUnavailableReason.InvalidSlot, planner.rejection(request.copy(slotIndex = 24), snapshot()))
        assertEquals(BcWardrobeUnavailableReason.RevisionChanged, planner.rejection(request.copy(expectedRevision = 6), snapshot()))
    }

    @Test
    fun wardrobeNamesMatchNativeAsciiAndLengthRules() {
        listOf("", " ", "Outfit 123", "A".repeat(20)).forEach { assertTrue(it, planner.validateName(it)) }
        listOf("A".repeat(21), "礼服", "Outfit!", "Line\nBreak", "Name_1").forEach { assertFalse(it, planner.validateName(it)) }
    }

    @Test
    fun saveIncludesBodyHairAndClothingButExcludesItemAndEphemeralMetadata() {
        val character = self(
            BcCharacterItem("BodyUpper", "Normal", colorJson = "\"#EDD7BE\"", difficulty = 7, craftJson = "{\"Name\":\"Body\"}"),
            BcCharacterItem("HairFront", "Short", colorJson = "[\"#001122\"]", propertyJson = "{\"Expression\":\"Happy\",\"Custom\":{\"keep\":true}}"),
            BcCharacterItem("Cloth", "OldDress", propertyJson = "{\"TypeRecord\":{\"a\":2},\"Expression\":\"Unused\"}", difficulty = 4, craftJson = "{\"Name\":\"Dress\"}"),
            BcCharacterItem("ItemArms", "Rope", propertyJson = "{\"Custom\":42}"),
        )
        val saved = planner.saveAppearance(character, catalog())
        assertEquals(setOf("BodyUpper", "HairFront", "Cloth"), saved.map { it.group }.toSet())
        assertEquals("[\"#001122\"]", saved.single { it.group == "HairFront" }.colorJson)
        assertEquals("{\"keep\":true}", BcItemPropertyJson.readPropertyValue(saved.single { it.group == "HairFront" }.propertyJson, "Custom"))
        assertEquals(mapOf("a" to 2), BcItemPropertyJson.readTypeRecord(saved.single { it.group == "Cloth" }.propertyJson))
        assertTrue(saved.all { BcItemPropertyJson.readPropertyValue(it.propertyJson, "Expression") == null })
    }

    @Test
    fun savePreservesLegacyAssetAndUnknownPropertiesInsideKnownAppearanceCategory() {
        val character = self(BcCharacterItem("HairFront", "LegacyHair", colorJson = "\"Blue\"", propertyJson = "{\"FutureSetting\":[1,2],\"Expression\":null}"))
        assertEquals(listOf(BcWardrobeItem("HairFront", "LegacyHair", "\"Blue\"", "{\"FutureSetting\":[1,2]}")), planner.saveAppearance(character, catalog()))
    }

    @Test
    fun savePreservesOpaqueTypeRecordValuesAndOnlyRemovesTopLevelExpression() {
        val property = "{\"TypeRecord\":{\"future\":{\"Expression\":\"Keep nested\",\"value\":true},\"unknown\":\"mode\"},\"Expression\":\"Happy\",\"Extra\":[null,3]}"
        val saved = planner.saveAppearance(self(BcCharacterItem("Cloth", "LegacyDress", propertyJson = property)), catalog()).single()
        assertEquals("{\"future\":{\"Expression\":\"Keep nested\",\"value\":true},\"unknown\":\"mode\"}", BcItemPropertyJson.readPropertyValue(saved.propertyJson, "TypeRecord"))
        assertEquals("[null,3]", BcItemPropertyJson.readPropertyValue(saved.propertyJson, "Extra"))
        assertNull(BcItemPropertyJson.readPropertyValue(saved.propertyJson, "Expression"))
    }

    @Test(expected = IllegalStateException::class)
    fun saveCannotGuessTheCategoryOfAnUnknownGroup() {
        planner.saveAppearance(self(BcCharacterItem("UnknownBodyGroup", "Unknown")), catalog())
    }

    @Test
    fun fullAppearanceApplyPreservesItemPoseAndCurrentExpressionInsteadOfSavedExpression() {
        val rope = BcCharacterItem("ItemArms", "Rope", colorJson = "\"Green\"", difficulty = 5, propertyJson = "{\"Custom\":7}", craftJson = "{\"Name\":\"Rope\"}")
        val eyes = BcCharacterItem("Eyes", "Eyes1", propertyJson = "{\"Expression\":\"Happy\"}")
        val character = self(rope, eyes).copy(activePose = listOf("Kneel", "Yoked"))
        val state = wardrobe(
            BcWardrobeItem("BodyUpper", "Small", colorJson = "\"Tan\""),
            BcWardrobeItem("HairFront", "Long", colorJson = "\"Black\""),
            BcWardrobeItem("Cloth", "NewDress", colorJson = "\"Red\"", propertyJson = "{\"Custom\":2}"),
            BcWardrobeItem("Eyes", "EyesAlt", propertyJson = "{\"Expression\":\"Sad\"}"),
            BcWardrobeItem("ItemArms", "OtherRope"),
        )
        val plan = planner.plan(context(character, state), 0, catalog())
        assertNull(plan.unavailableReason)
        assertEquals(rope, plan.appearance.single { it.group == "ItemArms" })
        assertEquals("Small", plan.appearance.single { it.group == "BodyUpper" }.name)
        assertEquals("Long", plan.appearance.single { it.group == "HairFront" }.name)
        assertEquals("NewDress", plan.appearance.single { it.group == "Cloth" }.name)
        assertEquals("\"Happy\"", BcItemPropertyJson.readPropertyValue(plan.appearance.single { it.group == "Eyes" }.propertyJson, "Expression"))
        assertEquals(character.activePose, plan.sourceState?.character?.activePose)
        assertFalse(plan.partial)
    }

    @Test
    fun emptyUnsupportedAndOutOfRangeSlotsNeverProduceChanges() {
        val state = wardrobe().copy(slots = listOf(BcWardrobeSlot(0, null, BcWardrobeSlotStatus.Empty, null)))
        assertEquals(BcWardrobeUnavailableReason.EmptySlot, planner.plan(context(wardrobe = state), 0, catalog()).unavailableReason)
        val unsupported = state.copy(slots = listOf(BcWardrobeSlot(0, null, BcWardrobeSlotStatus.Unsupported, null)))
        assertEquals(BcWardrobeUnavailableReason.UnsupportedSlot, planner.plan(context(wardrobe = unsupported), 0, catalog()).unavailableReason)
        assertEquals(BcWardrobeUnavailableReason.InvalidSlot, planner.plan(context(), 24, catalog()).unavailableReason)
    }

    @Test
    fun unknownAndDuplicateSlotGroupsArePartialAndPreserveCurrentData() {
        val character = self(BcCharacterItem("Cloth", "OldDress", propertyJson = "{\"Custom\":7}"))
        val duplicate = planner.plan(context(character, wardrobe(BcWardrobeItem("Cloth", "NewDress"), BcWardrobeItem("Cloth", "OldDress"))), 0, catalog())
        assertEquals(character.appearance.single(), duplicate.appearance.single { it.group == "Cloth" })
        assertEquals(BcWardrobeEntryReason.DuplicateGroup, duplicate.entries.single { it.group == "Cloth" }.reason)
        val unknown = planner.plan(context(character, wardrobe(BcWardrobeItem("Cloth", "FutureDress"), BcWardrobeItem("FutureGroup", "Future"))), 0, catalog())
        assertEquals(BcWardrobeEntryReason.UnknownAsset, unknown.entries.single { it.group == "Cloth" }.reason)
        assertEquals(BcWardrobeEntryReason.UnknownGroup, unknown.entries.single { it.group == "FutureGroup" }.reason)
        assertTrue(unknown.partial)
    }

    @Test
    fun lockedCurrentAppearanceIsPreservedIncludingLockColorAndCraft() {
        val locked = BcCharacterItem("Cloth", "OldDress", "\"Purple\"", 8, "{\"LockedBy\":\"Padlock\",\"LockMemberNumber\":20,\"Custom\":7}", "{\"Name\":\"Locked dress\"}")
        val plan = planner.plan(context(self(locked), wardrobe(BcWardrobeItem("Cloth", "NewDress"))), 0, catalog())
        assertEquals(locked, plan.appearance.single { it.group == "Cloth" })
        assertEquals(BcWardrobeEntryReason.LockedCurrentItem, plan.entries.single { it.group == "Cloth" }.reason)
        assertTrue(plan.partial)
    }

    @Test
    fun unsafeSavedLockFieldsCannotIntroduceOrReplaceCurrentLocks() {
        listOf("{\"LockedBy\":\"Padlock\"}", "{\"LockMemberNumber\":20}", "{\"CombinationNumber\":\"1234\"}", "{\"Effect\":[\"Lock\"]}").forEach { property ->
            val old = BcCharacterItem("Cloth", "OldDress")
            val plan = planner.plan(context(self(old), wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = property))), 0, catalog())
            assertEquals(old, plan.appearance.single { it.group == "Cloth" })
            assertEquals(BcWardrobeEntryReason.UnsafeSlotProperty, plan.entries.single { it.group == "Cloth" }.reason)
        }
    }

    @Test
    fun missingOptionalClothingIsRemovedAndMissingMandatoryAppearanceUsesNativeDefault() {
        val old = BcCharacterItem("Cloth", "OldDress")
        val plan = planner.plan(context(self(old), wardrobe(BcWardrobeItem("HairFront", "Long"))), 0, catalog())
        assertFalse(plan.appearance.any { it.group == "Cloth" })
        assertEquals(BcWardrobeEntryAction.Removed, plan.entries.single { it.group == "Cloth" }.action)
        assertEquals("Normal", plan.appearance.single { it.group == "BodyUpper" }.name)
        assertEquals(BcWardrobeEntryAction.RestoredDefault, plan.entries.single { it.group == "BodyUpper" }.action)
    }

    @Test
    fun mandatoryMirrorUsesCounterpartNameAndColor() {
        val metadata = catalog().let { it.copy(groups = it.groups + ("Eyes2" to checkNotNull(it.group("Eyes2")).copy(mirrorGroup = "Eyes"))) }
        val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Eyes", "EyesAlt", "\"Blue\""))), 0, metadata)
        assertEquals("EyesAlt", plan.appearance.single { it.group == "Eyes2" }.name)
        assertEquals("\"Blue\"", plan.appearance.single { it.group == "Eyes2" }.colorJson)
        assertEquals(BcWardrobeEntryAction.RestoredDefault, plan.entries.single { it.group == "Eyes2" }.action)
    }

    @Test
    fun unavailableMandatoryDefaultPreservesExistingAppearanceWithExplicitPartialReason() {
        val current = BcCharacterItem("BodyUpper", "Normal", colorJson = "\"Tan\"")
        val metadata = catalog().let { it.copy(groups = it.groups + ("BodyUpper" to checkNotNull(it.group("BodyUpper")).copy(defaultAssetName = "Future"))) }
        val plan = planner.plan(context(self(current), wardrobe(BcWardrobeItem("HairFront", "Long"))), 0, metadata)
        assertEquals(current, plan.appearance.single { it.group == "BodyUpper" })
        assertEquals(BcWardrobeEntryReason.MandatoryFallbackUnavailable, plan.entries.single { it.group == "BodyUpper" }.reason)
        assertTrue(plan.partial)
    }

    @Test
    fun unknownWardrobeMetadataAndUncustomizableGroupsPreserveCurrent() {
        listOf(
            BcGroupRule("Cloth", category = "Appearance", clothing = true, allowCustomize = false) to BcWardrobeEntryReason.GroupNotCustomizable,
            BcGroupRule("Cloth", category = "Appearance", clothing = true, unsupportedWardrobeMetadata = setOf("MirrorGroup")) to BcWardrobeEntryReason.UnsupportedMetadata,
        ).forEach { (group, reason) ->
            val metadata = catalog().let { it.copy(groups = it.groups + ("Cloth" to group)) }
            val old = BcCharacterItem("Cloth", "OldDress")
            val plan = planner.plan(context(self(old), wardrobe(BcWardrobeItem("Cloth", "NewDress"))), 0, metadata)
            assertEquals(old, plan.appearance.single { it.group == "Cloth" })
            assertEquals(reason, plan.entries.single { it.group == "Cloth" }.reason)
        }
    }

    @Test
    fun paidInventoryOwnershipAndUnknownPriceAreDistinguishedFromFreeAssets() {
        val paid = asset("Cloth", "NewDress").copy(value = 100.0)
        val metadata = catalog(paid)
        val state = wardrobe(BcWardrobeItem("Cloth", "NewDress"))
        val unavailable = planner.plan(context(wardrobe = state), 0, metadata)
        assertEquals(BcWardrobeEntryReason.InventoryUnavailable, unavailable.entries.single { it.group == "Cloth" }.reason)
        val owned = state.copy(privateInputs = state.privateInputs.copy(inventoryItems = listOf(BcWardrobeInventoryItem("Cloth", "NewDress"))))
        assertEquals("NewDress", planner.plan(context(wardrobe = owned), 0, metadata).appearance.single { it.group == "Cloth" }.name)
        val unknown = planner.plan(context(wardrobe = state), 0, catalog(paid.copy(value = null)))
        assertEquals(BcWardrobeEntryReason.InventoryUnavailable, unknown.entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun locationAndSharedBuyGroupProvideNativeAvailability() {
        val state = wardrobe(BcWardrobeItem("Cloth", "NewDress"))
        val located = asset("Cloth", "NewDress").copy(value = 100.0, availableLocations = setOf("ChatRoom"))
        assertEquals("NewDress", planner.plan(context(wardrobe = state), 0, catalog(located)).appearance.single { it.group == "Cloth" }.name)
        val shared = asset("Cloth", "NewDress").copy(value = 100.0, buyGroup = "DressSet")
        val freeVariant = asset("Cloth", "OldDress").copy(value = 0.0, buyGroup = "DressSet")
        assertEquals("NewDress", planner.plan(context(wardrobe = state), 0, catalog(shared, freeVariant)).appearance.single { it.group == "Cloth" }.name)
    }

    @Test
    fun nativeInventoryIdsDoNotGrantDifferentAssetsWithAnUnrelatedIdCollision() {
        val wanted = asset("Cloth", "NewDress").copy(value = 100.0, inventoryId = 42)
        val state = wardrobe(BcWardrobeItem("Cloth", "NewDress")).let { it.copy(privateInputs = it.privateInputs.copy(inventoryItems = null, inventoryIds = listOf(42))) }
        assertEquals("NewDress", planner.plan(context(wardrobe = state), 0, catalog(wanted)).appearance.single { it.group == "Cloth" }.name)
        val collision = asset("HairFront", "Short").copy(value = 100.0, inventoryId = 42)
        val plan = planner.plan(context(wardrobe = state), 0, catalog(collision, wanted))
        assertEquals(BcWardrobeEntryReason.InventoryUnavailable, plan.entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun sameBuyGroupAndInventoryIdDoNotGrantUnownedPaidVariantAfterNativeInventoryLoad() {
        val first = asset("Cloth", "OldDress").copy(value = 100.0, inventoryId = 42, buyGroup = "DressSet")
        val wanted = asset("Cloth", "NewDress").copy(value = 100.0, inventoryId = 42, buyGroup = "DressSet")
        val state = wardrobe(BcWardrobeItem("Cloth", "NewDress")).let { it.copy(privateInputs = it.privateInputs.copy(inventoryItems = listOf(BcWardrobeInventoryItem("Cloth", "OldDress")), inventoryIds = listOf(42))) }
        val plan = planner.plan(context(wardrobe = state), 0, catalog(first, wanted))
        assertEquals(BcWardrobeEntryReason.InventoryUnavailable, plan.entries.single { it.group == "Cloth" }.reason)
        assertFalse(plan.appearance.any { it.group == "Cloth" && it.name == "NewDress" })
    }

    @Test
    fun blockedAssetIsSkippedWhileOtherLegalAppearanceChangesApply() {
        val character = self(BcCharacterItem("Cloth", "OldDress")).copy(blockItemsJson = "[{\"Group\":\"Cloth\",\"Name\":\"NewDress\"}]")
        val plan = planner.plan(context(character, wardrobe(BcWardrobeItem("Cloth", "NewDress"), BcWardrobeItem("HairFront", "Long"))), 0, catalog())
        assertEquals("OldDress", plan.appearance.single { it.group == "Cloth" }.name)
        assertEquals("Long", plan.appearance.single { it.group == "HairFront" }.name)
        assertEquals(BcWardrobeEntryReason.PermissionDenied, plan.entries.single { it.group == "Cloth" }.reason)
        assertTrue(plan.partial)
    }

    @Test
    fun blockAppearanceCodesPreserveOnlySpecifiedGroupsEvenWithExpiredRecord() {
        val old = BcCharacterItem("Cloth", "OldDress")
        val owned = self(old).copy(ownership = BcCharacterOwnership("Owner", 20, stage = 1))
        val logs = unlockedLog() + BcOwnerRuleLogRecord("BlockAppearanceA", "OwnerRule", 1L)
        val plan = planner.plan(context(owned, wardrobe(BcWardrobeItem("Cloth", "NewDress"), BcWardrobeItem("HairFront", "Long")), logs).copy(serverTimeMillis = null), 0, catalog())
        assertEquals(old, plan.appearance.single { it.group == "Cloth" })
        assertEquals("Long", plan.appearance.single { it.group == "HairFront" }.name)
        assertEquals(BcWardrobeEntryReason.BlockAppearanceOwnerRule, plan.entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun forbiddenColorAndMissingDefaultColorDoNotGuessAValidColor() {
        val metadata = catalog().let { it.copy(groups = it.groups + ("Cloth" to checkNotNull(it.group("Cloth")).copy(allowColorize = false))) }
        val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", "\"Red\""))), 0, metadata)
        assertEquals(BcWardrobeEntryReason.ColorNotCustomizable, plan.entries.single { it.group == "Cloth" }.reason)
        val noDefault = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress"))), 0, catalog(asset("Cloth", "NewDress").copy(defaultColor = null)))
        assertEquals(BcWardrobeEntryReason.UnsupportedMetadata, noDefault.entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun unsupportedLegacyExtendedPropertyIsSkippedAndTypedDefaultsMergeWithSavedRecord() {
        val oldType = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = "{\"Type\":\"Legacy\"}"))), 0, catalog())
        assertEquals(BcWardrobeEntryReason.UnsupportedItemProperty, oldType.entries.single { it.group == "Cloth" }.reason)
        val rule = BcExtendedItemRule("Cloth", "NewDress", BcExtendedItemArchetype.Typed, "a", defaultPropertyJson = "{\"TypeRecord\":{\"a\":0},\"Custom\":true}")
        val metadata = catalog().copy(extendedItems = mapOf(BcAssetKey("Cloth", "NewDress") to rule))
        val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = "{\"TypeRecord\":{\"a\":2}}"))), 0, metadata)
        assertEquals(mapOf("a" to 2), BcItemPropertyJson.readTypeRecord(plan.appearance.single { it.group == "Cloth" }.propertyJson))
        val unsupported = metadata.copy(extendedItems = mapOf(BcAssetKey("Cloth", "NewDress") to rule.copy(supportLevel = BcExtendedItemSupportLevel.Unsupported)))
        assertEquals(BcWardrobeEntryReason.UnsupportedItemProperty, planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress"))), 0, unsupported).entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun migratedLegacyTypeAndReadableRecordRestoreWithoutDroppingOpaqueProperties() {
        val property = "{\"Type\":\"Legacy\",\"TypeRecord\":{\"a\":2},\"Custom\":{\"keep\":[true,null]},\"Expression\":\"Ignored\"}"
        val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = property))), 0, catalog())
        val item = plan.appearance.single { it.group == "Cloth" }
        assertEquals("NewDress", item.name)
        assertEquals("\"Legacy\"", BcItemPropertyJson.readPropertyValue(item.propertyJson, "Type"))
        assertEquals(mapOf("a" to 2), BcItemPropertyJson.readTypeRecord(item.propertyJson))
        assertEquals("{\"keep\":[true,null]}", BcItemPropertyJson.readPropertyValue(item.propertyJson, "Custom"))
        assertNull(BcItemPropertyJson.readPropertyValue(item.propertyJson, "Expression"))
        assertNull(plan.entries.single { it.group == "Cloth" }.reason)
        val malformed = property.replace("{\"a\":2}", "{\"a\":\"unknown\"}")
        val rejected = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = malformed))), 0, catalog())
        assertEquals(BcWardrobeEntryReason.UnsupportedItemProperty, rejected.entries.single { it.group == "Cloth" }.reason)
    }

    @Test
    fun supportedTypedAndVibratingLegacyNamesMigrateOnlyExactKnownOptions() {
        listOf(BcExtendedItemArchetype.Typed, BcExtendedItemArchetype.Vibrating).forEach { archetype ->
            val rule = BcExtendedItemRule("Cloth", "NewDress", archetype, "a", typedOptions = listOf(BcExtendedItemOptionRule("Base", 0), BcExtendedItemOptionRule("Pleated", 1)), defaultPropertyJson = "{\"TypeRecord\":{\"a\":0}}")
            val metadata = catalog().copy(extendedItems = mapOf(BcAssetKey("Cloth", "NewDress") to rule))
            val property = "{\"Type\":\"Pleated\",\"Custom\":{\"keep\":true}}"
            val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = property))), 0, metadata)
            val item = plan.appearance.single { it.group == "Cloth" }
            assertEquals("NewDress", item.name)
            assertEquals(mapOf("a" to 1), BcItemPropertyJson.readTypeRecord(item.propertyJson))
            assertEquals("\"Pleated\"", BcItemPropertyJson.readPropertyValue(item.propertyJson, "Type"))
            assertEquals("{\"keep\":true}", BcItemPropertyJson.readPropertyValue(item.propertyJson, "Custom"))
            val unknown = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = "{\"Type\":\"pleated\"}"))), 0, metadata)
            assertEquals(BcWardrobeEntryReason.UnsupportedItemProperty, unknown.entries.single { it.group == "Cloth" }.reason)
        }
    }

    @Test
    fun supportedModularLegacyTypeMigratesKnownTokensAndRejectsUnknownFragments() {
        val rule = BcExtendedItemRule("Cloth", "NewDress", BcExtendedItemArchetype.Modular, "unused", modules = listOf(
            BcExtendedItemModuleRule("Top", "a", listOf(BcExtendedItemModuleOptionRule("Base", 0), BcExtendedItemModuleOptionRule("High", 1))),
            BcExtendedItemModuleRule("Bottom", "b", listOf(BcExtendedItemModuleOptionRule("Base", 0), BcExtendedItemModuleOptionRule("Low", 2))),
        ))
        val metadata = catalog().copy(extendedItems = mapOf(BcAssetKey("Cloth", "NewDress") to rule))
        val plan = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = "{\"Type\":\"a1b2\",\"Custom\":[1,2]}"))), 0, metadata)
        val property = plan.appearance.single { it.group == "Cloth" }.propertyJson
        assertEquals(mapOf("a" to 1, "b" to 2), BcItemPropertyJson.readTypeRecord(property))
        assertEquals("\"a1b2\"", BcItemPropertyJson.readPropertyValue(property, "Type"))
        assertEquals("[1,2]", BcItemPropertyJson.readPropertyValue(property, "Custom"))
        listOf("a1!b2", "a1c2", "a9b2", "a1a0b2").forEach { type ->
            val rejected = planner.plan(context(wardrobe = wardrobe(BcWardrobeItem("Cloth", "NewDress", propertyJson = "{\"Type\":\"$type\"}"))), 0, metadata)
            assertEquals(BcWardrobeEntryReason.UnsupportedItemProperty, rejected.entries.single { it.group == "Cloth" }.reason)
        }
    }

    @Test
    fun privateNpcOwnerAppliesOwnerRulesWhenPublicSelfOmitsOwnerAndKeepsNativePriority() {
        val original = wardrobe()
        val privateOwner = original.copy(privateInputs = original.privateInputs.copy(owner = "NPC-Owner", privateCharacterJson = "[{\"Event\":[{\"Name\":\"PlayerCollaring\",\"Value\":2}]}]"))
        val character = self().copy(owner = null)
        val blockChange = unlockedLog() + BcOwnerRuleLogRecord("BlockChange", "OwnerRule")
        assertEquals(BcWardrobeUnavailableReason.BlockChangeOwnerRule, planner.unavailableReason(context(character, privateOwner, blockChange), catalog()))
        val appearanceLogs = unlockedLog() + BcOwnerRuleLogRecord("BlockAppearanceA", "OwnerRule")
        val plan = planner.plan(context(character, privateOwner, appearanceLogs), 0, catalog())
        assertEquals(BcWardrobeEntryReason.BlockAppearanceOwnerRule, plan.entries.single { it.group == "Cloth" }.reason)
        val onlineTrial = character.copy(ownership = BcCharacterOwnership("Online", 20, stage = 0))
        assertNull(planner.unavailableReason(context(onlineTrial, privateOwner, blockChange), catalog()))
        val npcTrial = privateOwner.copy(privateInputs = privateOwner.privateInputs.copy(privateCharacterJson = "[]", gameJson = "{\"GGTS\":{\"Level\":6}}"))
        assertNull(planner.unavailableReason(context(character, npcTrial, blockChange).copy(roomSpace = ""), catalog()))
    }

    @Test
    fun finalBodyStateAllowsClothingPrerequisiteThatOldBodyDidNotMeet() {
        val character = self(BcCharacterItem("BodyUpper", "FlatSmall")).copy(body = BcCharacterBodyState(hasBreasts = false, hasFlatChest = true))
        val bra = asset("Bra", "Bra1").copy(prerequisites = setOf("HasBreasts"))
        val metadata = catalog(bra).let { it.copy(groups = it.groups + ("Bra" to BcGroupRule("Bra", category = "Appearance", clothing = true))) }
        val plan = planner.plan(context(character, wardrobe(BcWardrobeItem("BodyUpper", "Normal"), BcWardrobeItem("Bra", "Bra1"))), 0, metadata)
        assertEquals("Normal", plan.appearance.single { it.group == "BodyUpper" }.name)
        assertEquals("Bra1", plan.appearance.single { it.group == "Bra" }.name)
        assertFalse(plan.entries.any { it.reason == BcWardrobeEntryReason.PermissionDenied })
    }

    @Test
    fun bodyChangeCannotInvalidateRetainedItemAndRollsBackWithoutRemovingItem() {
        val retained = BcCharacterItem("ItemVulva", "VaginalDevice")
        val character = self(BcCharacterItem("Pussy", "Pussy1"), retained)
        val metadata = catalog(asset("Pussy", "Pussy1"), asset("Pussy", "Penis"), asset("ItemVulva", "VaginalDevice").copy(prerequisites = setOf("HasVagina"))).let {
            it.copy(groups = it.groups + mapOf("Pussy" to BcGroupRule("Pussy", category = "Appearance"), "ItemVulva" to BcGroupRule("ItemVulva", category = "Item")))
        }
        val plan = planner.plan(context(character, wardrobe(BcWardrobeItem("Pussy", "Penis"))), 0, metadata)
        assertEquals("Pussy1", plan.appearance.single { it.group == "Pussy" }.name)
        assertEquals(retained, plan.appearance.single { it.group == "ItemVulva" })
        assertEquals(BcWardrobeEntryReason.FinalAppearanceIncompatible, plan.entries.single { it.group == "Pussy" }.reason)
    }

    @Test
    fun dependentRollbacksReachSafeFixedPointIndependentOfSlotOrder() {
        val currentBody = BcCharacterItem("BodyUpper", "Normal")
        val currentBra = BcCharacterItem("Bra", "OldBra", propertyJson = "{\"LockedBy\":\"Padlock\"}")
        val character = self(currentBody, currentBra)
        val metadata = catalog(asset("Bra", "OldBra").copy(prerequisites = setOf("HasBreasts")), asset("Bra", "Bra1").copy(prerequisites = setOf("HasBreasts"))).let {
            it.copy(groups = it.groups + ("Bra" to BcGroupRule("Bra", category = "Appearance", clothing = true)))
        }
        val desired = listOf(BcWardrobeItem("BodyUpper", "FlatSmall"), BcWardrobeItem("Bra", "Bra1"), BcWardrobeItem("HairFront", "Long"))
        val forward = planner.plan(context(character, wardrobe(*desired.toTypedArray())), 0, metadata)
        val reverse = planner.plan(context(character, wardrobe(*desired.reversed().toTypedArray())), 0, metadata)
        assertEquals(currentBody, forward.appearance.single { it.group == "BodyUpper" })
        assertEquals(currentBra, forward.appearance.single { it.group == "Bra" })
        assertEquals("Long", forward.appearance.single { it.group == "HairFront" }.name)
        assertEquals(forward.appearance, reverse.appearance)
        assertEquals(forward.entries, reverse.entries)
    }

    private fun request() = BcWardrobeRequest(10, BcRoomName("Room"), 3, 7, 0, 5, catalog())

    private fun snapshot(character: BcCharacterSnapshot = self()) = BcSessionSnapshot(
        state = BcSessionState.Ready, connectionHealth = BcConnectionHealth.VerifiedOnline, desiredOnline = true,
        bcVersion = "R129", account = BcAccount(10, "Self", "self"),
        currentRoom = BcRoom(BcRoomName("Room"), "", 1, 10, false, null, space = "", game = ""), roomPresenceEpoch = 3,
        roomCharacters = listOf(character), relationships = BcRelationshipSnapshot(accountMemberNumber = 10, accountGeneration = 7),
        selfRuleLog = unlockedLog(), selfRuleLogSessionId = "login", wardrobeState = wardrobe(),
    )

    private fun context(
        character: BcCharacterSnapshot = self(),
        wardrobe: BcWardrobeState = wardrobe(),
        logs: List<BcOwnerRuleLogRecord>? = unlockedLog(),
    ) = BcWardrobeContext(character, BcRoomName("Room"), "", "", setOf(10), emptySet(), wardrobe, logs, "login", 1_000L)

    private fun self(vararg items: BcCharacterItem) = BcCharacterSnapshot(
        id = "10", memberNumber = 10, name = "Self", displayName = "Self", isSelf = true,
        appearance = items.toList(), body = BcCharacterBodyState.fromAppearance(items.toList()),
    )

    private fun unlockedLog() = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom"))

    private fun wardrobe(vararg items: BcWardrobeItem) = BcWardrobeState(
        memberNumber = 10, selfRuleLogSessionId = "login", revision = 5,
        rawWardrobeJson = "[]", rawNamesJson = "[]", wardrobeAvailable = true, namesAvailable = true,
        slots = listOf(BcWardrobeSlot(0, "Outfit", BcWardrobeSlotStatus.Saved, items.toList().ifEmpty { listOf(BcWardrobeItem("Cloth", "NewDress")) })),
        privateInputs = BcWardrobePrivateInputs(gameAvailable = true, inventoryAvailable = true, inventoryItems = emptyList(), privateCharacterAvailable = true),
    )

    private fun asset(group: String, name: String) = BcAssetRule(group, name, value = 0.0, defaultColor = listOf("Default"))

    private fun catalog(vararg additions: BcAssetRule): BcRuleCatalog {
        val groups = listOf(
            BcGroupRule("BodyUpper", category = "Appearance", allowNone = false, defaultAssetName = "Normal"),
            BcGroupRule("HairFront", category = "Appearance", allowNone = false, defaultAssetName = "Short"),
            BcGroupRule("Cloth", category = "Appearance", clothing = true),
            BcGroupRule("Eyes", category = "Appearance", allowNone = false, defaultAssetName = "Eyes1", allowExpression = listOf("Happy", "Sad")),
            BcGroupRule("Eyes2", category = "Appearance", allowNone = false, defaultAssetName = "Eyes1", allowExpression = listOf("Happy", "Sad")),
            BcGroupRule("ItemArms", category = "Item"),
            BcGroupRule("ItemNeck", category = "Item"),
        )
        val assets = listOf(
            asset("BodyUpper", "Normal"), asset("BodyUpper", "Small"), asset("BodyUpper", "FlatSmall"),
            asset("HairFront", "Short"), asset("HairFront", "Long"),
            asset("Cloth", "OldDress"), asset("Cloth", "NewDress"),
            asset("Eyes", "Eyes1"), asset("Eyes", "EyesAlt"), asset("Eyes2", "Eyes1"), asset("Eyes2", "EyesAlt"),
            asset("ItemArms", "Rope"), asset("ItemNeck", "ClubSlaveCollar"),
        )
        return BcRuleCatalog("R129", groups.associateBy { it.name }, emptyMap(), (assets + additions).associateBy { BcAssetKey(it.group, it.name) })
    }
}
