package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.AccountQueryResult
import bot.inker.bc.razor.protocol.common.ForceDisconnectReason
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason

internal fun AccountQueryResult.toOnlineFriendConversations(): List<BcConversation> {
    if (query != "OnlineFriends") return emptyList()
    val resultArray = result?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return resultArray.mapNotNull { element ->
        val json = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val memberNumber = json.get("MemberNumber")?.takeIf { !it.isJsonNull }?.asInt ?: return@mapNotNull null
        val displayName = json.stringOrNull("Nickname")?.takeIf(String::isNotBlank)
            ?: json.stringOrNull("MemberName")?.takeIf(String::isNotBlank)
            ?: json.stringOrNull("Name")?.takeIf(String::isNotBlank)
            ?: "#$memberNumber"
        val roomName = json.stringOrNull("ChatRoomName")
        BcConversation(
            id = BcConversationId("message:$memberNumber"),
            type = ConversationType.Direct,
            title = displayName,
            subtitle = roomName?.takeIf { it.isNotBlank() }?.let { "Online in $it" } ?: "Online friend",
            updatedAt = null,
            availability = setOf(ConversationAvailability.OnlineFriend),
            onlineRoomName = roomName?.takeIf { it.isNotBlank() },
        )
    }
}

internal fun ForceDisconnectReason.toGatewayReason(): BcSessionForceDisconnectReason =
    when (this) {
        ForceDisconnectReason.DUPLICATED_LOGIN -> BcSessionForceDisconnectReason.DuplicatedLogin
        ForceDisconnectReason.RATE_LIMITED -> BcSessionForceDisconnectReason.RateLimited
        ForceDisconnectReason.UNKNOWN -> BcSessionForceDisconnectReason.Unknown
    }

private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString
