package dev.vivian.mobilebc.data.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageResolutionPersistenceTest {
    @Test
    fun allResolutionVariantsRoundTripWithoutChangingDisplayTextOrRawPacket() {
        val states = listOf(
            BcMessageResolution.Plain,
            BcMessageResolution.NeedsTranslation("ActivityKey"),
            BcMessageResolution.Resolved("ActivityKey", "Resolved text"),
            BcMessageResolution.Failed("ActivityKey", "Fallback text"),
        )

        states.forEach { resolution ->
            val original = message(resolution)

            assertEquals(original, original.toEntity(9999).toMessageOrNull())
        }
    }

    @Test
    fun migrationAddsResolutionMetadataWithoutRewritingExistingMessages() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected migration operation: ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        MIGRATION_12_13.migrate(database)

        assertEquals(12, MIGRATION_12_13.startVersion)
        assertEquals(13, MIGRATION_12_13.endVersion)
        assertEquals(3, statements.size)
        assertTrue(statements.all { it.startsWith("ALTER TABLE messages ADD COLUMN") })
        assertTrue(statements.any { it.contains("`resolutionType` TEXT NOT NULL DEFAULT 'Plain'") })
        assertTrue(statements.any { it.contains("`resolutionKey` TEXT") })
        assertTrue(statements.any { it.contains("`resolutionText` TEXT") })
    }

    private fun message(resolution: BcMessageResolution) = BcMessage(
        id = BcMessageId("message-1"),
        conversationId = BcConversationId("room:current"),
        sender = BcSender(1001, "Alice"),
        kind = BcMessageKind.Activity,
        text = "Displayed text\n[Original text]",
        timestamp = Instant.ofEpochMilli(1_000),
        resolution = resolution,
        raw = BcRawMessage(
            sourceEvent = "ChatRoomMessage",
            wireType = "Activity",
            payloadJson = """{"Sender":1001,"Type":"Activity","Content":"ActivityKey"}""",
            dictionaryJson = """[{"Original":"Original text"}]""",
        ),
    )
}
