package dev.vivian.mobilebc.core.socket

import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

internal class RazorRateLimiter(
    private val executor: ScheduledExecutorService,
    private val chatMaxMessages: Int = 14,
    private val chatWindowMs: Long = 1_200L,
    private val emit: (String, String) -> Unit,
) {
    private val queues: Map<OutboundLane, ArrayDeque<QueuedPacket>> =
        OutboundLane.entries.associateWith { ArrayDeque() }
    private val chatSentAtMillis = ArrayDeque<Long>()
    private var drainScheduled = false
    private var drainGeneration = 0L
    private var closed = false

    suspend fun send(packet: OutboundPacket) {
        val completion = CompletableDeferred<Unit>()
        enqueue(packet, completion)
        try {
            completion.await()
        } catch (cancelled: CancellationException) {
            synchronized(this) {
                queues.values.forEach { queue -> queue.removeAll { it.completion === completion } }
            }
            completion.cancel(cancelled)
            throw cancelled
        }
    }

    fun enqueue(packet: OutboundPacket) {
        enqueue(packet, completion = null)
    }

    fun clear(failure: Throwable = IllegalStateException("Socket sender queue was cleared.")) {
        val pending = mutableListOf<QueuedPacket>()
        synchronized(this) {
            queues.values.forEach { queue ->
                while (queue.isNotEmpty()) pending += queue.removeFirst()
            }
            chatSentAtMillis.clear()
            drainScheduled = false
            drainGeneration++
        }
        pending.forEach { queued ->
            queued.completion?.completeExceptionally(failure)
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        clear()
    }

    private fun enqueue(packet: OutboundPacket, completion: CompletableDeferred<Unit>?) {
        synchronized(this) {
            check(!closed) { "Socket sender is closed." }
            val queue = queues.getValue(packet.lane)
            queue.addLast(QueuedPacket(packet, completion))

            val shouldPreemptBlockedChatDrain =
                packet.lane != OutboundLane.Chat && drainScheduled

            scheduleDrainLocked(delayMs = 0L, replace = shouldPreemptBlockedChatDrain)
        }
    }

    private fun drain(generation: Long) {
        while (true) {
            val queued = synchronized(this) {
                if (closed || generation != drainGeneration) return

                val now = System.currentTimeMillis()
                pruneChatWindowLocked(now)

                val next = nextSendableLocked()
                if (next == null) {
                    val delayMs = nextDelayLocked(now)
                    if (delayMs == null) {
                        drainScheduled = false
                    } else {
                        drainScheduled = false
                        scheduleDrainLocked(delayMs)
                    }
                    return
                }

                if (next.packet.lane == OutboundLane.Chat) {
                    chatSentAtMillis.addLast(now)
                }
                next
            }

            runCatching {
                val emitPacket = {
                    val batch = queued.packet.prepareEmissions?.invoke()
                    if (batch != null) batch.forEach { (event, payload) -> emit(event, payload) }
                    else {
                        val payload = queued.packet.preparePayloadJson?.invoke() ?: queued.packet.payloadJson
                        emit(queued.packet.eventName, payload)
                    }
                }
                val guard = queued.packet.emitGuard
                if (guard == null) emitPacket() else guard(emitPacket)
            }.onSuccess {
                queued.completion?.complete(Unit)
            }.onFailure { throwable ->
                queued.completion?.completeExceptionally(throwable)
            }
        }
    }

    private fun nextSendableLocked(): QueuedPacket? {
        OutboundLane.entries.sortedBy { it.priority }.forEach { lane ->
            if (lane == OutboundLane.Chat && chatSentAtMillis.size >= chatMaxMessages) return@forEach
            val queue = queues.getValue(lane)
            if (queue.isNotEmpty()) return queue.removeFirst()
        }
        return null
    }

    private fun nextDelayLocked(now: Long): Long? {
        if (queues.values.all { it.isEmpty() }) return null
        if (queues.anyNonChatPending()) return 1L
        val oldestChat = chatSentAtMillis.firstOrNull() ?: return 1L
        return (oldestChat + chatWindowMs - now).coerceAtLeast(1L)
    }

    private fun Map<OutboundLane, ArrayDeque<QueuedPacket>>.anyNonChatPending(): Boolean =
        any { (lane, queue) -> lane != OutboundLane.Chat && queue.isNotEmpty() }

    private fun pruneChatWindowLocked(now: Long) {
        val windowStart = now - chatWindowMs
        while (chatSentAtMillis.isNotEmpty() && chatSentAtMillis.first() < windowStart) {
            chatSentAtMillis.removeFirst()
        }
    }

    private fun scheduleDrainLocked(delayMs: Long, replace: Boolean = false) {
        if (drainScheduled && !replace) return

        drainScheduled = true
        val generation = ++drainGeneration

        executor.schedule(
            Runnable { drain(generation) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }
}

internal data class OutboundPacket(
    val eventName: String,
    val payloadJson: String,
    val lane: OutboundLane,
    val emitGuard: ((() -> Unit) -> Unit)? = null,
    val preparePayloadJson: (() -> String)? = null,
    val prepareEmissions: (() -> List<Pair<String, String>>)? = null,
)

internal enum class OutboundLane(val priority: Int) {
    Control(0),
    RoomControl(1),
    InteractiveQuery(2),
    Chat(3),
    AccountUpdate(4),
}

private data class QueuedPacket(
    val packet: OutboundPacket,
    val completion: CompletableDeferred<Unit>?,
)
