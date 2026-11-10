package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason

internal fun BcRoomMemberAdminCommand.toRoomMemberAdminPayload(): JsonObject = JsonObject().apply {
    addProperty("MemberNumber", targetMemberNumber)
    addProperty("Action", action.wireValue)
    if (action == BcRoomMemberAdminAction.Kick || action == BcRoomMemberAdminAction.Ban) {
        addProperty("Publish", true)
    }
}

internal fun rejectRoomMemberAdministrationIf(condition: Boolean, reason: BcRoomMemberAdminRejectionReason) {
    if (condition) throw BcRoomMemberAdminRejectedException(reason)
}

internal fun JsonElement?.exactRoomMemberNumbersOrNull(): List<Int>? {
    if (this?.isJsonArray != true) return null
    val values = asJsonArray.map { value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        val number = runCatching { value.asBigDecimal.intValueExact() }.getOrNull() ?: return null
        if (number <= 0) return null
        number
    }
    return values.toList()
}
