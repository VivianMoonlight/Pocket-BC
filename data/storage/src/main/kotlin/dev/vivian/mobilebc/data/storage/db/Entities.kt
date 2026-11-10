package dev.vivian.mobilebc.data.storage

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    primaryKeys = ["ownerMemberNumber", "conversationId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
        Index(value = ["ownerMemberNumber", "updatedAtMillis"]),
    ],
)
internal data class ConversationEntity(
    val ownerMemberNumber: Int,
    val conversationId: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val pinned: Boolean,
    val muted: Boolean,
    val unreadCount: Int,
    val updatedAtMillis: Long?,
    val lastMessageId: String?,
    val roomName: String? = null,
    val roomDescription: String? = null,
    val roomMemberCount: Int? = null,
    val roomMemberLimit: Int? = null,
    val roomIsLocked: Boolean? = null,
    val roomLanguage: String? = null,
    val roomMapType: String? = null,
    val roomMembersJson: String? = null,
    val roomUpdatedAtMillis: Long? = null,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["ownerMemberNumber", "conversationId", "messageId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
        Index(value = ["ownerMemberNumber", "conversationId"]),
        Index(value = ["ownerMemberNumber", "conversationId", "timestampMillis", "messageId"]),
        Index(
            value = [
                "ownerMemberNumber",
                "conversationId",
                "senderIsSelf",
                "visibility",
                "timestampMillis",
                "messageId",
            ],
            name = "index_messages_unread_cursor",
        ),
    ],
)
internal data class MessageEntity(
    val ownerMemberNumber: Int,
    val conversationId: String,
    val messageId: String,
    val addressType: String,
    val addressMemberNumber: Int?,
    val senderMemberNumber: Int?,
    val senderName: String,
    val senderIsSelf: Boolean,
    val kind: String,
    val transport: String,
    val text: String,
    val timestampMillis: Long,
    val delivery: String,
    val visibility: String,
    val notificationPolicy: String,
    val targetMemberNumber: Int?,
    val rawSourceEvent: String?,
    val rawWireType: String?,
    val rawBeepType: String?,
    val rawPayloadJson: String?,
    val rawDictionaryJson: String?,
    @ColumnInfo(defaultValue = "'Plain'")
    val resolutionType: String = "Plain",
    val resolutionKey: String? = null,
    val resolutionText: String? = null,
    val rawRoomName: String? = null,
)



@Entity(
    tableName = "message_read_cursors",
    primaryKeys = ["ownerMemberNumber", "conversationId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
    ],
)
internal data class MessageReadCursorEntity(
    val ownerMemberNumber: Int,
    val conversationId: String,
    val readThroughTimestampMillis: Long,
    val readThroughMessageId: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "outbox_messages",
    primaryKeys = ["ownerMemberNumber", "clientMsgId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
        Index(value = ["ownerMemberNumber", "conversationId"]),
        Index(value = ["ownerMemberNumber", "state", "createdAtMillis"]),
        Index(value = ["ownerMemberNumber", "state", "nextAttemptAtMillis", "createdAtMillis"]),
    ],
)
internal data class OutboxMessageEntity(
    val ownerMemberNumber: Int,
    val clientMsgId: String,
    val conversationId: String,
    val transport: String,
    val payloadJson: String,
    val state: String,
    val attempts: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String?,
    val nextAttemptAtMillis: Long?,
)

@Entity(
    tableName = "inbox_events",
    primaryKeys = ["ownerMemberNumber", "packetId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
        Index(value = ["ownerMemberNumber", "conversationId"]),
        Index(value = ["ownerMemberNumber", "eventName"]),
        Index(value = ["ownerMemberNumber", "receivedAtMillis"]),
    ],
)
internal data class InboxEventEntity(
    val ownerMemberNumber: Int,
    val packetId: String,
    val eventName: String,
    val conversationId: String?,
    val receivedAtMillis: Long,
    val rawJson: String?,
)

@Entity(
    tableName = "sync_cursors",
    primaryKeys = ["ownerMemberNumber", "conversationId"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
    ],
)
internal data class SyncCursorEntity(
    val ownerMemberNumber: Int,
    val conversationId: String,
    val seq: Long?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "dead_letters",
    indices = [
        Index(value = ["eventName"]),
        Index(value = ["stage"]),
        Index(value = ["createdAtMillis"]),
    ],
)
internal data class DeadLetterEntity(
    @PrimaryKey val id: String,
    val stage: String,
    val eventName: String,
    val codecName: String?,
    val rawPayload: String,
    val errorCode: String,
    val errorMessage: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "contact_profiles",
    primaryKeys = ["ownerMemberNumber", "memberNumber"],
    indices = [
        Index(value = ["ownerMemberNumber"]),
        Index(value = ["ownerMemberNumber", "lastSeenAtMillis"]),
    ],
)
internal data class ContactProfileEntity(
    val ownerMemberNumber: Int,
    val memberNumber: Int,
    val name: String,
    val labelColor: String? = null,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val accountName: String? = null,
    val characterName: String? = null,
    val nickname: String? = null,
    val title: String? = null,
    val description: String? = null,
    val pronouns: String? = null,
    val statusText: String?,
    val role: String?,
    val ownerName: String? = null,
    val loverNamesJson: String? = null,
    val ownerRelationJson: String? = null,
    val loverRelationsJson: String? = null,
    val portraitJson: String? = null,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val snapshotUpdatedAtMillis: Long? = null,
    val lianUpdatedAtMillis: Long? = null,
)
