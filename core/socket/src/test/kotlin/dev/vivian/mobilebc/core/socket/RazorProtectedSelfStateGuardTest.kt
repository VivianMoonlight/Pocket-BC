package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorProtectedSelfStateGuardTest {
    @Test
    fun staleSourceAppearanceIsRejectedBeforeCapturingAQueuedGuard() {
        listOf(true, false).forEach { ownerPoseRules ->
            val fixture = Fixture()
            val expected = fixture.expectation()
            fixture.updateSelf { item("ItemArms").addProperty("Color", "#000000") }

            assertThrows(IllegalStateException::class.java) {
                fixture.store.captureProtectedSelfUpdateGuard(expected, ownerPoseRules)
            }
        }
    }

    @Test
    fun queuedProtectedUpdatesRejectChangesToRelevantSelfState() {
        val changes = listOf<Pair<String, JsonObject.() -> Unit>>(
            "equipment" to { item("ItemArms").addProperty("Name", "LeatherCuffs") },
            "color" to { item("ItemArms").addProperty("Color", "#000000") },
            "difficulty" to { item("ItemArms").addProperty("Difficulty", 99) },
            "craft" to { item("ItemArms").getAsJsonObject("Craft").addProperty("Name", "New craft") },
            "property" to { item("ItemArms").getAsJsonObject("Property").addProperty("Type", "Changed") },
            "pose" to { add("ActivePose", JsonParser.parseString("[\"Kneel\"]")) },
            "legacy owner" to { addProperty("Owner", "Changed owner") },
            "ownership" to { getAsJsonObject("Ownership").addProperty("Stage", 2) },
            "asset family" to { addProperty("AssetFamily", "OtherFamily") },
        )
        changes.forEach { (name, change) ->
            listOf(true, false).forEach { ownerPoseRules ->
                val fixture = Fixture()
                val guard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(), ownerPoseRules)
                fixture.updateSelf(change)
                var emitted = false

                assertThrows(name, IllegalStateException::class.java) { guard { emitted = true } }
                assertFalse(name, emitted)
            }
        }
    }

    @Test
    fun ownerArrivalAndDepartureInvalidateQueuedProtectedUpdates() {
        listOf(false, true).forEach { ownerInitiallyPresent ->
            val fixture = Fixture(ownerInitiallyPresent)
            val guard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(), true)
            if (ownerInitiallyPresent) {
                fixture.handle("ChatRoomSyncMemberLeave", """{"SourceMemberNumber":20}""")
            } else {
                fixture.handle("ChatRoomSyncMemberJoin", """{"SourceMemberNumber":20,"Character":{"ID":"20","MemberNumber":20,"Name":"Owner"}}""")
            }
            var emitted = false

            assertThrows(IllegalStateException::class.java) { guard { emitted = true } }
            assertFalse(emitted)
        }
    }

    @Test
    fun unrelatedMembersCanChangeNamesAndEquipmentWithoutCancellingProtectedUpdates() {
        val fixture = Fixture()
        val poseGuard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(), true)
        val expressionGuard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(setOf("Eyes")), false)
        fixture.handle("ChatRoomSyncCharacter", """{"SourceMemberNumber":30,"Character":{"ID":"30","MemberNumber":30,"Name":"Renamed","Appearance":[{"Group":"ItemArms","Name":"LeatherCuffs","Color":"#000000"}]}}""")
        var emissions = 0

        poseGuard { emissions++ }
        expressionGuard { emissions++ }

        assertEquals(2, emissions)
    }

    @Test
    fun editedExpressionEchoesAreAllowedButOtherItemMetadataStillInvalidatesTheGuard() {
        val changes = listOf<Pair<String, JsonObject.() -> Unit>>(
            "color" to { item("Eyes").addProperty("Color", "#000000") },
            "property" to { item("Eyes").getAsJsonObject("Property").addProperty("Type", "Changed") },
            "craft" to { item("Eyes").add("Craft", JsonParser.parseString("{\"Name\":\"New\"}")) },
            "unedited expression" to { item("Eyebrows").getAsJsonObject("Property").addProperty("Expression", "Angry") },
        )
        changes.forEach { (name, change) ->
            val fixture = Fixture()
            val guard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(setOf("Eyes", "Eyes2")), false)
            fixture.handle("ChatRoomSyncExpression", """{"MemberNumber":10,"Group":"Eyes","Name":"Closed"}""")
            fixture.handle("ChatRoomSyncExpression", """{"MemberNumber":10,"Group":"Eyes2","Name":"Closed"}""")
            var emissions = 0
            guard { emissions++ }
            assertEquals(1, emissions)
            fixture.updateSelf(change)

            assertThrows(name, IllegalStateException::class.java) { guard { emissions++ } }
            assertEquals(1, emissions)
        }
    }

    @Test
    fun hiddenRuleInvalidationImmediatelyBlocksQueuedUpdatesAndNewOwnerRestrictedPose() {
        val fixture = Fixture()
        val expected = fixture.expectation()
        val poseGuard = fixture.store.captureProtectedSelfUpdateGuard(expected, true)
        val expressionGuard = fixture.store.captureProtectedSelfUpdateGuard(expected, false)
        val activityGuard = fixture.store.captureRoomActionGuard()
        val eventCount = fixture.events.size

        assertTrue(fixture.store.invalidateSelfRuleLog(fixture.store.captureReceiveContext()))
        assertEquals(eventCount, fixture.events.size)
        var emissions = 0
        assertThrows(IllegalStateException::class.java) { poseGuard { emissions++ } }
        assertThrows(IllegalStateException::class.java) { expressionGuard { emissions++ } }
        assertEquals(0, emissions)
        assertThrows(IllegalStateException::class.java) { fixture.store.captureProtectedSelfUpdateGuard(expected, true) }
        val freshExpressionGuard = fixture.store.captureProtectedSelfUpdateGuard(expected, false)
        freshExpressionGuard { emissions++ }
        activityGuard { emissions++ }

        assertEquals(2, emissions)
    }

    @Test
    fun resetSessionClearsRuleInvalidationAndRejectsOldRoomContextsAfterRelogin() {
        val fixture = Fixture()
        val oldContext = fixture.store.captureReceiveContext()
        val oldGuard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(), true)
        assertTrue(fixture.store.invalidateSelfRuleLog(oldContext))
        fixture.store.resetSession()
        fixture.join()

        assertFalse(fixture.store.invalidateSelfRuleLog(oldContext))
        var emissions = 0
        assertThrows(Exception::class.java) { oldGuard { emissions++ } }
        assertEquals(0, emissions)
        val freshGuard = fixture.store.captureProtectedSelfUpdateGuard(fixture.expectation(), true)
        freshGuard { emissions++ }

        assertEquals(1, emissions)
    }

    private class Fixture(private val ownerPresent: Boolean = true) {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = RazorRoomStateStore(Gson(), events::add, queueAccountAppearance = {})
        private var self = JsonParser.parseString(SELF_CHARACTER).asJsonObject

        init { join() }

        fun join() {
            store.selfMemberNumber = 10
            val owner = if (ownerPresent) ",{\"ID\":\"20\",\"MemberNumber\":20,\"Name\":\"Owner\"}" else ""
            handle("ChatRoomSync", "{\"Name\":\"A\",\"Character\":[$self$owner,{\"ID\":\"30\",\"MemberNumber\":30,\"Name\":\"Other\"}]}")
        }

        fun handle(event: String, json: String) { assertTrue(store.handle(RazorWireParser.parse(event, json))) }

        fun updateSelf(change: JsonObject.() -> Unit) {
            self = self.deepCopy().apply(change)
            handle("ChatRoomSyncCharacter", "{\"SourceMemberNumber\":10,\"Character\":$self}")
        }

        fun expectation(editedGroups: Set<String> = emptySet()): BcSelfStateEmitExpectation {
            val characters: List<BcCharacterSnapshot> = when (val event = events.last()) {
                is BcSessionGatewayEvent.RoomJoined -> event.characters
                is BcSessionGatewayEvent.RoomUpdated -> event.characters
                else -> error("Expected a room snapshot")
            }
            val self = characters.single { it.isSelf }
            return BcSelfStateEmitExpectation(self, characters.any { it.memberNumber == self.ownership?.memberNumber }, editedGroups)
        }
    }

    private companion object {
        fun JsonObject.item(group: String): JsonObject = getAsJsonArray("Appearance")
            .single { it.asJsonObject.get("Group").asString == group }.asJsonObject

        const val SELF_CHARACTER = """{"ID":"10","MemberNumber":10,"Name":"Self","AssetFamily":"Female3DCG","Owner":"Owner","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":1,"Start":1000,"Notes":"Rules"},"ActivePose":[],"Appearance":[{"Group":"Eyes","Name":"Eyes","Color":"Default","Property":{"Expression":"Open"}},{"Group":"Eyes2","Name":"Eyes2","Property":{"Expression":"Open"}},{"Group":"Eyebrows","Name":"Eyebrows","Property":{"Expression":"Neutral"}},{"Group":"ItemArms","Name":"HempRope","Color":"#FFFFFF","Difficulty":1,"Property":{"Type":"Normal"},"Craft":{"Name":"Original craft"}}]}"""
    }
}
