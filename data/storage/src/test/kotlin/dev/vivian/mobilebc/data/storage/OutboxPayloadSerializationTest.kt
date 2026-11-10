package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboxPayloadSerializationTest {
    @Test
    fun serializeRoomChatPreservesTheExistingPayload() {
        val payload =
            BcOutboundCommand(
                clientMsgId = "cm_chat_1",
                conversationId = BcConversationId("room:current"),
                kind = BcMessageKind.Chat,
                transport = BcMessageTransport.RoomChat,
                text = "hello room",
                targetMemberNumber = null,
                createdAtMillis = 1782460800123,
            ).toOutboxPayloadJson()

        assertJsonEquals(fixture("bc_chat_send_v1.json"), payload)
    }

    @Test
    fun serializeAccountBeepPreservesTheExistingPayload() {
        val payload =
            BcOutboundCommand(
                clientMsgId = "cm_beep_1",
                conversationId = BcConversationId("message:1001"),
                kind = BcMessageKind.Beep,
                transport = BcMessageTransport.AccountBeep,
                text = "hello direct",
                targetMemberNumber = 1001,
                createdAtMillis = 1782460800123,
            ).toOutboxPayloadJson()

        assertJsonEquals(fixture("bc_account_beep_send_v1.json"), payload)
    }

    @Test
    fun strictRestoreReadsExistingRoomChatPayload() {
        val command = restoredFixture(
            name = "bc_chat_send_v1.json",
            clientMsgId = "cm_chat_1",
            conversationId = "room:current",
            transport = BcMessageTransport.RoomChat,
        )

        assertEquals("cm_chat_1", command.clientMsgId)
        assertEquals(BcConversationId("room:current"), command.conversationId)
        assertEquals(BcMessageKind.Chat, command.kind)
        assertEquals(BcMessageTransport.RoomChat, command.transport)
        assertEquals("hello room", command.text)
        assertEquals(null, command.targetMemberNumber)
        assertEquals(1782460800123, command.createdAtMillis)
    }

    @Test
    fun strictRestoreReadsExistingAccountBeepPayload() {
        val command = restoredFixture(
            name = "bc_account_beep_send_v1.json",
            clientMsgId = "cm_beep_1",
            conversationId = "message:1001",
            transport = BcMessageTransport.AccountBeep,
        )

        assertEquals("cm_beep_1", command.clientMsgId)
        assertEquals(BcConversationId("message:1001"), command.conversationId)
        assertEquals(BcMessageKind.Beep, command.kind)
        assertEquals(BcMessageTransport.AccountBeep, command.transport)
        assertEquals("hello direct", command.text)
        assertEquals(1001, command.targetMemberNumber)
        assertEquals(1782460800123, command.createdAtMillis)
    }

    @Test
    fun localAndProtocolTransportsCannotBeSerializedForTheOutbox() {
        listOf(BcMessageTransport.LocalSystem, BcMessageTransport.ProtocolEvent).forEach { transport ->
            val command = BcOutboundCommand(
                clientMsgId = "local-1",
                conversationId = BcConversationId("system"),
                kind = BcMessageKind.System,
                transport = transport,
                text = "local message",
                targetMemberNumber = null,
                createdAtMillis = 1782460800123,
            )

            assertThrows(IllegalStateException::class.java) { command.toOutboxPayloadJson() }
        }
    }

    private fun restoredFixture(
        name: String,
        clientMsgId: String,
        conversationId: String,
        transport: BcMessageTransport,
    ): BcOutboundCommand =
        PersistedOutboxMessage(
            clientMsgId = clientMsgId,
            ownerMemberNumber = 9999,
            conversationId = BcConversationId(conversationId),
            transport = transport.name,
            payloadJson = fixture(name),
            state = OutboxMessageState.Pending,
            attempts = 0,
            createdAtMillis = 1782460800123,
            updatedAtMillis = 1782460800123,
        ).toValidatedOutboxDrainMessage().command

    private fun assertJsonEquals(expected: String, actual: String) {
        val expectedJson = JSONObject(expected)
        val actualJson = JSONObject(actual)
        assertEquals(expectedJson.toComparableMap(), actualJson.toComparableMap())
    }

    private fun JSONObject.toComparableMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key -> opt(key).takeUnless { it == JSONObject.NULL } }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) {
            "Missing fixture $name"
        }.readText()
}
