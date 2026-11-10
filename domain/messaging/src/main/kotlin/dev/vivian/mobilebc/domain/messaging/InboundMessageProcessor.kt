package dev.vivian.mobilebc.domain.messaging

import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcInboundActivityCommand
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcNotificationPolicy
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.protocol.BcInboundPacket
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.CodecRegistry
import dev.vivian.mobilebc.core.protocol.DeadLetterSink
import dev.vivian.mobilebc.core.protocol.DecodeResult
import dev.vivian.mobilebc.core.protocol.NoOpDeadLetterSink
import dev.vivian.mobilebc.core.protocol.ProtocolError
import dev.vivian.mobilebc.core.protocol.bcReplyIdFromDictionaryOrNull
import dev.vivian.mobilebc.core.protocol.defaultBcInboundCodecRegistry as protocolInboundCodecRegistry
import java.time.Instant
import java.io.StringReader
import org.json.JSONArray
import kotlinx.coroutines.CancellationException

data class BcInboundMessageContext(
    val ownerMemberNumber: Int?,
    val ownerDisplayName: String?,
    val roomMembers: Map<Int, String>,
    val roomConversationId: BcConversationId?,
    val ignoredMemberNumbers: Set<Int> = emptySet(),
    val roomName: String? = null,
)

class BcInboundMessageProcessor(
    private val persistenceStore: BcInboundPersistenceStore,
    private val ownerMemberNumber: () -> Int? = { null },
    private val ownerDisplayName: () -> String? = { null },
    private val roomMemberLookup: () -> Map<Int, String> = { emptyMap() },
    private val currentRoomConversationId: () -> BcConversationId? = { null },
    private val isConversationVisible: (BcConversationId) -> Boolean = { false },
    private val ignoredMemberNumbers: () -> Set<Int> = { emptySet() },
    private val onFriendRequest: suspend (BcInboundFriendRequest) -> Unit = {},
    private val showDeobfuscatedOriginal: () -> Boolean = { false },
    private val chatTextResolver: BcChatTextResolver = PlainBcChatTextResolver,
    deadLetterSink: DeadLetterSink = NoOpDeadLetterSink,
    private val codecRegistry: CodecRegistry<BcInboundPacket> = defaultBcInboundCodecRegistry(
        deadLetterSink = deadLetterSink,
    ),
) {
    suspend fun handle(
        frame: CanonicalFrame,
        context: BcInboundMessageContext = BcInboundMessageContext(
            ownerMemberNumber = ownerMemberNumber(),
            ownerDisplayName = ownerDisplayName(),
            roomMembers = roomMemberLookup(),
            roomConversationId = currentRoomConversationId(),
            ignoredMemberNumbers = ignoredMemberNumbers(),
        ),
        textResolver: BcChatTextResolver = chatTextResolver,
    ): BcInboundProcessingResult =
        when (val decoded = codecRegistry.decode(frame)) {
            is DecodeResult.Success -> {
                val packet = decoded.packet
                val senderMemberNumber = packet.senderMemberNumber()
                val ownerMemberNumber = context.ownerMemberNumber
                if (senderMemberNumber != null &&
                    senderMemberNumber != ownerMemberNumber &&
                    senderMemberNumber in context.ignoredMemberNumbers
                ) {
                    BcInboundIgnored(packet.packetId, senderMemberNumber)
                } else {
                    if (ownerMemberNumber != null && senderMemberNumber == ownerMemberNumber &&
                        packet is BcInboundPacket.ChatMessage &&
                        packet.message.wireType in setOf("Chat", "Emote", "Action", "Activity", "Whisper")
                    ) {
                        packet.message.clientMsgId?.let { clientMsgId ->
                            persistenceStore.confirmOutgoing(ownerMemberNumber, clientMsgId, packet.receivedAtMillis)
                        }
                    }
                    val receiveContext = frame.receiveContext
                    record(packet, context, textResolver, if (receiveContext != null) receiveContext.roomName else context.roomName)
                }
            }
            is DecodeResult.DeadLetter -> BcInboundProcessingResult.DeadLetter(decoded.reason)
        }

    private suspend fun record(
        packet: BcInboundPacket,
        context: BcInboundMessageContext,
        textResolver: BcChatTextResolver,
        roomName: String?,
    ): BcInboundProcessingResult {
        val owner = context.ownerMemberNumber
        val message = owner?.let { ownerMemberNumber ->
            packet.toMessageOrNull(
                ownerMemberNumber = ownerMemberNumber,
                ownerDisplayName = context.ownerDisplayName,
                roomMembers = context.roomMembers,
                currentRoomConversationId = context.roomConversationId,
                roomName = roomName,
                chatTextResolver = textResolver,
                showDeobfuscatedOriginal = showDeobfuscatedOriginal(),
            )
        }
        val conversationId = if (packet is BcInboundPacket.AccountBeep && message == null) {
            null
        } else {
            message?.conversationId ?: packet.conversationId
        }
        val command = BcInboundPersistenceCommand(
            event = BcInboxEvent(
                packetId = packet.packetId,
                eventName = packet.eventName,
                conversationId = conversationId,
                receivedAtMillis = packet.receivedAtMillis,
                rawJson = packet.rawJson,
            ),
            ownerMemberNumber = owner,
            contactProfile = if (owner != null) packet.lianContactProfileOrNull() else null,
            message = message,
            cursorConversationId = conversationId,
            cursorSeq = null,
            cursorUpdatedAtMillis = packet.receivedAtMillis,
            markReadThroughMessage = message?.let { candidate ->
                isConversationVisible(candidate.conversationId)
            } ?: false,
        )
        return when (val result = persistenceStore.record(command)) {
            BcInboundPersistenceResult.Recorded -> {
                packet.toFriendRequestOrNull(owner)?.let { request -> onFriendRequest(request) }
                BcInboundProcessingResult.Recorded(packet, message)
            }
            is BcInboundPersistenceResult.Duplicate -> BcInboundProcessingResult.Duplicate(result.packetId)
        }
    }
}

data class BcInboxEvent(
    val packetId: String,
    val eventName: String,
    val conversationId: BcConversationId?,
    val receivedAtMillis: Long,
    val rawJson: String?,
)

data class BcInboundPersistenceCommand(
    val event: BcInboxEvent,
    val ownerMemberNumber: Int?,
    val message: BcMessage?,
    val cursorConversationId: BcConversationId?,
    val cursorSeq: Long?,
    val cursorUpdatedAtMillis: Long,
    val markReadThroughMessage: Boolean = false,
    val contactProfile: BcContactProfile? = null,
)

private fun BcInboundPacket.lianContactProfileOrNull(): BcContactProfile? {
    if (this !is BcInboundPacket.AccountBeep || beep.beepType != "LCPlayerInfo") return null
    val content = beep.message ?: return null
    return runCatching {
        val data = JsonReader(StringReader(content)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = JsonParser.parseReader(reader)
            if (!value.isJsonObject || reader.peek() != JsonToken.END_DOCUMENT) return null
            value.asJsonObject
        }
        fun string(key: String) = data.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        BcContactProfile(
            memberNumber = beep.memberNumber,
            name = string("Name") ?: beep.memberName ?: "#${beep.memberNumber}",
            nickname = string("Nickname"),
            avatarUrl = string("Avatar") ?: return null,
            signature = string("Signature") ?: return null,
            firstSeenAtMillis = receivedAtMillis,
            lastSeenAtMillis = receivedAtMillis,
            lianUpdatedAtMillis = receivedAtMillis,
        )
    }.getOrNull()
}

interface BcInboundPersistenceStore {
    suspend fun confirmOutgoing(ownerMemberNumber: Int, clientMsgId: String, receivedAtMillis: Long) = Unit
    suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult
}

sealed interface BcInboundPersistenceResult {
    data object Recorded : BcInboundPersistenceResult
    data class Duplicate(val packetId: String) : BcInboundPersistenceResult
}

sealed interface BcInboundProcessingResult {
    data class Recorded(
        val packet: BcInboundPacket,
        val message: BcMessage?,
    ) : BcInboundProcessingResult
    data class Duplicate(val packetId: String) : BcInboundProcessingResult
    data class DeadLetter(val reason: ProtocolError) : BcInboundProcessingResult
}

fun BcInboundPacket.toInboundActivityCommandOrNull(): BcInboundActivityCommand? {
    val chat = (this as? BcInboundPacket.ChatMessage)?.message ?: return null
    if (!chat.wireType.equals("Activity", ignoreCase = true)) return null
    val targetMemberNumber = chat.targetMemberNumber ?: return null
    val targetGroup = chat.activityTargetGroup?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val activityName = chat.activityName?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return BcInboundActivityCommand(
        sourceMemberNumber = chat.senderMemberNumber,
        targetMemberNumber = targetMemberNumber,
        targetGroup = targetGroup,
        activityName = activityName,
        clientMsgId = chat.clientMsgId,
    )
}

fun defaultBcInboundCodecRegistry(
    deadLetterSink: DeadLetterSink = NoOpDeadLetterSink,
): CodecRegistry<BcInboundPacket> =
    protocolInboundCodecRegistry(deadLetterSink = deadLetterSink)

private suspend fun BcInboundPacket.toMessageOrNull(
    ownerMemberNumber: Int,
    ownerDisplayName: String?,
    roomMembers: Map<Int, String>,
    currentRoomConversationId: BcConversationId?,
    roomName: String?,
    chatTextResolver: BcChatTextResolver,
    showDeobfuscatedOriginal: Boolean,
): BcMessage? {
    return when (this) {
        is BcInboundPacket.ChatMessage -> {
            val senderIsSelf = message.senderMemberNumber == ownerMemberNumber
            val senderName = message.senderName
                ?: roomMembers[message.senderMemberNumber]
                ?: ownerDisplayName.takeIf { senderIsSelf && !it.isNullOrBlank() }
                ?: "#${message.senderMemberNumber}"
            if (message.wireType == "Whisper") {
                val otherMemberNumber = if (senderIsSelf) {
                    message.targetMemberNumber ?: return null
                } else {
                    message.senderMemberNumber
                }
                return BcMessage(
                    id = BcMessageId(message.clientMsgId ?: packetId),
                    conversationId = BcConversationId("message:$otherMemberNumber"),
                    address = BcMessageAddress.Direct(otherMemberNumber),
                    sender = BcSender(
                        memberNumber = message.senderMemberNumber,
                        displayName = senderName,
                        isSelf = senderIsSelf,
                    ),
                    kind = BcMessageKind.Whisper,
                    transport = BcMessageTransport.RoomWhisper,
                    text = message.content.withOriginal(message.dictionaryJson, showDeobfuscatedOriginal),
                    timestamp = Instant.ofEpochMilli(receivedAtMillis),
                    delivery = DeliveryState.Sent,
                    visibility = BcMessageVisibility.Visible,
                    notificationPolicy = if (senderIsSelf) BcNotificationPolicy.None else BcNotificationPolicy.DirectMessage,
                    targetMemberNumber = message.targetMemberNumber,
                    replyToId = bcReplyIdFromDictionaryOrNull(message.dictionaryJson),
                    raw = BcRawMessage(
                        sourceEvent = "ChatRoomMessage",
                        wireType = message.wireType,
                        payloadJson = rawJson,
                        dictionaryJson = message.dictionaryJson,
                        roomName = roomName,
                    ),
                )
            }
            val kind = when (message.wireType) {
                "Chat" -> BcMessageKind.Chat
                "Emote" -> BcMessageKind.Emote
                "Action" -> BcMessageKind.Action
                "Activity" -> BcMessageKind.Activity
                "ServerMessage" -> BcMessageKind.System
                else -> return null
            }
            val resolvedText = try {
                chatTextResolver.resolve(message, kind, roomMembers.withOwner(ownerMemberNumber, ownerDisplayName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BcChatTextResolution(
                    text = message.content,
                    resolution = BcMessageResolution.Failed(message.content, message.content),
                )
            }
            BcMessage(
                id = BcMessageId(message.clientMsgId ?: packetId),
                conversationId = currentRoomConversationId ?: conversationId ?: return null,
                address = BcMessageAddress.CurrentRoom,
                sender = BcSender(
                    memberNumber = message.senderMemberNumber,
                    displayName = senderName,
                    isSelf = senderIsSelf,
                ),
                kind = kind,
                transport = BcMessageTransport.RoomChat,
                text = resolvedText.text.withOriginal(message.dictionaryJson, showDeobfuscatedOriginal),
                timestamp = Instant.ofEpochMilli(receivedAtMillis),
                delivery = DeliveryState.Sent,
                visibility = BcMessageVisibility.Visible,
                notificationPolicy = if (senderIsSelf || kind != BcMessageKind.Chat) {
                    BcNotificationPolicy.None
                } else {
                    BcNotificationPolicy.RoomMessage
                },
                targetMemberNumber = message.targetMemberNumber,
                resolution = resolvedText.resolution,
                replyToId = bcReplyIdFromDictionaryOrNull(message.dictionaryJson),
                raw = BcRawMessage(
                    sourceEvent = "ChatRoomMessage",
                    wireType = message.wireType,
                    payloadJson = rawJson,
                    dictionaryJson = message.dictionaryJson,
                    roomName = roomName,
                ),
            )
        }

        is BcInboundPacket.AccountBeep -> {
            if (!beep.beepType.isNullOrEmpty()) return null
            if (beep.message.isJsonDataBeep()) return null

            val senderIsSelf = beep.memberNumber == ownerMemberNumber
            BcMessage(
                id = BcMessageId(beep.clientMsgId ?: packetId),
                conversationId = conversationId ?: BcConversationId("message:${beep.memberNumber}"),
                address = BcMessageAddress.Direct(beep.memberNumber),
                sender = BcSender(
                    memberNumber = beep.memberNumber,
                    displayName = beep.memberName ?: "#${beep.memberNumber}",
                    isSelf = senderIsSelf,
                ),
                kind = BcMessageKind.Beep,
                transport = BcMessageTransport.AccountBeep,
                text = beep.message ?: "Beep",
                timestamp = Instant.ofEpochMilli(receivedAtMillis),
                delivery = DeliveryState.Sent,
                visibility = BcMessageVisibility.Visible,
                notificationPolicy = if (senderIsSelf) BcNotificationPolicy.None else BcNotificationPolicy.DirectMessage,
                targetMemberNumber = ownerMemberNumber,
                raw = BcRawMessage(
                    sourceEvent = "AccountBeep",
                    wireType = "AccountBeep",
                    beepType = beep.beepType,
                    payloadJson = rawJson,
                ),
            )
        }
    }
}

private fun String?.isJsonDataBeep(): Boolean {
    val content = this?.trim() ?: return false
    if (!content.startsWith("{") || !content.endsWith("}")) return false
    return runCatching {
        JsonReader(StringReader(content)).use { reader ->
            reader.strictness = Strictness.STRICT
            JsonParser.parseReader(reader).isJsonObject && reader.peek() == JsonToken.END_DOCUMENT
        }
    }.getOrDefault(false)
}

private fun String.withOriginal(dictionaryJson: String?, enabled: Boolean): String {
    if (!enabled || dictionaryJson.isNullOrBlank()) return this
    val original = runCatching {
        val entries = JSONArray(dictionaryJson)
        (0 until entries.length())
            .asSequence()
            .mapNotNull { index -> entries.optJSONObject(index)?.optString("Original") }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull() ?: return this
    return "$this\n[$original]"
}

private fun Map<Int, String>.withOwner(ownerMemberNumber: Int, ownerDisplayName: String?): Map<Int, String> {
    if (ownerDisplayName.isNullOrBlank() || containsKey(ownerMemberNumber)) return this
    return this + (ownerMemberNumber to ownerDisplayName)
}

data class BcInboundIgnored(
    val packetId: String,
    val senderMemberNumber: Int,
) : BcInboundProcessingResult

data class BcInboundFriendRequest(
    val senderMemberNumber: Int,
    val receivedAtMillis: Long,
)

private fun BcInboundPacket.senderMemberNumber(): Int? =
    when (this) {
        is BcInboundPacket.ChatMessage -> message.senderMemberNumber
        is BcInboundPacket.AccountBeep -> beep.memberNumber
    }

private fun BcInboundPacket.toFriendRequestOrNull(ownerMemberNumber: Int?): BcInboundFriendRequest? {
    val chat = (this as? BcInboundPacket.ChatMessage)?.message ?: return null
    if (!chat.wireType.equals("Hidden", ignoreCase = true)) return null
    if (chat.content != "ChatRoomFriendRequestAdd") return null
    if (ownerMemberNumber == null || chat.senderMemberNumber == ownerMemberNumber) return null
    if (chat.targetMemberNumber != null && chat.targetMemberNumber != ownerMemberNumber) return null
    return BcInboundFriendRequest(
        senderMemberNumber = chat.senderMemberNumber,
        receivedAtMillis = receivedAtMillis,
    )
}
