package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

class BcEchoSwordPermissionTest {
    private val engine = DefaultBcInteractionPermissionEngine()
    private val rules = BcEchoActivityCatalog.mergeInto(
        BcRuleCatalog(
            version = "echo-sword-test",
            groups = listOf("ItemHandheld", "ItemNeck", "ItemMouth").associateWith { BcGroupRule(it) },
            activities = emptyMap(),
            assets = emptyMap(),
        ),
    )

    @Test
    fun weaponCombinationTypeRecordAllowsBothActualSwordActivities() {
        assertSwordActivities("武器组合", """{"TypeRecord":{"t":1,"s":1}}""", allowed = true)
    }

    @Test
    fun knifeTypeRecordAllowsBothActualSwordActivities() {
        assertSwordActivities("刀", """{"TypeRecord":{"A":1}}""", allowed = true)
    }

    @Test
    fun nonSwordTypeRecordDoesNotAllowSwordActivities() {
        assertSwordActivities("武器组合", """{"TypeRecord":{"t":1,"s":0}}""", allowed = false)
        assertSwordActivities("刀", """{"TypeRecord":{"A":0}}""", allowed = false)
    }

    @Test
    fun ordinaryTextCannotProvideWeaponTypeRecords() {
        val property = """{"Text":"TypeRecord: { t: 1, s: 1, A: 1 }"}"""
        assertSwordActivities("武器组合", property, allowed = false)
        assertSwordActivities("刀", property, allowed = false)
    }

    @Test
    fun unrelatedNestedObjectCannotProvideWeaponTypeRecords() {
        val property = """{"Custom":{"TypeRecord":{"t":1,"s":1,"A":1}}}"""
        assertSwordActivities("武器组合", property, allowed = false)
        assertSwordActivities("刀", property, allowed = false)
    }

    @Test
    fun unrelatedTopLevelFieldsCannotOverrideTheActualTypeRecord() {
        val property = """{"TypeRecord":{"t":0,"s":0,"A":0},"t":1,"s":1,"A":1}"""
        assertSwordActivities("武器组合", property, allowed = false)
        assertSwordActivities("刀", property, allowed = false)
    }

    private fun assertSwordActivities(itemName: String, propertyJson: String, allowed: Boolean) {
        listOf("剑架在脖子上" to "ItemNeck", "剑拍脸" to "ItemMouth").forEach { (activity, group) ->
            val decision = engine.evaluate(
                BcPermissionRequest(
                    context = BcPermissionContext(
                        actor = Actor.copy(appearance = listOf(BcCharacterItem("ItemHandheld", itemName, propertyJson = propertyJson))),
                        target = Target,
                        room = BcPermissionRoomContext(memberNumbers = setOf(Actor.memberNumber, Target.memberNumber)),
                        rules = rules,
                    ),
                    candidate = BcInteractionCandidate.Activity(Target.memberNumber, group, activity),
                ),
            )
            if (allowed) {
                assertTrue("Expected $itemName to allow $activity, got $decision", decision is BcPermissionDecision.Allowed)
            } else {
                assertTrue("Expected $itemName to reject $activity, got $decision", decision is BcPermissionDecision.Denied)
                assertTrue((decision as BcPermissionDecision.Denied).reasons.any { it.code == BcPermissionReasonCode.ActorPrerequisiteNotMet })
            }
        }
    }

    private companion object {
        val Actor = BcCharacterSnapshot("actor", 1001, "Actor", "Actor", true)
        val Target = BcCharacterSnapshot("target", 2002, "Target", "Target", false)
    }
}
