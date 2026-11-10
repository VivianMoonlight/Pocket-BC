package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Collections

/** Raw authority owned by the room store; typed room projections must not be used for updates. */
internal class RazorRoomRawSettings private constructor(private val evidence: JsonObject) {
    fun evidenceCopy(): JsonObject = evidence.deepCopy()

    fun validatedUpdateSettings(): JsonObject {
        validateSettings(evidence)
        return JsonObject().apply {
            UPDATE_FIELDS.forEach { field ->
                if (evidence.has(field)) add(field, evidence.get(field).deepCopy())
            }
        }
    }

    fun buildUpdate(
        baseline: JsonObject,
        editedFields: Map<String, JsonElement>,
        selfMemberNumber: Int,
    ): JsonObject {
        val edits = editedFields.mapValues { (_, value) -> value.deepCopy() }
        val unsupportedEdits = edits.keys - BASIC_FIELDS
        if (unsupportedEdits.isNotEmpty()) {
            fail(RazorRoomRawSettingsFailure.UnsupportedEditedFields, unsupportedEdits)
        }
        validateSettings(evidence)
        if (selfMemberNumber <= 0 || evidence.getAsJsonArray("Admin").none { it.exactIntOrNull() == selfMemberNumber }) {
            fail(RazorRoomRawSettingsFailure.NotAdmin, setOf("Admin"))
        }

        val expected = baseline.deepCopy()
        val missingBaseline = edits.keys.filterNotTo(linkedSetOf(), expected::has)
        if (missingBaseline.isNotEmpty()) {
            fail(RazorRoomRawSettingsFailure.MissingBaselineFields, missingBaseline)
        }
        val conflicts = edits.keys.filterTo(linkedSetOf()) { evidence.get(it) != expected.get(it) }
        if (conflicts.isNotEmpty()) {
            fail(RazorRoomRawSettingsFailure.EditedFieldConflict, conflicts)
        }

        return JsonObject().apply {
            UPDATE_FIELDS.forEach { field ->
                if (evidence.has(field)) add(field, evidence.get(field).deepCopy())
            }
            edits.forEach { (field, value) -> add(field, value) }
            validateSettings(this)
        }
    }

    companion object {
        private val BASIC_FIELDS = setOf("Name", "Description", "Limit", "Background", "Language", "Visibility", "Access")
        private val UPDATE_FIELDS = listOf(
            "Name", "Language", "Description", "Background", "Limit",
            "Admin", "Whitelist", "Ban", "BlockCategory", "Game",
            "Visibility", "Access", "MapData", "Custom",
        )
        private val REQUIRED_FIELDS = UPDATE_FIELDS.filterNot { it == "MapData" || it == "Custom" }.toSet()
        private val SYNC_ONLY_FIELDS = setOf(
            "Character", "SourceMemberNumber", "Space", "Locked", "Private", "CanJoin",
            "MemberCount", "MemberLimit", "Creator", "CreatorMemberNumber", "Friends", "MapType",
        )
        private val STRING_FIELDS = setOf("Name", "Language", "Description", "Background", "Game")
        private val MEMBER_LIST_FIELDS = setOf("Admin", "Whitelist", "Ban")
        private val STRING_LIST_FIELDS = setOf("BlockCategory", "Visibility", "Access")

        fun capture(payloadJson: String): RazorRoomRawSettings? = runCatching {
            val parsed = JsonParser.parseString(payloadJson)
            parsed.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { RazorRoomRawSettings(it.deepCopy()) }
        }.getOrNull()

        private fun validateSettings(settings: JsonObject) {
            val unknown = settings.keySet() - UPDATE_FIELDS.toSet() - SYNC_ONLY_FIELDS
            if (unknown.isNotEmpty()) {
                fail(RazorRoomRawSettingsFailure.UnknownTopLevelFields, unknown)
            }
            val missing = REQUIRED_FIELDS.filterNotTo(linkedSetOf(), settings::has)
            if (missing.isNotEmpty()) {
                fail(RazorRoomRawSettingsFailure.MissingRequiredFields, missing)
            }
            val invalid = linkedSetOf<String>()
            STRING_FIELDS.filterTo(invalid) { !settings.get(it).isString() }
            MEMBER_LIST_FIELDS.filterTo(invalid) { field ->
                val value = settings.get(field)
                !value.isJsonArray || value.asJsonArray.any { (it.exactIntOrNull() ?: 0) <= 0 }
            }
            STRING_LIST_FIELDS.filterTo(invalid) { field ->
                val value = settings.get(field)
                !value.isJsonArray || value.asJsonArray.any { !it.isString() }
            }
            if (settings.get("Limit").exactIntOrNull() == null) invalid += "Limit"
            if (settings.has("MapData")) {
                val value = settings.get("MapData")
                if (!value.isJsonNull && (!value.isJsonObject || !value.asJsonObject.get("Type").isString())) {
                    invalid += "MapData"
                }
            }
            if (settings.has("Custom")) {
                val value = settings.get("Custom")
                if (!value.isJsonNull && !value.isJsonObject) invalid += "Custom"
            }
            if (invalid.isNotEmpty()) fail(RazorRoomRawSettingsFailure.InvalidFieldShape, invalid)
        }

        private fun JsonElement?.isString(): Boolean =
            this?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.isString == true

        private fun JsonElement.exactIntOrNull(): Int? {
            if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
            return runCatching { asBigDecimal.intValueExact() }.getOrNull()
        }

        private fun fail(reason: RazorRoomRawSettingsFailure, fields: Set<String>): Nothing =
            throw RazorRoomRawSettingsException(reason, fields)
    }
}

internal enum class RazorRoomRawSettingsFailure {
    MissingRequiredFields,
    InvalidFieldShape,
    UnknownTopLevelFields,
    NotAdmin,
    UnsupportedEditedFields,
    MissingBaselineFields,
    EditedFieldConflict,
}

internal class RazorRoomRawSettingsException(
    val reason: RazorRoomRawSettingsFailure,
    fields: Set<String>,
) : IllegalStateException("${reason.name}: ${fields.joinToString()}") {
    val fields: Set<String> = Collections.unmodifiableSet(fields.toSet())
}
