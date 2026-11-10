package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcInboundMessageContextTest {
    @Test
    fun delayedMessageUsesReceivingAccountRoomAndMemberNames() = runTest {
        val stored = mutableListOf<BcInboundPersistenceCommand>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = recordingStore(stored),
            ownerMemberNumber = { 999 },
            roomMemberLookup = { mapOf(20 to "New room name") },
            currentRoomConversationId = { bcRoomConversationId("B") },
        )
        processor.handle(frame(), receivingContext())

        val command = stored.single()
        assertEquals(10, command.ownerMemberNumber)
        assertEquals(bcRoomConversationId("A"), command.message?.conversationId)
        assertEquals(bcRoomConversationId("A"), command.event.conversationId)
        assertEquals(bcRoomConversationId("A"), command.cursorConversationId)
        assertEquals("Alice", command.message?.sender?.displayName)
    }

    @Test
    fun selfWhisperUsesItsTargetForMessageEventAndCursor() = runTest {
        val stored = mutableListOf<BcInboundPersistenceCommand>()
        val processor = BcInboundMessageProcessor(persistenceStore = recordingStore(stored))
        val raw = """{"Sender":10,"Target":20,"Type":"Whisper","Content":"Hello"}"""
        val frame = CanonicalFrame("/", "ChatRoomMessage", listOf(raw), 123, raw = raw, occurrenceId = 1)

        processor.handle(frame, receivingContext())

        val command = stored.single()
        val targetConversation = BcConversationId("message:20")
        assertEquals(targetConversation, command.message?.conversationId)
        assertEquals(targetConversation, command.event.conversationId)
        assertEquals(targetConversation, command.cursorConversationId)
    }

    @Test
    fun translationFailureStillPersistsRawAndFailedResolution() = runTest {
        val stored = mutableListOf<BcInboundPersistenceCommand>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = recordingStore(stored),
            chatTextResolver = BcChatTextResolver { _, _, _ -> error("resource unavailable") },
        )
        processor.handle(frame("Action"), receivingContext())

        val command = stored.single()
        assertEquals(frame("Action").raw, command.event.rawJson)
        assertEquals("Hello", command.message?.text)
        assertEquals(BcMessageResolution.Failed("Hello", "Hello"), command.message?.resolution)
    }

    @Test
    fun resourceFallbackPersistsUnresolvedMessageWithoutUsingBrokenResolver() = runTest {
        val stored = mutableListOf<BcInboundPersistenceCommand>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = recordingStore(stored),
            chatTextResolver = BcChatTextResolver { _, _, _ -> error("must not run") },
        )
        processor.handle(frame("Activity"), receivingContext(), PlainBcChatTextResolver)

        assertEquals(BcMessageResolution.NeedsTranslation("Hello"), stored.single().message?.resolution)
    }

    @Test
    fun cancellationOfTranslationDoesNotBecomePersistedFailure() = runTest {
        val stored = mutableListOf<BcInboundPersistenceCommand>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = recordingStore(stored),
            chatTextResolver = BcChatTextResolver { _, _, _ -> throw CancellationException("closed") },
        )
        try {
            processor.handle(frame("Action"), receivingContext())
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(stored.isEmpty())
        }
    }

    private fun recordingStore(commands: MutableList<BcInboundPersistenceCommand>) =
        object : BcInboundPersistenceStore {
            override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult {
                commands += command
                return BcInboundPersistenceResult.Recorded
            }
        }

    private fun receivingContext() = BcInboundMessageContext(
        ownerMemberNumber = 10,
        ownerDisplayName = "Self",
        roomMembers = mapOf(20 to "Alice"),
        roomConversationId = bcRoomConversationId("A"),
    )

    private fun frame(type: String = "Chat"): CanonicalFrame {
        val raw = """{"Sender":20,"Type":"$type","Content":"Hello"}"""
        return CanonicalFrame("/", "ChatRoomMessage", listOf(raw), 123, raw = raw, occurrenceId = 1)
    }
}
