package dev.vivian.mobilebc.core.socket

import java.util.concurrent.Executors
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RazorRateLimiterTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun roomControlBypassesBlockedChatLane() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val limiter = RazorRateLimiter(executor = executor, chatMaxMessages = 1, chatWindowMs = 60_000L) { event, payload ->
            sent += event to payload
        }

        limiter.send(OutboundPacket("ChatRoomChat", """{"Index":-1}""", OutboundLane.Chat))
        repeat(10) { index ->
            limiter.enqueue(OutboundPacket("ChatRoomChat", """{"Index":$index}""", OutboundLane.Chat))
        }
        limiter.send(OutboundPacket("ChatRoomJoin", """{"Name":"Room"}""", OutboundLane.RoomControl))

        assertEquals(
            listOf(
                "ChatRoomChat" to """{"Index":-1}""",
                "ChatRoomJoin" to """{"Name":"Room"}""",
            ),
            sent,
        )
    }

    @Test
    fun chatLaneUsesWindowLimit() {
        val sent = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val limiter = RazorRateLimiter(executor = executor, chatMaxMessages = 2, chatWindowMs = 60_000L) { event, payload ->
            sent += event to payload
        }

        repeat(3) { index ->
            limiter.enqueue(OutboundPacket("ChatRoomChat", """{"Index":$index}""", OutboundLane.Chat))
        }
        Thread.sleep(100L)

        assertEquals(
            listOf(
                "ChatRoomChat" to """{"Index":0}""",
                "ChatRoomChat" to """{"Index":1}""",
            ),
            sent,
        )
    }

    @Test
    fun sendFailsWhenEmitterFails() = runBlocking {
        val limiter = RazorRateLimiter(executor = executor) { _, _ ->
            error("Socket.IO transport is not connected.")
        }

        try {
            limiter.send(OutboundPacket("ChatRoomChat", "{}", OutboundLane.Chat))
            fail("Expected sender failure")
        } catch (expected: IllegalStateException) {
            assertEquals("Socket.IO transport is not connected.", expected.message)
        }
    }

    @Test
    fun cancellingAnUnsentPacketRemovesItBeforeTheExecutorCanEmitIt() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { started.countDown(); release.await() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val limiter = RazorRateLimiter(executor = executor) { _, payload -> emitted += payload }
        try {
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                limiter.send(OutboundPacket("ChatRoomChat", "cancelled", OutboundLane.Chat))
            }

            sending.cancelAndJoin()
            release.countDown()
            executor.submit {}.get(1, TimeUnit.SECONDS)

            assertTrue(emitted.isEmpty())
        } finally {
            release.countDown()
        }
    }
}
