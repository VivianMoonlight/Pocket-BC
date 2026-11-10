package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal class BcInboundPersistenceRetrier(
    private val scope: CoroutineScope,
    private val retryDelayMillis: Long = 1_000L,
    private val onFailure: (CanonicalFrame, Throwable) -> Unit = { _, _ -> },
) {
    private class PendingFrame(
        val frame: CanonicalFrame,
        val saveUnresolved: suspend (CanonicalFrame) -> Unit,
    )

    private val lock = Any()
    private val pendingFrames = ArrayDeque<PendingFrame>()
    private var workerToken: Any? = null

    init {
        require(retryDelayMillis > 0) { "Inbound persistence retry delay must be positive." }
    }

    val pendingCount: Int
        get() = synchronized(lock) { pendingFrames.size }

    suspend fun retryUntilSaved(save: suspend () -> Unit) {
        while (true) {
            coroutineContext.ensureActive()
            try {
                save()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                delay(retryDelayMillis)
            }
        }
    }

    fun retain(frame: CanonicalFrame, saveUnresolved: suspend (CanonicalFrame) -> Unit) {
        val token = synchronized(lock) {
            if (pendingFrames.none { it.frame === frame }) {
                pendingFrames.addLast(PendingFrame(frame, saveUnresolved))
            }
            if (workerToken != null) null else Any().also { workerToken = it }
        }
        if (token != null) scope.launch { saveRetainedFrames(token) }
    }

    private suspend fun saveRetainedFrames(token: Any) {
        try {
            while (true) {
                val pending = synchronized(lock) {
                    pendingFrames.peekFirst().also {
                        if (it == null && workerToken === token) workerToken = null
                    }
                } ?: return
                retryUntilSaved {
                    try {
                        pending.saveUnresolved(pending.frame)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        runCatching { onFailure(pending.frame, failure) }
                        throw failure
                    }
                }
                synchronized(lock) { pendingFrames.removeFirst() }
            }
        } finally {
            synchronized(lock) {
                if (workerToken === token) workerToken = null
            }
        }
    }
}
