package dev.vivian.mobilebc.core.socket

import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RazorAccountUpdateQueueTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun flushMergesQueuedFieldsWithLastWriteWinning() {
        val sent = mutableListOf<Map<String, Any?>>()
        val queue = RazorAccountUpdateQueue(
            debounceMs = 60_000L,
            maxWaitMs = 60_000L,
            executor = executor,
            send = { sent += it },
        )

        queue.queue(mapOf("Title" to "Old", "LabelColor" to "Red"))
        queue.queue(mapOf("Title" to "New"))
        queue.flush()

        assertEquals(listOf(mapOf("Title" to "New", "LabelColor" to "Red")), sent)
    }

    @Test
    fun clearDropsPendingFields() {
        val sent = mutableListOf<Map<String, Any?>>()
        val queue = RazorAccountUpdateQueue(
            debounceMs = 60_000L,
            maxWaitMs = 60_000L,
            executor = executor,
            send = { sent += it },
        )

        queue.queue(mapOf("Title" to "Hidden"))
        queue.clear()
        queue.flush()

        assertEquals(emptyList<Map<String, Any?>>(), sent)
    }
}
