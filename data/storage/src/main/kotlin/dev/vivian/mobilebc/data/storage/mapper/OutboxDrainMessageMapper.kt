package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatMaxLength
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.sync.OutboxDrainMessage
import org.json.JSONObject

internal fun BcOutboundCommand.toOutboxPayloadJson(): String {
    check(
        transport == BcMessageTransport.RoomChat ||
            transport == BcMessageTransport.RoomWhisper ||
            transport == BcMessageTransport.AccountBeep,
    ) { "Transport $transport cannot be persisted in the outbox." }
    requireReplySupported(replyToId, replyRoomName, kind, transport, conversationId)
    requireOriginRoom(originRoomName, replyRoomName, transport)
    requireRoomChatLength(kind, transport, text)
    return JSONObject()
        .put("clientMsgId", clientMsgId)
        .put("conversationId", conversationId.value)
        .put("kind", kind.name)
        .put("transport", transport.name)
        .put("text", text)
        .put("targetMemberNumber", targetMemberNumber ?: JSONObject.NULL)
        .put("createdAtMillis", createdAtMillis)
        .apply { replyToId?.let { put("replyToId", it) } }
        .apply { replyRoomName?.let { put("replyRoomName", it) } }
        .apply { originRoomName?.let { put("originRoomName", it) } }
        .toString()
}

internal fun PersistedOutboxMessage.toValidatedOutboxDrainMessage(): OutboxDrainMessage {
    val payload = JSONObject(payloadJson)
    val payloadClientMsgId = payload.requiredString("clientMsgId")
    val payloadConversationId = payload.requiredString("conversationId")
    val payloadTransport = payload.requiredString("transport")
    val payloadCreatedAtMillis = payload.requiredLong("createdAtMillis")
    require(payloadClientMsgId == clientMsgId) { "clientMsgId does not match the outbox row." }
    require(payloadConversationId == conversationId.value) { "conversationId does not match the outbox row." }
    require(payloadTransport == transport) { "transport does not match the outbox row." }
    require(payloadCreatedAtMillis == createdAtMillis) { "createdAtMillis does not match the outbox row." }
    require(ownerMemberNumber >= 0 && attempts >= 0 && createdAtMillis >= 0) { "Invalid outbox metadata." }

    val commandTransport = enumValueOf<BcMessageTransport>(payloadTransport)
    val kind = enumValueOf<BcMessageKind>(payload.requiredString("kind"))
    val text = payload.get("text")
    require(text is String) { "text must be a string." }
    val targetMemberNumber = payload.optionalMemberNumber("targetMemberNumber")
    val replyToId = if (payload.has("replyToId")) payload.requiredString("replyToId") else null
    val replyRoomName = if (payload.has("replyRoomName")) payload.requiredString("replyRoomName") else null
    // Old reply commands already have an exact room scope. Ordinary legacy whispers do not;
    // rejecting those uses the existing invalid-payload path and preserves the failed local row.
    val originRoomName = if (payload.has("originRoomName")) payload.requiredString("originRoomName")
        else replyRoomName.takeIf { commandTransport == BcMessageTransport.RoomWhisper }
    requireReplySupported(replyToId, replyRoomName, kind, commandTransport, conversationId)
    requireOriginRoom(originRoomName, replyRoomName, commandTransport)
    requireRoomChatLength(kind, commandTransport, text)
    when (commandTransport) {
        BcMessageTransport.RoomChat -> {
            require(conversationId.value.startsWith("room:") && conversationId.value.length > 5) {
                "Room chat requires a room conversation."
            }
            require(kind in ROOM_CHAT_KINDS) { "Invalid room chat kind." }
            require(targetMemberNumber == null) {
                "Room chat cannot have a target member."
            }
        }
        BcMessageTransport.RoomWhisper,
        BcMessageTransport.AccountBeep,
        -> {
            val expectedKind = if (commandTransport == BcMessageTransport.RoomWhisper) {
                BcMessageKind.Whisper
            } else {
                BcMessageKind.Beep
            }
            require(kind == expectedKind) { "Direct transport does not match the message kind." }
            require(targetMemberNumber != null && conversationId.value == "message:$targetMemberNumber") {
                "Direct conversation does not match the target member."
            }
        }
        BcMessageTransport.LocalSystem,
        BcMessageTransport.ProtocolEvent,
        -> error("Transport $commandTransport cannot be dispatched from the outbox.")
    }
    return OutboxDrainMessage(
        ownerMemberNumber = ownerMemberNumber,
        clientMsgId = clientMsgId,
        command = BcOutboundCommand(
            clientMsgId = payloadClientMsgId,
            conversationId = BcConversationId(payloadConversationId),
            kind = kind,
            transport = commandTransport,
            text = text,
            targetMemberNumber = targetMemberNumber,
            createdAtMillis = payloadCreatedAtMillis,
            replyToId = replyToId,
            replyRoomName = replyRoomName,
            originRoomName = originRoomName,
        ),
        attempts = attempts,
    )
}

private fun JSONObject.requiredString(name: String): String {
    val value = get(name)
    require(value is String && value.isNotBlank()) { "$name must be a nonblank string." }
    return value
}

private fun JSONObject.requiredLong(name: String): Long {
    val value = get(name)
    require(value is Int || value is Long) { "$name must be an integer." }
    return (value as Number).toLong()
}

private fun JSONObject.optionalMemberNumber(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = requiredLong(name)
    require(value in 0..Int.MAX_VALUE.toLong()) { "$name is outside the member number range." }
    return value.toInt()
}

private val ROOM_CHAT_KINDS = setOf(
    BcMessageKind.Chat,
    BcMessageKind.Emote,
    BcMessageKind.Action,
)

private fun requireReplySupported(
    replyToId: String?,
    replyRoomName: String?,
    kind: BcMessageKind,
    transport: BcMessageTransport,
    conversationId: BcConversationId,
) {
    require((replyToId == null) == (replyRoomName == null)) { "Replies require a room scope." }
    if (replyToId == null) return
    require(replyToId.isNotBlank()) { "replyToId must be a nonblank string." }
    require(!replyRoomName.isNullOrBlank()) { "replyRoomName must be a nonblank string." }
    require(
        (transport == BcMessageTransport.RoomChat && (kind == BcMessageKind.Chat || kind == BcMessageKind.Emote)) ||
            (transport == BcMessageTransport.RoomWhisper && kind == BcMessageKind.Whisper),
    ) { "This message kind does not support replies." }
    if (transport == BcMessageTransport.RoomChat) {
        require(conversationId == bcRoomConversationId(replyRoomName)) { "Reply room scope does not match its conversation." }
    }
}

private fun requireRoomChatLength(kind: BcMessageKind, transport: BcMessageTransport, text: String) {
    if ((transport == BcMessageTransport.RoomChat && (kind == BcMessageKind.Chat || kind == BcMessageKind.Emote)) ||
        transport == BcMessageTransport.RoomWhisper
    ) {
        require(text.length <= BcRoomChatMaxLength) { "Room chat is limited to $BcRoomChatMaxLength characters." }
    }
}

private fun requireOriginRoom(originRoomName: String?, replyRoomName: String?, transport: BcMessageTransport) {
    require(originRoomName == null || originRoomName.isNotBlank()) { "originRoomName must be a nonblank string." }
    if (transport != BcMessageTransport.RoomWhisper) return
    require(!(originRoomName ?: replyRoomName).isNullOrBlank()) {
        "This queued whisper has no original room. Return to its original room and resend it manually."
    }
    require(originRoomName == null || replyRoomName == null || originRoomName == replyRoomName) {
        "Whisper origin room does not match its reply room."
    }
}
