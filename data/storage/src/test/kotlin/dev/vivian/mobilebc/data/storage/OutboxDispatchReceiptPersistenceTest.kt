package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OutboxDispatchReceiptPersistenceTest {
    @Test
    fun confirmedWhisperShowsSentContentAndDictionaryWhileOutboxKeepsOriginalInput() {
        val command = whisperCommand()
        val persisted = PersistedOutboxMessage(
            ownerMemberNumber = 10,
            clientMsgId = command.clientMsgId,
            conversationId = command.conversationId,
            transport = command.transport.name,
            payloadJson = command.toOutboxPayloadJson(),
            state = OutboxMessageState.Pending,
            attempts = 0,
            createdAtMillis = command.createdAtMillis,
            updatedAtMillis = command.createdAtMillis,
        )
        val receipt = BcOutboundDispatchReceipt(
            content = "mm mm",
            dictionaryJson = """[{"MsgId":"native-1"},{"ReplyId":"target"},{"Tag":"SpeechEffects","Effects":["gagGarble"]}]""",
        )

        val prepared = localWhisper().toEntity(10).withDispatchReceipt(receipt, DeliveryState.Sent)
        val restoredMessage = checkNotNull(prepared.toMessageOrNull())

        assertEquals("mm mm", restoredMessage.text)
        assertEquals(DeliveryState.Sent, restoredMessage.delivery)
        assertEquals(receipt.dictionaryJson, restoredMessage.raw?.dictionaryJson)
        assertEquals("target", restoredMessage.replyToId)
        assertEquals("Room A", restoredMessage.raw?.roomName)
        assertEquals("hello room", persisted.toValidatedOutboxDrainMessage().command.text)
        assertEquals(command, persisted.toValidatedOutboxDrainMessage().command)
    }

    @Test
    fun receiptCannotOverwriteAnAuthoritativeEchoThatArrivedBeforeSendCompletion() {
        val echo = localWhisper().toEntity(10).copy(
            text = "server resolved text",
            rawSourceEvent = "ChatRoomMessage",
            rawPayloadJson = """{"Content":"server resolved text"}""",
            delivery = DeliveryState.Sent.name,
        )

        assertSame(
            echo,
            echo.withDispatchReceipt(BcOutboundDispatchReceipt("mm", "[]"), DeliveryState.Sending),
        )
    }

    @Test
    fun emptyTransformedContentIsPersistedAndMissingReceiptFieldsKeepExistingMetadata() {
        val original = localWhisper().toEntity(10)
        val empty = original.withDispatchReceipt(BcOutboundDispatchReceipt(content = ""), DeliveryState.Sent)

        assertEquals("", empty.text)
        assertEquals(original.rawDictionaryJson, empty.rawDictionaryJson)
        assertEquals(DeliveryState.Sent.name, empty.delivery)

        val unchangedContent = original.withDispatchReceipt(BcOutboundDispatchReceipt(), DeliveryState.Sent)
        assertEquals(original.text, unchangedContent.text)
        assertEquals(original.rawDictionaryJson, unchangedContent.rawDictionaryJson)
    }

    private fun whisperCommand() = BcOutboundCommand(
        clientMsgId = "native-1",
        conversationId = BcConversationId("message:20"),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        text = "hello room",
        targetMemberNumber = 20,
        createdAtMillis = 1_000,
        replyToId = "target",
        replyRoomName = "Room A",
        originRoomName = "Room A",
    )

    private fun localWhisper() = BcMessage(
        id = BcMessageId("native-1"),
        conversationId = BcConversationId("message:20"),
        address = BcMessageAddress.Direct(20),
        sender = BcSender(10, "Alice", isSelf = true),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        text = "hello room",
        timestamp = Instant.ofEpochMilli(1_000),
        delivery = DeliveryState.Sending,
        targetMemberNumber = 20,
        replyToId = "target",
        raw = BcRawMessage(
            sourceEvent = "LocalOutgoing",
            wireType = "Whisper",
            dictionaryJson = """[{"MsgId":"native-1"},{"ReplyId":"target"}]""",
            roomName = "Room A",
        ),
    )
}
