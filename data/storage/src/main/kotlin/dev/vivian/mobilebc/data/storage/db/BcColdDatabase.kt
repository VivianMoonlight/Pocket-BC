package dev.vivian.mobilebc.data.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageReadCursorEntity::class,
        OutboxMessageEntity::class,
        InboxEventEntity::class,
        SyncCursorEntity::class,
        DeadLetterEntity::class,
        ContactProfileEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
internal abstract class BcColdDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun messageReadCursorDao(): MessageReadCursorDao
    abstract fun outboxDao(): OutboxDao
    abstract fun inboxDao(): InboxDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun deadLetterDao(): DeadLetterDao
    abstract fun contactProfileDao(): ContactProfileDao
}

internal fun createBcColdDatabase(context: Context): BcColdDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        BcColdDatabase::class.java,
        "mobile-bc-cold.db",
    )
        .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
        .build()
