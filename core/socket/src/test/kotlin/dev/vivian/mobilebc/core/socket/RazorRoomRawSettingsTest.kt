package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRoomRawSettingsTest {
    @Test
    fun basicUpdateRetainsLatestUneditedFieldsWithoutTypedEnumOrNestedDataLoss() {
        val baseline = room()
        val latest = room().apply {
            addProperty("Name", "Latest name")
            add("Admin", json("[1001,3003]"))
            add("Whitelist", json("[4004]"))
            add("Ban", json("[5005]"))
            addProperty("Game", "FutureGame")
            addProperty("Language", "FutureLanguage")
            add("BlockCategory", json("[\"FutureCategory\"]"))
            add("Visibility", json("[\"Admin\",\"FutureVisibility\"]"))
            add("Access", json("[\"FutureAccess\"]"))
            add("MapData", json("""{"Type":"FutureMap","Tiles":"latest tiles","Objects":"latest objects","Effects":"encoded","Future":{"a":[1,null,"two"]}}"""))
            add("Custom", json("""{"ImageURL":"https://example.com/room.png","SizeMode":2,"ImageFilter":"#123456","MusicURL":"https://example.com/music.mp3","MusicStart":3.25,"Future":{"enabled":true}}"""))
        }

        val updated = capture(latest).buildUpdate(baseline, mapOf("Description" to JsonPrimitive("Edited description")), 1001)

        assertEquals("Edited description", updated.get("Description").asString)
        updateFields.filterNot { it == "Description" }.forEach { assertEquals(it, latest.get(it), updated.get(it)) }
        assertEquals(updateFields, updated.keySet())
        assertFalse(updated.has("Space"))
        assertFalse(updated.has("Character"))
        assertFalse(updated.has("SourceMemberNumber"))
        assertFalse(updated.has("Locked"))
        listOf("Private", "CanJoin", "MemberCount", "MemberLimit", "Creator", "CreatorMemberNumber", "Friends", "MapType")
            .forEach { assertFalse(it, updated.has(it)) }
    }

    @Test
    fun evidenceAndPreparedPayloadCannotMutateStoredAuthority() {
        val raw = capture(room())
        val leakedEvidence = raw.evidenceCopy().apply {
            addProperty("Description", "changed")
            getAsJsonArray("Admin").remove(0)
            getAsJsonObject("MapData").addProperty("Type", "Always")
            getAsJsonObject("Custom").addProperty("Future", "changed")
        }
        val baseline = room()
        val edited = JsonObject().apply { addProperty("Nested", "caller") }

        val update = raw.buildUpdate(baseline, emptyMap(), 1001)
        update.addProperty("Description", "changed again")
        update.getAsJsonArray("Admin").remove(0)
        update.getAsJsonObject("MapData").add("Future", edited)
        leakedEvidence.addProperty("Name", "changed")
        edited.addProperty("Nested", "changed")

        assertEquals(room(), raw.evidenceCopy())
        assertEquals(room().get("Admin"), raw.buildUpdate(baseline, emptyMap(), 1001).get("Admin"))
    }

    @Test
    fun conflictDetectionChecksOnlyExplicitlyEditedFieldsAgainstTheirOriginalValues() {
        val baseline = room()
        val latest = room().apply {
            addProperty("Name", "Renamed by another admin")
            addProperty("Description", "Changed by another admin")
        }

        assertFailure(
            RazorRoomRawSettingsFailure.EditedFieldConflict,
            setOf("Description"),
        ) { capture(latest).buildUpdate(baseline, mapOf("Description" to JsonPrimitive("My draft")), 1001) }

        val updated = capture(latest).buildUpdate(baseline, mapOf("Limit" to JsonPrimitive(12)), 1001)
        assertEquals("Renamed by another admin", updated.get("Name").asString)
        assertEquals("Changed by another admin", updated.get("Description").asString)
        assertEquals(12, updated.get("Limit").asInt)
    }

    @Test
    fun missingBaselineIsRejectedInsteadOfTreatingAbsentAsNull() {
        val baseline = room().apply { remove("Description") }
        assertFailure(
            RazorRoomRawSettingsFailure.MissingBaselineFields,
            setOf("Description"),
        ) { capture(room()).buildUpdate(baseline, mapOf("Description" to JsonPrimitive("My draft")), 1001) }
    }

    @Test
    fun optionalSettingsKeepAbsenceExplicitNullAndObjectsDistinct() {
        val absent = room().apply {
            remove("MapData")
            remove("Custom")
        }
        val absentUpdate = capture(absent).buildUpdate(absent, emptyMap(), 1001)
        assertFalse(absentUpdate.has("MapData"))
        assertFalse(absentUpdate.has("Custom"))

        val explicitNull = room().apply {
            add("MapData", JsonNull.INSTANCE)
            add("Custom", JsonNull.INSTANCE)
        }
        val nullUpdate = capture(explicitNull).buildUpdate(explicitNull, emptyMap(), 1001)
        assertTrue(nullUpdate.has("MapData"))
        assertTrue(nullUpdate.get("MapData").isJsonNull)
        assertTrue(nullUpdate.has("Custom"))
        assertTrue(nullUpdate.get("Custom").isJsonNull)

        val objects = room()
        val objectUpdate = capture(objects).buildUpdate(objects, emptyMap(), 1001)
        assertEquals(objects.get("MapData"), objectUpdate.get("MapData"))
        assertEquals(objects.get("Custom"), objectUpdate.get("Custom"))
    }

    @Test
    fun everyRequiredRawSettingMustBePresentWithoutDefaultFilling() {
        (updateFields - setOf("MapData", "Custom")).forEach { field ->
            val incomplete = room().apply { remove(field) }
            assertFailure(RazorRoomRawSettingsFailure.MissingRequiredFields, setOf(field)) {
                capture(incomplete).buildUpdate(room(), emptyMap(), 1001)
            }
        }
    }

    @Test
    fun malformedRawShapesAreRejectedWithoutNumberOrStringCoercion() {
        val invalidValues = mapOf(
            "Name" to "12",
            "Language" to "null",
            "Description" to "false",
            "Background" to "[]",
            "Limit" to "10.5",
            "Admin" to "[1001,\"2002\"]",
            "Whitelist" to "[0]",
            "Ban" to "[2147483648]",
            "BlockCategory" to "[true]",
            "Game" to "{}",
            "Visibility" to "[1]",
            "Access" to "null",
            "MapData" to "{\"Type\":1}",
            "Custom" to "[]",
        )
        invalidValues.forEach { (field, value) ->
            val malformed = room().apply { add(field, json(value)) }
            assertFailure(RazorRoomRawSettingsFailure.InvalidFieldShape, setOf(field)) {
                capture(malformed).buildUpdate(room(), emptyMap(), 1001)
            }
        }
    }

    @Test
    fun currentAdminMustContainTheExactPositiveMemberNumber() {
        val revoked = room().apply { add("Admin", json("[2002]")) }
        assertFailure(RazorRoomRawSettingsFailure.NotAdmin, setOf("Admin")) {
            capture(revoked).buildUpdate(room(), emptyMap(), 1001)
        }
        assertFailure(RazorRoomRawSettingsFailure.NotAdmin, setOf("Admin")) {
            capture(room()).buildUpdate(room(), emptyMap(), 0)
        }
        listOf("[1001.5]", "[\"1001\"]", "[true]", "[1001.0000000000000000000001]", "[-1001]").forEach { admin ->
            val malformed = room().apply { add("Admin", json(admin)) }
            assertFailure(RazorRoomRawSettingsFailure.InvalidFieldShape, setOf("Admin")) {
                capture(malformed).buildUpdate(room(), emptyMap(), 1001)
            }
        }
        val integralNumber = room().apply { add("Admin", json("[1.001e3]")) }
        assertEquals(integralNumber.get("Admin"), capture(integralNumber).buildUpdate(room(), emptyMap(), 1001).get("Admin"))
    }

    @Test
    fun unknownTopLevelSettingsRemainEvidenceAndBlockAnUnverifiedUpdateProtocol() {
        val unknown = room().apply { add("FutureRoomSetting", json("{\"Mode\":\"new\"}")) }
        val captured = capture(unknown)
        assertEquals(unknown, captured.evidenceCopy())
        assertFailure(RazorRoomRawSettingsFailure.UnknownTopLevelFields, setOf("FutureRoomSetting")) {
            captured.buildUpdate(room(), mapOf("Description" to JsonPrimitive("My draft")), 1001)
        }
    }

    @Test
    fun onlySevenBasicFieldsCanBeEditedAndEditsKeepRequiredTypes() {
        listOf("Admin", "Whitelist", "Ban", "Game", "BlockCategory", "MapData", "Custom", "Space", "FutureRoomSetting").forEach { field ->
            assertFailure(RazorRoomRawSettingsFailure.UnsupportedEditedFields, setOf(field)) {
                capture(room()).buildUpdate(room(), mapOf(field to JsonNull.INSTANCE), 1001)
            }
        }
        assertFailure(RazorRoomRawSettingsFailure.InvalidFieldShape, setOf("Description")) {
            capture(room()).buildUpdate(room(), mapOf("Description" to JsonNull.INSTANCE), 1001)
        }
    }

    @Test
    fun malformedOrNonObjectPacketsCannotProduceRawAuthority() {
        listOf("", "{", "null", "[]", "\"room\"", "12").forEach { assertNull(RazorRoomRawSettings.capture(it)) }
        assertNotNull(RazorRoomRawSettings.capture("{}"))
    }

    private fun capture(raw: JsonObject): RazorRoomRawSettings = checkNotNull(RazorRoomRawSettings.capture(raw.toString()))

    private fun json(raw: String): JsonElement = JsonParser.parseString(raw)

    private fun room(): JsonObject = json(
        """{
            "Name":"Room", "Language":"EN", "Description":"Original description", "Background":"MainHall", "Limit":10,
            "Admin":[1001,2002], "Whitelist":[], "Ban":[], "BlockCategory":[], "Game":"",
            "Visibility":["All"], "Access":["All"], "MapData":{"Type":"Never","Future":{"a":1}}, "Custom":{"Future":"retained"},
            "Space":"X", "Locked":false, "SourceMemberNumber":2002, "Character":[{"MemberNumber":1001}],
            "Private":false, "CanJoin":true, "MemberCount":2, "MemberLimit":10, "Creator":"Creator", "CreatorMemberNumber":2002,
            "Friends":[1001], "MapType":"Never"
        }""",
    ).asJsonObject

    private fun assertFailure(reason: RazorRoomRawSettingsFailure, fields: Set<String>, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected ${reason.name}")
        } catch (failure: RazorRoomRawSettingsException) {
            assertEquals(reason, failure.reason)
            assertEquals(fields, failure.fields)
        }
    }

    private val updateFields = setOf(
        "Name", "Language", "Description", "Background", "Limit", "Admin", "Whitelist", "Ban", "BlockCategory", "Game",
        "Visibility", "Access", "MapData", "Custom",
    )
}
