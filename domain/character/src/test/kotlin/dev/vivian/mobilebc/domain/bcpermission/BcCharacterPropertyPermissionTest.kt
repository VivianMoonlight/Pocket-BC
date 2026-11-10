package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterItemSource
import dev.vivian.mobilebc.domain.bccharacterstate.DefaultBcCharacterStateResolver
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcCharacterPropertyPermissionTest {
    private val engine = DefaultBcInteractionPermissionEngine()

    @Test
    fun ordinaryPropertyTextDoesNotCreateCharacterEffectsOrAttributes() {
        val state = DefaultBcCharacterStateResolver().resolve(
            character = Actor.copy(
                appearance = listOf(
                    item(
                        group = "ItemMouth",
                        propertyJson = """{"Text":"Gag MouthOpen ReceiveShock TriggerShock CanAttachMittens"}""",
                    ),
                ),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Actor,
        )

        assertFalse(state.isGagged())
        assertTrue(state.canUseMouthOrTongue())
        assertFalse(state.isMouthOpen())
        assertFalse(state.hasAttributeLike("CanAttachMittens"))
        assertFalse(state.wornItems.single().canReceiveShock())
        assertFalse(state.wornItems.single().canTriggerShock("ShockItem", "ItemNeck"))
    }

    @Test
    fun parsedPropertyEffectsAndAttributesStillProvideCharacterState() {
        val state = DefaultBcCharacterStateResolver().resolve(
            character = Actor.copy(
                appearance = listOf(
                    item(
                        group = "ItemMouth",
                        propertyJson = """{"Effect":["GagLight","MouthOpen","ReceiveShock","TriggerShock"],"Attribute":["CanAttachMittens"]}""",
                    ),
                ),
            ),
            rules = BcRuleCatalog.Empty,
            source = BcCharacterItemSource.Actor,
        )

        assertTrue(state.isGagged())
        assertFalse(state.canUseMouthOrTongue())
        assertTrue(state.isMouthOpen())
        assertTrue(state.hasAttributeLike("CanAttachMittens"))
        assertTrue(state.wornItems.single().canReceiveShock())
        assertTrue(state.wornItems.single().canTriggerShock("ShockItem", "ItemNeck"))
    }

    @Test
    fun ordinaryGagTextDoesNotBlockMouthActivitiesOrSatisfyGagPrerequisite() {
        val appearance = listOf(item("ItemMouth", """{"Text":"Gag"}"""))

        assertTrue(
            evaluate(prerequisite = "UseMouth", actorAppearance = appearance) is BcPermissionDecision.Allowed,
        )
        assertDeniedWith(
            evaluate(prerequisite = "IsGagged", actorAppearance = appearance),
            BcPermissionReasonCode.ActorNotGagged,
        )
    }

    @Test
    fun ordinaryMouthOpenTextDoesNotSatisfyTargetMouthOpen() {
        assertDeniedWith(
            evaluate(
                prerequisite = "TargetMouthOpen",
                group = "ItemMouth",
                targetAppearance = listOf(item("ItemMouth", """{"Text":"MouthOpen"}""")),
            ),
            BcPermissionReasonCode.TargetMouthNotOpen,
        )
        assertTrue(
            evaluate(
                prerequisite = "TargetMouthOpen",
                group = "ItemMouth",
                targetAppearance = listOf(item("ItemMouth", """{"Effect":["MouthOpen"]}""")),
            ) is BcPermissionDecision.Allowed,
        )
    }

    @Test
    fun ordinaryReceiveShockTextDoesNotCreateAShockReceiver() {
        assertDeniedWith(
            evaluate(
                prerequisite = "ShockItem",
                group = "ItemNeck",
                actorAppearance = listOf(item("ItemHandheld", """{"Effect":["TriggerShock"]}""")),
                targetAppearance = listOf(item("ItemNeck", """{"Text":"ReceiveShock"}""")),
            ),
            BcPermissionReasonCode.MissingShockReceiverItem,
        )
    }

    @Test
    fun ordinaryTriggerShockTextDoesNotCreateAShockTrigger() {
        assertDeniedWith(
            evaluate(
                prerequisite = "ShockItem",
                group = "ItemNeck",
                actorAppearance = listOf(item("ItemHandheld", """{"Text":"TriggerShock"}""")),
                targetAppearance = listOf(item("ItemNeck", """{"Effect":["ReceiveShock"]}""")),
            ),
            BcPermissionReasonCode.MissingShockTriggerItem,
        )
    }

    @Test
    fun parsedPropertyEffectsStillAllowShockActivity() {
        assertTrue(
            evaluate(
                prerequisite = "ShockItem",
                group = "ItemNeck",
                actorAppearance = listOf(item("ItemHandheld", """{"Effect":["TriggerShock"]}""")),
                targetAppearance = listOf(item("ItemNeck", """{"Effect":["ReceiveShock"]}""")),
            ) is BcPermissionDecision.Allowed,
        )
    }

    @Test
    fun attributePrerequisiteUsesParsedAttributesInsteadOfOrdinaryText() {
        assertDeniedWith(
            evaluate(
                prerequisite = "CanAttachMittens",
                targetAppearance = listOf(item("ItemArms", """{"Text":"CanAttachMittens"}""")),
            ),
            BcPermissionReasonCode.TargetPrerequisiteNotMet,
        )
        assertTrue(
            evaluate(
                prerequisite = "CanAttachMittens",
                targetAppearance = listOf(item("ItemArms", """{"Attribute":["CanAttachMittens"]}""")),
            ) is BcPermissionDecision.Allowed,
        )
    }

    private fun evaluate(
        prerequisite: String,
        group: String = "ItemArms",
        actorAppearance: List<BcCharacterItem> = emptyList(),
        targetAppearance: List<BcCharacterItem> = emptyList(),
    ): BcPermissionDecision {
        val activity = BcActivityRule(
            name = "TestActivity",
            targetGroups = setOf(group),
            prerequisites = setOf(prerequisite),
        )
        return engine.evaluate(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = Actor.copy(appearance = actorAppearance),
                    target = Target.copy(appearance = targetAppearance),
                    room = BcPermissionRoomContext(memberNumbers = setOf(Actor.memberNumber, Target.memberNumber)),
                    rules = BcRuleCatalog(
                        version = "property-permission-test",
                        groups = mapOf(group to BcGroupRule(name = group)),
                        activities = mapOf(activity.name to activity),
                        assets = emptyMap(),
                    ),
                ),
                candidate = BcInteractionCandidate.Activity(
                    targetMemberNumber = Target.memberNumber,
                    group = group,
                    activityName = activity.name,
                ),
            ),
        )
    }

    private fun item(group: String, propertyJson: String) = BcCharacterItem(
        group = group,
        name = "NeutralItem",
        propertyJson = propertyJson,
    )

    private fun assertDeniedWith(decision: BcPermissionDecision, code: BcPermissionReasonCode) {
        assertTrue("Expected $code, got $decision", decision is BcPermissionDecision.Denied)
        assertTrue((decision as BcPermissionDecision.Denied).reasons.any { it.code == code })
    }

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
