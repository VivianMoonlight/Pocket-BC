package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorOutboundAccountUpdateBatchTest {
    @Test
    fun drainedBatchKeepsItsQueueTimeAccountGuardAfterClearAndNewLogin() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val generation = AtomicInteger(1)
        val pipeline = RazorOutboundPipeline(
            Gson(),
            captureAccountEmitGuard = {
                val queuedGeneration = generation.get()
                val guard: (() -> Unit) -> Unit = { emitPacket ->
                    if (generation.get() != queuedGeneration) throw OutboxDispatchDeferredException("Old account")
                    emitPacket()
                }
                guard
            },
        ) { event, payload -> emitted += event to payload }
        try {
            pipeline.queueAccountUpdates(mapOf("Log" to "old log"))
            val heldBatch = requireNotNull(pipeline.drainAccountUpdatePacket())
            generation.incrementAndGet()
            pipeline.clear()

            val failure = runCatching { pipeline.flushAccountUpdates(heldBatch) }.exceptionOrNull()
            assertTrue(failure is OutboxDispatchDeferredException)
            assertTrue(emitted.isEmpty())

            pipeline.queueAccountUpdates(mapOf("Log" to "new log"))
            pipeline.flushAccountUpdates()
            assertEquals(1, emitted.size)
            assertEquals("new log", JsonParser.parseString(emitted.single().second).asJsonObject["Log"].asString)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun lastWriteReplacesTheFieldOriginWhileUntouchedFieldsKeepTheirGuard() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val generation = AtomicInteger(1)
        val pipeline = RazorOutboundPipeline(
            Gson(),
            captureAccountEmitGuard = {
                val queuedGeneration = generation.get()
                val guard: (() -> Unit) -> Unit = { emitPacket ->
                    if (generation.get() != queuedGeneration) throw OutboxDispatchDeferredException("Old account")
                    emitPacket()
                }
                guard
            },
        ) { event, payload -> emitted += event to payload }
        try {
            pipeline.queueAccountUpdates(mapOf("Title" to "old title", "Log" to "old log"))
            generation.incrementAndGet()
            pipeline.queueAccountUpdates(mapOf("Log" to "new log"))
            assertTrue(runCatching { pipeline.flushAccountUpdates() }.exceptionOrNull() is OutboxDispatchDeferredException)
            assertTrue(emitted.isEmpty())

            pipeline.queueAccountUpdates(mapOf("Title" to "old title", "Log" to "old log"))
            generation.incrementAndGet()
            pipeline.queueAccountUpdates(mapOf("Title" to "new title", "Log" to "new log"))
            pipeline.flushAccountUpdates()
            val fields = JsonParser.parseString(emitted.single().second).asJsonObject
            assertEquals("new title", fields["Title"].asString)
            assertEquals("new log", fields["Log"].asString)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun separatePartialBatchesBothEmitAndCompleteTheirFlushWaiters() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val pipeline = RazorOutboundPipeline(Gson()) { event, payload -> emitted += event to payload }
        val executor = RazorOutboundPipeline::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(pipeline) as ScheduledExecutorService
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { blocked.countDown(); release.await() }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        try {
            pipeline.queueRelationshipEconomyEffect(
                BcRelationshipEconomyEffect.OwnershipOfferEndTrial,
                BcRelationshipEconomyState(money = 500, reputation = emptyMap()),
            )
            val first = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.flushAccountUpdates() }
            pipeline.queueAccountAppearance(emptyList())
            val second = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.flushAccountUpdates() }

            release.countDown()
            withTimeout(2_000L) {
                first.join()
                second.join()
            }

            assertEquals(listOf("AccountUpdate", "AccountUpdate"), emitted.map { it.first })
            val firstFields = JsonParser.parseString(emitted[0].second).asJsonObject
            val secondFields = JsonParser.parseString(emitted[1].second).asJsonObject
            assertEquals(400, firstFields.get("Money").asInt)
            assertTrue(firstFields.has("Reputation"))
            assertEquals(setOf("Appearance"), secondFields.keySet())
            assertEquals(0, secondFields.getAsJsonArray("Appearance").size())
        } finally {
            release.countDown()
            pipeline.close()
        }
    }
}
