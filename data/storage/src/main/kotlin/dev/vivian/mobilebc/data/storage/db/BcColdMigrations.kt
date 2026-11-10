package dev.vivian.mobilebc.data.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contact_profiles ADD COLUMN lianUpdatedAtMillis INTEGER")
    }
}

internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contact_profiles ADD COLUMN labelColor TEXT")
        database.execSQL("ALTER TABLE contact_profiles ADD COLUMN avatarUrl TEXT")
        database.execSQL("ALTER TABLE contact_profiles ADD COLUMN signature TEXT")
    }
}

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_read_cursors` (
                `ownerMemberNumber` INTEGER NOT NULL,
                `conversationId` TEXT NOT NULL,
                `readThroughTimestampMillis` INTEGER NOT NULL,
                `readThroughMessageId` TEXT NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`ownerMemberNumber`, `conversationId`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_read_cursors_ownerMemberNumber` ON `message_read_cursors` (`ownerMemberNumber`)",
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_messages_unread_cursor`
            ON `messages` (`ownerMemberNumber`, `conversationId`, `senderIsSelf`, `visibility`, `timestampMillis`, `messageId`)
            """.trimIndent(),
        )

        val conversations = mutableListOf<LegacyConversationUnread>()
        database.query(
            "SELECT ownerMemberNumber, conversationId, unreadCount, COALESCE(updatedAtMillis, 0) FROM conversations",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                conversations += LegacyConversationUnread(
                    ownerMemberNumber = cursor.getInt(0),
                    conversationId = cursor.getString(1),
                    unreadCount = cursor.getInt(2).coerceAtLeast(0),
                    updatedAtMillis = cursor.getLong(3),
                )
            }
        }

        conversations.forEach { conversation ->
            val boundary = database.query(
                """
                SELECT timestampMillis, messageId
                FROM messages
                WHERE ownerMemberNumber = ?
                  AND conversationId = ?
                  AND senderIsSelf = 0
                  AND visibility = 'Visible'
                ORDER BY timestampMillis DESC, messageId DESC
                LIMIT 1 OFFSET ?
                """.trimIndent(),
                arrayOf(
                    conversation.ownerMemberNumber,
                    conversation.conversationId,
                    conversation.unreadCount,
                ),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    MessageReadOrder(
                        timestampMillis = cursor.getLong(0),
                        messageId = cursor.getString(1),
                    )
                } else {
                    MessageReadOrder.BeforeAll
                }
            }

            database.execSQL(
                """
                INSERT OR REPLACE INTO message_read_cursors (
                    ownerMemberNumber,
                    conversationId,
                    readThroughTimestampMillis,
                    readThroughMessageId,
                    updatedAtMillis
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    conversation.ownerMemberNumber,
                    conversation.conversationId,
                    boundary.timestampMillis,
                    boundary.messageId,
                    conversation.updatedAtMillis,
                ),
            )
        }
    }
}


internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            UPDATE outbox_messages
            SET state = CASE
                WHEN transport = 'AccountBeep' THEN 'Confirmed'
                WHEN EXISTS (
                    SELECT 1
                    FROM messages AS m
                    WHERE m.ownerMemberNumber = outbox_messages.ownerMemberNumber
                      AND m.messageId = outbox_messages.clientMsgId
                      AND m.delivery = 'Sent'
                ) THEN 'Confirmed'
                ELSE 'Submitted'
            END
            WHERE state = 'Sent'
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        listOf(
            "accountName" to "TEXT",
            "nickname" to "TEXT",
            "title" to "TEXT",
            "description" to "TEXT",
            "pronouns" to "TEXT",
            "ownerName" to "TEXT",
            "loverNamesJson" to "TEXT",
            "snapshotUpdatedAtMillis" to "INTEGER",
        ).forEach { (name, type) ->
            database.execSQL("ALTER TABLE contact_profiles ADD COLUMN `$name` $type")
        }
    }
}

internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE messages ADD COLUMN `resolutionType` TEXT NOT NULL DEFAULT 'Plain'")
        database.execSQL("ALTER TABLE messages ADD COLUMN `resolutionKey` TEXT")
        database.execSQL("ALTER TABLE messages ADD COLUMN `resolutionText` TEXT")
    }
}

internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE messages ADD COLUMN `rawRoomName` TEXT")
    }
}

internal data class MessageReadOrder(
    val timestampMillis: Long,
    val messageId: String,
) : Comparable<MessageReadOrder> {
    override fun compareTo(other: MessageReadOrder): Int =
        compareValuesBy(this, other, MessageReadOrder::timestampMillis, MessageReadOrder::messageId)

    companion object {
        val BeforeAll = MessageReadOrder(Long.MIN_VALUE, "")
    }
}

private data class LegacyConversationUnread(
    val ownerMemberNumber: Int,
    val conversationId: String,
    val unreadCount: Int,
    val updatedAtMillis: Long,
)

internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        listOf("ownerRelationJson", "loverRelationsJson", "portraitJson").forEach {
            database.execSQL("ALTER TABLE contact_profiles ADD COLUMN `$it` TEXT")
        }
    }
}

internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contact_profiles ADD COLUMN characterName TEXT")
    }
}
