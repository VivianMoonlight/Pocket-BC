package dev.vivian.mobilebc.core.protocol

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcRawAccountBeep
import dev.vivian.mobilebc.core.model.BcRawChatMessage
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

fun defaultBcInboundCodecRegistry(
    deadLetterSink: DeadLetterSink = NoOpDeadLetterSink,
): CodecRegistry<BcInboundPacket> =
    CodecRegistry(
        codecs = listOf(
            BcChatMessageCodec,
            BcAccountBeepCodec,
        ),
        deadLetterSink = deadLetterSink,
    )

object BcChatMessageCodec : PacketCodec<BcInboundPacket> {
    override val transportEventNames: Set<String> = setOf("ChatRoomMessage", "ChatRoomChat")
    override val domainEventName: String = "chat.message.created"

    override fun canDecode(frame: CanonicalFrame): Boolean =
        super.canDecode(frame) || frame.isChatRoomChatPayload()

    override fun decode(frame: CanonicalFrame): DecodeResult<BcInboundPacket> {
        val rawJson = frame.args.firstOrNull() ?: frame.raw
        return runCatching {
            val json = unwrapChatRoomChatPayload(rawJson)
            val payloadJson = json.toString()
            val senderMemberNumber = json.optNullableIntAny("Sender", "sender", "senderMemberNumber")
                ?: json.optSourceCharacterFromDictionary()
                ?: return DecodeResult.DeadLetter(frame, ProtocolError.DecodeFailed("Missing chat sender."))
            val wireType = json.optNullableStringAny("Type", "type", "wireType") ?: "Chat"
            val dictionaryJson = json.optJsonStringAny("Dictionary", "dictionary", "dictionaryJson")
            val dictionaryEntries = dictionaryJson.dictionaryEntriesOrNull()
            val activityFields = dictionaryEntries.activityFieldsFromDictionaryOrNull()
            val targetMemberNumber = json.optNullableIntAny("Target", "target", "targetMemberNumber")
                ?: activityFields?.targetMemberNumber
            val clientMsgId = if (dictionaryJson?.trimStart()?.startsWith("[") == true) {
                bcMessageIdFromDictionaryOrNull(dictionaryJson)
            } else {
                json.optNullableStringAny("MsgId", "msgId", "clientMsgId")
                    ?: dictionaryEntries.clientMsgIdFromDictionaryOrNull()
            }
            val conversationId = if (wireType == "Whisper") {
                null
            } else {
                BcConversationId("room:current")
            }
            DecodeResult.Success(
                BcInboundPacket.ChatMessage(
                    packetId = frame.packetId(
                        prefix = "chat",
                        clientMsgId = clientMsgId,
                        stablePayload = payloadJson,
                    ),
                    conversationId = conversationId,
                    receivedAtMillis = frame.receivedAtMillis,
                    rawJson = payloadJson,
                    message = BcRawChatMessage(
                        senderMemberNumber = senderMemberNumber,
                        senderName = json.optNullableStringAny("SenderName", "senderName"),
                        content = json.optNullableStringAny("Content", "content", "Message", "message").orEmpty(),
                        wireType = wireType,
                        targetMemberNumber = targetMemberNumber,
                        targetName = json.optNullableStringAny("TargetName", "targetName"),
                        clientMsgId = clientMsgId,
                        dictionaryJson = dictionaryJson,
                        payloadJson = payloadJson,
                        activityName = activityFields?.activityName,
                        activityTargetGroup = activityFields?.targetGroup,
                    ),
                )
            )
        }.getOrElse { throwable ->
            DecodeResult.DeadLetter(frame, ProtocolError.DecodeFailed(throwable.message ?: "Chat decode failed."))
        }
    }
}

object BcAccountBeepCodec : PacketCodec<BcInboundPacket> {
    override val transportEventNames: Set<String> = setOf("AccountBeep")
    override val domainEventName: String = "chat.direct_message.created"

    override fun decode(frame: CanonicalFrame): DecodeResult<BcInboundPacket> {
        val rawJson = frame.args.firstOrNull() ?: frame.raw
        return runCatching {
            val json = JSONObject(rawJson)
            val memberNumber = json.optNullableIntAny("MemberNumber", "memberNumber")
                ?: return DecodeResult.DeadLetter(frame, ProtocolError.DecodeFailed("Missing beep memberNumber."))
            val beepType = json.optNullableStringPreservingBlankAny("BeepType", "beepType")
            val clientMsgId = json.optNullableStringAny("MsgId", "msgId", "clientMsgId")
            DecodeResult.Success(
                BcInboundPacket.AccountBeep(
                    packetId = frame.packetId("beep", clientMsgId, rawJson),
                    conversationId = if (beepType.isNullOrEmpty()) BcConversationId("message:$memberNumber") else null,
                    receivedAtMillis = frame.receivedAtMillis,
                    rawJson = rawJson,
                    beep = BcRawAccountBeep(
                        memberNumber = memberNumber,
                        memberName = json.optNullableStringAny("MemberName", "memberName"),
                        chatRoomName = json.optNullableStringAny("ChatRoomName", "chatRoomName"),
                        chatRoomSpace = json.optNullableStringAny("ChatRoomSpace", "chatRoomSpace"),
                        private = json.optNullableBooleanAny("Private", "private"),
                        beepType = beepType,
                        message = json.optNullableStringAny("Message", "message"),
                        clientMsgId = clientMsgId,
                        payloadJson = rawJson,
                    ),
                )
            )
        }.getOrElse { throwable ->
            DecodeResult.DeadLetter(frame, ProtocolError.DecodeFailed(throwable.message ?: "Beep decode failed."))
        }
    }
}

private fun CanonicalFrame.packetId(
    prefix: String,
    clientMsgId: String? = null,
    stablePayload: String = raw,
): String {
    val stableClientMsgId = clientMsgId?.trim()?.takeIf { it.isNotEmpty() }
    return if (stableClientMsgId != null) {
        "$prefix:msgid:${sha256Hex(stableClientMsgId)}"
    } else {
        // Without a server/client ID, identical payloads can be intentional repeated messages.
        // Reprocessing the same frame remains idempotent; a new reception is a new occurrence.
        val occurrence = "${receivedAtMillis}:${occurrenceId ?: 0L}"
        "$prefix:${sha256Hex("$transportEventName|$stablePayload|occurrence:$occurrence")}"
    }
}

private fun CanonicalFrame.isChatRoomChatPayload(): Boolean =
    transportEventName == "Message" &&
        runCatching {
            val rawJson = args.firstOrNull() ?: raw
            when (val value = JSONTokener(rawJson).nextValue()) {
                is JSONObject -> value.optString("Message") == "ChatRoomChat"
                is JSONArray -> value.chatPayloadObjectFromArray() != null
                else -> false
            }
        }.getOrDefault(false)

private fun unwrapChatRoomChatPayload(rawJson: String): JSONObject {
    val value = JSONTokener(rawJson).nextValue()
    return when (value) {
        is JSONArray -> value.chatPayloadObjectFromArray() ?: JSONObject(rawJson)
        is JSONObject -> value.chatRoomChatPayload() ?: value
        else -> JSONObject(rawJson)
    }
}

private fun JSONObject.isChatPayloadObject(): Boolean =
    has("Type") && has("Content") && has("Dictionary")

private fun JSONObject.chatRoomChatPayload(): JSONObject? {
    if (optString("Message") != "ChatRoomChat") return null
    for (key in listOf("Data", "data", "Payload", "payload", "Args", "args")) {
        val value = opt(key) ?: continue
        when (value) {
            is JSONObject -> return value
            is JSONArray -> value.optJSONObject(0)?.let { return it }
        }
    }
    return null
}

private fun JSONArray.chatPayloadObjectFromArray(): JSONObject? {
    for (index in 0 until length()) {
        val value = opt(index)
        if (value is JSONObject && value.isChatPayloadObject()) return value
    }
    return null
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableStringPreservingBlank(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (has(name) && !isNull(name)) optBoolean(name) else null

private fun JSONObject.optNullableIntAny(vararg names: String): Int? =
    names.firstNotNullOfOrNull(::optNullableInt)

private fun JSONObject.optNullableStringAny(vararg names: String): String? =
    names.firstNotNullOfOrNull(::optNullableString)

private fun JSONObject.optNullableStringPreservingBlankAny(vararg names: String): String? =
    names.firstNotNullOfOrNull(::optNullableStringPreservingBlank)

private fun JSONObject.optNullableBooleanAny(vararg names: String): Boolean? =
    names.firstNotNullOfOrNull(::optNullableBoolean)

private fun JSONObject.optJsonStringAny(vararg names: String): String? {
    for (name in names) {
        if (!has(name) || isNull(name)) continue
        val value = get(name)
        val json = when (value) {
            is JSONObject,
            is JSONArray,
            -> value.toString()

            is String -> value.takeIf { it.isNotBlank() }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
        if (json != null) return json
    }
    return null
}

private fun String?.dictionaryEntriesOrNull(): JSONArray? {
    if (isNullOrBlank()) return null
    return when (val value = runCatching { JSONTokener(this).nextValue() }.getOrNull()) {
        is JSONArray -> value
        is JSONObject -> JSONArray().put(value)
        else -> null
    }
}

private fun JSONArray?.clientMsgIdFromDictionaryOrNull(): String? {
    if (this == null) return null
    for (index in 0 until length()) {
        val entry = optJSONObject(index) ?: continue
        val tag = entry.optNullableStringAny("Tag", "tag")
        val msgId = entry.optNullableStringAny("MsgId", "msgId", "clientMsgId")
        if (msgId != null && (tag == null || tag.equals("MsgId", ignoreCase = true))) {
            return msgId
        }
    }
    return null
}

private data class ActivityDictionaryFields(
    val targetMemberNumber: Int?,
    val targetGroup: String?,
    val activityName: String?,
)

private fun JSONArray?.activityFieldsFromDictionaryOrNull(): ActivityDictionaryFields? {
    if (this == null) return null
    var targetMemberNumber: Int? = null
    var targetGroup: String? = null
    var activityName: String? = null
    for (index in 0 until length()) {
        val entry = optJSONObject(index) ?: continue
        val tag = entry.optNullableStringAny("Tag", "tag")
        targetMemberNumber = targetMemberNumber
            ?: entry.optNullableInt("TargetCharacter")
            ?: entry.optNullableInt("MemberNumber").takeIf {
                tag.equals("TargetCharacter", ignoreCase = true) ||
                    tag.equals("DestinationCharacter", ignoreCase = true)
            }
        targetGroup = targetGroup ?: entry.optNullableStringAny("FocusGroupName", "focusGroupName")
        activityName = activityName ?: entry.optNullableStringAny("ActivityName", "activityName")
    }
    return ActivityDictionaryFields(
        targetMemberNumber = targetMemberNumber,
        targetGroup = targetGroup,
        activityName = activityName,
    ).takeIf { it.targetMemberNumber != null || it.targetGroup != null || it.activityName != null }
}

private fun JSONObject.optSourceCharacterFromDictionary(): Int? {
    val dictionary = opt("Dictionary") ?: opt("dictionary") ?: return null
    val entries = when (dictionary) {
        is JSONArray -> dictionary
        is String -> runCatching { JSONArray(dictionary) }.getOrNull()
        else -> null
    } ?: return null

    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        entry.optNullableInt("SourceCharacter")?.let { return it }
    }
    return null
}
