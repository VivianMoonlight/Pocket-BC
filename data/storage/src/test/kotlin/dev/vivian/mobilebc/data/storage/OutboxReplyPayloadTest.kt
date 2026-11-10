package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboxReplyPayloadTest {
    @Test
    fun noReplyOmitsTheFieldAndKeepsHistoricalPayloadsRestorable() {
        val original = command(replyToId = null)
        val payload = original.toOutboxPayloadJson()

        assertFalse(JSONObject(payload).has("replyToId"))
        assertFalse(JSONObject(payload).has("replyRoomName"))
        assertEquals(original, row(original, payload).toValidatedOutboxDrainMessage().command)
        assertNull(row(original, payload).toValidatedOutboxDrainMessage().command.replyToId)
    }

    @Test
    fun nativeReplySurvivesTheDurableQueueForChatEmoteAndWhisper() {
        listOf(
            BcMessageKind.Chat to BcMessageTransport.RoomChat,
            BcMessageKind.Emote to BcMessageTransport.RoomChat,
            BcMessageKind.Whisper to BcMessageTransport.RoomWhisper,
        ).forEach { (kind, transport) ->
            val original = command(kind, transport)
            val payload = original.toOutboxPayloadJson()

            assertEquals("native-target", JSONObject(payload).get("replyToId"))
            assertEquals("A", JSONObject(payload).get("replyRoomName"))
            assertEquals(original, row(original, payload).toValidatedOutboxDrainMessage().command)
        }
    }

    @Test
    fun replyIdsMustBeNonblankWhenSerializedOrRestored() {
        listOf("", "  ", "\n\t").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { command(replyToId = blank).toOutboxPayloadJson() }
            assertRejectedPayload { put("replyToId", blank) }
        }
    }

    @Test
    fun restoredReplyIdsRejectJsonValuesThatCouldBeCoercedToStrings() {
        listOf(123, true, JSONObject(), JSONArray().put("native-target"), JSONObject.NULL).forEach { value ->
            assertRejectedPayload { put("replyToId", value) }
        }
    }

    @Test
    fun restoredReplyIdIsKeptExactlyForNativeMessageLookup() {
        val original = command(replyToId = "id:with / symbols=中文")

        assertEquals(original, row(original, original.toOutboxPayloadJson()).toValidatedOutboxDrainMessage().command)
    }

    @Test
    fun replyIdAndRoomContextMustBePresentTogetherInTheDurablePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            command(replyRoomName = null).toOutboxPayloadJson()
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(replyToId = null, replyRoomName = "A").toOutboxPayloadJson()
        }
        assertRejectedPayload { remove("replyRoomName") }
        assertRejectedPayload { remove("replyToId") }
    }

    @Test
    fun replyRoomContextRejectsBlankAndNonStringValues() {
        listOf("", "  ").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) {
                command(replyRoomName = blank).toOutboxPayloadJson()
            }
            assertRejectedPayload { put("replyRoomName", blank) }
        }
        listOf(123, true, JSONObject(), JSONArray().put("A"), JSONObject.NULL).forEach { value ->
            assertRejectedPayload { put("replyRoomName", value) }
        }
    }

    @Test
    fun roomChatReplyContextMustMatchThePersistedConversation() {
        assertThrows(IllegalArgumentException::class.java) {
            command(replyRoomName = "B").toOutboxPayloadJson()
        }
        assertRejectedPayload { put("replyRoomName", "B") }
    }

    @Test
    fun actionAndAccountBeepCannotCarryRepliesThroughSerialization() {
        listOf(
            BcMessageKind.Action to BcMessageTransport.RoomChat,
            BcMessageKind.Beep to BcMessageTransport.AccountBeep,
        ).forEach { (kind, transport) ->
            assertThrows(IllegalArgumentException::class.java) { command(kind, transport).toOutboxPayloadJson() }
        }
    }

    @Test
    fun actionAndAccountBeepCannotAcquireRepliesWhenRestoredFromTheQueue() {
        listOf(
            BcMessageKind.Action to BcMessageTransport.RoomChat,
            BcMessageKind.Beep to BcMessageTransport.AccountBeep,
        ).forEach { (kind, transport) ->
            val original = command(kind, transport, replyToId = null)
            val payload = JSONObject(original.toOutboxPayloadJson())
                .put("replyToId", "native-target")
                .put("replyRoomName", "A")
                .toString()

            assertThrows(IllegalArgumentException::class.java) { row(original, payload).toValidatedOutboxDrainMessage() }
        }
    }

    @Test
    fun ordinaryAndReplyRoomMessagesAccept2000Utf16UnitsAndReject2001() {
        assertRoomTextBoundary("x".repeat(2_000), "x".repeat(2_001))
    }

    @Test
    fun emojiCountAsTwoUtf16UnitsInDurableRoomMessages() {
        val emoji = "\uD83D\uDE00"
        assertRoomTextBoundary(emoji.repeat(1_000), emoji.repeat(1_000) + "a")
    }

    @Test
    fun actionPayloadsKeepTheirExistingLengthBehavior() {
        val original = command(kind = BcMessageKind.Action, replyToId = null, text = "x".repeat(2_001))

        assertEquals(original, row(original, original.toOutboxPayloadJson()).toValidatedOutboxDrainMessage().command)
    }

    private fun assertRoomTextBoundary(accepted: String, rejected: String) {
        listOf(
            BcMessageKind.Chat to BcMessageTransport.RoomChat,
            BcMessageKind.Emote to BcMessageTransport.RoomChat,
            BcMessageKind.Whisper to BcMessageTransport.RoomWhisper,
        ).forEach { (kind, transport) ->
            listOf(null, "native-target").forEach { replyId ->
                val original = command(kind, transport, replyToId = replyId, text = accepted)
                val payload = original.toOutboxPayloadJson()
                assertEquals(original, row(original, payload).toValidatedOutboxDrainMessage().command)

                assertThrows(IllegalArgumentException::class.java) { original.copy(text = rejected).toOutboxPayloadJson() }
                val invalidPayload = JSONObject(payload).put("text", rejected).toString()
                assertThrows(IllegalArgumentException::class.java) { row(original, invalidPayload).toValidatedOutboxDrainMessage() }
            }
        }
    }

    private fun assertRejectedPayload(change: JSONObject.() -> Unit) {
        val original = command()
        val payload = JSONObject(original.toOutboxPayloadJson()).apply(change).toString()
        assertThrows(IllegalArgumentException::class.java) { row(original, payload).toValidatedOutboxDrainMessage() }
    }

    private fun row(command: BcOutboundCommand, payloadJson: String) = PersistedOutboxMessage(
        clientMsgId = command.clientMsgId,
        ownerMemberNumber = 10,
        conversationId = command.conversationId,
        transport = command.transport.name,
        payloadJson = payloadJson,
        state = OutboxMessageState.Pending,
        attempts = 2,
        createdAtMillis = command.createdAtMillis,
        updatedAtMillis = command.createdAtMillis,
    )

    private fun command(
        kind: BcMessageKind = BcMessageKind.Chat,
        transport: BcMessageTransport = BcMessageTransport.RoomChat,
        replyToId: String? = "native-target",
        replyRoomName: String? = if (replyToId == null) null else "A",
        text: String = "Reply text",
    ) = BcOutboundCommand(
        clientMsgId = "client-reply-1",
        conversationId = if (transport == BcMessageTransport.RoomChat) bcRoomConversationId("A") else BcConversationId("message:20"),
        kind = kind,
        transport = transport,
        text = text,
        targetMemberNumber = if (transport == BcMessageTransport.RoomChat) null else 20,
        createdAtMillis = 1_000,
        replyToId = replyToId,
        replyRoomName = replyRoomName,
        originRoomName = "A".takeIf { transport == BcMessageTransport.RoomWhisper },
    )
}
