package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.protocol.BcAccountBeepCodec
import dev.vivian.mobilebc.core.protocol.BcChatMessageCodec
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Lossless, ordered queue for frames owned by the messaging pipeline.
 *
 * Room-state and request-response events are handled directly by [RazorGatewayImpl] and must not
 * compete with persisted chat messages. Once filtered, an unbounded channel is acceptable here:
 * chat traffic is persisted by one sequential consumer and must not be silently discarded when a
 * database transaction or a cold dictionary lookup takes longer than a socket burst.
 */
internal class RazorInboundMessageQueue {
    private val undeliveredFrames = ArrayDeque<CanonicalFrame>()
    private val channel = Channel<CanonicalFrame>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { frame ->
            synchronized(undeliveredFrames) {
                undeliveredFrames.addLast(frame)
            }
        },
    )

    val frames: Flow<CanonicalFrame> = channel.receiveAsFlow()

    fun offer(frame: CanonicalFrame): Boolean {
        if (!frame.isMessagingFrame()) return false
        return channel.trySend(frame).isSuccess
    }

    fun close() {
        channel.close()
    }

    /** Call after stopping and joining the single collector so its interrupted receive is recovered. */
    fun drainPending(): List<CanonicalFrame> = synchronized(undeliveredFrames) {
        buildList {
            while (undeliveredFrames.isNotEmpty()) add(undeliveredFrames.removeFirst())
            while (true) add(channel.tryReceive().getOrNull() ?: break)
        }
    }

    private fun CanonicalFrame.isMessagingFrame(): Boolean =
        BcChatMessageCodec.canDecode(this) || BcAccountBeepCodec.canDecode(this)
}

/**
 * Coalesces the connection-health hint emitted for inbound chat traffic.
 *
 * A message remains queued for persistence regardless of this decision. The gate only prevents a
 * burst of chat packets from flooding the shared session-event flow with identical health hints.
 */
internal class RazorInboundMessageSignalGate(
    private val intervalMillis: Long = 15_000L,
) {
    private val lastSignalAtMillis = AtomicLong(NO_SIGNAL)

    init {
        require(intervalMillis >= 0L) { "Signal interval must not be negative." }
    }

    fun shouldSignal(nowMillis: Long): Boolean {
        while (true) {
            val previous = lastSignalAtMillis.get()
            if (
                previous != NO_SIGNAL &&
                nowMillis >= previous &&
                nowMillis - previous < intervalMillis
            ) {
                return false
            }
            if (lastSignalAtMillis.compareAndSet(previous, nowMillis)) return true
        }
    }

    fun reset() {
        lastSignalAtMillis.set(NO_SIGNAL)
    }

    private companion object {
        const val NO_SIGNAL = -1L
    }
}
