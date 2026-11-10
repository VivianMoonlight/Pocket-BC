package dev.vivian.mobilebc.data.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageReplyPersistenceTest {
    @Test
    fun nativeReplyAndWhisperRoomRoundTripWithTheRawPacket() {
        val original = whisperMessage()

        assertEquals(original, original.toEntity(10).toMessageOrNull())
    }

    @Test
    fun replyMetadataIsRestoredFromExistingDictionaryRows() {
        val original = whisperMessage()
        val row = original.toEntity(10).copy(
            rawDictionaryJson = "[{\"ReplyId\":\"historical-target\"}]",
            rawRoomName = null,
        )

        val restored = checkNotNull(row.toMessageOrNull())
        assertEquals("historical-target", restored.replyToId)
        assertNull(restored.raw?.roomName)
        assertEquals(original.text, restored.text)
        assertEquals(original.conversationId, restored.conversationId)
    }

    @Test
    fun missingDictionaryReplyMetadataDoesNotInventAReply() {
        val row = whisperMessage().toEntity(10)
        listOf(null, "[]", "[{\"MsgId\":\"native-1\"}]").forEach { dictionary ->
            val restored = checkNotNull(row.copy(rawDictionaryJson = dictionary).toMessageOrNull())

            assertNull(restored.replyToId)
        }
    }

    @Test
    fun malformedOrCoercedReplyMetadataDoesNotDiscardTheMessage() {
        val row = whisperMessage().toEntity(10)
        val dictionaries = listOf(
            "{",
            "null",
            "{\"ReplyId\":\"target\"}",
            "[\"{\\\"ReplyId\\\":\\\"target\\\"}\"]",
            "[{\"ReplyId\":1}]",
            "[{\"ReplyId\":true}]",
            "[{\"ReplyId\":null}]",
            "[{\"ReplyId\":{\"value\":\"target\"}}]",
            "[{\"ReplyId\":[\"target\"]}]",
            "[{\"ReplyId\":\"\"}]",
            "[{\"ReplyId\":\"   \"}]",
        )
        dictionaries.forEach { dictionary ->
            val restored = checkNotNull(row.copy(rawDictionaryJson = dictionary).toMessageOrNull())

            assertNull(dictionary, restored.replyToId)
            assertEquals(row.text, restored.text)
            assertEquals(dictionary, restored.raw?.dictionaryJson)
        }
    }

    @Test
    fun replyRestorationUsesTheLastStringFieldAsNativeBcDoes() {
        val row = whisperMessage().toEntity(10)
        val restored = checkNotNull(row.copy(rawDictionaryJson =
            "[{\"ReplyId\":\"older-target\"},{\"Tag\":\"Other\",\"ReplyId\":\"newer-target\"},{\"ReplyId\":false}]",
        ).toMessageOrNull())
        assertEquals("newer-target", restored.replyToId)

        val cleared = checkNotNull(row.copy(rawDictionaryJson =
            "[{\"ReplyId\":\"target\"},{\"ReplyId\":\" \"}]",
        ).toMessageOrNull())
        assertNull(cleared.replyToId)
    }

    @Test
    fun migrationAddsNullableRoomContextWithoutRewritingHistoricalMessages() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected migration operation: ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        MIGRATION_13_14.migrate(database)

        assertEquals(13, MIGRATION_13_14.startVersion)
        assertEquals(14, MIGRATION_13_14.endVersion)
        assertEquals(listOf("ALTER TABLE messages ADD COLUMN `rawRoomName` TEXT"), statements)
    }

    private fun whisperMessage() = BcMessage(
        id = BcMessageId("native-1"),
        conversationId = BcConversationId("message:20"),
        address = BcMessageAddress.Direct(20),
        sender = BcSender(20, "Alice"),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        text = "Reply text",
        timestamp = Instant.ofEpochMilli(1_000),
        targetMemberNumber = 10,
        replyToId = "native-target",
        raw = BcRawMessage(
            sourceEvent = "ChatRoomMessage",
            wireType = "Whisper",
            payloadJson = """{"Sender":20,"Target":10,"Type":"Whisper","Content":"Reply text","Dictionary":[{"MsgId":"native-1"},{"ReplyId":"native-target"}]}""",
            dictionaryJson = """[{"MsgId":"native-1"},{"ReplyId":"native-target"}]""",
            roomName = "Room A",
        ),
    )
}
