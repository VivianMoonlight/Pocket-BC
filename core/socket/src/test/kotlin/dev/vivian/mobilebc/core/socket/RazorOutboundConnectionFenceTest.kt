package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSerializer
import bot.inker.bc.razor.protocol.common.ItemBundle
import dev.vivian.mobilebc.core.model.BcServerConfig
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorOutboundConnectionFenceTest {
    @Test
    fun aDrainedAccountBatchCannotCaptureTheReplacementGenerationDuringSerialization() {
        val transport = RazorSocketTransport(PacketLogStore(), {}, {}, { _, _ -> })
        setSocket(transport, RecordingSocket())
        val serializing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gson = GsonBuilder().registerTypeAdapter(ItemBundle::class.java, JsonSerializer<ItemBundle> { item, _, _ ->
            serializing.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            JsonObject().apply {
                addProperty("Group", item.group)
                addProperty("Name", item.name)
            }
        }).create()
        val pipeline = RazorOutboundPipeline(
            gson = gson,
            captureEmitGuard = transport::captureEmitGuard,
            emitNow = transport::emitNow,
        )
        pipeline.queueAccountAppearance(listOf(ItemBundle(group = "ItemArms", name = "Rope")))
        val flushing = FutureTask(Callable { runBlocking { pipeline.flushAccountUpdates() } })
        val flushThread = Thread(flushing, "old-account-flush-test").apply { isDaemon = true; start() }
        val replacement = RecordingSocket()
        val replacing = FutureTask(Callable {
            transport.close()
            pipeline.clear()
            runBlocking {
                assertTrue(runCatching { transport.connect(BcServerConfig(serverUrl = "://", version = "R130")) }.isFailure)
            }
            setSocket(transport, replacement)
        })
        var replacementThread: Thread? = null
        try {
            assertTrue(serializing.await(5, TimeUnit.SECONDS))
            replacementThread = Thread(replacing, "replacement-account-test").apply { isDaemon = true; start() }
            awaitBlockedOrCompleted(replacementThread, requireNotNull(field(pipeline, "accountUpdates")))

            release.countDown()
            replacing.get(5, TimeUnit.SECONDS)
            val failure = runCatching { flushing.get(5, TimeUnit.SECONDS) }.exceptionOrNull()

            assertTrue(failure is ExecutionException && failure.cause is IllegalStateException)
            assertEquals(0, replacement.emitted)
        } finally {
            release.countDown()
            flushThread.join(5_000L)
            replacementThread?.join(5_000L)
            pipeline.close()
            transport.close()
        }
    }

    @Test
    fun selectedOldAccountBeepCannotEmitIntoAReplacementAfterTheQueueIsCleared() = runBlocking {
        val transport = RazorSocketTransport(PacketLogStore(), {}, {}, { _, _ -> })
        val previous = RecordingSocket()
        setSocket(transport, previous)
        val pipeline = RazorOutboundPipeline(
            gson = Gson(),
            captureEmitGuard = transport::captureEmitGuard,
            emitNow = transport::emitNow,
        )
        val executor = field(pipeline, "executor") as ScheduledExecutorService
        val lifecycleLock = requireNotNull(field(transport, "lifecycleLock"))
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executorThread = AtomicReference<Thread>()
        executor.submit {
            executorThread.set(Thread.currentThread())
            blocked.countDown()
            release.await()
        }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        var sendFailure: Throwable? = null
        try {
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                sendFailure = runCatching { pipeline.sendRaw("AccountBeep", "{}") }.exceptionOrNull()
            }
            val replacement = RecordingSocket()
            synchronized(lifecycleLock) {
                release.countDown()
                awaitBlockedOn(requireNotNull(executorThread.get()), lifecycleLock)
                // The limiter has already selected the packet and released its own lock.
                // Replacing the transport and clearing queues cannot recover that packet.
                transport.close()
                setSocket(transport, replacement)
                pipeline.clear()
            }

            sending.join()

            assertTrue(sendFailure is IllegalStateException)
            assertEquals(0, previous.emitted)
            assertEquals(0, replacement.emitted)
            assertTrue(transport.isConnected())
        } finally {
            release.countDown()
            pipeline.close()
            transport.close()
        }
    }

    private fun awaitBlockedOn(thread: Thread, lock: Any) {
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val info = threadMxBean.getThreadInfo(thread.id)
            if (info?.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(lock)
            ) return
            assertTrue("The selected packet did not reach the generation guard.", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun awaitBlockedOrCompleted(thread: Thread, lock: Any) {
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive) {
            val info = threadMxBean.getThreadInfo(thread.id)
            if (info?.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(lock)
            ) return
            assertTrue("Replacement did not reach the pending account batch.", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun setSocket(transport: RazorSocketTransport, socket: Socket) {
        RazorSocketTransport::class.java.getDeclaredField("socket")
            .apply { isAccessible = true }.set(transport, socket)
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        var emitted = 0

        override fun connected(): Boolean = true

        override fun emit(event: String, vararg args: Any?): Emitter = this.also { emitted++ }

        override fun disconnect(): Socket = this
    }
}
