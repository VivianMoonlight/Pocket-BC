package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

internal sealed interface BcInboundWork {
    data class Frame(val frame: CanonicalFrame) : BcInboundWork
    data class ResourceSyncCompleted(
        val key: BcInboundResourceKey,
        val result: BcInboundResourceSyncResult,
    ) : BcInboundWork
}

internal fun createInboundWorkChannel(
    onUndeliveredFrame: (CanonicalFrame) -> Unit,
): Channel<BcInboundWork> = Channel(
    capacity = Channel.UNLIMITED,
    onUndeliveredElement = { work ->
        if (work is BcInboundWork.Frame) onUndeliveredFrame(work.frame)
    },
)

internal sealed interface BcInboundResourceSyncResult {
    data object Success : BcInboundResourceSyncResult
    data class RetryableFailure(val error: Throwable) : BcInboundResourceSyncResult
    data class PermanentFailure(val error: Throwable) : BcInboundResourceSyncResult
}

internal class BcInboundWorkCoordinator(
    private val sessionId: Long,
    private val resourceGate: BcInboundResourceGate,
    private val processFrame: suspend (CanonicalFrame) -> Unit,
    private val requestResourceSync: (BcInboundResourceKey) -> Boolean,
    private val processUnresolvedFrame: suspend (CanonicalFrame) -> Unit = processFrame,
    private val onRejected: (
        frame: CanonicalFrame,
        decision: BcInboundResourceGateDecision.Reject,
    ) -> Unit = { _, _ -> },
    private val onOverflow: (
        frame: CanonicalFrame,
        key: BcInboundResourceKey,
    ) -> Unit = { _, _ -> },
    private val onPermanentFailure: (
        key: BcInboundResourceKey,
        frames: List<CanonicalFrame>,
        error: Throwable,
    ) -> Unit = { _, _, _ -> },
    private val onStaleCompletion: (BcInboundResourceKey) -> Unit = {},
    private val deferredCapacity: Int = DEFAULT_DEFERRED_CAPACITY,
) {
    private data class DeferredFrame(
        val key: BcInboundResourceKey,
        val frame: CanonicalFrame,
    )

    private val deferredFrames = ArrayDeque<DeferredFrame>()
    private val requestedKeys = mutableSetOf<BcInboundResourceKey>()

    init {
        require(deferredCapacity > 0) { "Deferred inbound capacity must be positive." }
    }

    internal val deferredCount: Int
        get() = deferredFrames.size

    fun ownsDeferred(frame: CanonicalFrame): Boolean = deferredFrames.any { it.frame === frame }

    suspend fun handle(work: BcInboundWork) {
        when (work) {
            is BcInboundWork.Frame -> handleFrame(work.frame)
            is BcInboundWork.ResourceSyncCompleted -> handleResourceSyncCompleted(work.key, work.result)
        }
    }

    fun takeDeferredFrames(): List<CanonicalFrame> {
        val frames = deferredFrames.map(DeferredFrame::frame)
        deferredFrames.clear()
        requestedKeys.clear()
        return frames
    }

    private suspend fun handleFrame(frame: CanonicalFrame) {
        when (val decision = resourceGate.decide(frame)) {
            BcInboundResourceGateDecision.Process -> processFrame(frame)
            is BcInboundResourceGateDecision.Defer -> defer(frame, decision.key)
            is BcInboundResourceGateDecision.Reject -> rejectUnresolvedFrame(frame, decision)
        }
    }

    private suspend fun handleResourceSyncCompleted(
        key: BcInboundResourceKey,
        result: BcInboundResourceSyncResult,
    ) {
        if (key.sessionId != sessionId) {
            onStaleCompletion(key)
            return
        }
        when (result) {
            is BcInboundResourceSyncResult.RetryableFailure -> Unit
            BcInboundResourceSyncResult.Success -> replayReadyFrames(key)
            is BcInboundResourceSyncResult.PermanentFailure -> {
                requestedKeys.remove(key)
                val matchingFrames = deferredFrames.filter { it.key == key }
                onPermanentFailure(key, matchingFrames.map(DeferredFrame::frame), result.error)
                processUnresolvedFrames(matchingFrames)
            }
        }
    }

    private suspend fun replayReadyFrames(key: BcInboundResourceKey) {
        requestedKeys.remove(key)
        var stillDeferred = false
        for (deferred in deferredFrames.filter { it.key == key }) {
            when (val decision = resourceGate.decide(deferred.frame, expectedKey = key)) {
                BcInboundResourceGateDecision.Process -> {
                    processFrame(deferred.frame)
                    deferredFrames.remove(deferred)
                }
                is BcInboundResourceGateDecision.Defer -> stillDeferred = true
                is BcInboundResourceGateDecision.Reject -> {
                    rejectUnresolvedFrame(deferred.frame, decision)
                    deferredFrames.remove(deferred)
                }
            }
        }
        if (stillDeferred) requestSyncIfNeeded(key, deferredFrames.first { it.key == key }.frame)
    }

    private suspend fun defer(frame: CanonicalFrame, key: BcInboundResourceKey) {
        if (key.sessionId != sessionId) {
            onRejected(
                frame,
                BcInboundResourceGateDecision.Reject(
                    version = key.version,
                    language = key.language,
                    error = BcInboundResourceContextChangedException(
                        "Resource key belongs to session ${key.sessionId}, not $sessionId.",
                    ),
                ),
            )
            return
        }
        deferredFrames.addLast(DeferredFrame(key, frame))
        if (deferredFrames.size > deferredCapacity) {
            val oldest = deferredFrames.first
            onOverflow(oldest.frame, oldest.key)
            processUnresolvedFrame(oldest.frame)
            deferredFrames.removeFirst()
        }
        requestSyncIfNeeded(key, frame)
    }

    private suspend fun requestSyncIfNeeded(key: BcInboundResourceKey, frame: CanonicalFrame) {
        if (key in requestedKeys) return
        var failure: Throwable? = null
        val requested = try {
            requestResourceSync(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
            false
        }
        if (requested) {
            requestedKeys += key
        } else {
            onRejected(
                frame,
                BcInboundResourceGateDecision.Reject(
                    key.version,
                    key.language,
                    failure ?: IllegalStateException("Resource sync was not accepted for $key."),
                ),
            )
            processUnresolvedFrames(deferredFrames.filter { it.key == key })
        }
    }

    private suspend fun rejectUnresolvedFrame(
        frame: CanonicalFrame,
        decision: BcInboundResourceGateDecision.Reject,
    ) {
        if (decision.error is CancellationException) throw decision.error
        onRejected(frame, decision)
        processUnresolvedFrame(frame)
    }

    private suspend fun processUnresolvedFrames(frames: List<DeferredFrame>) {
        for (deferred in frames) {
            processUnresolvedFrame(deferred.frame)
            deferredFrames.remove(deferred)
        }
    }

    companion object {
        const val DEFAULT_DEFERRED_CAPACITY: Int = 256
    }
}
