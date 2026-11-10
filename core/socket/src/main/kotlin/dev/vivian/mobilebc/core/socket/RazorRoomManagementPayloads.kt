package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.vivian.mobilebc.core.model.BcRoomBasicField
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import com.google.gson.JsonArray

internal fun BcRoomBasicSettings.toRoomBasicJson(): JsonObject = JsonObject().apply {
    addProperty("Name", name)
    addProperty("Description", description)
    addProperty("Limit", memberLimit)
    addProperty("Background", background)
    addProperty("Language", language)
    add("Visibility", JsonArray().apply { visibility.forEach(::add) })
    add("Access", JsonArray().apply { access.forEach(::add) })
}

internal fun JsonObject.toRoomBasicSettings(): BcRoomBasicSettings = BcRoomBasicSettings(
    name = get("Name").asString,
    description = get("Description").asString,
    memberLimit = get("Limit").asInt,
    background = get("Background").asString,
    language = get("Language").asString,
    visibility = getAsJsonArray("Visibility").map { it.asString },
    access = getAsJsonArray("Access").map { it.asString },
)

internal fun BcRoomBasicField.wireField(): String = when (this) {
    BcRoomBasicField.MemberLimit -> "Limit"
    else -> name
}

internal fun BcRoomUpdateCommand.editedRoomFields(): Map<String, JsonElement> {
    val values = settings.toRoomBasicJson()
    return editedFields.associate { it.wireField() to values.get(it.wireField()) }
}

internal fun BcRoomCreateCommand.toRoomCreatePayload(accountMemberNumber: Int, ban: List<Int>): JsonObject =
    settings.toRoomBasicJson().apply {
        addProperty("Space", space.wireValue)
        add("Admin", JsonArray().apply { add(accountMemberNumber) })
        add("Whitelist", JsonArray())
        add("Ban", JsonArray().apply { ban.forEach(::add) })
        addProperty("Game", "")
        add("BlockCategory", JsonArray())
    }

internal fun rejectRoomMutationIf(condition: Boolean, reason: BcRoomMutationRejectionReason) {
    if (condition) throw BcRoomMutationRejectedException(BcRoomMutationRejection(reason))
}

internal fun RazorRoomRawSettingsException.toRoomMutationRejection(): BcRoomMutationRejectedException {
    val reason = when (this.reason) {
        RazorRoomRawSettingsFailure.NotAdmin -> BcRoomMutationRejectionReason.NotAdministrator
        RazorRoomRawSettingsFailure.EditedFieldConflict -> BcRoomMutationRejectionReason.EditConflict
        else -> BcRoomMutationRejectionReason.SettingsUnavailable
    }
    val field = BcRoomBasicField.entries.firstOrNull { it.wireField() in fields }
    return BcRoomMutationRejectedException(BcRoomMutationRejection(reason, field))
}
