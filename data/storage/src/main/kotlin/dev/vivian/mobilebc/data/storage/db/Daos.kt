package dev.vivian.mobilebc.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query(
        """
        SELECT
            c.ownerMemberNumber AS ownerMemberNumber,
            c.conversationId AS conversationId,
            c.type AS type,
            CASE
                WHEN c.type = 'Direct' THEN COALESCE(
                    NULLIF(TRIM(cp.nickname), ''),
                    NULLIF(TRIM(cp.name), ''),
                    '#' || SUBSTR(c.conversationId, 9)
                )
                ELSE c.title
            END AS title,
            COALESCE(
                (
                    SELECT m.text
                    FROM messages AS m
                    WHERE m.ownerMemberNumber = c.ownerMemberNumber
                      AND m.conversationId = c.conversationId
                      AND m.visibility IN ('Visible', 'LocalOnly')
                    ORDER BY m.timestampMillis DESC, m.messageId DESC
                    LIMIT 1
                ),
                c.subtitle
            ) AS subtitle,
            c.pinned AS pinned,
            c.muted AS muted,
            (
                SELECT COUNT(*)
                FROM messages AS m
                WHERE m.ownerMemberNumber = c.ownerMemberNumber
                  AND m.conversationId = c.conversationId
                  AND m.senderIsSelf = 0
                  AND m.visibility = 'Visible'
                  AND (
                      rc.ownerMemberNumber IS NULL
                      OR m.timestampMillis > rc.readThroughTimestampMillis
                      OR (
                          m.timestampMillis = rc.readThroughTimestampMillis
                          AND m.messageId > rc.readThroughMessageId
                      )
                  )
            ) AS unreadCount,
            COALESCE(
                (
                    SELECT m.timestampMillis
                    FROM messages AS m
                    WHERE m.ownerMemberNumber = c.ownerMemberNumber
                      AND m.conversationId = c.conversationId
                      AND m.visibility IN ('Visible', 'LocalOnly')
                    ORDER BY m.timestampMillis DESC, m.messageId DESC
                    LIMIT 1
                ),
                c.updatedAtMillis
            ) AS updatedAtMillis,
            COALESCE(
                (
                    SELECT m.messageId
                    FROM messages AS m
                    WHERE m.ownerMemberNumber = c.ownerMemberNumber
                      AND m.conversationId = c.conversationId
                      AND m.visibility IN ('Visible', 'LocalOnly')
                    ORDER BY m.timestampMillis DESC, m.messageId DESC
                    LIMIT 1
                ),
                c.lastMessageId
            ) AS lastMessageId,
            c.roomName AS roomName,
            c.roomDescription AS roomDescription,
            c.roomMemberCount AS roomMemberCount,
            c.roomMemberLimit AS roomMemberLimit,
            c.roomIsLocked AS roomIsLocked,
            c.roomLanguage AS roomLanguage,
            c.roomMapType AS roomMapType,
            c.roomMembersJson AS roomMembersJson,
            c.roomUpdatedAtMillis AS roomUpdatedAtMillis
        FROM conversations AS c
        LEFT JOIN message_read_cursors AS rc
          ON rc.ownerMemberNumber = c.ownerMemberNumber
         AND rc.conversationId = c.conversationId
        LEFT JOIN contact_profiles AS cp
          ON c.type = 'Direct'
         AND cp.ownerMemberNumber = c.ownerMemberNumber
         AND cp.memberNumber = CAST(SUBSTR(c.conversationId, 9) AS INTEGER)
        WHERE c.ownerMemberNumber = :ownerMemberNumber
        ORDER BY c.pinned DESC,
            COALESCE(
                (
                    SELECT m.timestampMillis
                    FROM messages AS m
                    WHERE m.ownerMemberNumber = c.ownerMemberNumber
                      AND m.conversationId = c.conversationId
                      AND m.visibility IN ('Visible', 'LocalOnly')
                    ORDER BY m.timestampMillis DESC, m.messageId DESC
                    LIMIT 1
                ),
                c.updatedAtMillis,
                0
            ) DESC,
            c.conversationId ASC
        LIMIT :limit
        """,
    )
    fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE ownerMemberNumber = :ownerMemberNumber AND conversationId = :conversationId
        LIMIT 1
        """,
    )
    suspend fun findConversation(ownerMemberNumber: Int, conversationId: String): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE ownerMemberNumber = :ownerMemberNumber AND roomName = :roomName
        LIMIT 1
        """,
    )
    suspend fun findRoomConversation(ownerMemberNumber: Int, roomName: String): ConversationEntity?
}

@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query(
        """
        SELECT * FROM messages
        WHERE ownerMemberNumber = :ownerMemberNumber AND conversationId = :conversationId
          AND messageId = :messageId
        LIMIT 1
        """,
    )
    suspend fun find(ownerMemberNumber: Int, conversationId: String, messageId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE ownerMemberNumber = :ownerMemberNumber AND conversationId = :conversationId
          AND visibility IN ('Visible', 'LocalOnly')
        ORDER BY timestampMillis DESC, messageId DESC
        LIMIT :limit
        """,
    )
    fun observeMessages(ownerMemberNumber: Int, conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND (conversationId = :conversationId OR (
              :roomName IS NOT NULL AND rawRoomName = :roomName
              AND kind = 'Whisper' AND transport = 'RoomWhisper'
              AND (senderMemberNumber = :ownerMemberNumber OR targetMemberNumber = :ownerMemberNumber
                   OR (senderIsSelf = 0 AND targetMemberNumber IS NULL))
          ))
          AND visibility IN ('Visible', 'LocalOnly')
          AND (:query = '' OR instr(lower(text), lower(:query)) > 0)
          AND (:fromMillis IS NULL OR timestampMillis >= :fromMillis)
          AND (:untilMillis IS NULL OR timestampMillis < :untilMillis)
        ORDER BY
          CASE WHEN :oldestFirst THEN timestampMillis END ASC,
          CASE WHEN :oldestFirst THEN messageId END ASC,
          CASE WHEN NOT :oldestFirst THEN timestampMillis END DESC,
          CASE WHEN NOT :oldestFirst THEN messageId END DESC,
          conversationId ASC
        LIMIT :limit
        """,
    )
    fun observeHistory(
        ownerMemberNumber: Int,
        conversationId: String,
        roomName: String?,
        query: String,
        fromMillis: Long?,
        untilMillis: Long?,
        oldestFirst: Boolean,
        limit: Int,
    ): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE ownerMemberNumber = :ownerMemberNumber AND rawRoomName = :roomName
          AND kind = 'Whisper' AND transport = 'RoomWhisper'
          AND visibility IN ('Visible', 'LocalOnly')
          AND (senderMemberNumber = :ownerMemberNumber OR targetMemberNumber = :ownerMemberNumber
               OR (senderIsSelf = 0 AND targetMemberNumber IS NULL))
        ORDER BY timestampMillis DESC, messageId DESC
        LIMIT :limit
        """,
    )
    fun observeRoomWhispers(ownerMemberNumber: Int, roomName: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        """
        UPDATE messages
        SET delivery = :delivery
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND messageId = :messageId
        """,
    )
    suspend fun updateDelivery(ownerMemberNumber: Int, messageId: String, delivery: String)

    @Query(
        """
        UPDATE messages
        SET delivery = :delivery, visibility = :visibility
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND messageId = :messageId
        """,
    )
    suspend fun markFailedVisible(
        ownerMemberNumber: Int,
        messageId: String,
        delivery: String,
        visibility: String,
    )
}

@Dao
internal interface MessageReadCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageReadCursorEntity)

    @Query(
        """
        SELECT * FROM message_read_cursors
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND conversationId = :conversationId
        LIMIT 1
        """,
    )
    suspend fun find(ownerMemberNumber: Int, conversationId: String): MessageReadCursorEntity?
}

@Dao
internal interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OutboxMessageEntity)

    @Query(
        """
        SELECT * FROM outbox_messages
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND state = 'Pending'
          AND createdAtMillis <= :nowMillis
          AND (nextAttemptAtMillis IS NULL OR nextAttemptAtMillis <= :nowMillis)
        ORDER BY createdAtMillis ASC
        LIMIT :limit
        """,
    )
    suspend fun loadPending(ownerMemberNumber: Int, nowMillis: Long, limit: Int): List<OutboxMessageEntity>

    @Query(
        """
        SELECT * FROM outbox_messages
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND clientMsgId = :clientMsgId
          AND state = 'Pending'
          AND createdAtMillis <= :nowMillis
          AND (nextAttemptAtMillis IS NULL OR nextAttemptAtMillis <= :nowMillis)
        LIMIT 1
        """,
    )
    suspend fun findDispatchable(ownerMemberNumber: Int, clientMsgId: String, nowMillis: Long): OutboxMessageEntity?

    @Query(
        """
        SELECT * FROM outbox_messages
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND clientMsgId = :clientMsgId
        LIMIT 1
        """,
    )
    suspend fun find(ownerMemberNumber: Int, clientMsgId: String): OutboxMessageEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM outbox_messages
            WHERE ownerMemberNumber = :ownerMemberNumber AND state = 'Submitted'
        )
        """,
    )
    suspend fun hasPendingConfirmation(ownerMemberNumber: Int): Boolean

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Sending', attempts = attempts + 1, updatedAtMillis = :updatedAtMillis, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Pending'
          AND (nextAttemptAtMillis IS NULL OR nextAttemptAtMillis <= :updatedAtMillis)
        """,
    )
    suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Pending', attempts = MAX(attempts - 1, 0), updatedAtMillis = :updatedAtMillis,
            lastError = NULL, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Sending'
        """,
    )
    suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Submitted', updatedAtMillis = :updatedAtMillis, lastError = NULL, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Sending'
        """,
    )
    suspend fun markSubmitted(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Confirmed', updatedAtMillis = :updatedAtMillis, lastError = NULL, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber
          AND clientMsgId = :clientMsgId
        """,
    )
    suspend fun markConfirmed(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Pending', updatedAtMillis = :updatedAtMillis, lastError = :error, nextAttemptAtMillis = :nextAttemptAtMillis
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Sending'
        """,
    )
    suspend fun markRetryableFailure(ownerMemberNumber: Int, clientMsgId: String, error: String?, nextAttemptAtMillis: Long, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Failed', updatedAtMillis = :updatedAtMillis, lastError = :error, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Sending'
        """,
    )
    suspend fun markTerminalFailure(ownerMemberNumber: Int, clientMsgId: String, error: String?, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Failed', attempts = MAX(attempts - 1, 0), updatedAtMillis = :updatedAtMillis,
            lastError = :error, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Sending'
        """,
    )
    suspend fun markRejected(ownerMemberNumber: Int, clientMsgId: String, error: String?, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Failed', updatedAtMillis = :updatedAtMillis, lastError = :error, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND clientMsgId = :clientMsgId AND state = 'Pending'
        """,
    )
    suspend fun markInvalidPending(ownerMemberNumber: Int, clientMsgId: String, error: String, updatedAtMillis: Long): Int

    @Query(
        """
        SELECT clientMsgId FROM outbox_messages
        WHERE ownerMemberNumber = :ownerMemberNumber AND state = 'Submitted' AND updatedAtMillis <= :cutoffUpdatedAtMillis
        """,
    )
    suspend fun loadStaleSubmittedClientMsgIds(ownerMemberNumber: Int, cutoffUpdatedAtMillis: Long): List<String>

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Failed', updatedAtMillis = :updatedAtMillis, lastError = :error, nextAttemptAtMillis = NULL
        WHERE ownerMemberNumber = :ownerMemberNumber AND state = 'Submitted' AND updatedAtMillis <= :cutoffUpdatedAtMillis
        """,
    )
    suspend fun markStaleSubmittedFailed(ownerMemberNumber: Int, cutoffUpdatedAtMillis: Long, error: String, updatedAtMillis: Long)

    @Query(
        """
        SELECT clientMsgId FROM outbox_messages
        WHERE ownerMemberNumber = :ownerMemberNumber AND state = 'Sending' AND updatedAtMillis <= :cutoffUpdatedAtMillis
        """,
    )
    suspend fun loadStaleSendingClientMsgIds(ownerMemberNumber: Int, cutoffUpdatedAtMillis: Long): List<String>

    @Query(
        """
        UPDATE outbox_messages
        SET state = 'Pending', updatedAtMillis = :updatedAtMillis, lastError = :error, nextAttemptAtMillis = :nextAttemptAtMillis
        WHERE ownerMemberNumber = :ownerMemberNumber AND state = 'Sending' AND updatedAtMillis <= :cutoffUpdatedAtMillis
        """,
    )
    suspend fun markStaleSendingRetryable(ownerMemberNumber: Int, cutoffUpdatedAtMillis: Long, error: String, nextAttemptAtMillis: Long, updatedAtMillis: Long)
}

@Dao
internal interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: InboxEventEntity): Long
}

@Dao
internal interface SyncCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncCursorEntity)
}

@Dao
internal interface DeadLetterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeadLetterEntity)
}

@Dao
internal interface ContactProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ContactProfileEntity>)

    @Query(
        """
        SELECT * FROM contact_profiles
        WHERE ownerMemberNumber = :ownerMemberNumber AND memberNumber IN (:memberNumbers)
        """,
    )
    suspend fun findProfiles(ownerMemberNumber: Int, memberNumbers: List<Int>): List<ContactProfileEntity>

    @Query("SELECT * FROM contact_profiles WHERE ownerMemberNumber = :ownerMemberNumber AND memberNumber IN (:memberNumbers)")
    fun observeProfiles(ownerMemberNumber: Int, memberNumbers: List<Int>): Flow<List<ContactProfileEntity>>

    @Query(
        """
        SELECT * FROM contact_profiles
        WHERE ownerMemberNumber = :ownerMemberNumber AND memberNumber = :memberNumber
        LIMIT 1
        """,
    )
    fun observeProfile(ownerMemberNumber: Int, memberNumber: Int): kotlinx.coroutines.flow.Flow<ContactProfileEntity?>
}
