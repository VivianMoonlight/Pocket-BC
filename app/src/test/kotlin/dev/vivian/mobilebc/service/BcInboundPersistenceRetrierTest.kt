package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcInboundPersistenceRetrierTest {
    @Test
    fun normalProcessingRetriesTemporaryFailureUntilSaved() = runTest {
        var attempts = 0
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)

        retrier.retryUntilSaved {
            attempts++
            if (attempts < 3) error("database temporarily unavailable")
        }

        assertEquals(3, attempts)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun normalProcessingRetryStopsWhenCancelled() = runTest {
        var attempts = 0
        var saved = false
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val processing = launch {
            retrier.retryUntilSaved {
                attempts++
                error("database unavailable")
            }
            saved = true
        }
        runCurrent()
        assertEquals(1, attempts)

        processing.cancelAndJoin()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, attempts)
        assertFalse(saved)
    }

    @Test
    fun normalProcessingPropagatesCancellationException() = runTest {
        var attempts = 0
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)

        try {
            retrier.retryUntilSaved {
                attempts++
                throw CancellationException("session stopped")
            }
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun retainedFailureRemainsOwnedUntilSaved() = runTest {
        var available = false
        val saved = mutableListOf<CanonicalFrame>()
        val failures = mutableListOf<CanonicalFrame>()
        val pending = frame("one")
        val retrier = BcInboundPersistenceRetrier(
            backgroundScope,
            retryDelayMillis = 10,
            onFailure = { failedFrame, _ -> failures += failedFrame },
        )

        retrier.retain(pending) {
            if (!available) error("database unavailable")
            saved += it
        }
        assertEquals(1, retrier.pendingCount)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(1, retrier.pendingCount)
        assertEquals(listOf(pending, pending), failures)
        assertTrue(saved.isEmpty())

        available = true
        advanceTimeBy(10)
        runCurrent()

        assertEquals(listOf(pending), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun retainedRetriesPreserveFrameOrder() = runTest {
        var firstAttempts = 0
        val attempts = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = {
            attempts += it.raw
            if (it.raw == "one" && ++firstAttempts < 3) error("database unavailable")
            saved += it.raw
        }
        retrier.retain(frame("one"), save)
        retrier.retain(frame("two"), save)
        retrier.retain(frame("three"), save)
        runCurrent()

        assertEquals(listOf("one"), attempts)
        assertEquals(3, retrier.pendingCount)

        advanceTimeBy(20)
        runCurrent()

        assertEquals(listOf("one", "one", "one", "two", "three"), attempts)
        assertEquals(listOf("one", "two", "three"), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun retainingSameObjectDeduplicatesButEqualDistinctFramesRemain() = runTest {
        val first = frame("identical")
        val second = first.copy()
        val saved = mutableListOf<CanonicalFrame>()
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = { saved += it }
        assertEquals(first, second)

        retrier.retain(first, save)
        retrier.retain(first, save)
        retrier.retain(second, save)
        assertEquals(2, retrier.pendingCount)
        runCurrent()

        assertEquals(2, saved.size)
        assertSame(first, saved[0])
        assertSame(second, saved[1])
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun concurrentRetainsUseOneWorkerAndDeduplicateByIdentity() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val pending = frame("one")
        val saved = mutableListOf<CanonicalFrame>()
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = {
            releaseSave.await()
            saved += it
        }

        coroutineScope {
            repeat(64) {
                launch(Dispatchers.Default) { retrier.retain(pending, save) }
            }
        }
        assertEquals(1, retrier.pendingCount)
        assertTrue(saved.isEmpty())

        releaseSave.complete(Unit)
        runCurrent()

        assertEquals(listOf(pending), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun cancelledPersistenceScopeRetainsUnsavedFrames() = runTest {
        val persistenceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var attempts = 0
        val retrier = BcInboundPersistenceRetrier(persistenceScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = {
            attempts++
            error("database unavailable")
        }
        retrier.retain(frame("one"), save)
        retrier.retain(frame("two"), save)
        runCurrent()

        persistenceScope.cancel()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(2, retrier.pendingCount)
    }

    @Test
    fun diagnosticFailureDoesNotStopRetainedRetries() = runTest {
        var attempts = 0
        val saved = mutableListOf<CanonicalFrame>()
        val pending = frame("one")
        val retrier = BcInboundPersistenceRetrier(
            backgroundScope,
            retryDelayMillis = 10,
            onFailure = { _, _ -> error("logger unavailable") },
        )
        retrier.retain(pending) {
            if (++attempts == 1) error("database unavailable")
            saved += it
        }
        runCurrent()
        assertEquals(1, retrier.pendingCount)

        advanceTimeBy(10)
        runCurrent()

        assertEquals(listOf(pending), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun retainingPendingFrameRestartsCancelledWorkerWithoutDuplicatingIt() = runTest {
        var attempts = 0
        val pending = frame("one")
        val saved = mutableListOf<CanonicalFrame>()
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = {
            if (++attempts == 1) throw CancellationException("save interrupted")
            saved += it
        }
        retrier.retain(pending, save)
        runCurrent()
        assertEquals(1, retrier.pendingCount)
        assertTrue(saved.isEmpty())

        retrier.retain(pending, save)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(listOf(pending), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun newRetainedFrameStartsWorkerAfterPreviousQueueDrains() = runTest {
        val saved = mutableListOf<String>()
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val save: suspend (CanonicalFrame) -> Unit = { saved += it.raw }
        retrier.retain(frame("one"), save)
        runCurrent()
        assertEquals(0, retrier.pendingCount)

        retrier.retain(frame("two"), save)
        runCurrent()

        assertEquals(listOf("one", "two"), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun cancelledMatchedWorkReceiveRetainsFrameUntilPersistenceRecovers() = runTest {
        var available = false
        val received = mutableListOf<CanonicalFrame>()
        val saved = mutableListOf<CanonicalFrame>()
        val pending = frame("one")
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val work = createInboundWorkChannel { undelivered ->
            retrier.retain(undelivered) {
                if (!available) error("database unavailable")
                saved += it
            }
        }
        val receiver = launch(StandardTestDispatcher(testScheduler)) {
            for (item in work) {
                if (item is BcInboundWork.Frame) received += item.frame
            }
        }
        runCurrent()
        assertTrue(work.trySend(BcInboundWork.Frame(pending)).isSuccess)

        receiver.cancel()
        runCurrent()

        assertTrue(received.isEmpty())
        assertTrue(saved.isEmpty())
        assertEquals(1, retrier.pendingCount)

        available = true
        advanceTimeBy(10)
        runCurrent()
        work.cancel()
        runCurrent()

        assertEquals(listOf(pending), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun cancelledWorkReceiveDoesNotRetainFramesAlreadyProcessed() = runTest {
        val received = mutableListOf<CanonicalFrame>()
        val saved = mutableListOf<CanonicalFrame>()
        val delivered = frame("delivered")
        val interrupted = frame("interrupted")
        val retrier = BcInboundPersistenceRetrier(backgroundScope, retryDelayMillis = 10)
        val work = createInboundWorkChannel { undelivered ->
            retrier.retain(undelivered) { saved += it }
        }
        val receiver = launch(StandardTestDispatcher(testScheduler)) {
            for (item in work) {
                if (item is BcInboundWork.Frame) received += item.frame
            }
        }
        runCurrent()
        assertTrue(work.trySend(BcInboundWork.Frame(delivered)).isSuccess)
        runCurrent()
        assertEquals(listOf(delivered), received)

        assertTrue(work.trySend(BcInboundWork.Frame(interrupted)).isSuccess)
        receiver.cancel()
        runCurrent()
        work.cancel()
        runCurrent()

        assertEquals(listOf(delivered), received)
        assertEquals(listOf(interrupted), saved)
        assertEquals(0, retrier.pendingCount)
    }

    @Test
    fun cancelledResourceCompletionReceiveDoesNotInvokeFrameRecovery() = runTest {
        val received = mutableListOf<BcInboundWork>()
        val undelivered = mutableListOf<CanonicalFrame>()
        val work = createInboundWorkChannel { undelivered += it }
        val receiver = launch(StandardTestDispatcher(testScheduler)) {
            for (item in work) received += item
        }
        runCurrent()
        assertTrue(
            work.trySend(
                BcInboundWork.ResourceSyncCompleted(
                    BcInboundResourceKey(41L, "R131", "CN"),
                    BcInboundResourceSyncResult.Success,
                ),
            ).isSuccess,
        )

        receiver.cancel()
        runCurrent()
        work.cancel()

        assertTrue(received.isEmpty())
        assertTrue(undelivered.isEmpty())
    }

    private fun frame(content: String) = CanonicalFrame(
        namespace = "/",
        transportEventName = "ChatRoomMessage",
        args = listOf(content),
        receivedAtMillis = 1L,
        raw = content,
    )
}
