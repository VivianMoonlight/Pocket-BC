package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcMessageReplyTest {
    @Test
    fun nativeRoomChatAndEmoteExposeReferencesInTheCurrentRoom() {
        listOf(BcMessageKind.Chat, BcMessageKind.Emote).forEach { kind ->
            val message = roomMessage().copy(kind = kind)

            assertEquals(
                BcMessageReplyReference("native-1", bcRoomConversationId("Room A"), BcMessageTransport.RoomChat, "Room A"),
                message.replyReferenceOrNull("Room A"),
            )
        }
    }

    @Test
    fun roomReferenceRequiresTheCurrentRoomConversationAndAddress() {
        val message = roomMessage()
        assertNull(message.replyReferenceOrNull("Room B"))
        assertNull(message.copy(conversationId = BcConversationId("room:current")).replyReferenceOrNull("Room A"))
        assertNull(message.copy(address = BcMessageAddress.Direct(20)).replyReferenceOrNull("Room A"))
        assertNull(message.replyReferenceOrNull(""))
        assertNull(message.replyReferenceOrNull("  "))
    }

    @Test
    fun onlyVisibleConfirmedMessagesCanBecomeReplyTargets() {
        val message = roomMessage()
        DeliveryState.entries.filter { it != DeliveryState.Sent }.forEach { delivery ->
            assertNull(message.copy(delivery = delivery).replyReferenceOrNull("Room A"))
        }
        BcMessageVisibility.entries.filter { it != BcMessageVisibility.Visible }.forEach { visibility ->
            assertNull(message.copy(visibility = visibility).replyReferenceOrNull("Room A"))
        }
    }

    @Test
    fun nonUserRoomMessagesAndOtherTransportsCannotBecomeReplyTargets() {
        val message = roomMessage()
        BcMessageKind.entries.filter { it != BcMessageKind.Chat && it != BcMessageKind.Emote }.forEach { kind ->
            assertNull(message.copy(kind = kind).replyReferenceOrNull("Room A"))
        }
        listOf(BcMessageTransport.AccountBeep, BcMessageTransport.LocalSystem, BcMessageTransport.ProtocolEvent)
            .forEach { transport -> assertNull(message.copy(transport = transport).replyReferenceOrNull("Room A")) }
    }

    @Test
    fun localGeneratedIdsAndMessageIdsThatDoNotMatchTheDictionaryCannotBeRepliedTo() {
        val message = roomMessage()
        assertNull(message.copy(raw = null).replyReferenceOrNull("Room A"))
        assertNull(message.copy(raw = message.raw!!.copy(dictionaryJson = null)).replyReferenceOrNull("Room A"))
        assertNull(message.copy(id = BcMessageId("locally-generated-id")).replyReferenceOrNull("Room A"))
    }

    @Test
    fun msgIdRejectsMalformedCoercedAndNestedDictionaryValues() {
        val dictionaries = listOf(
            "{",
            "null",
            "{\"MsgId\":\"native-1\"}",
            "[\"{\\\"MsgId\\\":\\\"native-1\\\"}\"]",
            "[{\"MsgId\":1}]",
            "[{\"MsgId\":true}]",
            "[{\"MsgId\":null}]",
            "[{\"MsgId\":\"\"}]",
            "[{\"MsgId\":\"  \"}]",
            "[{\"MsgId\":{\"value\":\"native-1\"}}]",
            "[{\"MsgId\":[\"native-1\"]}]",
        )
        val message = roomMessage()
        dictionaries.forEach { dictionary ->
            assertNull(dictionary, message.copy(raw = message.raw!!.copy(dictionaryJson = dictionary)).replyReferenceOrNull("Room A"))
        }
    }

    @Test
    fun nativeDictionaryFieldsDoNotRequireMatchingTagMetadata() {
        val message = roomMessage()
        listOf("[{\"MsgId\":\"native-1\"}]", "[{\"Tag\":\"AnotherTag\",\"MsgId\":\"native-1\"}]")
            .forEach { dictionary ->
                assertEquals("native-1", message.copy(raw = message.raw!!.copy(dictionaryJson = dictionary))
                    .replyReferenceOrNull("Room A")?.messageId)
            }
    }

    @Test
    fun lastStringMsgIdControlsWhichMessageCanBeRepliedTo() {
        val message = roomMessage()
        val latest = message.copy(raw = message.raw!!.copy(
            dictionaryJson = "[{\"MsgId\":\"older-id\"},{\"MsgId\":\"native-1\"},{\"MsgId\":99}]",
        ))
        assertEquals("native-1", latest.replyReferenceOrNull("Room A")?.messageId)
        assertNull(message.copy(raw = message.raw!!.copy(
            dictionaryJson = "[{\"MsgId\":\"native-1\"},{\"MsgId\":\"later-id\"}]",
        )).replyReferenceOrNull("Room A"))
        assertNull(message.copy(raw = message.raw!!.copy(
            dictionaryJson = "[{\"MsgId\":\"native-1\"},{\"MsgId\":\" \"}]",
        )).replyReferenceOrNull("Room A"))
    }

    @Test
    fun incomingAndSelfWhispersUseTheDirectPeerAndCapturedRoom() {
        val incoming = whisperMessage()
        val outgoing = incoming.copy(sender = BcSender(10, "Self", isSelf = true), targetMemberNumber = 20)
        val expected = BcMessageReplyReference("native-1", BcConversationId("message:20"), BcMessageTransport.RoomWhisper, "Room A")

        assertEquals(expected, incoming.replyReferenceOrNull("Room A"))
        assertEquals(expected, outgoing.replyReferenceOrNull("Room A"))
    }

    @Test
    fun whisperCannotBeRepliedToAfterRoomChangeOrWithoutCapturedRoom() {
        val message = whisperMessage()
        assertNull(message.replyReferenceOrNull("Room B"))
        assertNull(message.copy(raw = message.raw!!.copy(roomName = null)).replyReferenceOrNull("Room A"))
        assertNull(message.copy(raw = message.raw!!.copy(roomName = "")).replyReferenceOrNull("Room A"))
    }

    @Test
    fun whisperRequiresAValidDirectAddressAndMatchingConversation() {
        val message = whisperMessage()
        assertNull(message.copy(address = BcMessageAddress.CurrentRoom).replyReferenceOrNull("Room A"))
        assertNull(message.copy(address = BcMessageAddress.Direct(-1), conversationId = BcConversationId("message:-1"))
            .replyReferenceOrNull("Room A"))
        assertNull(message.copy(address = BcMessageAddress.Direct(30)).replyReferenceOrNull("Room A"))
        assertNull(message.copy(conversationId = bcRoomConversationId("Room A")).replyReferenceOrNull("Room A"))
        assertNull(message.copy(kind = BcMessageKind.Chat).replyReferenceOrNull("Room A"))
    }

    private fun roomMessage() = BcMessage(
        id = BcMessageId("native-1"),
        conversationId = bcRoomConversationId("Room A"),
        sender = BcSender(20, "Alice"),
        kind = BcMessageKind.Chat,
        text = "Hello",
        timestamp = Instant.ofEpochMilli(1_000),
        raw = BcRawMessage(
            sourceEvent = "ChatRoomMessage",
            wireType = "Chat",
            dictionaryJson = "[{\"Tag\":\"BCXMsgId\",\"MsgId\":\"native-1\"}]",
        ),
    )

    private fun whisperMessage(): BcMessage {
        val message = roomMessage()
        return message.copy(
            conversationId = BcConversationId("message:20"),
            address = BcMessageAddress.Direct(20),
            kind = BcMessageKind.Whisper,
            transport = BcMessageTransport.RoomWhisper,
            targetMemberNumber = 10,
            raw = message.raw!!.copy(wireType = "Whisper", roomName = "Room A"),
        )
    }
}
