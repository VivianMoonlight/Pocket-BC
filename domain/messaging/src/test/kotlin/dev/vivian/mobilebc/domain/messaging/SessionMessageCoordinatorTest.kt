package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.core.model.DeliveryState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMessageCoordinatorTest {
    @Test
    fun outgoingMessagesAreVisibleWhileWaitingForServerEcho() {
        val coordinator = BcSessionMessageCoordinator()
        val account = BcAccount(memberNumber = 1001, displayName = "Alice", accountName = "alice")
        val room = coordinator.localOutgoingMessage(
            clientMsgId = "room-1",
            account = account,
            conversationId = coordinator.roomConversationId("Test room"),
            kind = BcMessageKind.Chat,
            text = "hello room",
            targetMemberNumber = null,
        )
        val whisper = coordinator.localOutgoingMessage(
            clientMsgId = "whisper-1",
            account = account,
            conversationId = coordinator.directConversationId(1002),
            kind = BcMessageKind.Whisper,
            text = "hello Bob",
            targetMemberNumber = 1002,
        )
        val selfWhisper = coordinator.localOutgoingMessage(
            clientMsgId = "self-whisper-1",
            account = account,
            conversationId = coordinator.directConversationId(1001),
            kind = BcMessageKind.Whisper,
            text = "note to self",
            targetMemberNumber = 1001,
        )

        assertEquals(BcMessageVisibility.Visible, room.visibility)
        assertEquals(BcMessageVisibility.Visible, whisper.visibility)
        assertEquals(BcMessageVisibility.Visible, selfWhisper.visibility)
        listOf(room, whisper, selfWhisper).forEach { assertEquals(DeliveryState.Sending, it.delivery) }
    }

    @Test
    fun deduplicateMessagesUsesTheAuthoritativeServerEcho() {
        val coordinator = BcSessionMessageCoordinator()
        val conversationId = coordinator.roomConversationId("Test room")
        val local = coordinator.localOutgoingMessage(
            clientMsgId = "local-1",
            account = BcAccount(memberNumber = 1001, displayName = "Alice", accountName = "alice"),
            conversationId = conversationId,
            kind = BcMessageKind.Chat,
            text = "hello",
            targetMemberNumber = null,
        ).copy(delivery = DeliveryState.Sent)
        val remote = BcMessage(
            id = BcMessageId("local-1"),
            conversationId = conversationId,
            address = BcMessageAddress.CurrentRoom,
            sender = BcSender(memberNumber = 1001, displayName = "Alice", isSelf = true),
            kind = BcMessageKind.Chat,
            transport = BcMessageTransport.RoomChat,
            text = "hello",
            timestamp = local.timestamp.plusMillis(500),
            delivery = DeliveryState.Sent,
            raw = BcRawMessage(
                sourceEvent = "ChatRoomMessage",
                wireType = "Chat",
                dictionaryJson = """[{"Tag":"MsgId","MsgId":"local-1"}]""",
            ),
        )

        val merged = coordinator.deduplicateMessages(listOf(local, remote))

        assertEquals(listOf(remote), merged)
    }

    @Test
    fun identicalTextWithDifferentWireMsgIdsIsNotDeduplicated() {
        val coordinator = BcSessionMessageCoordinator()
        val conversationId = coordinator.roomConversationId("Test room")
        val local = coordinator.localOutgoingMessage(
            clientMsgId = "client-a",
            account = BcAccount(memberNumber = 1001, displayName = "Alice", accountName = "alice"),
            conversationId = conversationId,
            kind = BcMessageKind.Chat,
            text = "same text",
            targetMemberNumber = null,
        )
        val remote = BcMessage(
            id = BcMessageId("client-b"),
            conversationId = conversationId,
            address = BcMessageAddress.CurrentRoom,
            sender = BcSender(memberNumber = 1001, displayName = "Alice", isSelf = true),
            kind = BcMessageKind.Chat,
            transport = BcMessageTransport.RoomChat,
            text = "same text",
            timestamp = local.timestamp.plusMillis(100),
            delivery = DeliveryState.Sent,
            raw = BcRawMessage(
                sourceEvent = "ChatRoomMessage",
                wireType = "Chat",
                dictionaryJson = """[{"Tag":"MsgId","MsgId":"client-b"}]""",
            ),
        )

        val merged = coordinator.deduplicateMessages(listOf(local, remote))

        assertEquals(listOf("client-a", "client-b"), merged.map { it.id.value })
    }

    @Test
    fun mergeConversationKeepsNewerSubtitleAndUpdatedAtWhenIncomingIsOlder() {
        val coordinator = BcSessionMessageCoordinator()
        val conversationId = BcConversationId("message:1002")
        val newerTime = Instant.parse("2026-07-01T12:00:00Z")
        val olderTime = Instant.parse("2026-07-01T11:00:00Z")
        val existing = conversation(
            id = conversationId,
            title = "New title",
            subtitle = "new subtitle",
            unreadCount = 3,
            muted = true,
            pinned = true,
            updatedAt = newerTime,
        )
        val incoming = conversation(
            id = conversationId,
            title = "Old title",
            subtitle = "old subtitle",
            unreadCount = 1,
            muted = false,
            pinned = false,
            updatedAt = olderTime,
        )

        val merged = coordinator.mergeConversation(listOf(existing), incoming).single()

        assertEquals("New title", merged.title)
        assertEquals("new subtitle", merged.subtitle)
        assertEquals(newerTime, merged.updatedAt)
        assertEquals(true, merged.muted)
        assertEquals(true, merged.pinned)
    }

    @Test
    fun mergeConversationStillAddsUnreadCountWhenIncomingIsOlder() {
        val coordinator = BcSessionMessageCoordinator()
        val conversationId = BcConversationId("message:1002")
        val existing = conversation(
            id = conversationId,
            unreadCount = 3,
            updatedAt = Instant.parse("2026-07-01T12:00:00Z"),
        )
        val incoming = conversation(
            id = conversationId,
            unreadCount = 2,
            updatedAt = Instant.parse("2026-07-01T11:00:00Z"),
        )

        val merged = coordinator.mergeConversation(listOf(existing), incoming).single()

        assertEquals(5, merged.unreadCount)
    }

    @Test
    fun mergeConversationUsesNewerIncomingSubtitleAndUpdatedAt() {
        val coordinator = BcSessionMessageCoordinator()
        val conversationId = BcConversationId("message:1002")
        val olderTime = Instant.parse("2026-07-01T11:00:00Z")
        val newerTime = Instant.parse("2026-07-01T12:00:00Z")
        val existing = conversation(
            id = conversationId,
            title = "Old title",
            subtitle = "old subtitle",
            updatedAt = olderTime,
        )
        val incoming = conversation(
            id = conversationId,
            title = "New title",
            subtitle = "new subtitle",
            updatedAt = newerTime,
        )

        val merged = coordinator.mergeConversation(listOf(existing), incoming).single()

        assertEquals("New title", merged.title)
        assertEquals("new subtitle", merged.subtitle)
        assertEquals(newerTime, merged.updatedAt)
    }

    @Test
    fun deduplicateMessagesOrdersDeterministicallyWhenTimestampsAreEqual() {
        val coordinator = BcSessionMessageCoordinator()
        val timestamp = Instant.parse("2026-07-01T12:00:00Z")
        val second = message(id = "message-b", timestamp = timestamp)
        val first = message(id = "message-a", timestamp = timestamp)

        val merged = coordinator.deduplicateMessages(listOf(second, first))

        assertEquals(listOf(first, second), merged)
    }

    private fun conversation(
        id: BcConversationId,
        title: String = "Title",
        subtitle: String = "Subtitle",
        unreadCount: Int = 0,
        muted: Boolean = false,
        pinned: Boolean = false,
        updatedAt: Instant? = null,
    ): BcConversation =
        BcConversation(
            id = id,
            type = ConversationType.Direct,
            title = title,
            subtitle = subtitle,
            unreadCount = unreadCount,
            muted = muted,
            pinned = pinned,
            updatedAt = updatedAt,
        )

    private fun message(
        id: String,
        timestamp: Instant,
    ): BcMessage =
        BcMessage(
            id = BcMessageId(id),
            conversationId = BcConversationId("message:1002"),
            address = BcMessageAddress.Direct(1002),
            sender = BcSender(memberNumber = 1002, displayName = "Bob"),
            kind = BcMessageKind.Chat,
            transport = BcMessageTransport.RoomChat,
            text = id,
            timestamp = timestamp,
        )
}
