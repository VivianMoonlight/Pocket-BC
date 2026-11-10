package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcNotificationPolicy
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

class BcSessionMessageCoordinator(
    val roomConversationId: BcConversationId = BcConversationId("room:current"),
    val systemConversationId: BcConversationId = BcConversationId("system"),
) {
    fun roomConversationId(room: BcRoom): BcConversationId =
        roomConversationId(room.name.value)

    fun roomConversationId(roomName: String): BcConversationId = bcRoomConversationId(roomName)

    fun currentRoomConversationId(currentRoom: BcRoom?): BcConversationId? =
        currentRoom?.let(::roomConversationId)

    fun isRoomConversation(conversationId: BcConversationId): Boolean =
        conversationId == roomConversationId || conversationId.value.startsWith("room:")

    fun isCurrentRoomConversation(conversationId: BcConversationId, currentRoom: BcRoom?): Boolean =
        currentRoomConversationId(currentRoom) == conversationId

    fun outboundMessage(
        clientMsgId: String,
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        kind: BcMessageKind,
        text: String,
        targetMemberNumber: Int?,
        createdAtMillis: Long = System.currentTimeMillis(),
        replyToId: String? = null,
        replyRoomName: String? = null,
        originRoomName: String? = null,
    ): BcOutboundMessage =
        BcOutboundMessage(
            clientMsgId = clientMsgId,
            ownerMemberNumber = ownerMemberNumber,
            conversationId = conversationId,
            kind = kind,
            transport = kind.outboundTransport(),
            text = text,
            targetMemberNumber = targetMemberNumber,
            createdAtMillis = createdAtMillis,
            replyToId = replyToId,
            replyRoomName = replyRoomName,
            originRoomName = originRoomName,
        )

    fun localOutgoingMessage(
        clientMsgId: String,
        account: BcAccount?,
        conversationId: BcConversationId,
        kind: BcMessageKind,
        text: String,
        targetMemberNumber: Int?,
        replyToId: String? = null,
        roomName: String? = null,
    ): BcMessage {
        val transport = kind.outboundTransport()
        return BcMessage(
            id = BcMessageId(clientMsgId),
            conversationId = conversationId,
            sender = BcSender(
                memberNumber = account?.memberNumber,
                displayName = account?.displayName ?: "Me",
                isSelf = true,
            ),
            kind = kind,
            address = if (isRoomConversation(conversationId) && targetMemberNumber == null) {
                BcMessageAddress.CurrentRoom
            } else {
                targetMemberNumber?.let(BcMessageAddress::Direct) ?: BcMessageAddress.System
            },
            transport = transport,
            text = text,
            timestamp = Instant.now(),
            delivery = DeliveryState.Sending,
            visibility = BcMessageVisibility.Visible,
            notificationPolicy = BcNotificationPolicy.None,
            targetMemberNumber = targetMemberNumber,
            resolution = BcMessageResolution.Plain,
            replyToId = replyToId,
            raw = BcRawMessage(
                sourceEvent = LOCAL_OUTGOING_SOURCE,
                wireType = kind.name,
                roomName = roomName,
                dictionaryJson = if (transport == BcMessageTransport.RoomChat || transport == BcMessageTransport.RoomWhisper) {
                    JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("Tag", "MsgId")
                            addProperty("MsgId", clientMsgId)
                        })
                        replyToId?.let { replyId ->
                            add(JsonObject().apply {
                                addProperty("Tag", "ReplyId")
                                addProperty("ReplyId", replyId)
                            })
                        }
                    }.toString()
                } else {
                    null
                },
            ),
        )
    }

    fun systemLoginConversation(displayName: String): BcConversation =
        BcConversation(
            id = systemConversationId,
            type = ConversationType.System,
            title = "System",
            subtitle = "Logged in as $displayName.",
            updatedAt = Instant.now(),
        )

    fun roomConversation(room: BcRoom): BcConversation =
        BcConversation(
            id = roomConversationId(room),
            type = ConversationType.CurrentRoom,
            title = room.name.value,
            subtitle = "${room.memberCount}/${room.memberLimit} members",
            pinned = true,
            updatedAt = Instant.now(),
        )

    fun sameRoomDirectConversations(members: List<BcMember>): List<BcConversation> =
        members.filterNot { it.isSelf }.map { member ->
            BcConversation(
                id = directConversationId(member.memberNumber),
                type = ConversationType.Direct,
                title = member.name,
                subtitle = "Same room, whisper available",
                updatedAt = null,
                availability = setOf(ConversationAvailability.SameRoom),
            )
        }

    fun friendConversations(memberNumbers: Set<Int>, selfMemberNumber: Int?): List<BcConversation> =
        memberNumbers.filterNot { it == selfMemberNumber }.map { memberNumber ->
            BcConversation(
                id = directConversationId(memberNumber),
                type = ConversationType.Direct,
                title = "#$memberNumber",
                subtitle = "Friend",
                updatedAt = null,
                availability = setOf(ConversationAvailability.OnlineFriend),
            )
        }

    fun mergeConversation(rows: List<BcConversation>, conversation: BcConversation): List<BcConversation> {
        val existing = rows.firstOrNull { it.id == conversation.id }
        val merged = if (existing != null) {
            val incomingUpdatedAt = conversation.updatedAt
            val existingUpdatedAt = existing.updatedAt
            val incomingIsDisplayCurrent = when {
                incomingUpdatedAt == null -> false
                existingUpdatedAt == null -> true
                else -> !incomingUpdatedAt.isBefore(existingUpdatedAt)
            }
            val displayConversation = if (incomingIsDisplayCurrent) conversation else existing
            displayConversation.copy(
                unreadCount = existing.unreadCount + conversation.unreadCount,
                muted = existing.muted,
                pinned = existing.pinned,
                hasMessages = existing.hasMessages || conversation.hasMessages,
            )
        } else {
            conversation
        }
        return (listOf(merged) + rows.filterNot { it.id == conversation.id }).sortedConversations()
    }

    fun mergePresenceConversation(rows: List<BcConversation>, conversation: BcConversation): List<BcConversation> {
        val existing = rows.firstOrNull { it.id == conversation.id }
        val merged = if (existing == null) {
            conversation
        } else {
            existing.copy(
                title = if (existing.hasMessages) {
                    existing.title
                } else {
                    conversation.title.ifBlank { existing.title }
                },
                subtitle = if (existing.hasMessages) {
                    existing.subtitle
                } else {
                    conversation.subtitle.ifBlank { existing.subtitle }
                },
                availability = existing.availability + conversation.availability,
                onlineRoomName = conversation.onlineRoomName ?: existing.onlineRoomName,
                hasMessages = existing.hasMessages,
            )
        }
        return (listOf(merged) + rows.filterNot { it.id == conversation.id }).sortedConversations()
    }

    fun addFriendConversationIfMissing(rows: List<BcConversation>, conversation: BcConversation): List<BcConversation> =
        if (rows.any { it.id == conversation.id }) {
            rows
        } else {
            (listOf(conversation) + rows).sortedConversations()
        }

    fun directConversationId(memberNumber: Int): BcConversationId =
        BcConversationId("message:$memberNumber")

    fun directMemberNumber(conversation: BcConversation): Int? =
        conversation.id.value.removePrefix("message:")
            .takeIf { it != conversation.id.value }
            ?.toIntOrNull()

    private fun isLocalOutgoing(message: BcMessage): Boolean =
        message.raw?.sourceEvent == LOCAL_OUTGOING_SOURCE

    fun deduplicateMessages(messages: List<BcMessage>): List<BcMessage> {
        val ordered = messages.inMessageOrder()
        // Persisted inbound room history contains no optimistic rows in the common case. Avoid
        // the pairwise local-echo scan for every database invalidation when there is nothing to
        // reconcile.
        return if (ordered.none(::isLocalOutgoing)) ordered else ordered.deduplicateLocalEchoes()
    }

    private fun compareMessageOrder(left: BcMessage, right: BcMessage): Int {
        val timestampComparison = left.timestamp.compareTo(right.timestamp)
        return if (timestampComparison != 0) {
            timestampComparison
        } else {
            left.id.value.compareTo(right.id.value)
        }
    }

    private fun List<BcConversation>.sortedConversations(): List<BcConversation> =
        sortedWith { left, right ->
            when {
                left.pinned != right.pinned -> if (left.pinned) -1 else 1
                else -> (right.updatedAt ?: Instant.EPOCH).compareTo(left.updatedAt ?: Instant.EPOCH)
            }
        }

    private fun List<BcMessage>.inMessageOrder(): List<BcMessage> {
        for (index in 1 until size) {
            if (compareMessageOrder(this[index - 1], this[index]) > 0) {
                return sortedWith(::compareMessageOrder)
            }
        }
        return this
    }

    private fun List<BcMessage>.deduplicateLocalEchoes(): List<BcMessage> {
        val output = mutableListOf<BcMessage>()
        for (message in this) {
            val duplicateIndex = output.indexOfLast { existing -> existing.isLocalEchoDuplicateOf(message) }
            if (duplicateIndex >= 0) {
                val existing = output[duplicateIndex]
                output[duplicateIndex] = mergeLocalEchoDuplicate(existing, message)
            } else {
                output += message
            }
        }
        return output
    }

    private fun mergeLocalEchoDuplicate(existing: BcMessage, message: BcMessage): BcMessage =
        when {
            isLocalOutgoing(existing) && !isLocalOutgoing(message) -> message
            !isLocalOutgoing(existing) && isLocalOutgoing(message) -> existing
            message.delivery == DeliveryState.Sent -> message
            else -> existing
        }

    private fun BcMessage.isLocalEchoDuplicateOf(other: BcMessage): Boolean {
        if (isLocalOutgoing(this) == isLocalOutgoing(other)) return false
        if (!sender.isSelf || !other.sender.isSelf) return false
        if (conversationId != other.conversationId) return false
        if (kind != other.kind || transport != other.transport) return false
        val millis = kotlin.math.abs(timestamp.toEpochMilli() - other.timestamp.toEpochMilli())
        if (millis > LOCAL_ECHO_DEDUP_WINDOW_MS) return false

        val leftClientMsgId = localEchoClientMsgIdOrNull()
        val rightClientMsgId = other.localEchoClientMsgIdOrNull()
        if (leftClientMsgId != null || rightClientMsgId != null) {
            return leftClientMsgId != null &&
                rightClientMsgId != null &&
                leftClientMsgId.sameWireToken(rightClientMsgId)
        }

        if (text == other.text && targetMemberNumber == other.targetMemberNumber) return true

        val leftKey = activityWireKeyOrNull() ?: return false
        val rightKey = other.activityWireKeyOrNull() ?: return false
        if (!leftKey.matches(rightKey)) return false

        val leftTarget = targetMemberNumber
        val rightTarget = other.targetMemberNumber
        return leftTarget == null || rightTarget == null || leftTarget == rightTarget
    }


    private fun BcMessage.localEchoClientMsgIdOrNull(): String? = when {
        isLocalOutgoing(this) -> id.value.trim()
            .takeIf { it.isNotEmpty() }
            ?.takeUnless { it.startsWith("local:", ignoreCase = true) }
        else -> raw?.dictionaryJson.clientMsgIdFromDictionaryOrNull()
    }

    private fun String?.clientMsgIdFromDictionaryOrNull(): String? {
        if (isNullOrBlank()) return null
        return MSG_ID_REGEX.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun BcMessage.activityWireKeyOrNull(): ActivityWireKey? {
        if (kind != BcMessageKind.Activity) return null
        val wireKey = resolution.translationKeyOrNull()?.trim().orEmpty()
        return wireKey.activityWireKeyFromContentOrNull()
    }

    private fun BcMessageResolution.translationKeyOrNull(): String? = when (this) {
        is BcMessageResolution.Resolved -> key
        is BcMessageResolution.NeedsTranslation -> key
        is BcMessageResolution.Failed -> key
        BcMessageResolution.Plain -> null
    }

    private fun String.activityWireKeyFromContentOrNull(): ActivityWireKey? {
        val stripped = when {
            startsWith(ACTIVITY_TAG_OTHER_PREFIX + "-") -> removePrefix(ACTIVITY_TAG_OTHER_PREFIX + "-")
            startsWith(ACTIVITY_TAG_SELF_PREFIX + "-") -> removePrefix(ACTIVITY_TAG_SELF_PREFIX + "-")
            else -> return null
        }
        val separator = stripped.indexOf('-')
        if (separator <= 0 || separator >= stripped.lastIndex) return null
        val group = stripped.substring(0, separator).trim()
        val activityName = stripped.substring(separator + 1).trim()
        if (group.isBlank() || activityName.isBlank()) return null
        return ActivityWireKey(group = group, activityName = activityName)
    }

    private fun ActivityWireKey.matches(other: ActivityWireKey): Boolean =
        group.sameWireToken(other.group) && activityName.sameWireToken(other.activityName)

    private fun String.sameWireToken(other: String): Boolean =
        trim().equals(other.trim(), ignoreCase = true)

    private data class ActivityWireKey(
        val group: String,
        val activityName: String,
    )

    private fun BcMessageKind.outboundTransport(): BcMessageTransport = when (this) {
        BcMessageKind.Whisper -> BcMessageTransport.RoomWhisper
        BcMessageKind.Beep -> BcMessageTransport.AccountBeep
        BcMessageKind.System,
        BcMessageKind.Error,
        -> BcMessageTransport.LocalSystem

        else -> BcMessageTransport.RoomChat
    }

    private companion object {
        private const val LOCAL_OUTGOING_SOURCE = "LocalOutgoing"
        private const val LOCAL_ECHO_DEDUP_WINDOW_MS = 2 * 60 * 1000L
        private const val ACTIVITY_TAG_OTHER_PREFIX = "ChatOther"
        private const val ACTIVITY_TAG_SELF_PREFIX = "ChatSelf"
        private val MSG_ID_REGEX = Regex(""""MsgId"\s*:\s*"([^"]+)"""")
    }
}
