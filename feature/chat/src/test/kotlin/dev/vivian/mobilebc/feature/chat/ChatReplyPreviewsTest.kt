package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.DeliveryState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReplyPreviewsTest {
    @Test
    fun visibleSentRoomOriginalProvidesAuthorTextAndJumpTarget() {
        val original = previewMessage("original", text = "Hello")
        val reply = previewMessage("reply", replyToId = original.id.value)

        assertEquals(
            mapOf(reply.chatUiKey to ChatReplyPreview("original", "Alice", "Hello", canJump = true, messageUiKey = original.chatUiKey)),
            chatReplyPreviews(listOf(original, reply)),
        )
    }

    @Test
    fun sameRoomWhisperOriginalCanBeQuotedWithinItsDirectConversation() {
        val original = previewMessage(
            "private-original",
            conversationId = "direct:123",
            transport = BcMessageTransport.RoomWhisper,
            kind = BcMessageKind.Whisper,
            roomName = "Room A",
            text = "Private hello",
        )
        val reply = original.copy(id = BcMessageId("private-reply"), replyToId = original.id.value)

        assertEquals(
            ChatReplyPreview("private-original", "Alice", "Private hello", canJump = true, messageUiKey = original.chatUiKey),
            chatReplyPreviews(listOf(original, reply))[reply.chatUiKey],
        )
    }

    @Test
    fun missingHiddenAndUnsentOriginalsKeepOnlyTheReplyId() {
        val original = previewMessage("original", text = "Must not leak")
        val reply = previewMessage("reply", replyToId = original.id.value)
        val unavailableOriginals = listOf(
            emptyList(),
            listOf(original.copy(visibility = BcMessageVisibility.HiddenProtocol)),
            listOf(original.copy(visibility = BcMessageVisibility.DebugOnly)),
            listOf(original.copy(visibility = BcMessageVisibility.LocalOnly)),
            listOf(original.copy(delivery = DeliveryState.Sending)),
            listOf(original.copy(delivery = DeliveryState.Failed)),
            listOf(original.copy(kind = BcMessageKind.Action)),
        )

        unavailableOriginals.forEach { originals ->
            assertEquals(
                originals.toString(),
                ChatReplyPreview("original"),
                chatReplyPreviews(originals + reply)[reply.chatUiKey],
            )
        }
    }

    @Test
    fun quoteNeverResolvesAcrossConversations() {
        val original = previewMessage("original", conversationId = "room:Other", text = "Other room")
        val reply = previewMessage("reply", replyToId = original.id.value)

        assertEquals(ChatReplyPreview("original"), chatReplyPreviews(listOf(original, reply))[reply.chatUiKey])
    }

    @Test
    fun whisperQuoteRequiresKnownMatchingRoomOnBothMessages() {
        val original = previewMessage(
            "original",
            conversationId = "direct:123",
            transport = BcMessageTransport.RoomWhisper,
            kind = BcMessageKind.Whisper,
            roomName = "Room A",
        )
        val reply = original.copy(id = BcMessageId("reply"), replyToId = original.id.value)
        val roomPairs = listOf(
            "Room A" to "Room B",
            null to "Room A",
            "Room A" to null,
            null to null,
            "" to "",
            " " to " ",
        )

        roomPairs.forEach { (originalRoom, replyRoom) ->
            assertEquals(
                "original room=$originalRoom; reply room=$replyRoom",
                ChatReplyPreview("original"),
                chatReplyPreviews(
                    listOf(
                        original.copy(raw = original.raw?.copy(roomName = originalRoom)),
                        reply.copy(raw = reply.raw?.copy(roomName = replyRoom)),
                    ),
                )[reply.chatUiKey],
            )
        }
    }

    @Test
    fun quoteNeverCrossesBetweenPublicAndPrivateTransports() {
        listOf(
            BcMessageTransport.RoomChat to BcMessageTransport.RoomWhisper,
            BcMessageTransport.RoomWhisper to BcMessageTransport.RoomChat,
            BcMessageTransport.RoomChat to BcMessageTransport.AccountBeep,
        ).forEach { (originalTransport, replyTransport) ->
            val original = previewMessage("original", transport = originalTransport, roomName = "Room A")
            val reply = previewMessage(
                "reply",
                transport = replyTransport,
                roomName = "Room A",
                replyToId = original.id.value,
            )

            assertEquals(
                "$originalTransport to $replyTransport",
                ChatReplyPreview("original"),
                chatReplyPreviews(listOf(original, reply))[reply.chatUiKey],
            )
        }
    }

    @Test
    fun emoteQuoteUsesNativeAuthorAndPunctuationRules() {
        mapOf(
            "waves" to "Alice waves",
            "'s eyes widen" to "Alice's eyes widen",
            ", startled, looks up" to "Alice, startled, looks up",
            "*The lights dim" to "The lights dim",
        ).forEach { (body, expected) ->
            val original = previewMessage("original", kind = BcMessageKind.Emote, text = body)
            val reply = previewMessage("reply", replyToId = original.id.value)

            assertEquals(
                body,
                ChatReplyPreview("original", "Alice", expected, canJump = true, messageUiKey = original.chatUiKey),
                chatReplyPreviews(listOf(original, reply))[reply.chatUiKey],
            )
        }
    }

    @Test
    fun collidingNativeIdsResolveOnlyWithinTheirConversationAndHaveDifferentUiKeys() {
        val aliceOriginal = previewMessage("same-native-id", conversationId = "message:123", text = "Alice secret",
            kind = BcMessageKind.Whisper, transport = BcMessageTransport.RoomWhisper)
        val bobOriginal = aliceOriginal.copy(conversationId = BcConversationId("message:456"), text = "Bob secret")
        val aliceReply = aliceOriginal.copy(id = BcMessageId("same-reply-id"), replyToId = "same-native-id")
        val bobReply = bobOriginal.copy(id = BcMessageId("same-reply-id"), replyToId = "same-native-id")
        val previews = chatReplyPreviews(listOf(aliceOriginal, bobOriginal, aliceReply, bobReply))
        assertEquals(2, previews.size)
        assertEquals("Alice secret", previews[aliceReply.chatUiKey]?.text)
        assertEquals("Bob secret", previews[bobReply.chatUiKey]?.text)
        assertEquals(aliceOriginal.chatUiKey, previews[aliceReply.chatUiKey]?.messageUiKey)
        assertEquals(bobOriginal.chatUiKey, previews[bobReply.chatUiKey]?.messageUiKey)
    }

    @Test
    fun messagesWithoutAReplyDoNotProduceQuoteEntries() {
        assertEquals(emptyMap<String, ChatReplyPreview>(), chatReplyPreviews(listOf(previewMessage("plain"))))
    }
}

private fun previewMessage(
    id: String,
    conversationId: String = "room:Room A",
    kind: BcMessageKind = BcMessageKind.Chat,
    transport: BcMessageTransport = BcMessageTransport.RoomChat,
    text: String = "Hello",
    roomName: String? = "Room A",
    replyToId: String? = null,
): BcMessage = BcMessage(
    id = BcMessageId(id),
    conversationId = BcConversationId(conversationId),
    sender = BcSender(memberNumber = 123, displayName = "Alice"),
    kind = kind,
    transport = transport,
    text = text,
    timestamp = Instant.EPOCH,
    raw = BcRawMessage(sourceEvent = "ChatRoomMessage", roomName = roomName),
    replyToId = replyToId,
)
