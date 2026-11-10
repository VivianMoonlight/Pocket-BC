package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.bcMessageIdFromDictionaryOrNull

fun BcMessage.replyReferenceOrNull(currentRoomName: String): BcMessageReplyReference? {
    if (currentRoomName.isBlank() || visibility != BcMessageVisibility.Visible || delivery != DeliveryState.Sent) {
        return null
    }
    val messageId = bcMessageIdFromDictionaryOrNull(raw?.dictionaryJson) ?: return null
    if (messageId != id.value) return null
    when (transport) {
        BcMessageTransport.RoomChat -> {
            if (kind != BcMessageKind.Chat && kind != BcMessageKind.Emote) return null
            val receivedRoomName = raw?.roomName
            if (receivedRoomName != null && receivedRoomName != currentRoomName) return null
            if (address != BcMessageAddress.CurrentRoom || conversationId != bcRoomConversationId(currentRoomName)) {
                return null
            }
        }
        BcMessageTransport.RoomWhisper -> {
            if (kind != BcMessageKind.Whisper || raw?.roomName != currentRoomName) return null
            val memberNumber = (address as? BcMessageAddress.Direct)?.memberNumber ?: return null
            if (memberNumber < 0 || conversationId.value != "message:$memberNumber") return null
        }
        else -> return null
    }
    return BcMessageReplyReference(messageId, conversationId, transport, currentRoomName)
}
