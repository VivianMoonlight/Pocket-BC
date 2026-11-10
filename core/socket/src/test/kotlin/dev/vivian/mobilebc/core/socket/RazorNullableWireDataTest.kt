package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import bot.inker.bc.razor.protocol.sync.PoseSyncMessage
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import org.junit.Assert.*
import org.junit.Test

class RazorNullableWireDataTest {
    private val gson = Gson()

    @Test fun characterDropsUnidentifiableItemsAndReputations() {
        val character = checkNotNull(parseCharacterFromJson(JsonParser.parseString("""
            {"MemberNumber":1,"Name":"A","ID":null,"Ownership":{"MemberNumber":2},
             "Appearance":[null,{}, {"Group":"HairFront","Name":null}, {"Group":"HairFront","Name":"Hair1"}],
             "Reputation":[null,{}, {"Type":null}, {"Type":"Dominant","Value":10}]}
        """), gson)).toBcCharacterSnapshot(false, gson)
        assertEquals("1", character.id)
        assertEquals("", character.ownership?.name)
        assertEquals(1, character.appearance.size)
        assertEquals(1, character.reputation.size)
    }

    @Test fun nullCharacterIdentityIsIgnored() {
        for (json in listOf("null", """{"MemberNumber":null,"Name":"A"}""", """{"MemberNumber":1,"Name":null}""")) {
            assertNull(parseCharacterFromJson(JsonParser.parseString(json), gson))
        }
        assertNull(parseCharacterFromJson(null, gson))
    }

    @Test fun accountListsIgnoreNullEntriesAndKeepValidRelations() {
        val account = gson.fromJson("""{"ID":"1","Name":"A","MemberNumber":1,
            "FriendList":[null,2],"WhiteList":[null,3],"Reputation":[null,{}, {"Type":"Dominant","Value":10}],
            "Ownership":{"MemberNumber":2},"Lovership":[null,{"MemberNumber":3}]}""", ServerAccountData::class.java)
        val relations = account.toBcSelfRelations()
        assertEquals(setOf(2), relations.friends)
        assertEquals(setOf(3), relations.whitelist)
        assertEquals(1, relations.loverships.size)
        assertEquals(mapOf("Dominant" to 10), relations.reputation)
        assertEquals("", relations.ownership?.name)
    }

    @Test fun poseIgnoresNullEntries() {
        val room = RoomState.fromSyncMessage(RoomSyncMessage(name = "><"), listOf(CharacterState("1", 1, "A")))
        val updated = room.applyPoseSync(gson.fromJson("""{"MemberNumber":1,"Pose":[null,"Kneel"]}""", PoseSyncMessage::class.java))
        assertEquals(listOf("Kneel"), updated.characters.single().activePose)
    }

    @Test fun roomSearchIgnoresNullAndUnnamedRooms() {
        val event = RazorWireParser.parse("ChatRoomSearchResult", """[null,{}, {"Name":null},{"Name":"><","Friends":[null,{"MemberNumber":2}]}]""") as RazorWireEvent.ChatRoomSearchResult
        assertEquals(1, event.value.size)
        assertEquals("", event.value.single().toBcRoomSummary().friends.single().name)
    }

    @Test fun loginRejectsMissingIdentity() {
        for (payload in listOf("null", "{}", """{"MemberNumber":1,"Name":null}""")) {
            val event = RazorWireParser.parse("LoginResponse", payload) as RazorWireEvent.LoginResponse
            assertTrue(event.value is bot.inker.bc.razor.protocol.auth.LoginResult.Error)
        }
    }

    @Test fun incompleteItemSyncLeavesRoomUsable() {
        val store = RazorRoomStateStore(gson, {}, {})
        store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"><","Character":[{"MemberNumber":1,"Name":"A"}]}"""))
        for (payload in listOf("{}", """{"Item":null}""", """{"Item":{"Target":1,"Group":null,"Name":"Hair1"}}""")) {
            assertTrue(store.handle(RazorWireParser.parse("ChatRoomSyncItem", payload)))
            assertNotNull(store.captureRoomProjection())
        }
    }
}
