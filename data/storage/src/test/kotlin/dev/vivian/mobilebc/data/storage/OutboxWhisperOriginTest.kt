package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxWhisperOriginTest {
    @Test
    fun ordinaryWhisperRetainsItsOriginalRoomAcrossDurablePayloadRoundTrip() {
        val original = whisper()
        val payload = original.toOutboxPayloadJson()
        val json = JSONObject(payload)

        assertEquals("临窗茶室", json.getString("originRoomName"))
        assertFalse(json.has("replyToId"))
        assertFalse(json.has("replyRoomName"))
        assertEquals(original, row(original, payload).toValidatedOutboxDrainMessage().command)
    }

    @Test
    fun oldOrdinaryWhisperWithoutARoomFailsWithAnActionableReason() {
        val original = whisper()
        val legacyPayload = JSONObject(original.toOutboxPayloadJson()).apply { remove("originRoomName") }.toString()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            row(original, legacyPayload).toValidatedOutboxDrainMessage()
        }
        assertTrue(failure.message.orEmpty().contains("original room"))
        assertTrue(failure.message.orEmpty().contains("resend it manually"))
    }

    @Test
    fun oldWhisperReplyRestoresItsExistingScopeWithoutGuessingTheCurrentRoom() {
        val original = whisper().copy(replyToId = "old-message", replyRoomName = "临窗茶室")
        val legacyPayload = JSONObject(original.toOutboxPayloadJson()).apply { remove("originRoomName") }.toString()

        assertEquals(original, row(original, legacyPayload).toValidatedOutboxDrainMessage().command)
    }

    @Test
    fun malformedOrConflictingWhisperScopeCannotBeSerializedOrRestored() {
        val original = whisper()
        assertThrows(IllegalArgumentException::class.java) { original.copy(originRoomName = null).toOutboxPayloadJson() }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(replyToId = "old-message", replyRoomName = "Other room").toOutboxPayloadJson()
        }
        listOf("", "   ", 42, JSONObject.NULL).forEach { value ->
            val invalid = JSONObject(original.toOutboxPayloadJson()).put("originRoomName", value).toString()
            assertThrows(IllegalArgumentException::class.java) { row(original, invalid).toValidatedOutboxDrainMessage() }
        }
        val invalidReply = JSONObject(original.toOutboxPayloadJson())
            .put("replyToId", "old-message").put("replyRoomName", "Other room").toString()
        assertThrows(IllegalArgumentException::class.java) { row(original, invalidReply).toValidatedOutboxDrainMessage() }
    }

    private fun whisper() = BcOutboundCommand(
        clientMsgId = "private-1",
        conversationId = BcConversationId("message:20"),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        text = "Only for you",
        targetMemberNumber = 20,
        createdAtMillis = 1_000L,
        originRoomName = "临窗茶室",
    )

    private fun row(command: BcOutboundCommand, payload: String) = PersistedOutboxMessage(
        clientMsgId = command.clientMsgId,
        ownerMemberNumber = 10,
        conversationId = command.conversationId,
        transport = command.transport.name,
        payloadJson = payload,
        state = OutboxMessageState.Pending,
        attempts = 0,
        createdAtMillis = command.createdAtMillis,
        updatedAtMillis = command.createdAtMillis,
    )
}
