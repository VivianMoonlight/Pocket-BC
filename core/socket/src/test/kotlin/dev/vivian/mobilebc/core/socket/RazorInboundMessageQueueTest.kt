package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorInboundMessageQueueTest {
    @Test
    fun cancelledMatchedReceiveIsRecoveredBeforeTheChannelTailAfterClose() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val dispatcher = PausedDispatcher()
        val received = mutableListOf<CanonicalFrame>()
        val collector = launch(dispatcher) { queue.frames.collect { received += it } }
        dispatcher.runPending()
        assertEquals(0, dispatcher.pendingTaskCount)
        val matched = frame("ChatRoomMessage", 1)
        val buffered = frame("ChatRoomMessage", 2)
        assertTrue(queue.offer(matched))
        assertEquals(1, dispatcher.pendingTaskCount)
        assertTrue(queue.offer(buffered))
        queue.close()

        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { collector.cancelAndJoin() }
        dispatcher.runPending()
        stopping.join()

        assertTrue(received.isEmpty())
        assertEquals(listOf(matched, buffered), queue.drainPending())
        assertTrue(queue.drainPending().isEmpty())
        assertFalse(queue.offer(frame("ChatRoomMessage", 3)))
    }

    @Test
    fun recoveryDoesNotRepeatFramesAlreadyDeliveredToTheCollector() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val dispatcher = PausedDispatcher()
        val delivered = frame("ChatRoomMessage", 1)
        queue.offer(delivered)
        val received = mutableListOf<CanonicalFrame>()
        val collector = launch(dispatcher) { queue.frames.collect { received += it } }
        dispatcher.runPending()
        assertEquals(listOf(delivered), received)
        val matched = frame("ChatRoomMessage", 2)
        val buffered = frame("ChatRoomMessage", 3)
        queue.offer(matched)
        queue.offer(buffered)
        assertEquals(1, dispatcher.pendingTaskCount)

        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { collector.cancelAndJoin() }
        dispatcher.runPending()
        stopping.join()

        assertEquals(listOf(delivered), received)
        assertEquals(listOf(matched, buffered), queue.drainPending())
        assertTrue(queue.drainPending().isEmpty())
    }

    @Test
    fun hostCanDrainAcceptedFramesBeforeTerminalCloseAndFutureOffersAreRejected() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val accepted = frame("ChatRoomMessage", 1)
        queue.offer(accepted)

        assertEquals(listOf(accepted), queue.drainPending())
        assertTrue(queue.drainPending().isEmpty())
        val remaining = frame("ChatRoomMessage", 2)
        queue.offer(remaining)
        queue.close()
        queue.close()

        assertFalse(queue.offer(frame("ChatRoomMessage", 3)))
        assertEquals(listOf(remaining), queue.frames.toList())
    }

    @Test
    fun filtersNonMessagingFramesBeforeTheyEnterTheQueue() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val roomSync = frame(eventName = "ChatRoomSync", occurrenceId = 1)
        val chat = frame(eventName = "ChatRoomChat", occurrenceId = 2)

        assertFalse(queue.offer(roomSync))
        assertTrue(queue.offer(chat))

        assertEquals(listOf(chat), queue.frames.take(1).toList())
    }

    @Test
    fun retainsOrderBeyondThePreviousSharedFlowCapacity() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val frames = (1L..256L).map { occurrenceId ->
            frame(eventName = "ChatRoomMessage", occurrenceId = occurrenceId)
        }

        frames.forEach { frame -> assertTrue(queue.offer(frame)) }

        assertEquals(frames, queue.frames.take(frames.size).toList())
    }

    @Test
    fun acceptsWrappedChatRoomChatButRejectsOtherMessageWrappers() = runBlocking {
        val queue = RazorInboundMessageQueue()
        val wrappedChat = frame(
            eventName = "Message",
            occurrenceId = 1,
            raw = """{"Message":"ChatRoomChat","Data":{"Type":"Chat","Content":"hello","Dictionary":[]}}""",
        )
        val unrelated = frame(
            eventName = "Message",
            occurrenceId = 2,
            raw = """{"Message":"ChatRoomSync","Data":{}}""",
        )

        assertTrue(queue.offer(wrappedChat))
        assertFalse(queue.offer(unrelated))
        assertEquals(listOf(wrappedChat), queue.frames.take(1).toList())
    }

    @Test
    fun coalescesSessionHealthSignalsWithoutAffectingQueuedMessages() {
        val gate = RazorInboundMessageSignalGate(intervalMillis = 15_000L)

        assertTrue(gate.shouldSignal(nowMillis = 100_000L))
        assertFalse(gate.shouldSignal(nowMillis = 100_001L))
        assertFalse(gate.shouldSignal(nowMillis = 114_999L))
        assertTrue(gate.shouldSignal(nowMillis = 115_000L))

        gate.reset()
        assertTrue(gate.shouldSignal(nowMillis = 115_001L))
    }

    private fun frame(
        eventName: String,
        occurrenceId: Long,
        raw: String = "{}",
    ): CanonicalFrame = CanonicalFrame(
        namespace = "/",
        transportEventName = eventName,
        args = listOf(raw),
        receivedAtMillis = occurrenceId,
        raw = raw,
        occurrenceId = occurrenceId,
    )

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pendingTaskCount: Int get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runPending() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
