package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BcInboundWorkCoordinatorTest {
    @Test
    fun plainWhileDeferred() = runTest {
        var ready = false
        val processed = mutableListOf<String>()
        val requested = mutableListOf<BcInboundResourceKey>()
        val coordinator = coordinator(
            ready = { ready },
            processed = processed,
            requested = requested,
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "deferred")))
        coordinator.handle(BcInboundWork.Frame(chatFrame("Chat", "plain")))

        assertEquals(listOf("plain"), processed)
        assertEquals(listOf(KEY), requested)
        assertEquals(1, coordinator.deferredCount)
    }

    @Test
    fun successfulReplayExactlyOnce() = runTest {
        var ready = false
        val processed = mutableListOf<String>()
        val requested = mutableListOf<BcInboundResourceKey>()
        val coordinator = coordinator({ ready }, processed, requested)
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(BcInboundWork.Frame(chatFrame("Activity", "two")))

        ready = true
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))

        assertEquals(listOf("one", "two"), processed)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun permanentFailure() = runTest {
        val dropped = mutableListOf<Pair<BcInboundResourceKey, List<String>>>()
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { false },
            processed = processed,
            requested = mutableListOf(),
            unresolved = unresolved,
            onPermanentFailure = { key, frames, _ ->
                dropped += key to frames.map(::content)
            },
        )
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(BcInboundWork.Frame(chatFrame("Activity", "two")))

        coordinator.handle(
            syncCompleted(
                KEY,
                BcInboundResourceSyncResult.PermanentFailure(IllegalStateException("missing")),
            ),
        )

        assertEquals(listOf(KEY to listOf("one", "two")), dropped)
        assertEquals(listOf("one", "two"), unresolved)
        assertTrue(processed.isEmpty())
        assertEquals(0, coordinator.deferredCount)
        coordinator.handle(
            syncCompleted(
                KEY,
                BcInboundResourceSyncResult.PermanentFailure(IllegalStateException("missing")),
            ),
        )
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
        assertEquals(listOf("one", "two"), unresolved)
    }

    @Test
    fun permanentFailurePreservesOnlyFramesForTheMatchingResourceKey() = runTest {
        var language = "CN"
        var ready = false
        val processed = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val gate = BcInboundResourceGate(
            sessionId = SESSION_ID,
            versionProvider = { "R131" },
            languageProvider = { language },
            resourcesReady = { _, _ -> ready },
        )
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = gate,
            processFrame = { frame -> processed += content(frame) },
            processUnresolvedFrame = { frame -> unresolved += content(frame) },
            requestResourceSync = { true },
            onPermanentFailure = { _, frames, _ -> dropped += frames.map(::content) },
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "chinese")))
        language = "EN"
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "english")))

        coordinator.handle(
            syncCompleted(
                KEY,
                BcInboundResourceSyncResult.PermanentFailure(IllegalStateException("missing CN")),
            ),
        )

        assertEquals(listOf("chinese"), dropped)
        assertEquals(listOf("chinese"), unresolved)
        assertTrue(processed.isEmpty())
        assertEquals(1, coordinator.deferredCount)

        ready = true
        coordinator.handle(syncCompleted(KEY.copy(language = "EN"), BcInboundResourceSyncResult.Success))
        assertEquals(listOf("english"), processed)
    }

    @Test
    fun changedLanguageRejectsDeferredFramesInsteadOfReplayingThem() = runTest {
        var language = "CN"
        var ready = false
        val processed = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val gate = BcInboundResourceGate(
            sessionId = SESSION_ID,
            versionProvider = { "R131" },
            languageProvider = { language },
            resourcesReady = { _, _ -> ready },
        )
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = gate,
            processFrame = { frame -> processed += content(frame) },
            processUnresolvedFrame = { frame -> unresolved += content(frame) },
            requestResourceSync = { true },
            onRejected = { frame, _ -> rejected += content(frame) },
        )
        coordinator.handle(BcInboundWork.Frame(chatFrame("Activity", "old-language")))

        language = "EN"
        ready = true
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))

        assertTrue(processed.isEmpty())
        assertEquals(listOf("old-language"), rejected)
        assertEquals(listOf("old-language"), unresolved)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun keyMismatchAndOwnershipHandoffPreventReplay() = runTest {
        var ready = false
        val processed = mutableListOf<String>()
        val coordinator = coordinator({ ready }, processed, mutableListOf())
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))

        ready = true
        coordinator.handle(syncCompleted(KEY.copy(language = "EN"), BcInboundResourceSyncResult.Success))
        assertTrue(processed.isEmpty())
        assertEquals(1, coordinator.deferredCount)

        assertEquals(listOf("one"), coordinator.takeDeferredFrames().map(::content))
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
        assertTrue(processed.isEmpty())
        assertEquals(0, coordinator.deferredCount)
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    @Test
    fun overflow() = runTest {
        val overflowed = mutableListOf<String>()
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        var ready = false
        val coordinator = coordinator(
            ready = { ready },
            processed = processed,
            requested = mutableListOf(),
            unresolved = unresolved,
            capacity = 2,
            onOverflow = { frame, _ -> overflowed += content(frame) },
        )
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "two")))
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "three")))

        ready = true
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))

        assertEquals(listOf("one"), overflowed)
        assertEquals(listOf("one"), unresolved)
        assertEquals(listOf("two", "three"), processed)
    }

    @Test
    fun retryableFailureKeepsDeferredFrames() = runTest {
        val processed = mutableListOf<String>()
        var ready = false
        val coordinator = coordinator({ ready }, processed, mutableListOf())
        coordinator.handle(BcInboundWork.Frame(chatFrame("ServerMessage", "one")))
        coordinator.handle(
            syncCompleted(
                KEY,
                BcInboundResourceSyncResult.RetryableFailure(IllegalStateException("offline")),
            ),
        )
        assertEquals(1, coordinator.deferredCount)

        ready = true
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
        assertEquals(listOf("one"), processed)
    }

    @Test
    fun rejectedResourceContextPreservesUnresolvedFrame() = runTest {
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val gate = BcInboundResourceGate(
            sessionId = SESSION_ID,
            versionProvider = { "" },
            languageProvider = { "CN" },
            resourcesReady = { _, _ -> false },
        )
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = gate,
            processFrame = { processed += content(it) },
            processUnresolvedFrame = { unresolved += content(it) },
            requestResourceSync = { error("Rejected resources must not request sync.") },
            onRejected = { frame, _ -> rejected += content(frame) },
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))

        assertTrue(processed.isEmpty())
        assertEquals(listOf("one"), rejected)
        assertEquals(listOf("one"), unresolved)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun cancelledResourceGatePropagatesWithoutFallback() = runTest {
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = BcInboundResourceGate(
                sessionId = SESSION_ID,
                versionProvider = { throw CancellationException("stopping") },
                languageProvider = { KEY.language },
                resourcesReady = { _, _ -> false },
            ),
            processFrame = { processed += content(it) },
            processUnresolvedFrame = { unresolved += content(it) },
            requestResourceSync = { error("Cancelled lookup must not request sync.") },
        )

        try {
            coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertTrue(processed.isEmpty())
            assertTrue(unresolved.isEmpty())
            assertEquals(0, coordinator.deferredCount)
        }
    }

    @Test
    fun failedSyncEnqueuePreservesUnresolvedFrameExactlyOnce() = runTest {
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { false },
            processed = processed,
            requested = mutableListOf(),
            unresolved = unresolved,
            requestSync = { false },
            onRejected = { frame, _ -> rejected += content(frame) },
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
        assertTrue(coordinator.takeDeferredFrames().isEmpty())

        assertTrue(processed.isEmpty())
        assertEquals(listOf("one"), rejected)
        assertEquals(listOf("one"), unresolved)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun syncEnqueueExceptionPreservesUnresolvedFrame() = runTest {
        val failure = IllegalStateException("sync queue closed")
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<Throwable>()
        val coordinator = coordinator(
            ready = { false },
            processed = mutableListOf(),
            requested = mutableListOf(),
            unresolved = unresolved,
            requestSync = { throw failure },
            onRejected = { _, decision -> rejected += decision.error },
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))

        assertEquals(listOf(failure), rejected)
        assertEquals(listOf("one"), unresolved)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun cancelledSyncEnqueuePropagatesAndRetainsFrameForHandoff() = runTest {
        val unresolved = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { false },
            processed = mutableListOf(),
            requested = mutableListOf(),
            unresolved = unresolved,
            requestSync = { throw CancellationException("stopping") },
        )
        val frame = chatFrame("Action", "one")

        try {
            coordinator.handle(BcInboundWork.Frame(frame))
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertTrue(coordinator.ownsDeferred(frame))
            assertTrue(unresolved.isEmpty())
        }
        val transferred = coordinator.takeDeferredFrames()

        assertSame(frame, transferred.single())
        assertTrue(unresolved.isEmpty())
        assertFalse(coordinator.ownsDeferred(frame))
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    @Test
    fun wrongSessionResourceKeyIsRejectedWithoutProcessingOrSync() = runTest {
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val gate = BcInboundResourceGate(
            sessionId = SESSION_ID + 1,
            versionProvider = { KEY.version },
            languageProvider = { KEY.language },
            resourcesReady = { _, _ -> false },
        )
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = gate,
            processFrame = { processed += content(it) },
            processUnresolvedFrame = { unresolved += content(it) },
            requestResourceSync = { error("Another session must not request sync.") },
            onRejected = { frame, _ -> rejected += content(frame) },
        )

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        assertTrue(coordinator.takeDeferredFrames().isEmpty())

        assertTrue(processed.isEmpty())
        assertTrue(unresolved.isEmpty())
        assertEquals(listOf("one"), rejected)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun takingDeferredFramesPreservesArrivalOrderAcrossResourceKeysExactlyOnce() = runTest {
        var language = "CN"
        val processed = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val coordinator = BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = BcInboundResourceGate(
                sessionId = SESSION_ID,
                versionProvider = { KEY.version },
                languageProvider = { language },
                resourcesReady = { _, _ -> false },
            ),
            processFrame = { processed += content(it) },
            processUnresolvedFrame = { unresolved += content(it) },
            requestResourceSync = { true },
        )
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        language = "EN"
        coordinator.handle(BcInboundWork.Frame(chatFrame("Activity", "two")))
        language = "CN"
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "three")))

        val transferred = coordinator.takeDeferredFrames()
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
        coordinator.handle(syncCompleted(KEY.copy(language = "EN"), BcInboundResourceSyncResult.Success))

        assertTrue(processed.isEmpty())
        assertTrue(unresolved.isEmpty())
        assertEquals(listOf("one", "two", "three"), transferred.map(::content))
        assertTrue(transferred.none(coordinator::ownsDeferred))
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun takingDeferredFramesTransfersOwnershipAndClearsSyncRequests() = runTest {
        val unresolved = mutableListOf<String>()
        val requested = mutableListOf<BcInboundResourceKey>()
        val coordinator = coordinator(
            ready = { false },
            processed = mutableListOf(),
            requested = requested,
            unresolved = unresolved,
        )
        val first = chatFrame("Action", "one")
        val second = chatFrame("Action", "two")
        coordinator.handle(BcInboundWork.Frame(first))
        coordinator.handle(BcInboundWork.Frame(second))

        val transferred = coordinator.takeDeferredFrames()
        assertTrue(coordinator.takeDeferredFrames().isEmpty())

        assertEquals(2, transferred.size)
        assertSame(first, transferred[0])
        assertSame(second, transferred[1])
        assertEquals(0, coordinator.deferredCount)
        assertFalse(coordinator.ownsDeferred(first))
        assertFalse(coordinator.ownsDeferred(second))
        assertTrue(unresolved.isEmpty())

        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "three")))
        assertEquals(listOf(KEY, KEY), requested)
    }

    @Test
    fun cancelledPermanentFailureFallbackRetainsUnsavedFramesForHandoff() = runTest {
        val unresolved = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { false },
            processed = mutableListOf(),
            requested = mutableListOf(),
            processUnresolved = { frame ->
                if (content(frame) == "two") throw CancellationException("stopping")
                unresolved += content(frame)
            },
        )
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        val second = chatFrame("Activity", "two")
        val third = chatFrame("Action", "three")
        coordinator.handle(BcInboundWork.Frame(second))
        coordinator.handle(BcInboundWork.Frame(third))

        try {
            coordinator.handle(
                syncCompleted(
                    KEY,
                    BcInboundResourceSyncResult.PermanentFailure(IllegalStateException("missing")),
                ),
            )
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertTrue(coordinator.ownsDeferred(second))
            assertEquals(2, coordinator.deferredCount)
            assertEquals(listOf("one"), unresolved)
        }
        val transferred = coordinator.takeDeferredFrames()
        coordinator.handle(
            syncCompleted(
                KEY,
                BcInboundResourceSyncResult.PermanentFailure(IllegalStateException("missing")),
            ),
        )

        assertEquals(2, transferred.size)
        assertSame(second, transferred[0])
        assertSame(third, transferred[1])
        assertEquals(listOf("one"), unresolved)
        assertTrue(transferred.none(coordinator::ownsDeferred))
        assertEquals(0, coordinator.deferredCount)
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    @Test
    fun failedReadyReplayRetainsUnsavedFramesForHandoff() = runTest {
        var ready = false
        val failure = IllegalStateException("database unavailable")
        val processed = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { ready },
            processed = processed,
            requested = mutableListOf(),
            processFrame = { frame ->
                if (content(frame) == "two") throw failure
                processed += content(frame)
            },
        )
        val second = chatFrame("Action", "two")
        val third = chatFrame("Action", "three")
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(BcInboundWork.Frame(second))
        coordinator.handle(BcInboundWork.Frame(third))
        ready = true

        try {
            coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
            fail("Persistence failures must propagate.")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
            assertEquals(listOf("one"), processed)
            assertEquals(2, coordinator.deferredCount)
            assertTrue(coordinator.ownsDeferred(second))
            assertTrue(coordinator.ownsDeferred(third))
        }
        val transferred = coordinator.takeDeferredFrames()
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))

        assertEquals(2, transferred.size)
        assertSame(second, transferred[0])
        assertSame(third, transferred[1])
        assertEquals(listOf("one"), processed)
        assertTrue(transferred.none(coordinator::ownsDeferred))
        assertEquals(0, coordinator.deferredCount)
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    @Test
    fun cancelledReadyReplayRetainsUnsavedFramesForHandoff() = runTest {
        var ready = false
        val processed = mutableListOf<String>()
        val coordinator = coordinator(
            ready = { ready },
            processed = processed,
            requested = mutableListOf(),
            processFrame = { frame ->
                if (content(frame) == "two") throw CancellationException("stopping")
                processed += content(frame)
            },
        )
        val second = chatFrame("Action", "two")
        val third = chatFrame("Action", "three")
        coordinator.handle(BcInboundWork.Frame(chatFrame("Action", "one")))
        coordinator.handle(BcInboundWork.Frame(second))
        coordinator.handle(BcInboundWork.Frame(third))
        ready = true

        try {
            coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertEquals(listOf("one"), processed)
            assertEquals(2, coordinator.deferredCount)
            assertTrue(coordinator.ownsDeferred(second))
            assertTrue(coordinator.ownsDeferred(third))
        }
        val transferred = coordinator.takeDeferredFrames()
        coordinator.handle(syncCompleted(KEY, BcInboundResourceSyncResult.Success))

        assertEquals(2, transferred.size)
        assertSame(second, transferred[0])
        assertSame(third, transferred[1])
        assertEquals(listOf("one"), processed)
        assertTrue(transferred.none(coordinator::ownsDeferred))
        assertEquals(0, coordinator.deferredCount)
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    @Test
    fun cancelledOverflowFallbackRetainsOldestAndArrivingFrames() = runTest {
        val attempted = mutableListOf<CanonicalFrame>()
        val coordinator = coordinator(
            ready = { false },
            processed = mutableListOf(),
            requested = mutableListOf(),
            capacity = 1,
            processUnresolved = { frame ->
                attempted += frame
                throw CancellationException("stopping")
            },
        )
        val oldest = chatFrame("Action", "one")
        coordinator.handle(BcInboundWork.Frame(oldest))
        val arriving = chatFrame("Action", "two")

        try {
            coordinator.handle(BcInboundWork.Frame(arriving))
            fail("Cancellation must propagate.")
        } catch (_: CancellationException) {
            assertEquals(2, coordinator.deferredCount)
            assertTrue(coordinator.ownsDeferred(oldest))
            assertTrue(coordinator.ownsDeferred(arriving))
            assertSame(oldest, attempted.single())
        }
        val transferred = coordinator.takeDeferredFrames()

        assertEquals(2, transferred.size)
        assertSame(oldest, transferred[0])
        assertSame(arriving, transferred[1])
        assertTrue(transferred.none(coordinator::ownsDeferred))
        assertEquals(0, coordinator.deferredCount)
        assertTrue(coordinator.takeDeferredFrames().isEmpty())
    }

    private fun coordinator(
        ready: () -> Boolean,
        processed: MutableList<String>,
        requested: MutableList<BcInboundResourceKey>,
        unresolved: MutableList<String> = mutableListOf(),
        capacity: Int = 256,
        processFrame: suspend (CanonicalFrame) -> Unit = { processed += content(it) },
        requestSync: (BcInboundResourceKey) -> Boolean = { true },
        processUnresolved: suspend (CanonicalFrame) -> Unit = { unresolved += content(it) },
        onRejected: (CanonicalFrame, BcInboundResourceGateDecision.Reject) -> Unit = { _, _ -> },
        onOverflow: (CanonicalFrame, BcInboundResourceKey) -> Unit = { _, _ -> },
        onPermanentFailure: (BcInboundResourceKey, List<CanonicalFrame>, Throwable) -> Unit =
            { _, _, _ -> },
    ): BcInboundWorkCoordinator {
        val gate = BcInboundResourceGate(
            sessionId = SESSION_ID,
            versionProvider = { KEY.version },
            languageProvider = { KEY.language },
            resourcesReady = { _, _ -> ready() },
        )
        return BcInboundWorkCoordinator(
            sessionId = SESSION_ID,
            resourceGate = gate,
            processFrame = processFrame,
            processUnresolvedFrame = processUnresolved,
            requestResourceSync = { key -> requested += key; requestSync(key) },
            onRejected = onRejected,
            onOverflow = onOverflow,
            onPermanentFailure = onPermanentFailure,
            deferredCapacity = capacity,
        )
    }

    private fun syncCompleted(
        key: BcInboundResourceKey,
        result: BcInboundResourceSyncResult,
    ) = BcInboundWork.ResourceSyncCompleted(key, result)

    private fun chatFrame(wireType: String, content: String): CanonicalFrame {
        val payload = """{"Sender":123,"Type":"$wireType","Content":"$content","Dictionary":[]}"""
        return CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomMessage",
            args = listOf(payload),
            receivedAtMillis = 1L,
            raw = payload,
        )
    }

    private fun content(frame: CanonicalFrame): String =
        Regex(""""Content":"([^"]+)"""").find(frame.raw)!!.groupValues[1]

    companion object {
        private const val SESSION_ID = 41L
        private val KEY = BcInboundResourceKey(SESSION_ID, "R131", "CN")
    }
}
