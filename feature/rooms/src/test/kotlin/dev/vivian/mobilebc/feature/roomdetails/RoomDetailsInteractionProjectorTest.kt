package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionActivity
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionGroup
import dev.vivian.mobilebc.core.model.BcInteractionItem
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcInteractionPermissionEngine
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.character.BcCharacterRosterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsInteractionProjectorTest {
    @Test
    fun activityCacheTracksPermissionInputsAndCatalogReplacement() {
        val target = character(emptyList())
        val actor = target.copy(id = "self", memberNumber = 1, isSelf = true)
        var roster = BcCharacterRosterState.InRoom(
            roomName = dev.vivian.mobilebc.core.model.BcRoomName("Test"),
            characters = listOf(actor, target),
        )
        val projector = RoomDetailsInteractionProjector(DefaultBcInteractionPermissionEngine()) { roster }
        var ruleCatalog = rules().copy(
            activities = mapOf("Kiss" to BcActivityRule("Kiss", targetGroups = setOf("ItemMouth"))),
        )
        var displayCatalog = catalog()
        fun rows(targetSnapshot: BcCharacterSnapshot = target, allowedOnly: Boolean = false) =
            projector.activityUiStates("ItemMouth", targetSnapshot, ruleCatalog, displayCatalog, allowedOnly)

        val initial = rows()
        assertTrue(initial.single().enabled)
        org.junit.Assert.assertSame(initial, rows())
        roster = roster.copy(characters = listOf(actor.copy(displayName = "Renamed"), target))
        val actorUpdated = rows()
        org.junit.Assert.assertNotSame(initial, actorUpdated)
        org.junit.Assert.assertNotSame(actorUpdated, rows(target.copy(displayName = "New target")))

        roster = roster.copy(characters = listOf(actor))
        assertFalse(rows().single().enabled)
        assertTrue(rows(allowedOnly = true).isEmpty())
        roster = roster.copy(characters = listOf(actor, target))
        assertTrue(rows().single().enabled)

        displayCatalog = displayCatalog.copy(
            activities = listOf(BcInteractionActivity("ItemMouth", "Kiss", "Updated label")),
        )
        assertEquals("Updated label", rows().single().displayName)
        ruleCatalog = ruleCatalog.copy(activities = emptyMap())
        assertFalse(rows().single().enabled)
    }

    private val projector = RoomDetailsInteractionProjector(
        permissionEngine = DefaultBcInteractionPermissionEngine(),
        rosterState = { BcCharacterRosterState.NotInRoom },
    )

    @Test
    fun mirroredItemPartUsesSourceActionsWithTheSelectedTargetGroup() {
        val target = character(emptyList())
        val actor = target.copy(id = "self", memberNumber = 1, isSelf = true)
        val roster = BcCharacterRosterState.InRoom(
            roomName = dev.vivian.mobilebc.core.model.BcRoomName("Test"),
            characters = listOf(actor, target),
        )
        val mappedRules = rules().let { base ->
            base.copy(
                groups = base.groups + ("ItemArms" to base.groups.getValue("ItemArms").copy(mirrorActivitiesFrom = "ItemMouth")),
                activities = mapOf(
                    "Kiss" to BcActivityRule("Kiss", targetGroups = setOf("ItemMouth")),
                    "Touch" to BcActivityRule("Touch", targetGroups = setOf("ItemArms")),
                ),
            )
        }
        val mappedCatalog = catalog().copy(activities = listOf(
            BcInteractionActivity("ItemMouth", "Kiss", "Kiss"),
            BcInteractionActivity("ItemArms", "Kiss", "Arm kiss"),
            BcInteractionActivity("ItemArms", "Touch", "Touch"),
        ))
        val mappedProjector = RoomDetailsInteractionProjector(DefaultBcInteractionPermissionEngine()) { roster }
        val groups = mappedProjector.groupStates(InteractionMode.ActionPicker, target, mappedCatalog, mappedRules)
        assertTrue(groups.first { it.name == "ItemArms" }.hasActivities)
        val rows = mappedProjector.activityUiStates("ItemArms", target, mappedRules, mappedCatalog, allowedOnly = true)
        assertEquals(setOf("Kiss", "Touch"), rows.map { it.name }.toSet())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.group == "ItemArms" && it.enabled })
        assertEquals("Arm kiss", rows.first { it.name == "Kiss" }.displayName)
        val sourceRows = mappedProjector.activityUiStates("ItemMouth", target, mappedRules, mappedCatalog, allowedOnly = true)
        assertEquals(setOf("Kiss", "Touch"), sourceRows.map { it.name }.toSet())
        assertTrue(sourceRows.all { it.group == "ItemMouth" && it.enabled })
    }

    @Test
    fun groupProjectionUsesTheModeSpecificSourceSet() {
        val target = character(
            appearance = listOf(
                BcCharacterItem(group = "ItemMouth", name = "BallGag"),
                BcCharacterItem(group = "Cloth", name = "Dress"),
            ),
        )
        val actionGroups = projector.groupStates(
            mode = InteractionMode.ActionPicker,
            target = target,
            snapshot = catalog(),
            rules = rules(),
        )
        assertTrue(actionGroups.map { it.name }.containsAll(listOf("ItemMouth", "ItemArms")))
        assertTrue(actionGroups.first { it.name == "ItemMouth" }.hasActivities)
        assertFalse(actionGroups.first { it.name == "ItemArms" }.hasActivities)

        val itemGroups = projector.groupStates(
            mode = InteractionMode.ItemPicker,
            target = target,
            snapshot = catalog(),
            rules = rules(),
        )
        assertTrue(itemGroups.map { it.name }.containsAll(listOf("ItemMouth", "ItemArms")))
        assertTrue(itemGroups.none { it.name == "Cloth" })
        assertEquals(itemGroups.map { it.name }, actionGroups.filter { it.hasItems || it.hasCurrentItem }.map { it.name })

        val clothingGroups = projector.groupStates(
            mode = InteractionMode.ClothingPicker,
            target = target,
            snapshot = catalog(),
            rules = rules(),
        )
        assertEquals(listOf("Cloth"), clothingGroups.map { it.name })

        val fullGroups = projector.groupStates(
            mode = InteractionMode.FullSheet,
            target = target,
            snapshot = catalog(),
            rules = rules(),
        )
        assertTrue(fullGroups.map { it.name }.containsAll(listOf("ItemMouth", "ItemArms", "Cloth")))
    }

    @Test
    fun onlyTheFullSheetFallsBackToTheFirstGroup() {
        val groups = listOf(
            InteractionGroupUiState("ItemMouth", "Mouth", true, true, false),
            InteractionGroupUiState("ItemArms", "Arms", false, true, false),
        )

        assertEquals(groups.first(), projector.selectGroup(groups, selectedGroupName = null, InteractionMode.FullSheet))
        assertNull(projector.selectGroup(groups, selectedGroupName = null, InteractionMode.ActionPicker))
        assertNull(projector.selectGroup(groups, selectedGroupName = null, InteractionMode.ItemPicker))
        assertEquals(groups[1], projector.selectGroup(groups, selectedGroupName = "itemarms", InteractionMode.ItemPicker))
    }

    @Test
    fun currentItemConfigurationRequiresSupportedPropertyOptions() {
        val target = character(appearance = listOf(BcCharacterItem("ItemMouth", "BallGag")))
        val extended = BcExtendedItemRule(
            group = "ItemMouth",
            name = "BallGag",
            archetype = BcExtendedItemArchetype.Typed,
            recordKey = "typed",
            typedOptions = listOf(BcExtendedItemOptionRule("Tight", 1, propertyJson = """{"TypeRecord":{"typed":1}}""")),
        )
        fun projected(rule: BcExtendedItemRule?): InteractionCurrentItemUiState = checkNotNull(
            projector.currentItemUiState(
                target = target,
                group = "ItemMouth",
                snapshot = catalog(),
                rules = rules().copy(extendedItems = rule?.let { mapOf(BcAssetKey(it.group, it.name) to it) }.orEmpty()),
            ),
        )

        assertTrue(projected(extended).configurable)
        assertTrue(projected(extended.copy(supportLevel = BcExtendedItemSupportLevel.Partial)).configurable)
        assertFalse(projected(extended.copy(supportLevel = BcExtendedItemSupportLevel.Unsupported)).configurable)
        assertFalse(projected(extended.copy(typedOptions = emptyList())).configurable)
        assertFalse(projected(null).configurable)
    }

    @Test
    fun unavailableRowsExposeStructuredUserMessages() {
        val target = character(appearance = emptyList())
        val loadingRows = projector.activityUiStates(
            group = "ItemMouth",
            target = target,
            rules = null,
            snapshot = catalog(),
            allowedOnly = false,
        )

        assertEquals(
            InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing),
            loadingRows.single().permissionMessage,
        )

        val actor = target.copy(id = "self", memberNumber = 1, isSelf = true)
        val activeProjector = RoomDetailsInteractionProjector(
            permissionEngine = DefaultBcInteractionPermissionEngine(),
            rosterState = {
                BcCharacterRosterState.InRoom(
                    roomName = dev.vivian.mobilebc.core.model.BcRoomName("Test"),
                    characters = listOf(actor, target),
                )
            },
        )
        val disabledRules = rules().copy(
            assets = mapOf(
                BcAssetKey("ItemArms", "Rope") to BcAssetRule(
                    group = "ItemArms",
                    name = "Rope",
                    enable = false,
                ),
            ),
        )
        val item = activeProjector.itemUiStates(
            group = "ItemArms",
            target = target,
            rules = disabledRules,
            snapshot = catalog(),
        ).single()

        assertEquals(
            InteractionUserMessage.Permission(BcPermissionReasonCode.AssetDisabled),
            item.permissionMessage,
        )
    }

    @Test
    fun echoActionsUseNativePermissionProjectionAndKeepTargetModes() {
        val actor = BcCharacterSnapshot(
            id = "self",
            memberNumber = 1,
            name = "Self",
            displayName = "Self",
            isSelf = true,
            appearance = listOf(BcCharacterItem("Wings", "AngelWings")),
        )
        val target = character(appearance = emptyList())
        val roster = BcCharacterRosterState.InRoom(
            roomName = dev.vivian.mobilebc.core.model.BcRoomName("Test"),
            characters = listOf(actor, target),
        )
        val echoProjector = RoomDetailsInteractionProjector(
            permissionEngine = DefaultBcInteractionPermissionEngine(),
            rosterState = { roster },
        )
        val echoRules = BcEchoActivityCatalog.mergeInto(BcRuleCatalog.Empty)

        val otherActions = echoProjector.activityUiStates(
            group = "ItemArms",
            target = target,
            rules = echoRules,
            snapshot = null,
            allowedOnly = true,
        )
        val selfActions = echoProjector.activityUiStates(
            group = "ItemHead",
            target = actor,
            rules = echoRules,
            snapshot = null,
            allowedOnly = true,
        )

        assertTrue(otherActions.any { it.name == "翅膀轻拍" })
        assertTrue(selfActions.any { it.name == "歪头" })
        assertEquals(
            setOf(InteractionActivityEffectUiState.Expression),
            selfActions.first { it.name == "闭上眼睛" }.effects,
        )
        assertTrue(otherActions.none { it.name == "歪头" })
    }

    @Test
    fun activityCatalogIsNotSilentlyTruncatedAfterEightyRows() {
        val target = character(appearance = emptyList())
        val actor = target.copy(id = "self", memberNumber = 1, isSelf = true)
        val activeProjector = RoomDetailsInteractionProjector(
            permissionEngine = DefaultBcInteractionPermissionEngine(),
            rosterState = {
                BcCharacterRosterState.InRoom(
                    roomName = dev.vivian.mobilebc.core.model.BcRoomName("Test"),
                    characters = listOf(actor, target),
                )
            },
        )
        val activities = (1..100).map { index -> BcInteractionActivity("ItemMouth", "Action$index", "Action $index") }
        val rows = activeProjector.activityUiStates(
            group = "ItemMouth",
            target = target,
            rules = rules().copy(
                activities = activities.associate { it.name to BcActivityRule(it.name, targetGroups = setOf("ItemMouth")) },
            ),
            snapshot = catalog().copy(activities = activities),
            allowedOnly = false,
        )

        assertEquals(activities.map { it.name }.toSet(), rows.map { it.name }.toSet())
    }

    private fun character(
        appearance: List<BcCharacterItem>,
    ): BcCharacterSnapshot = BcCharacterSnapshot(
        id = "target",
        memberNumber = 2,
        name = "Target",
        displayName = "Target",
        isSelf = false,
        appearance = appearance,
    )

    private fun catalog(): BcInteractionCatalogSnapshot = BcInteractionCatalogSnapshot(
        version = "R129",
        generatedAtEpochMillis = 0L,
        groups = listOf(
            BcInteractionGroup("ItemMouth", "Mouth", hasActivities = true, hasItems = true),
            BcInteractionGroup("ItemArms", "Arms", hasActivities = false, hasItems = true),
            BcInteractionGroup("Cloth", "Cloth", hasActivities = true, hasItems = true),
        ),
        activities = listOf(
            BcInteractionActivity("ItemMouth", "Kiss", "Kiss"),
            BcInteractionActivity("Cloth", "Touch", "Touch"),
        ),
        items = listOf(
            BcInteractionItem("ItemMouth", "BallGag", "Ball gag"),
            BcInteractionItem("ItemArms", "Rope", "Rope"),
            BcInteractionItem("Cloth", "Dress", "Dress"),
        ),
        sourcePaths = emptyList(),
    )

    private fun rules(): BcRuleCatalog = BcRuleCatalog(
        version = "R129",
        groups = listOf(
            BcGroupRule(name = "ItemMouth", category = "Item", isItem = true),
            BcGroupRule(name = "ItemArms", category = "Item", isItem = true),
            BcGroupRule(name = "Cloth", category = "Appearance", clothing = true, isItem = false),
        ).associateBy { it.name },
        activities = emptyMap(),
        assets = listOf(
            BcAssetRule("ItemMouth", "BallGag"),
            BcAssetRule("ItemArms", "Rope"),
            BcAssetRule("Cloth", "Dress"),
        ).associateBy { BcAssetKey(it.group, it.name) },
    )
}
