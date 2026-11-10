package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.isTimelineVisible
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcEmoteDisplayText

data class ChatReplySelection(
    val reference: BcMessageReplyReference,
    val author: String,
    val text: String,
    val ownerMemberNumber: Int,
    val accountGeneration: Long,
    val roomPresenceEpoch: Long,
)

data class ChatReplyPreview(
    val messageId: String,
    val author: String? = null,
    val text: String? = null,
    val canJump: Boolean = false,
    val messageUiKey: String? = null,
)

/** A room timeline projects multiple conversations whose native message IDs may coincide. */
val BcMessage.chatUiKey: String
    get() = "${conversationId.value.length}:${conversationId.value}:${id.value}"

/** Resolve quotes only within the visible conversation and the original whisper's room. */
internal fun chatReplyPreviews(messages: List<BcMessage>): Map<String, ChatReplyPreview> {
    val originals = messages.filter {
        it.visibility == BcMessageVisibility.Visible && it.delivery == DeliveryState.Sent &&
            (it.kind == BcMessageKind.Chat || it.kind == BcMessageKind.Emote || it.kind == BcMessageKind.Whisper)
    }.associateBy { it.conversationId to it.id.value }
    return messages.mapNotNull { message ->
        val replyId = message.replyToId ?: return@mapNotNull null
        val original = originals[message.conversationId to replyId]?.takeIf {
            it.conversationId == message.conversationId && when (message.transport) {
                BcMessageTransport.RoomChat -> it.transport == BcMessageTransport.RoomChat
                BcMessageTransport.RoomWhisper -> it.transport == BcMessageTransport.RoomWhisper &&
                    !message.raw?.roomName.isNullOrBlank() && it.raw?.roomName == message.raw?.roomName
                else -> false
            }
        }
        message.chatUiKey to if (original == null) {
            ChatReplyPreview(messageId = replyId)
        } else {
            ChatReplyPreview(original.id.value, original.sender.displayName, original.replyPreviewText(),
                canJump = true, messageUiKey = original.chatUiKey)
        }
    }.toMap()
}

internal fun BcMessage.replyPreviewText(): String = if (kind == BcMessageKind.Emote) {
    bcEmoteDisplayText(text, sender.displayName)
} else {
    text
}

/** Project the same stored private records into their room; never copy them into the room conversation. */
internal fun mergeRoomTimeline(
    roomMessages: List<BcMessage>,
    whispers: List<BcMessage>,
    roomConversationId: BcConversationId,
    roomName: String?,
    ownerMemberNumber: Int?,
): List<BcMessage> = (
    roomMessages.filter { it.conversationId == roomConversationId && it.transport != BcMessageTransport.RoomWhisper } +
        whispers.filter { it.isWhisperInRoom(roomName, ownerMemberNumber) }
    ).distinctBy { it.conversationId to it.id }
    .sortedWith(compareBy<BcMessage> { it.timestamp }.thenBy { it.id.value })

private fun BcMessage.isWhisperInRoom(roomName: String?, owner: Int?): Boolean {
    if (roomName.isNullOrBlank() || owner == null || kind != BcMessageKind.Whisper ||
        transport != BcMessageTransport.RoomWhisper || raw?.roomName != roomName || !isTimelineVisible()
    ) return false
    val peer = (address as? BcMessageAddress.Direct)?.memberNumber ?: return false
    if (peer == owner || conversationId.value != "message:$peer") return false
    return if (sender.memberNumber == owner) {
        targetMemberNumber == peer
    } else {
        sender.memberNumber == peer && (targetMemberNumber == null || targetMemberNumber == owner)
    }
}
