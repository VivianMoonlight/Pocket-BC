package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterLovership
import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcActivitySource
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBcInteractionPermissionEngineTest {
    private val engine = DefaultBcInteractionPermissionEngine()

    @Test
    fun evaluateActivityAllowsKnownActivityOnKnownGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(),
                candidate = activityCandidate(activityName = "Spank"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable
        assertTrue(executable is BcExecutableInteraction.Activity)
        val activity = executable as BcExecutableInteraction.Activity
        assertEquals(Target.memberNumber, activity.command.targetMemberNumber)
        assertEquals("ItemArms", activity.command.targetGroup)
        assertEquals("Spank", activity.command.activityName)
    }

    @Test
    fun evaluateActivityQuarantinesUnknownPrerequisite() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        groups = listOf(BcGroupRule(name = "ItemMouth")),
                        activities = listOf(
                            BcActivityRule(
                                name = "MouthOnly",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("FuturePrerequisite"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "MouthOnly"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.UnsupportedActivityPrerequisite)
    }

    @Test
    fun zoneAccessibleChecksTargetButTargetZoneAccessibleChecksActor() {
        val blocker = BcAssetRule(
            group = "ItemHood",
            name = "Blocker",
            blocks = setOf("ItemArms"),
        )
        val rules = ruleCatalog(
            groups = listOf(BcGroupRule("ItemArms"), BcGroupRule("ItemHood")),
            activities = listOf(
                BcActivityRule("Touch", targetGroups = setOf("ItemArms"), prerequisites = setOf("ZoneAccessible")),
                BcActivityRule("ReverseTouch", targetGroups = setOf("ItemArms"), prerequisites = setOf("TargetZoneAccessible")),
            ),
            assets = listOf(blocker),
        )

        val targetBlocked = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(appearance = listOf(BcCharacterItem("ItemHood", "Blocker"))),
                    rules = rules,
                ),
                candidate = activityCandidate(activityName = "Touch"),
            ),
        )
        val actorBlocked = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemHood", "Blocker"))),
                    rules = rules,
                ),
                candidate = activityCandidate(activityName = "ReverseTouch"),
            ),
        )

        assertDeniedWith(targetBlocked, BcPermissionReasonCode.TargetZoneBlocked)
        assertDeniedWith(actorBlocked, BcPermissionReasonCode.ActorZoneBlocked)
    }

    @Test
    fun targetMouthOpenOnlyAppliesToMouthGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(appearance = listOf(BcCharacterItem("ItemMouth", "ClosedGag"))),
                    rules = ruleCatalog(
                        groups = listOf(BcGroupRule("ItemHead"), BcGroupRule("ItemMouth")),
                        activities = listOf(
                            BcActivityRule(
                                name = "Look",
                                targetGroups = setOf("ItemHead"),
                                prerequisites = setOf("TargetMouthOpen"),
                            ),
                        ),
                        assets = listOf(BcAssetRule("ItemMouth", "ClosedGag")),
                    ),
                ),
                candidate = activityCandidate(group = "ItemHead", activityName = "Look"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun siblingGenderPrerequisitesUseSharedOwnerAndBodySex() {
        val brotherActor = Actor.copy(
            ownerMemberNumber = 99,
            body = BcCharacterBodyState(hasVagina = false, hasPenis = true),
        )
        val brotherTarget = Target.copy(
            ownerMemberNumber = 99,
            body = BcCharacterBodyState(hasVagina = false, hasPenis = true),
        )
        val rules = ruleCatalog(
            activities = listOf(
                BcActivityRule("BrotherHug", targetGroups = setOf("ItemArms"), prerequisites = setOf("Brothers")),
            ),
        )

        val allowed = engine.evaluate(
            BcPermissionRequest(
                context = context(actor = brotherActor, target = brotherTarget, rules = rules),
                candidate = activityCandidate(activityName = "BrotherHug"),
            ),
        )
        val denied = engine.evaluate(
            BcPermissionRequest(
                context = context(actor = brotherActor, target = brotherTarget.copy(ownerMemberNumber = 100), rules = rules),
                candidate = activityCandidate(activityName = "BrotherHug"),
            ),
        )

        assertTrue(allowed is BcPermissionDecision.Allowed)
        assertDeniedWith(denied, BcPermissionReasonCode.ActorPrerequisiteNotMet)
    }

    @Test
    fun roomArousalBlockDisablesActivities() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    room = BcPermissionRoomContext(
                        roomName = "TestRoom",
                        memberNumbers = setOf(Actor.memberNumber, Target.memberNumber),
                        blockCategories = setOf("Arousal"),
                    ),
                ),
                candidate = activityCandidate(activityName = "Spank"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.RoomBlocksActivities)
    }

    @Test
    fun arousalZoneAndActivityPreferencesAreAppliedWhenPresent() {
        val rules = ruleCatalog(
            groups = listOf(BcGroupRule(name = "ItemArms", arousalZoneId = 0)),
            activities = listOf(
                BcActivityRule(
                    name = "Spank",
                    targetGroups = setOf("ItemArms"),
                    activityId = 0,
                ),
            ),
        )
        val zoneDisabled = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(arousalSettingsJson = """{"Active":"Hybrid","Zone":"d","Activity":"z"}"""),
                    rules = rules,
                ),
                candidate = activityCandidate(activityName = "Spank"),
            ),
        )
        val actorBlocked = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(arousalSettingsJson = """{"Activity":"f"}"""),
                    target = Target.copy(arousalSettingsJson = """{"Active":"Hybrid","Zone":"f","Activity":"z"}"""),
                    rules = rules,
                ),
                candidate = activityCandidate(activityName = "Spank"),
            ),
        )

        assertDeniedWith(zoneDisabled, BcPermissionReasonCode.TargetArousalZoneDisabled)
        assertDeniedWith(actorBlocked, BcPermissionReasonCode.ActivityPreferenceBlocked)
    }

    @Test
    fun echoActedZoneNakedDoesNotTreatAPlugAsCoveredButStillHonorsButtChastity() {
        val plug = BcAssetRule(
            group = "ItemButt",
            name = "Plug",
            effects = setOf("IsPlugged"),
        )
        val chastity = BcAssetRule(
            group = "ItemPelvis",
            name = "ChastityBelt",
            effects = setOf("ButtChaste"),
        )
        val rules = ruleCatalog(
            groups = listOf(BcGroupRule("ItemButt"), BcGroupRule("ItemPelvis")),
            activities = listOf(
                BcActivityRule(
                    name = "EchoButtAction",
                    targetGroups = setOf("ItemButt"),
                    prerequisites = setOf("Luzi_ActedZoneNaked"),
                    source = BcActivitySource.Echo,
                ),
            ),
            assets = listOf(plug, chastity),
        )

        val plugged = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(appearance = listOf(BcCharacterItem("ItemButt", "Plug"))),
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemButt", activityName = "EchoButtAction"),
            ),
        )
        val chaste = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(appearance = listOf(BcCharacterItem("ItemPelvis", "ChastityBelt"))),
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemButt", activityName = "EchoButtAction"),
            ),
        )

        assertTrue(plugged is BcPermissionDecision.Allowed)
        assertDeniedWith(chaste, BcPermissionReasonCode.TargetZoneNotNaked)
    }

    @Test
    fun echoPrivateExposedChecksVulvaAccessWithoutAddingPelvisAccess() {
        val pelvisBlocker = BcAssetRule(
            group = "ItemTorso",
            name = "PelvisBlocker",
            blocks = setOf("ItemPelvis"),
        )
        val vulvaBlocker = BcAssetRule(
            group = "ItemTorso",
            name = "VulvaBlocker",
            blocks = setOf("ItemVulva"),
        )
        val rules = ruleCatalog(
            groups = listOf(BcGroupRule("ItemHands"), BcGroupRule("ItemTorso")),
            activities = listOf(
                BcActivityRule(
                    name = "EchoPrivateAction",
                    targetGroups = setOf("ItemHands"),
                    prerequisites = setOf("Luzi_PrivateExposed"),
                    source = BcActivitySource.Echo,
                ),
            ),
            assets = listOf(pelvisBlocker, vulvaBlocker),
        )

        val pelvisBlocked = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemTorso", "PelvisBlocker"))),
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemHands", activityName = "EchoPrivateAction"),
            ),
        )
        val vulvaBlocked = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemTorso", "VulvaBlocker"))),
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemHands", activityName = "EchoPrivateAction"),
            ),
        )

        assertTrue(pelvisBlocked is BcPermissionDecision.Allowed)
        assertDeniedWith(vulvaBlocked, BcPermissionReasonCode.ActorPrerequisiteNotMet)
    }

    @Test
    fun echoCuddleRequiresBothItemMiscGroupsToBeEmpty() {
        val rules = BcEchoActivityCatalog.mergeInto(ruleCatalog())
        val candidate = activityCandidate(group = "ItemTorso", activityName = "钻进怀里")

        val allowed = engine.evaluate(
            BcPermissionRequest(context = context(rules = rules), candidate = candidate),
        )
        val actorOccupied = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemMisc", "Occupied"))),
                    rules = rules,
                ),
                candidate = candidate,
            ),
        )
        val targetOccupied = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(appearance = listOf(BcCharacterItem("ItemMisc", "Occupied"))),
                    rules = rules,
                ),
                candidate = candidate,
            ),
        )

        assertTrue(allowed is BcPermissionDecision.Allowed)
        assertDeniedWith(actorOccupied, BcPermissionReasonCode.ActorZoneOccupied)
        assertDeniedWith(targetOccupied, BcPermissionReasonCode.TargetZoneOccupied)
    }

    @Test
    fun echoCarryAndTrolleyUseStructuredActorAndTargetItemRequirements() {
        val rules = BcEchoActivityCatalog.mergeInto(
            ruleCatalog(
                groups = listOf(BcGroupRule("ItemDevices")),
                activities = emptyList(),
                assets = listOf(
                    BcAssetRule("ItemDevices", "BurlapSack"),
                    BcAssetRule("ItemDevices", "Trolley", effects = setOf("Leash")),
                ),
            ),
        )
        val sackTarget = Target.copy(
            appearance = listOf(BcCharacterItem("ItemDevices", "BurlapSack")),
        )
        val trolleyTarget = Target.copy(
            appearance = listOf(BcCharacterItem("ItemDevices", "Trolley")),
        )

        val carry = engine.evaluate(
            BcPermissionRequest(
                context = context(target = sackTarget, rules = rules),
                candidate = activityCandidate(group = "ItemTorso", activityName = "扛起"),
            ),
        )
        val carryWithExistingActorItem = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemMisc", "扛起来的麻袋"))),
                    target = sackTarget,
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemTorso", activityName = "扛起"),
            ),
        )
        val carryWithOccupiedActorSlot = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemMisc", "Other"))),
                    target = sackTarget,
                    rules = rules,
                ),
                candidate = activityCandidate(group = "ItemTorso", activityName = "扛起"),
            ),
        )
        val carryWithoutSack = engine.evaluate(
            BcPermissionRequest(
                context = context(rules = rules),
                candidate = activityCandidate(group = "ItemTorso", activityName = "扛起"),
            ),
        )
        val trolley = engine.evaluate(
            BcPermissionRequest(
                context = context(target = trolleyTarget, rules = rules),
                candidate = activityCandidate(group = "ItemTorso", activityName = "手推车"),
            ),
        )

        assertTrue(carry is BcPermissionDecision.Allowed)
        assertTrue(carryWithExistingActorItem is BcPermissionDecision.Allowed)
        assertDeniedWith(carryWithOccupiedActorSlot, BcPermissionReasonCode.ActorZoneOccupied)
        assertDeniedWith(carryWithoutSack, BcPermissionReasonCode.TargetPrerequisiteNotMet)
        assertTrue(trolley is BcPermissionDecision.Allowed)
    }

    @Test
    fun evaluateActivityWithNeedsDeniesWhenActorHasNoRequiredItem() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        activities = listOf(
                            BcActivityRule(
                                name = "CropStrike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-CropStrike"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "CropStrike"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.MissingRequiredActorItem)
    }

    @Test
    fun evaluateActivityWithNeedsAllowsUsingActorAllowActivityItem() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "Crop")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "CropStrike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-CropStrike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Crop",
                                allowActivity = setOf("CropStrike"),
                                allowActivityOn = setOf("ItemArms"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "CropStrike"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.Activity
        assertEquals("ItemHandheld", executable.command.activityAssetGroup)
        assertEquals("Crop", executable.command.activityAssetName)
        assertEquals(BcPermissionItemSource.Actor, executable.usedItem?.source)
    }

    @Test
    fun filterExpandsNeedsActivityForEveryMatchingActorItem() {
        val result = engine.filter(
            BcPermissionFilterRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(
                            BcCharacterItem(group = "ItemHandheld", name = "Crop"),
                            BcCharacterItem(group = "ItemHandheld", name = "Cane"),
                        ),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Strike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-Strike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Crop",
                                allowActivity = setOf("Strike"),
                            ),
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Cane",
                                allowActivity = setOf("Strike"),
                            ),
                        ),
                    ),
                ),
                candidates = listOf(activityCandidate(activityName = "Strike")),
            ),
        )

        assertEquals(2, result.allowed.size)
        val assetNames = result.allowed
            .filterIsInstance<BcExecutableInteraction.Activity>()
            .mapNotNull { it.command.activityAssetName }
            .toSet()
        assertEquals(setOf("Crop", "Cane"), assetNames)
    }

    @Test
    fun evaluateActivityResolvesSelectedItemVariantInsidePermissionEngine() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(
                            BcCharacterItem(group = "ItemHandheld", name = "Crop"),
                            BcCharacterItem(group = "ItemHandheld", name = "Cane"),
                        ),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Strike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-Strike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Crop",
                                allowActivity = setOf("Strike"),
                            ),
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Cane",
                                allowActivity = setOf("Strike"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(
                    activityName = "Strike",
                    activityAssetGroup = "ItemHandheld",
                    activityAssetName = "Cane",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.Activity
        assertEquals("Cane", executable.command.activityAssetName)
    }

    @Test
    fun evaluateActivityRejectsStaleSelectedItemVariant() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "Crop")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Strike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-Strike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Crop",
                                allowActivity = setOf("Strike"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(
                    activityName = "Strike",
                    activityAssetGroup = "ItemHandheld",
                    activityAssetName = "Cane",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.SelectedActivityItemUnavailable)
    }

    @Test
    fun evaluateActivityWithTargetNeedsAllowsUsingTargetAllowActivityItem() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemNeck", name = "BellCollar")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemNeck"),
                            BcGroupRule(name = "ItemMouth"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "RingBell",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("TargetNeeds-RingBell"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemNeck",
                                name = "BellCollar",
                                allowActivity = setOf("RingBell"),
                                allowActivityOn = setOf("ItemMouth"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "RingBell"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.Activity
        assertEquals("ItemNeck", executable.command.activityAssetGroup)
        assertEquals("BellCollar", executable.command.activityAssetName)
        assertEquals(BcPermissionItemSource.Target, executable.usedItem?.source)
    }

    @Test
    fun evaluateShockItemAllowsWhenTargetCanReceiveShockAndActorCanTriggerShock() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "RemoteControl")),
                    ),
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemNeck", name = "ShockCollar")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemNeck"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "ShockItem",
                                targetGroups = setOf("ItemNeck"),
                                prerequisites = setOf("ShockItem"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemNeck",
                                name = "ShockCollar",
                                effects = setOf("ReceiveShock"),
                            ),
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "RemoteControl",
                                effects = setOf("TriggerShock"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemNeck", activityName = "ShockItem"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.Activity
        assertEquals("ItemHandheld", executable.command.activityAssetGroup)
        assertEquals("RemoteControl", executable.command.activityAssetName)
        assertEquals(BcPermissionItemSource.Actor, executable.usedItem?.source)
    }

    @Test
    fun evaluateShockItemDeniesWithoutShockReceiver() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "RemoteControl")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemNeck"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "ShockItem",
                                targetGroups = setOf("ItemNeck"),
                                prerequisites = setOf("ShockItem"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "RemoteControl",
                                effects = setOf("TriggerShock"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemNeck", activityName = "ShockItem"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.MissingShockReceiverItem)
    }

    @Test
    fun wearItemNeedsServerCheckWhenServerAllowItemIsUnknown() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(serverAllowItem = null),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.NeedsServerCheck)
        val needsServerCheck = decision as BcPermissionDecision.NeedsServerCheck
        assertEquals(BcPermissionReasonCode.ServerAllowItemRequired, needsServerCheck.reasons.single().code)
    }

    @Test
    fun wearItemAllowsWhenServerAllowItemPassed() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(serverAllowItem = true),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val executable = (decision as BcPermissionDecision.Allowed).executable
        assertTrue(executable is BcExecutableInteraction.ItemUpdate)
        val itemUpdate = executable as BcExecutableInteraction.ItemUpdate
        assertEquals("ItemArms", itemUpdate.command.group)
        assertEquals("HempRope", itemUpdate.command.name)
    }

    @Test
    fun wearItemPublishesActionUseMessage() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(serverAllowItem = true),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val itemUpdate = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
        val action = itemUpdate.command.publishAction
        assertEquals("ActionUse", action?.content)
        assertEquals(null, action?.previousAssetName)
        assertEquals("HempRope", action?.nextAssetName)
    }

    @Test
    fun replaceItemPublishesActionSwapMessage() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemArms", name = "OldRope")),
                    ),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "OldRope"),
                            BcAssetRule(group = "ItemArms", name = "HempRope"),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val itemUpdate = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
        val action = itemUpdate.command.publishAction
        assertEquals("ActionSwap", action?.content)
        assertEquals("OldRope", action?.previousAssetName)
        assertEquals("HempRope", action?.nextAssetName)
    }

    @Test
    fun removeItemPublishesActionRemoveMessage() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemArms", name = "HempRope")),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val itemUpdate = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
        val action = itemUpdate.command.publishAction
        assertEquals("ActionRemove", action?.content)
        assertEquals("HempRope", action?.previousAssetName)
        assertEquals(null, action?.nextAssetName)
    }

    @Test
    fun modifyItemPublishesActionUseMessage() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemArms", name = "HempRope")),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.ModifyItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                    itemName = "HempRope",
                    propertyJson = """{"TypeRecord":{"typed":1}}""",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val itemUpdate = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
        val action = itemUpdate.command.publishAction
        assertEquals("ActionUse", action?.content)
        assertEquals(null, action?.previousAssetName)
        assertEquals("HempRope", action?.nextAssetName)
    }

    @Test
    fun clothingUpdatesDoNotPublishRestraintActionMessages() {
        val clothingRules = ruleCatalog(
            groups = listOf(
                BcGroupRule(
                    name = "Cloth",
                    category = "Appearance",
                    clothing = true,
                    isItem = false,
                ),
            ),
            assets = listOf(
                BcAssetRule(group = "Cloth", name = "OldDress"),
                BcAssetRule(group = "Cloth", name = "NewDress"),
            ),
        )
        val emptyTarget = Target.copy(appearance = emptyList())
        val dressedTarget = Target.copy(
            appearance = listOf(BcCharacterItem(group = "Cloth", name = "OldDress")),
        )
        val candidates = listOf(
            context(target = emptyTarget, rules = clothingRules, serverAllowItem = true) to
                BcInteractionCandidate.WearItem(Target.memberNumber, "Cloth", "NewDress"),
            context(target = dressedTarget, rules = clothingRules, serverAllowItem = true) to
                BcInteractionCandidate.WearItem(Target.memberNumber, "Cloth", "NewDress"),
            context(target = dressedTarget, rules = clothingRules, serverAllowItem = true) to
                BcInteractionCandidate.ModifyItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "Cloth",
                    itemName = "OldDress",
                    propertyJson = """{"TypeRecord":{"typed":1}}""",
                ),
            context(target = dressedTarget, rules = clothingRules, serverAllowItem = true) to
                BcInteractionCandidate.RemoveItem(Target.memberNumber, "Cloth"),
        )

        candidates.forEach { (permissionContext, candidate) ->
            val decision = engine.evaluate(BcPermissionRequest(permissionContext, candidate))
            assertTrue(decision is BcPermissionDecision.Allowed)
            val update = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
            assertEquals(null, update.command.publishAction)
        }
    }

    @Test
    fun selfCanRemoveUnknownEchoClothingWithoutPublishingItemAction() {
        val self = Actor.copy(
            appearance = listOf(BcCharacterItem(group = "Cloth", name = "EchoCustomDress")),
        )
        val rules = ruleCatalog(
            groups = listOf(
                BcGroupRule(
                    name = "Cloth",
                    category = "Appearance",
                    clothing = true,
                    isItem = false,
                ),
            ),
            assets = emptyList(),
        )

        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = self,
                    target = self,
                    rules = rules,
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(self.memberNumber, "Cloth"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
        val update = (decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate
        assertEquals(null, update.command.publishAction)
        assertEquals(null, update.command.name)
    }

    @Test
    fun wearItemDeniesDisabledAssetBeforeServerCheck() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "HempRope",
                                enable = false,
                            ),
                        ),
                    ),
                    serverAllowItem = null,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.AssetDisabled)
    }

    @Test
    fun wearItemDeniesOwnerOnlyAssetForNonOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "OwnerCuffs",
                                ownerOnly = true,
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "OwnerCuffs"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.ItemExclusivePermissionDenied)
    }

    @Test
    fun wearItemAllowsOwnerOnlyAssetForOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(ownerMemberNumber = Actor.memberNumber),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "OwnerCuffs",
                                ownerOnly = true,
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "OwnerCuffs"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemAllowsLoverOnlyAssetForLover() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(lovership = listOf(BcCharacterLovership(name = "Actor", memberNumber = Actor.memberNumber))),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "LoverCuffs",
                                loverOnly = true,
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "LoverCuffs"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun removeItemDeniesWhenGroupDoesNotAllowNone() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(
                                name = "ItemDevices",
                                allowNone = false,
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemDevices",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.GroupDoesNotAllowNone)
    }

    @Test
    fun filterSeparatesAllowedDeniedAndNeedsServerCheck() {
        val result = engine.filter(
            BcPermissionFilterRequest(
                context = context(serverAllowItem = null),
                candidates = listOf(
                    activityCandidate(activityName = "Spank"),
                    activityCandidate(activityName = "UnknownActivity"),
                    wearItemCandidate(),
                ),
            ),
        )

        assertEquals(1, result.allowed.size)
        assertEquals(1, result.denied.size)
        assertEquals(BcPermissionReasonCode.UnknownActivity, result.denied.single().reasons.single().code)
        assertEquals(1, result.needsServerCheck.size)
        assertEquals(BcPermissionReasonCode.ServerAllowItemRequired, result.needsServerCheck.single().reasons.single().code)
    }

    @Test
    fun commonRulesDenyWhenTargetIsNotInRoom() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    room = BcPermissionRoomContext(
                        roomName = "TestRoom",
                        memberNumbers = setOf(Actor.memberNumber),
                    ),
                ),
                candidate = activityCandidate(activityName = "Spank"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetNotInRoom)
    }


    @Test
    fun activityWithZoneAccessibleDeniesWhenTargetItemBlocksGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHood", name = "BlockingHood")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemMouth"),
                            BcGroupRule(name = "ItemHood"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Kiss",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("ZoneAccessible"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHood",
                                name = "BlockingHood",
                                blocks = setOf("ItemMouth"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "Kiss"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetZoneBlocked)
    }

    @Test
    fun activityWithZoneAccessibleAllowsWhenBlockingItemAllowsActivityOnGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHood", name = "ActivityFriendlyHood")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemMouth"),
                            BcGroupRule(name = "ItemHood"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Kiss",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("ZoneAccessible"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHood",
                                name = "ActivityFriendlyHood",
                                blocks = setOf("ItemMouth"),
                                allowActivityOn = setOf("ItemMouth"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "Kiss"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesWhenAnotherTargetItemBlocksGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHood", name = "BlockingHood")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemMouth"),
                            BcGroupRule(name = "ItemHood"),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHood",
                                name = "BlockingHood",
                                blocks = setOf("ItemMouth"),
                            ),
                            BcAssetRule(
                                group = "ItemMouth",
                                name = "PanelGag",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(group = "ItemMouth", itemName = "PanelGag"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetZoneBlocked)
    }

    @Test
    fun activityUseHandsDeniesWhenActorHasMergedFingersPropertyEffect() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemHands",
                                name = "Mittens",
                                propertyJson = """{"Effect":["MergedFingers"]}""",
                            ),
                        ),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHands"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Touch",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("UseHands"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHands",
                                name = "Mittens",
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "Touch"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.ActorCannotUseHands)
    }

    @Test
    fun activityTargetMouthOpenDeniesWhenMouthItemDoesNotOpenMouth() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemMouth", name = "ClosedGag")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(BcGroupRule(name = "ItemMouth")),
                        activities = listOf(
                            BcActivityRule(
                                name = "Feed",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("TargetMouthOpen"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemMouth",
                                name = "ClosedGag",
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "Feed"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetMouthNotOpen)
    }



    @Test
    fun wearItemDeniesTargetBlockedItemPermission() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(blockItemsJson = """{"ItemArms":{"HempRope":[""]}}"""),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemPermissionBlocked)
    }

    @Test
    fun wearItemDeniesTargetLimitedItemPermissionForNonPrivilegedActor() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(limitedItemsJson = """{"ItemArms":{"HempRope":[""]}}"""),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemPermissionLimited)
    }

    @Test
    fun wearItemAllowsTargetLimitedItemPermissionForWhitelistedActor() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        whiteList = listOf(Actor.memberNumber),
                        limitedItemsJson = """{"ItemArms":{"HempRope":[""]}}""",
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesTargetLimitedItemPermissionForWhitelistWhenTargetDisallowsWhitelistAccess() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        allowedInteractions = 3,
                        whiteList = listOf(Actor.memberNumber),
                        limitedItemsJson = """{"ItemArms":{"HempRope":[""]}}""",
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemPermissionLimited)
    }

    @Test
    fun wearItemDoesNotTreatBlacklistAsUniversalBlockWhenEveryoneCanInteract() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        allowedInteractions = 0,
                        blackList = listOf(Actor.memberNumber),
                    ),
                    serverAllowItem = null,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertTrue(decision is BcPermissionDecision.NeedsServerCheck)
    }

    @Test
    fun wearItemDeniesBlacklistedActorWhenTargetUsesEveryoneExceptBlacklist() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        allowedInteractions = 1,
                        blackList = listOf(Actor.memberNumber),
                    ),
                    serverAllowItem = null,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetBlacklistedActor)
    }

    @Test
    fun wearItemDeniesTargetBlockedTypePermissionFromTypeRecord() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(blockItemsJson = """{"ItemArms":{"HempRope":["typed1"]}}"""),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(propertyJson = """{"TypeRecord":{"typed":1}}"""),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemPermissionBlocked)
    }


    @Test
    fun wearItemAllowsTypedDefaultOptionEvenIfTypedZeroPermissionIsBlocked() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(blockItemsJson = """{"ItemArms":{"HempRope":["typed0"]}}"""),
                    rules = ruleCatalog(
                        assets = listOf(BcAssetRule(group = "ItemArms", name = "HempRope")),
                        extendedItems = listOf(
                            BcExtendedItemRule(
                                group = "ItemArms",
                                name = "HempRope",
                                archetype = BcExtendedItemArchetype.Typed,
                                recordKey = "typed",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(propertyJson = """{"TypeRecord":{"typed":0}}"""),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesModularDefaultTypePermissionFromTypeRecord() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(blockItemsJson = """{"ItemArms":{"HempRope":["module0"]}}"""),
                    rules = ruleCatalog(
                        assets = listOf(BcAssetRule(group = "ItemArms", name = "HempRope")),
                        extendedItems = listOf(
                            BcExtendedItemRule(
                                group = "ItemArms",
                                name = "HempRope",
                                archetype = BcExtendedItemArchetype.Modular,
                                recordKey = "module",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(propertyJson = """{"TypeRecord":{"module":0}}"""),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemPermissionBlocked)
    }

    @Test
    fun removeItemDeniesOwnerOnlyCurrentItemForNonOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemArms", name = "OwnerCuffs")),
                    ),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "OwnerCuffs", ownerOnly = true),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.ItemExclusivePermissionDenied)
    }

    @Test
    fun replacementChecksRemovalPermissionOfThePreviousItem() {
        val candidate = wearItemCandidate()
        for (allowRemoval in listOf(false, true)) {
            val decision = engine.evaluate(
                BcPermissionRequest(
                    context = context(
                        target = Target.copy(appearance = listOf(BcCharacterItem("ItemArms", "OwnerCuffs"))),
                        rules = ruleCatalog(assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "OwnerCuffs", ownerOnly = true, allowRemoveExclusive = allowRemoval),
                            BcAssetRule(group = "ItemArms", name = "HempRope"),
                        )),
                        serverAllowItem = true,
                    ),
                    candidate = candidate,
                ),
            )
            if (allowRemoval) assertTrue(decision is BcPermissionDecision.Allowed)
            else assertDeniedWith(decision, BcPermissionReasonCode.ItemExclusivePermissionDenied)
        }
    }

    @Test
    fun replacementCannotRemoveAnUnknownItemFromAnotherCharacter() {
        val decision = engine.evaluate(BcPermissionRequest(
            context = context(
                target = Target.copy(appearance = listOf(BcCharacterItem("ItemArms", "UnknownCuffs"))),
                serverAllowItem = true,
            ),
            candidate = wearItemCandidate(),
        ))
        assertDeniedWith(decision, BcPermissionReasonCode.UnknownAsset)
    }

    @Test
    fun removeItemAllowsAllowRemoveExclusiveOwnerOnlyCurrentItemWhenUnlocked() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemArms", name = "OwnerCuffs")),
                    ),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "OwnerCuffs",
                                ownerOnly = true,
                                allowRemoveExclusive = true,
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun removeItemDeniesOwnerOnlyLockForNonOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemArms",
                                name = "HempRope",
                                propertyJson = """{"LockedBy":"OwnerPadlock"}""",
                            ),
                        ),
                    ),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "HempRope"),
                            BcAssetRule(group = "ItemMisc", name = "OwnerPadlock", ownerOnly = true),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemLocked)
    }

    @Test
    fun removeItemAllowsExclusivePadlockOnOtherCharacter() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemArms",
                                name = "HempRope",
                                propertyJson = """{"LockedBy":"ExclusivePadlock"}""",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun removeItemDeniesPasswordLockedCurrentItem() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemArms",
                                name = "HempRope",
                                propertyJson = """{"LockedBy":"PasswordPadlock"}""",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetItemLocked)
    }

    @Test
    fun modifyItemAllowsOwnerPadlockForOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        ownerMemberNumber = Actor.memberNumber,
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemArms",
                                name = "HempRope",
                                propertyJson = """{"LockedBy":"OwnerPadlock"}""",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.ModifyItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemArms",
                    itemName = "HempRope",
                    propertyJson = """{"TypeRecord":{"typed":1}}""",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesLocallyWhenAllowedInteractionsRequiresOwner() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(allowedInteractions = 5),
                    serverAllowItem = null,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.LocalAllowItemDenied)
    }



    @Test
    fun wearItemDeniesWhenActorCannotInteractWithTarget() {
        val restrictedActor = Actor.copy(
            appearance = listOf(
                BcCharacterItem(group = "ItemArms", name = "ArmBinder"),
                BcCharacterItem(group = "ItemMouth", name = "PanelGag"),
                BcCharacterItem(group = "ItemFeet", name = "FootBinder"),
            ),
        )
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = restrictedActor,
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemMouth"),
                            BcGroupRule(name = "ItemFeet"),
                        ),
                        assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "HempRope"),
                            BcAssetRule(group = "ItemArms", name = "ArmBinder", effects = setOf("Block", "BlockHands")),
                            BcAssetRule(group = "ItemMouth", name = "PanelGag", effects = setOf("BlockMouth")),
                            BcAssetRule(group = "ItemFeet", name = "FootBinder", effects = setOf("BlockFeet")),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.ActorPrerequisiteNotMet)
    }

    @Test
    fun wearItemDeniesTargetEnclosedByDifferentGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemDevices", name = "WoodenBox")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemDevices"),
                        ),
                        assets = listOf(
                            BcAssetRule(group = "ItemArms", name = "HempRope"),
                            BcAssetRule(group = "ItemDevices", name = "WoodenBox", effects = setOf("Enclose")),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetZoneBlocked)
    }

    @Test
    fun removeItemAllowsRemovingTheEnclosingItemGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemDevices", name = "WoodenBox")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(BcGroupRule(name = "ItemDevices")),
                        assets = listOf(BcAssetRule(group = "ItemDevices", name = "WoodenBox", effects = setOf("Enclose"))),
                    ),
                    serverAllowItem = true,
                ),
                candidate = BcInteractionCandidate.RemoveItem(
                    targetMemberNumber = Target.memberNumber,
                    group = "ItemDevices",
                ),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesAssetPrerequisiteWhenTargetIsNotCollared() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "CollarOnlyCuffs",
                                prerequisites = setOf("Collared"),
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "CollarOnlyCuffs"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun wearItemAllowsAssetPrerequisiteWhenTargetIsCollared() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemNeck", name = "LeatherCollar")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemNeck"),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "CollarOnlyCuffs",
                                prerequisites = setOf("Collared"),
                            ),
                            BcAssetRule(
                                group = "ItemNeck",
                                name = "LeatherCollar",
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "CollarOnlyCuffs"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun wearItemDeniesOptionPrerequisiteFromPropertyJson() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(serverAllowItem = true),
                candidate = wearItemCandidate(
                    propertyJson = """{"TypeRecord":{"typed":1},"Prerequisite":["Collared"]}""",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun wearItemQuarantinesUnknownOptionPrerequisite() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(serverAllowItem = true),
                candidate = wearItemCandidate(
                    propertyJson = """{"TypeRecord":{"typed":1},"Prerequisite":["FutureOptionRule"]}""",
                ),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.UnsupportedOptionPrerequisite)
    }

    @Test
    fun wearItemDeniesAllowActivePoseMismatch() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(activePose = listOf("Kneel")),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "StandingOnlyCuffs",
                                allowActivePose = setOf("BaseLower"),
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "StandingOnlyCuffs"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPoseNotAllowed)
    }

    @Test
    fun wearItemAllowsAllowActivePoseFromDifferentCategory() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(activePose = listOf("Kneel")),
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "UpperOnlyCuffs",
                                allowActivePose = setOf("BaseUpper"),
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "UpperOnlyCuffs"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun activitySupportsAccessMouthPrerequisite() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHood", name = "MouthBlockingHood")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemMouth"),
                            BcGroupRule(name = "ItemHood"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Feed",
                                targetGroups = setOf("ItemMouth"),
                                prerequisites = setOf("AccessMouth"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHood",
                                name = "MouthBlockingHood",
                                blocks = setOf("ItemMouth"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemMouth", activityName = "Feed"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetZoneBlocked)
    }

    @Test
    fun wearItemDeniesHasPenisPrerequisiteForDefaultFemaleTarget() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "PenisOnlyItem",
                                prerequisites = setOf("HasPenis"),
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "PenisOnlyItem"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }


    @Test
    fun unknownWornItemPropertyBlockStillBlocksTargetGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(
                            BcCharacterItem(
                                group = "ItemDevices",
                                name = "UnknownBox",
                                propertyJson = """{"Block":["ItemArms"]}""",
                            ),
                        ),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemDevices"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Touch",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("ZoneAccessible"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "Touch"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetZoneBlocked)
    }

    @Test
    fun onBedPrerequisiteDeniesWhenTargetIsNotOnBed() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "BedOnlyCuffs",
                                prerequisites = setOf("OnBed"),
                            ),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "BedOnlyCuffs"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun nakedHandsPrerequisiteDeniesWhenTargetWearsGloves() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "Gloves", name = "WarmGloves")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "Gloves"),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemArms",
                                name = "HandSkinItem",
                                prerequisites = setOf("NakedHands"),
                            ),
                            BcAssetRule(group = "Gloves", name = "WarmGloves"),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(itemName = "HandSkinItem"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun fullWardrobeAccessExplicitFalseBlocksNonClothingAppearanceGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    target = Target.copy(
                        onlineSharedSettingsJson = """{"AllowFullWardrobeAccess":false}""",
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(
                                name = "BodyUpper",
                                category = "Appearance",
                                clothing = false,
                                isItem = false,
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(group = "BodyUpper", name = "XLarge"),
                        ),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(group = "BodyUpper", itemName = "XLarge"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun missingFullWardrobeAccessBlocksNonClothingAppearanceGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(
                                name = "BodyUpper",
                                category = "Appearance",
                                clothing = false,
                                isItem = false,
                            ),
                        ),
                        assets = listOf(BcAssetRule(group = "BodyUpper", name = "XLarge")),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(group = "BodyUpper", itemName = "XLarge"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.TargetPrerequisiteNotMet)
    }

    @Test
    fun missingFullWardrobeAccessDoesNotBlockClothingGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(
                                name = "Cloth",
                                category = "Appearance",
                                clothing = true,
                                isItem = false,
                            ),
                        ),
                        assets = listOf(BcAssetRule(group = "Cloth", name = "Dress")),
                    ),
                    serverAllowItem = true,
                ),
                candidate = wearItemCandidate(group = "Cloth", itemName = "Dress"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }


    @Test
    fun evaluateActivityWithNeedsDoesNotUseItemNameWithoutAllowActivity() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "Strike")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Strike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-Strike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Strike",
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "Strike"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.MissingRequiredActorItem)
    }


    @Test
    fun evaluateActivityWithNeedsIgnoresAllowActivityOnLikeBcCharacterItemsForActivity() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "Crop")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "Strike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-Strike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "Crop",
                                allowActivity = setOf("Strike"),
                                allowActivityOn = setOf("ItemFeet"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(activityName = "Strike"),
            ),
        )

        assertTrue(decision is BcPermissionDecision.Allowed)
    }

    @Test
    fun evaluateShockItemRequiresReceiverOnFocusedGroup() {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemHandheld", name = "RemoteControl")),
                    ),
                    target = Target.copy(
                        appearance = listOf(BcCharacterItem(group = "ItemNeckAccessories", name = "ShockUnit")),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemNeck"),
                            BcGroupRule(name = "ItemNeckAccessories"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "ShockItem",
                                targetGroups = setOf("ItemNeck"),
                                prerequisites = setOf("ShockItem"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemNeckAccessories",
                                name = "ShockUnit",
                                effects = setOf("ReceiveShock"),
                            ),
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "RemoteControl",
                                effects = setOf("TriggerShock"),
                            ),
                        ),
                    ),
                ),
                candidate = activityCandidate(group = "ItemNeck", activityName = "ShockItem"),
            ),
        )

        assertDeniedWith(decision, BcPermissionReasonCode.MissingShockReceiverItem)
    }



    @Test
    fun filterPageScansOnlyRequestedCandidateWindow() {
        val candidates = listOf(
            activityCandidate(activityName = "Spank"),
            activityCandidate(activityName = "Spank"),
            wearItemCandidate(),
        )

        val page = engine.filterPage(
            BcPermissionFilterPageRequest(
                context = context(serverAllowItem = true),
                candidates = candidates,
                offset = 1,
                limit = 1,
            ),
        )

        assertEquals(1, page.scannedCandidateCount)
        assertEquals(1, page.emittedInteractionCount)
        assertEquals(2, page.nextOffset)
        assertTrue(page.hasMoreCandidates)
        assertEquals(1, page.result.allowed.size)
    }

    @Test
    fun filterPageDoesNotSplitExpandedActivityCandidate() {
        val page = engine.filterPage(
            BcPermissionFilterPageRequest(
                context = context(
                    actor = Actor.copy(
                        appearance = listOf(
                            BcCharacterItem(group = "ItemHandheld", name = "CropA"),
                            BcCharacterItem(group = "ItemHandheld", name = "CropB"),
                        ),
                    ),
                    rules = ruleCatalog(
                        groups = listOf(
                            BcGroupRule(name = "ItemArms"),
                            BcGroupRule(name = "ItemHandheld"),
                        ),
                        activities = listOf(
                            BcActivityRule(
                                name = "CropStrike",
                                targetGroups = setOf("ItemArms"),
                                prerequisites = setOf("Needs-CropStrike"),
                            ),
                        ),
                        assets = listOf(
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "CropA",
                                allowActivity = setOf("CropStrike"),
                            ),
                            BcAssetRule(
                                group = "ItemHandheld",
                                name = "CropB",
                                allowActivity = setOf("CropStrike"),
                            ),
                        ),
                    ),
                ),
                candidates = listOf(activityCandidate(activityName = "CropStrike")),
                offset = 0,
                limit = 1,
            ),
        )

        assertEquals(1, page.scannedCandidateCount)
        assertEquals(2, page.result.allowed.size)
        assertEquals(null, page.nextOffset)
    }

    private fun assertDeniedWith(
        decision: BcPermissionDecision,
        code: BcPermissionReasonCode,
    ) {
        assertTrue(decision is BcPermissionDecision.Denied)
        val denied = decision as BcPermissionDecision.Denied
        assertTrue("Expected $code in ${denied.reasons}", denied.reasons.any { it.code == code })
    }

    private fun activityCandidate(
        group: String = "ItemArms",
        activityName: String,
        activityAssetGroup: String? = null,
        activityAssetName: String? = null,
    ) = BcInteractionCandidate.Activity(
        targetMemberNumber = Target.memberNumber,
        group = group,
        activityName = activityName,
        activityAssetGroup = activityAssetGroup,
        activityAssetName = activityAssetName,
    )

    private fun wearItemCandidate(
        group: String = "ItemArms",
        itemName: String = "HempRope",
        propertyJson: String? = null,
    ) = BcInteractionCandidate.WearItem(
        targetMemberNumber = Target.memberNumber,
        group = group,
        itemName = itemName,
        propertyJson = propertyJson,
    )

    private fun context(
        actor: BcCharacterSnapshot = Actor,
        target: BcCharacterSnapshot = Target,
        rules: BcRuleCatalog = ruleCatalog(),
        room: BcPermissionRoomContext = BcPermissionRoomContext(
            roomName = "TestRoom",
            memberNumbers = setOf(actor.memberNumber, target.memberNumber),
        ),
        serverAllowItem: Boolean? = null,
    ): BcPermissionContext = BcPermissionContext(
        actor = actor,
        target = target,
        room = room,
        rules = rules,
        serverAllowItem = serverAllowItem,
    )

    private fun ruleCatalog(
        groups: List<BcGroupRule> = listOf(BcGroupRule(name = "ItemArms")),
        activities: List<BcActivityRule> = listOf(
            BcActivityRule(
                name = "Spank",
                targetGroups = setOf("ItemArms"),
                selfTargetGroups = setOf("ItemArms"),
            ),
        ),
        assets: List<BcAssetRule> = listOf(
            BcAssetRule(
                group = "ItemArms",
                name = "HempRope",
            ),
        ),
        extendedItems: List<BcExtendedItemRule> = emptyList(),
    ): BcRuleCatalog = BcRuleCatalog(
        version = "test",
        groups = groups.associateBy { it.name },
        activities = activities.associateBy { it.name },
        assets = assets.associateBy { BcAssetKey(it.group, it.name) },
        extendedItems = extendedItems.associateBy { BcAssetKey(it.group, it.name) },
    )

    private companion object {
        val Actor = BcCharacterSnapshot(
            id = "actor",
            memberNumber = 1001,
            name = "Actor",
            displayName = "Actor",
            isSelf = true,
        )

        val Target = BcCharacterSnapshot(
            id = "target",
            memberNumber = 2002,
            name = "Target",
            displayName = "Target",
            isSelf = false,
        )
    }
}
