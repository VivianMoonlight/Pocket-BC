package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcSender
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomMessageProjectionTest {
    @Test
    fun olderMessageDoesNotRegressLatestProjection() {
        val existing = ConversationEntity(
            ownerMemberNumber = 9999,
            conversationId = "message:1001",
            type = "Direct",
            title = "Alice",
            subtitle = "newer",
            pinned = false,
            muted = false,
            unreadCount = 7,
            updatedAtMillis = 200L,
            lastMessageId = "newer-id",
        )
        val older = message(id = "older-id", text = "older", timestampMillis = 100L)

        val projected = older.toConversationEntity(ownerMemberNumber = 9999, existing = existing)

        assertEquals("newer", projected.subtitle)
        assertEquals(200L, projected.updatedAtMillis)
        assertEquals("newer-id", projected.lastMessageId)
        assertEquals(7, projected.unreadCount)
    }

    @Test
    fun newerMessageUpdatesLatestProjectionWithoutOwningUnreadState() {
        val existing = ConversationEntity(
            ownerMemberNumber = 9999,
            conversationId = "message:1001",
            type = "Direct",
            title = "Alice",
            subtitle = "old",
            pinned = false,
            muted = false,
            unreadCount = 4,
            updatedAtMillis = 100L,
            lastMessageId = "old-id",
        )
        val newer = message(id = "new-id", text = "new", timestampMillis = 200L)

        val projected = newer.toConversationEntity(ownerMemberNumber = 9999, existing = existing)

        assertEquals("new", projected.subtitle)
        assertEquals(200L, projected.updatedAtMillis)
        assertEquals("new-id", projected.lastMessageId)
        assertEquals(4, projected.unreadCount)
    }

    private fun message(id: String, text: String, timestampMillis: Long): BcMessage =
        BcMessage(
            id = BcMessageId(id),
            conversationId = BcConversationId("message:1001"),
            address = BcMessageAddress.Direct(1001),
            sender = BcSender(memberNumber = 1001, displayName = "Alice"),
            kind = BcMessageKind.Chat,
            text = text,
            timestamp = Instant.ofEpochMilli(timestampMillis),
        )
}
