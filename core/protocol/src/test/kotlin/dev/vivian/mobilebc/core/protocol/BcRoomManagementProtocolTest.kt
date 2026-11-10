package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.room.RoomManagementResponse
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcRoomManagementProtocolTest {
    @Test
    fun createAndUpdateResponsesPreserveExactStringTokens() {
        assertEquals(
            RoomManagementResponse.Valid("ChatRoomCreated"),
            createResponse("\"ChatRoomCreated\""),
        )
        assertEquals(RoomManagementResponse.Valid("Updated"), updateResponse("\"Updated\""))

        listOf("FutureRejection", "updated", " Updated ", "", "房间不可用").forEach { token ->
            val payloadJson = createRazorWireGson().toJson(token)
            assertEquals(RoomManagementResponse.Valid(token), createResponse(payloadJson))
            assertEquals(RoomManagementResponse.Valid(token), updateResponse(payloadJson))
        }
    }

    @Test
    fun managementResponsesRejectNonStringJsonWithoutCoercion() {
        listOf("null", "true", "false", "0", "1.5", "[]", "[\"Updated\"]", "{}",
            "{\"Response\":\"Updated\"}").forEach { payloadJson ->
            assertEquals(RoomManagementResponse.Invalid(payloadJson), createResponse(payloadJson))
            assertEquals(RoomManagementResponse.Invalid(payloadJson), updateResponse(payloadJson))
        }
    }

    @Test
    fun malformedAndLenientJsonResponsesAreExplicitlyInvalid() {
        listOf(
            "", " ", "Updated", "'Updated'", "/* comment */\"Updated\"",
            "\"Updated\" false", "\"Updated\"//comment", "\"unterminated",
            "\"bad\\xescape\"", "\"line\nbreak\"",
        ).forEach { payloadJson ->
            assertEquals(RoomManagementResponse.Invalid(payloadJson), createResponse(payloadJson))
            assertEquals(RoomManagementResponse.Invalid(payloadJson), updateResponse(payloadJson))
        }
    }

    @Test
    fun validResponseJsonWhitespaceAndEscapesDecodeWithoutChangingTokenContent() {
        val payloadJson = " \n\t\"Future\\nRejection\\u0021\"\r "

        assertEquals(RoomManagementResponse.Valid("Future\nRejection!"), createResponse(payloadJson))
        assertEquals(RoomManagementResponse.Valid("Future\nRejection!"), updateResponse(payloadJson))
    }

    @Test
    fun fullRoomSyncCarriesExactRawSettingsAndSyncMetadata() {
        val payloadJson = """
            {
              "Name":"Example", "Admin":[1001], "Access":["FutureAccess"],
              "Visibility":[], "Game":"FutureGame", "Space":"FutureSpace",
              "Custom":{"ImageURL":"image","SizeMode":"FutureMode","MusicStart":1.5,
                "Future":{"Values":[null,true,"0",9007199254740993]}},
              "MapData":{"Type":"FutureMap","Tiles":[[1,2]],"Future":{"Enabled":null}},
              "Description":null, "FutureSetting":{"Nested":[{"Value":"保留"}]},
              "Character":[{"MemberNumber":1001,"Name":"Alice","FutureCharacter":true}],
              "SourceMemberNumber":42
            }
        """.trimIndent()

        val event = RazorWireParser.parse("ChatRoomSync", payloadJson) as RazorWireEvent.ChatRoomSync
        val raw = JsonParser.parseString(requireNotNull(event.rawPayloadJson)).asJsonObject

        assertEquals(payloadJson, event.rawPayloadJson)
        assertEquals("Example", event.value.name)
        assertEquals(listOf("FutureAccess"), event.value.access)
        assertEquals("9007199254740993", raw["Custom"].asJsonObject["Future"].asJsonObject["Values"].asJsonArray[3].asString)
        assertTrue(raw.has("Description"))
        assertTrue(raw["Description"].isJsonNull)
        assertFalse(raw.has("Language"))
        assertEquals(42, raw["SourceMemberNumber"].asInt)
        assertTrue(raw["Character"].asJsonArray[0].asJsonObject["FutureCharacter"].asBoolean)
    }

    @Test
    fun roomPropertiesPreserveMissingNullAndUnknownNestedSettings() {
        val payloadJson = """ {"Name":"Renamed","Custom":null,"MapData":{"Type":"Always","Objects":[{"Unknown":null}]},"FutureSetting":false} """

        val event = RazorWireParser.parse("ChatRoomSyncRoomProperties", payloadJson) as RazorWireEvent.RoomProperties
        val raw = JsonParser.parseString(requireNotNull(event.rawPayloadJson)).asJsonObject

        assertEquals(payloadJson, event.rawPayloadJson)
        assertEquals("Renamed", event.value.name)
        assertNull(event.value.custom)
        assertTrue(raw.has("Custom"))
        assertTrue(raw["Custom"].isJsonNull)
        assertFalse(raw.has("Admin"))
        assertTrue(raw["MapData"].asJsonObject["Objects"].asJsonArray[0].asJsonObject.has("Unknown"))
        assertFalse(raw["FutureSetting"].asBoolean)
    }

    @Test
    fun malformedRoomSettingsBecomeExplicitInvalidEventsWithExactRawEvidence() {
        listOf("ChatRoomSync", "ChatRoomSyncRoomProperties").forEach { eventName ->
            listOf(
                "null", "[]", "true", "\"Room\"", "{", "",
                """ {"Name":"Example","Admin":{},"FutureSetting":{"Keep":true}} """,
                """ {"Name":"Example","Custom":[],"FutureSetting":{"Keep":true}} """,
            ).forEach { payloadJson ->
                val event = RazorWireParser.parse(eventName, payloadJson)

                assertEquals(RazorWireEvent.InvalidRoomSettings(eventName, payloadJson), event)
            }
        }
    }

    @Test
    fun nullMapSyncRetainsItsExistingSeparateCharacterProtocolBoundary() {
        val event = RazorWireParser.parse("ChatRoomSyncMapData", """{"MemberNumber":1001,"MapData":null}""")

        assertTrue(event is RazorWireEvent.MapDataSync)
        assertFalse(event is RazorWireEvent.InvalidRoomSettings)
    }

    @Test
    fun mapSyncCarriesExactRawMapAndFieldPresence() {
        listOf(
            """{"MemberNumber":1001,"MapData":{"Type":"Hybrid","Position":{"X":1,"Y":2,"Unknown":[null,"保留"]}},"FutureSource":42}""",
            """{"MemberNumber":1001,"MapData":null}""",
            """{"MemberNumber":1001}""",
        ).forEach { payloadJson ->
            val event = RazorWireParser.parse("ChatRoomSyncMapData", payloadJson) as RazorWireEvent.MapDataSync

            assertEquals(payloadJson, event.rawPayloadJson)
            assertEquals(1001, event.value.memberNumber)
            val original = JsonParser.parseString(payloadJson).asJsonObject
            val retained = JsonParser.parseString(requireNotNull(event.rawPayloadJson)).asJsonObject
            assertEquals(original.has("MapData"), retained.has("MapData"))
            assertEquals(original, retained)
        }
    }

    @Test
    fun searchCanJoinReadsOnlyBooleanAndNeverInfersFromLocked() {
        val payloadJson = """[
            {"Name":"A","CanJoin":true,"Locked":true},
            {"Name":"B","CanJoin":false,"Locked":false},
            {"Name":"C","Locked":false},
            {"Name":"D","Locked":true}
        ]"""
        val rooms = (RazorWireParser.parse("ChatRoomSearchResult", payloadJson) as RazorWireEvent.ChatRoomSearchResult).value

        assertEquals(listOf(true, false, null, null), rooms.map { it.canJoin })
        assertEquals(listOf(true, false, false, true), rooms.map { it.locked })
    }

    @Test
    fun malformedSearchCanJoinValuesRemainUnknownWithoutDroppingResults() {
        listOf("null", "\"true\"", "\"false\"", "1", "0", "{}", "[]", "[true]")
            .forEach { valueJson ->
                val payloadJson = """[{"Name":"Unknown","CanJoin":$valueJson},{"Name":"Valid","CanJoin":true}]"""
                val rooms = (RazorWireParser.parse("ChatRoomSearchResult", payloadJson) as RazorWireEvent.ChatRoomSearchResult).value

                assertNull(valueJson, rooms[0].canJoin)
                assertEquals(true, rooms[1].canJoin)
                assertEquals("Unknown", rooms[0].name)
            }
    }

    @Test
    fun searchCanJoinRequiresExactWireFieldCase() {
        val payloadJson = """[
            {"Name":"A","canJoin":true,"CANJOIN":true},
            {"Name":"B","CanJoin":false,"canJoin":true,"CANJOIN":true}
        ]"""
        val rooms = (RazorWireParser.parse("ChatRoomSearchResult", payloadJson) as RazorWireEvent.ChatRoomSearchResult).value

        assertNull(rooms[0].canJoin)
        assertEquals(false, rooms[1].canJoin)
    }

    private fun createResponse(payloadJson: String): RoomManagementResponse =
        (RazorWireParser.parse("ChatRoomCreateResponse", payloadJson) as RazorWireEvent.ChatRoomCreateResponse).value

    private fun updateResponse(payloadJson: String): RoomManagementResponse =
        (RazorWireParser.parse("ChatRoomUpdateResponse", payloadJson) as RazorWireEvent.ChatRoomUpdateResponse).value
}
