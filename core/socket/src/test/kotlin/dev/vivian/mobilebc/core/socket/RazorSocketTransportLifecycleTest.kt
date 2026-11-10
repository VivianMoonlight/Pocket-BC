package dev.vivian.mobilebc.core.socket

import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import dev.vivian.mobilebc.core.model.BcServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorSocketTransportLifecycleTest {
    @Test
    fun terminalCloseCannotBeUndoneByAnOrdinaryCloseOrNewConnectAttempt() = runBlocking {
        val transport = transport()
        transport.close(terminal = true)
        transport.close()

        val failure = runCatching { transport.connect(BcServerConfig(version = "R130")) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(field(transport, "socket"))
    }

    @Test
    fun staleGenerationCannotEmitOrCloseTheReplacementConnection() {
        val transport = transport()
        val replacement = disconnectedSocket()
        setField(transport, "socket", replacement)
        setField(transport, "connectionGeneration", 2L)
        var emitted = false

        val failure = runCatching { transport.emitIfCurrent(1L) { emitted = true } }.exceptionOrNull()
        val closed = transport.close(expectedGeneration = 1L)

        assertTrue(failure is IllegalStateException)
        assertTrue(!emitted)
        assertTrue(!closed)
        assertSame(replacement, field(transport, "socket"))
        transport.close()
    }

    @Test
    fun delayedCleanupFromAnOldAttemptPreservesTheReplacementSocket() {
        val transport = transport()
        val previous = disconnectedSocket()
        val replacement = disconnectedSocket()
        setField(transport, "socket", replacement)
        setField(transport, "connectionGeneration", 2L)

        closeAttempt(transport, previous, 1L)
        closeAttempt(transport, replacement, 1L)

        assertSame(replacement, field(transport, "socket"))
        assertEquals(2L, field(transport, "connectionGeneration"))
        transport.close()
    }

    @Test
    fun currentAttemptCleanupClosesOnlyItsSocketAndCancelsItsWaiter() {
        val transport = transport()
        val current = disconnectedSocket()
        val waiter = CompletableDeferred<Unit>()
        setField(transport, "socket", current)
        setField(transport, "connectionGeneration", 2L)
        setField(transport, "connectWaiter", waiter)

        closeAttempt(transport, current, 2L)

        assertNull(field(transport, "socket"))
        assertEquals(3L, field(transport, "connectionGeneration"))
        assertTrue(waiter.isCancelled)
    }

    @Test
    fun hostCloseImmediatelyCancelsAnAwaitingConnect() {
        val transport = transport()
        val waiter = CompletableDeferred<Unit>()
        setField(transport, "socket", disconnectedSocket())
        setField(transport, "connectWaiter", waiter)

        transport.close()

        assertTrue(waiter.isCancelled)
        assertNull(field(transport, "socket"))
    }

    private fun transport() = RazorSocketTransport(PacketLogStore(), {}, {}, { _, _ -> })

    private fun disconnectedSocket(): Socket = IO.socket(URI.create("http://localhost"), IO.Options().apply { forceNew = true })

    private fun closeAttempt(transport: RazorSocketTransport, socket: Socket, generation: Long) {
        RazorSocketTransport::class.java.getDeclaredMethod("close", Socket::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(transport, socket, generation)
    }

    private fun setField(transport: RazorSocketTransport, name: String, value: Any) {
        RazorSocketTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.set(transport, value)
    }

    private fun field(transport: RazorSocketTransport, name: String): Any? =
        RazorSocketTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.get(transport)
}
