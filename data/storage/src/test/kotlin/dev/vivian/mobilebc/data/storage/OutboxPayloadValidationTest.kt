package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboxPayloadValidationTest {
    @Test
    fun serializedDurableEnvelopesRestoreAllSupportedCommands() {
        val dispatchableKinds = listOf(
            BcMessageKind.Chat to BcMessageTransport.RoomChat,
            BcMessageKind.Emote to BcMessageTransport.RoomChat,
            BcMessageKind.Action to BcMessageTransport.RoomChat,
            BcMessageKind.Whisper to BcMessageTransport.RoomWhisper,
            BcMessageKind.Beep to BcMessageTransport.AccountBeep,
        )
        dispatchableKinds.forEach { (kind, transport) ->
            val target = if (transport == BcMessageTransport.RoomChat) null else 42
            val original = row(kind, transport, target)
            val restored = original.toValidatedOutboxDrainMessage()
            val payload = restored.command.toOutboxPayloadJson()

            assertEquals(restored, original.copy(payloadJson = payload).toValidatedOutboxDrainMessage())
        }
    }

    @Test
    fun roomChatKindsRestoreTheOriginalCommandAndDrainMetadata() {
        listOf(BcMessageKind.Chat, BcMessageKind.Action, BcMessageKind.Emote).forEach { kind ->
            assertRestored(row(kind = kind), kind, BcMessageTransport.RoomChat, null)
        }
    }

    @Test
    fun whisperAndBeepRestoreTheirDirectTargets() {
        listOf(
            BcMessageKind.Whisper to BcMessageTransport.RoomWhisper,
            BcMessageKind.Beep to BcMessageTransport.AccountBeep,
        ).forEach { (kind, transport) ->
            assertRestored(row(kind, transport, target = 42), kind, transport, 42)
        }
    }

    @Test
    fun activityCannotBeDispatchedThroughRoomChat() {
        assertRejected(row(kind = BcMessageKind.Activity))
    }

    @Test
    fun missingTargetAndExplicitNullBothMeanNoRoomTarget() {
        val original = row()
        val withoutTarget = original.withPayload { remove("targetMemberNumber") }

        assertEquals(original.toValidatedOutboxDrainMessage(), withoutTarget.toValidatedOutboxDrainMessage())
    }

    @Test
    fun integerAndLongTimestampsAreAcceptedWithoutTruncation() {
        listOf(1_000L, Int.MAX_VALUE.toLong() + 1).forEach { timestamp ->
            val original = row(createdAtMillis = timestamp)

            assertEquals(timestamp, original.toValidatedOutboxDrainMessage().command.createdAtMillis)
        }
    }

    @Test
    fun malformedJsonCannotBecomeADispatchableMessage() {
        listOf("{", "[]", "null").forEach { json ->
            assertRejected(row().copy(payloadJson = json))
        }
    }

    @Test
    fun unknownTransportIsRejectedEvenWhenItMatchesTheRow() {
        assertRejected(row().copy(transport = "FutureTransport").withPayload {
            put("transport", "FutureTransport")
        })
    }

    @Test
    fun unknownKindIsRejectedInsteadOfDefaultingToChat() {
        assertRejected(row().withPayload { put("kind", "FutureKind") })
    }

    @Test
    fun payloadIdentityAndRoutingMustMatchThePersistedRow() {
        val mismatches = mapOf(
            "clientMsgId" to "another-client",
            "conversationId" to "room:another",
            "transport" to "RoomWhisper",
            "createdAtMillis" to 1_001L,
        )
        mismatches.forEach { (field, value) ->
            assertRejected(row().withPayload { put(field, value) })
        }
    }

    @Test
    fun requiredFieldsCannotBeAbsentOrNull() {
        listOf("clientMsgId", "conversationId", "transport", "kind", "text", "createdAtMillis")
            .forEach { field ->
                assertRejected(row().withPayload { remove(field) })
                assertRejected(row().withPayload { put(field, JSONObject.NULL) })
            }
    }

    @Test
    fun stringFieldsRejectValuesThatJsonCouldCoerceToStrings() {
        mapOf(
            "clientMsgId" to 123,
            "conversationId" to JSONObject(),
            "transport" to true,
            "kind" to 7,
            "text" to 456,
        ).forEach { (field, value) ->
            assertRejected(row().withPayload { put(field, value) })
        }
    }

    @Test
    fun clientAndConversationIdsMustContainNonblankStrings() {
        listOf("", "   ").forEach { blank ->
            assertRejected(row().copy(clientMsgId = blank).withPayload { put("clientMsgId", blank) })
            assertRejected(row().copy(conversationId = BcConversationId(blank)).withPayload {
                put("conversationId", blank)
            })
        }
    }

    @Test
    fun timestampRejectsNumericStringsFractionalNumbersAndBooleans() {
        listOf("1000", 1_000.5, true).forEach { value ->
            assertRejected(row().withPayload { put("createdAtMillis", value) })
        }
    }

    @Test
    fun directTargetMustBeAPresentNonnegativeIntegerWithinMemberNumberRange() {
        val direct = row(BcMessageKind.Whisper, BcMessageTransport.RoomWhisper, target = 42)
        assertRejected(direct.withPayload { remove("targetMemberNumber") })
        listOf(JSONObject.NULL, -1, "42", 42.5, true, Int.MAX_VALUE.toLong() + 1).forEach { target ->
            assertRejected(direct.withPayload { put("targetMemberNumber", target) })
        }
    }

    @Test
    fun directConversationMustReferToTheSameTargetMember() {
        listOf(
            BcMessageKind.Whisper to BcMessageTransport.RoomWhisper,
            BcMessageKind.Beep to BcMessageTransport.AccountBeep,
        ).forEach { (kind, transport) ->
            val direct = row(kind, transport, target = 42)
            assertRejected(direct.withPayload { put("targetMemberNumber", 43) })
            assertRejected(direct.copy(conversationId = BcConversationId("room:test")).withPayload {
                put("conversationId", "room:test")
            })
        }
    }

    @Test
    fun directTransportsRejectKindsFromOtherTransports() {
        assertRejected(row(BcMessageKind.Beep, BcMessageTransport.RoomWhisper, target = 42))
        assertRejected(row(BcMessageKind.Whisper, BcMessageTransport.AccountBeep, target = 42))
        assertRejected(row(BcMessageKind.Chat, BcMessageTransport.RoomWhisper, target = 42))
    }

    @Test
    fun roomChatRejectsWhispersBeepsAndSystemKinds() {
        listOf(BcMessageKind.Whisper, BcMessageKind.Beep, BcMessageKind.System, BcMessageKind.Error)
            .forEach { kind -> assertRejected(row(kind = kind)) }
    }

    @Test
    fun roomChatRequiresANonemptyRoomConversation() {
        listOf("message:42", "room:").forEach { conversation ->
            assertRejected(row().copy(conversationId = BcConversationId(conversation)).withPayload {
                put("conversationId", conversation)
            })
        }
    }

    @Test
    fun roomChatRejectsANonNullTargetIncludingZero() {
        assertRejected(row(target = 0))
    }

    @Test
    fun localAndProtocolTransportsCannotBeDrainedFromOutbox() {
        listOf(BcMessageTransport.LocalSystem, BcMessageTransport.ProtocolEvent).forEach { transport ->
            assertRejected(row(kind = BcMessageKind.System, transport = transport))
        }
    }

    @Test
    fun invalidPersistedDrainMetadataIsRejected() {
        assertRejected(row().copy(ownerMemberNumber = -1))
        assertRejected(row().copy(attempts = -1))
        assertRejected(row(createdAtMillis = -1))
    }

    private fun assertRestored(
        row: PersistedOutboxMessage,
        kind: BcMessageKind,
        transport: BcMessageTransport,
        target: Int?,
    ) {
        val restored = row.toValidatedOutboxDrainMessage()

        assertEquals(row.ownerMemberNumber, restored.ownerMemberNumber)
        assertEquals(row.clientMsgId, restored.clientMsgId)
        assertEquals(row.attempts, restored.attempts)
        assertEquals(
            BcOutboundCommand(
                clientMsgId = row.clientMsgId,
                conversationId = row.conversationId,
                kind = kind,
                transport = transport,
                text = "Hello",
                targetMemberNumber = target,
                createdAtMillis = row.createdAtMillis,
                originRoomName = "test".takeIf { transport == BcMessageTransport.RoomWhisper },
            ),
            restored.command,
        )
    }

    private fun assertRejected(row: PersistedOutboxMessage) {
        assertThrows(Exception::class.java) { row.toValidatedOutboxDrainMessage() }
    }

    private fun PersistedOutboxMessage.withPayload(change: JSONObject.() -> Unit): PersistedOutboxMessage =
        copy(payloadJson = JSONObject(payloadJson).apply(change).toString())

    private fun row(
        kind: BcMessageKind = BcMessageKind.Chat,
        transport: BcMessageTransport = BcMessageTransport.RoomChat,
        target: Int? = null,
        createdAtMillis: Long = 1_000L,
    ): PersistedOutboxMessage {
        val conversation = if (transport == BcMessageTransport.RoomWhisper ||
            transport == BcMessageTransport.AccountBeep
        ) {
            "message:$target"
        } else {
            "room:test"
        }
        return PersistedOutboxMessage(
            clientMsgId = "client-1",
            ownerMemberNumber = 100,
            conversationId = BcConversationId(conversation),
            transport = transport.name,
            payloadJson = JSONObject()
                .put("clientMsgId", "client-1")
                .put("conversationId", conversation)
                .put("transport", transport.name)
                .put("kind", kind.name)
                .put("text", "Hello")
                .put("targetMemberNumber", target ?: JSONObject.NULL)
                .put("createdAtMillis", createdAtMillis)
                .apply { if (transport == BcMessageTransport.RoomWhisper) put("originRoomName", "test") }
                .toString(),
            state = OutboxMessageState.Pending,
            attempts = 2,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = createdAtMillis,
        )
    }
}
