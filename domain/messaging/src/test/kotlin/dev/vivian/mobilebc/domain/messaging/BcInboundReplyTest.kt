package dev.vivian.mobilebc.domain.messaging

import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcInboundReceiveContext
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.bcMessageIdFromDictionaryOrNull
import dev.vivian.mobilebc.core.protocol.bcReplyIdFromDictionaryOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcInboundReplyTest {
    @Test
    fun incomingRoomAndWhisperRepliesKeepNativeIdsWithoutChangingContent() = runTest {
        listOf("Chat", "Emote", "Whisper").forEach { type ->
            val commands = mutableListOf<BcInboundPersistenceCommand>()
            val processor = processor(commands)
            val frame = frame(type)

            processor.handle(frame, context())

            val message = checkNotNull(commands.single().message)
            assertEquals("original-message", message.replyToId)
            assertEquals("incoming-message", message.id.value)
            assertEquals("Reply text", message.text)
            assertEquals(JsonParser.parseString(frame.raw), JsonParser.parseString(checkNotNull(message.raw?.payloadJson)))
            assertEquals("Room A", message.raw?.roomName)
            assertEquals("incoming-message", message.replyReferenceOrNull("Room A")?.messageId)
        }
    }

    @Test
    fun receiveTimeRoomMetadataWinsOverLaterRoomContextForWhispers() = runTest {
        val commands = mutableListOf<BcInboundPersistenceCommand>()
        processor(commands).handle(frame("Whisper"), context().copy(roomName = "Room B"))

        val message = checkNotNull(commands.single().message)
        assertEquals("Room A", message.raw?.roomName)
        assertNull(message.replyReferenceOrNull("Room B"))
    }

    @Test
    fun absentReceivingRoomDoesNotBorrowLaterRoomMetadata() = runTest {
        val commands = mutableListOf<BcInboundPersistenceCommand>()
        val receivedOutsideRoom = frame("Whisper").let { frame ->
            frame.copy(receiveContext = frame.receiveContext?.copy(roomName = null))
        }
        processor(commands).handle(receivedOutsideRoom, context())

        val message = checkNotNull(commands.single().message)
        assertNull(message.raw?.roomName)
        assertNull(message.replyReferenceOrNull("Room A"))
        assertEquals("original-message", message.replyToId)
    }

    @Test
    fun explicitContextStoresRoomForFramesWithoutGatewayMetadata() = runTest {
        val commands = mutableListOf<BcInboundPersistenceCommand>()
        processor(commands).handle(frame("Whisper").copy(receiveContext = null), context())

        assertEquals("Room A", commands.single().message?.raw?.roomName)
    }

    @Test
    fun invalidReplyIdTypesDoNotBecomeCoercedReferencesOrDiscardMessages() = runTest {
        listOf("7", "true", "null", "{}", "[]", "\" \"").forEach { value ->
            val commands = mutableListOf<BcInboundPersistenceCommand>()
            val raw = """{"Sender":20,"Type":"Chat","Content":"Reply text","Dictionary":[{"MsgId":"incoming-message"},{"ReplyId":$value}]}"""
            processor(commands).handle(frame("Chat").copy(raw = raw, args = listOf(raw)), context())

            assertNull(commands.single().message?.replyToId)
            assertEquals("Reply text", commands.single().message?.text)
        }
    }

    @Test
    fun dictionaryMessageIdsAreStructuredFieldsAndFollowNativeLastEntrySemantics() {
        val dictionary = """[{"Text":"fake MsgId and ReplyId"},{"MsgId":"old"},{"MsgId":"new","Tag":"Other"},{"MsgId":1},{"ReplyId":"target"}]"""
        assertEquals("new", bcMessageIdFromDictionaryOrNull(dictionary))
        assertEquals("target", bcReplyIdFromDictionaryOrNull(dictionary))
        assertNull(bcMessageIdFromDictionaryOrNull("""[{"Text":"{\"MsgId\":\"fake\"}"}]"""))
    }

    @Test
    fun nativeDictionaryIdentityMatchesTheReferencedIdForUntaggedAndBcxMessages() = runTest {
        listOf(
            """[{"Tag":"BCXMsgId","MsgId":"incoming-message"},{"ReplyId":"original-message"}]""",
            """[{"Tag":"MsgId","MsgId":"old-id"},{"MsgId":"incoming-message"},{"ReplyId":"original-message"}]""",
        ).forEach { dictionary ->
            val commands = mutableListOf<BcInboundPersistenceCommand>()
            val raw = """{"Sender":20,"Type":"Chat","Content":"Reply text","Dictionary":$dictionary}"""
            processor(commands).handle(frame("Chat").copy(raw = raw, args = listOf(raw)), context())

            val message = checkNotNull(commands.single().message)
            assertEquals("incoming-message", message.id.value)
            assertEquals("incoming-message", message.replyReferenceOrNull("Room A")?.messageId)
            assertEquals("original-message", message.replyToId)
        }
    }

    @Test
    fun ownSubmittedWhisperKeepsTheSameMessageIdAndReplyAcrossPersistence() {
        val coordinator = BcSessionMessageCoordinator()
        val outgoing = coordinator.localOutgoingMessage(
            clientMsgId = "own-whisper",
            account = BcAccount(10, "Self", "self"),
            conversationId = coordinator.directConversationId(20),
            kind = BcMessageKind.Whisper,
            text = "Reply text",
            targetMemberNumber = 20,
            replyToId = "original-message",
            roomName = "Room A",
        )

        assertEquals("original-message", bcReplyIdFromDictionaryOrNull(outgoing.raw?.dictionaryJson))
        assertEquals("own-whisper", bcMessageIdFromDictionaryOrNull(outgoing.raw?.dictionaryJson))
        assertNull(outgoing.replyReferenceOrNull("Room A"))
        assertEquals("own-whisper", outgoing.copy(delivery = DeliveryState.Sent).replyReferenceOrNull("Room A")?.messageId)
    }

    private fun processor(commands: MutableList<BcInboundPersistenceCommand>) = BcInboundMessageProcessor(
        persistenceStore = object : BcInboundPersistenceStore {
            override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult {
                commands += command
                return BcInboundPersistenceResult.Recorded
            }
        },
    )

    private fun context() = BcInboundMessageContext(
        ownerMemberNumber = 10,
        ownerDisplayName = "Self",
        roomMembers = mapOf(10 to "Self", 20 to "Alice"),
        roomConversationId = bcRoomConversationId("Room A"),
        roomName = "Room A",
    )

    private fun frame(type: String): CanonicalFrame {
        val raw = """{"Sender":20,"Target":10,"Type":"$type","Content":"Reply text","Dictionary":[{"MsgId":"incoming-message"},{"ReplyId":"original-message"}]}"""
        return CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomMessage",
            args = listOf(raw),
            receivedAtMillis = 1000,
            raw = raw,
            receiveContext = BcInboundReceiveContext(10, "Self", "Room A", mapOf(20 to "Alice"), 1),
        )
    }
}
