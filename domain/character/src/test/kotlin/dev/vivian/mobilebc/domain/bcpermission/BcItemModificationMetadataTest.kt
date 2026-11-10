package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcItemModificationMetadataTest {
    private val engine = DefaultBcInteractionPermissionEngine()

    @Test
    fun modifyingConfigurationPreservesLayerColorsAndCustomCraft() {
        val command = evaluateItemUpdate(
            BcInteractionCandidate.ModifyItem(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                itemName = currentItem.name,
                propertyJson = UPDATED_PROPERTY,
                difficulty = currentItem.difficulty,
            ),
        )

        assertEquals(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                name = currentItem.name,
                colorJson = currentItem.colorJson,
                difficulty = currentItem.difficulty,
                propertyJson = UPDATED_PROPERTY,
                craftJson = currentItem.craftJson,
                publishAction = BcItemActionMessage("ActionUse", nextAssetName = currentItem.name, nextCraftName = "Custom Rope"),
            ),
            command,
        )
    }

    @Test
    fun modifyingConfigurationUsesRequestedDifficulty() {
        val command = evaluateItemUpdate(
            BcInteractionCandidate.ModifyItem(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                itemName = currentItem.name,
                propertyJson = UPDATED_PROPERTY,
                difficulty = 0,
            ),
        )

        assertEquals(0, command.difficulty)
    }

    @Test
    fun modifyingConfigurationWithoutDifficultyPreservesCurrentDifficulty() {
        val command = evaluateItemUpdate(
            BcInteractionCandidate.ModifyItem(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                itemName = currentItem.name,
                propertyJson = UPDATED_PROPERTY,
            ),
        )
        assertEquals(currentItem.difficulty, command.difficulty)
    }

    @Test
    fun replacingItemDoesNotCarryOverPreviousItemMetadata() {
        val command = evaluateItemUpdate(
            BcInteractionCandidate.WearItem(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                itemName = "LeatherCuffs",
            ),
        )

        assertEquals(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                name = "LeatherCuffs",
                publishAction = BcItemActionMessage(
                    "ActionSwap",
                    previousAssetName = currentItem.name,
                    nextAssetName = "LeatherCuffs",
                    previousCraftName = "Custom Rope",
                ),
            ),
            command,
        )
    }

    @Test
    fun removingItemDoesNotCarryOverPreviousItemMetadata() {
        val command = evaluateItemUpdate(
            BcInteractionCandidate.RemoveItem(targetMemberNumber = TARGET, group = currentItem.group),
        )

        assertEquals(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = TARGET,
                group = currentItem.group,
                publishAction = BcItemActionMessage("ActionRemove", previousAssetName = currentItem.name, previousCraftName = "Custom Rope"),
            ),
            command,
        )
    }

    private fun evaluateItemUpdate(candidate: BcInteractionCandidate): BcCharacterItemUpdateCommand {
        val decision = engine.evaluate(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = character(1001, isSelf = true),
                    target = character(TARGET, isSelf = false).copy(appearance = listOf(currentItem)),
                    room = BcPermissionRoomContext(memberNumbers = setOf(1001, TARGET)),
                    rules = BcRuleCatalog(
                        version = "item-metadata-test",
                        groups = mapOf("ItemArms" to BcGroupRule("ItemArms")),
                        activities = emptyMap(),
                        assets = listOf("HempRope", "LeatherCuffs").associate { name ->
                            BcAssetKey("ItemArms", name) to BcAssetRule("ItemArms", name)
                        },
                    ),
                    serverAllowItem = true,
                ),
                candidate = candidate,
            ),
        )
        assertTrue("Expected an allowed item update, got $decision", decision is BcPermissionDecision.Allowed)
        return ((decision as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
    }

    private fun character(memberNumber: Int, isSelf: Boolean) = BcCharacterSnapshot(
        id = memberNumber.toString(),
        memberNumber = memberNumber,
        name = "Character $memberNumber",
        displayName = "Character $memberNumber",
        isSelf = isSelf,
    )

    private companion object {
        const val TARGET = 2002
        const val UPDATED_PROPERTY = """{"TypeRecord":{"typed":1},"Effect":["Block"]}"""
        val currentItem = BcCharacterItem(
            group = "ItemArms",
            name = "HempRope",
            colorJson = """["#663300","#112233","Default"]""",
            difficulty = 7,
            propertyJson = """{"TypeRecord":{"typed":0}}""",
            craftJson = """{"Name":"Custom Rope","Description":"Made for a friend","MemberNumber":1001,"Property":"Secure"}""",
        )
    }
}
