package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorPreparedOutboundPipelineTest {
    @Test
    fun queuedPreparationUsesTheLatestStateAndReturnsTheValueSentOnTheWire() = runBlocking {
        val fixture = Fixture()
        var latest = PreparedItem("#FFFFFF", 1)
        var returned: PreparedItem? = null
        var failure: Throwable? = null
        val calls = AtomicInteger()
        try {
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    fixture.pipeline.sendPreparedJson("ChatRoomCharacterItemUpdate") {
                        calls.incrementAndGet()
                        latest
                    }
                }.onSuccess { returned = it }.onFailure { failure = it }
            }
            assertEquals(0, calls.get())
            assertTrue(fixture.emitted.isEmpty())
            latest = PreparedItem("#123456", 2)
            fixture.releaseOutbound()
            withTimeout(2_000L) { sending.join() }

            assertNull(failure)
            assertEquals(1, calls.get())
            assertEquals(latest, returned)
            val (event, json) = fixture.emitted.single()
            assertEquals("ChatRoomCharacterItemUpdate", event)
            assertEquals(JsonParser.parseString(Gson().toJson(returned)), JsonParser.parseString(json))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparationAndEmissionRunInsideBothActualRoomAndConnectionGuards() = runBlocking {
        val transport = transport()
        val roomStore = roomStore()
        val connectionLock = requireNotNull(field(transport, "lifecycleLock"))
        val fixture = Fixture(transport::captureEmitGuard, validateEmission = {
            check(Thread.holdsLock(roomStore)) { "Emission escaped the room guard." }
            check(Thread.holdsLock(connectionLock)) { "Emission escaped the connection guard." }
        })
        var failure: Throwable? = null
        try {
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    fixture.pipeline.sendPreparedJson("ChatRoomCharacterItemUpdate", roomStore.captureRoomActionGuard()) {
                        check(Thread.holdsLock(roomStore)) { "Preparation escaped the room guard." }
                        check(Thread.holdsLock(connectionLock)) { "Preparation escaped the connection guard." }
                        PreparedItem("#123456", 2)
                    }
                }.exceptionOrNull()
            }
            fixture.releaseOutbound()
            withTimeout(2_000L) { sending.join() }

            assertNull(failure)
            assertEquals(1, fixture.emitted.size)
        } finally {
            fixture.close()
            transport.close()
        }
    }

    @Test
    fun preparationFailureDoesNotEmitAndReachesTheSendingCaller() = runBlocking {
        val fixture = Fixture()
        val calls = AtomicInteger()
        var failure: Throwable? = null
        try {
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    fixture.pipeline.sendPreparedJson<PreparedItem>("ChatRoomCharacterItemUpdate") {
                        calls.incrementAndGet()
                        throw IllegalArgumentException("Live item preparation failed.")
                    }
                }.exceptionOrNull()
            }
            fixture.releaseOutbound()
            withTimeout(2_000L) { sending.join() }

            assertTrue(failure is IllegalArgumentException)
            assertEquals("Live item preparation failed.", failure?.message)
            assertEquals(1, calls.get())
            assertTrue(fixture.emitted.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleConnectionOrRoomRejectsTheQueuedPacketBeforePreparation() = runBlocking {
        listOf("connection", "room").forEach { staleGuard ->
            val transport = transport()
            val roomStore = roomStore()
            val fixture = Fixture(transport::captureEmitGuard)
            val calls = AtomicInteger()
            var failure: Throwable? = null
            try {
                fixture.blockOutbound()
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    failure = runCatching {
                        fixture.pipeline.sendPreparedJson("ChatRoomCharacterItemUpdate", roomStore.captureRoomActionGuard()) {
                            calls.incrementAndGet()
                            PreparedItem("#123456", 2)
                        }
                    }.exceptionOrNull()
                }
                if (staleGuard == "connection") transport.close() else roomStore.clearRoom()
                fixture.releaseOutbound()
                withTimeout(2_000L) { sending.join() }

                val expectedMessage = if (staleGuard == "connection") "connection was replaced" else "active room session"
                assertTrue("$staleGuard: $failure", failure?.message?.contains(expectedMessage) == true)
                assertEquals(0, calls.get())
                assertTrue(fixture.emitted.isEmpty())
            } finally {
                fixture.close()
                transport.close()
            }
        }
    }

    @Test
    fun clearedAndCancelledQueuedPacketsNeverPrepareOrEmit() = runBlocking {
        listOf(false, true).forEach { cancel ->
            val fixture = Fixture()
            val calls = AtomicInteger()
            var failure: Throwable? = null
            try {
                fixture.blockOutbound()
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    failure = runCatching {
                        fixture.pipeline.sendPreparedJson("ChatRoomCharacterItemUpdate") {
                            calls.incrementAndGet()
                            PreparedItem("#123456", 2)
                        }
                    }.exceptionOrNull()
                }
                if (cancel) sending.cancelAndJoin() else fixture.pipeline.clear()
                fixture.releaseOutbound()
                withTimeout(2_000L) { sending.join() }
                fixture.awaitExecutor()

                if (cancel) assertTrue(sending.isCancelled) else assertTrue(failure is IllegalStateException)
                assertEquals(0, calls.get())
                assertTrue(fixture.emitted.isEmpty())
            } finally {
                fixture.close()
            }
        }
    }

    private data class PreparedItem(val color: String, val revision: Int)

    private class Fixture(
        captureGuard: () -> ((() -> Unit) -> Unit) = { { packet -> packet() } },
        validateEmission: () -> Unit = {},
    ) {
        val emitted = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val pipeline = RazorOutboundPipeline(Gson(), captureGuard) { event, json ->
            validateEmission()
            emitted += event to json
        }
        private val executor = field(pipeline, "executor") as ScheduledExecutorService
        private val release = CountDownLatch(1)

        fun blockOutbound() {
            val blocked = CountDownLatch(1)
            executor.submit { blocked.countDown(); release.await() }
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
        }

        fun releaseOutbound() { release.countDown() }
        fun awaitExecutor() { executor.submit {}.get(1, TimeUnit.SECONDS) }
        fun close() { release.countDown(); pipeline.close() }
    }

    private companion object {
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)

        fun transport() = RazorSocketTransport(PacketLogStore(), {}, {}, { _, _ -> })

        fun roomStore() = RazorRoomStateStore(Gson(), emit = {}, queueAccountAppearance = {}).apply {
            selfMemberNumber = 10
            handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self"}]}"""))
        }
    }
}
