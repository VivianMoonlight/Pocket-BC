package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorGatewayLifecycleTest {
    @Test
    fun completedEconomyFlushCannotRestoreItsBalanceAfterTheCurrentSocketDisconnects() = runBlocking {
        val gateway = RazorGatewayImpl()
        val transport = gatewayField(gateway, "transport") as RazorSocketTransport
        val pipeline = gatewayField(gateway, "outbound") as RazorOutboundPipeline
        val executor = RazorOutboundPipeline::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(pipeline) as ScheduledExecutorService
        val previous = RecordingSocket()
        setTransportSocket(transport, previous)
        bindSocketCallbacks(transport, previous)
        authenticate(gateway)
        val release = freezeExecutor(executor)
        var updateFailure: Throwable? = null
        try {
            val update = launch(start = CoroutineStart.UNDISPATCHED) {
                updateFailure = runCatching {
                    gateway.applyRelationshipEconomyEffect(BcRelationshipEconomyEffect.OwnershipOfferEndTrial)
                }.exceptionOrNull()
            }
            release.countDown()
            executor.submit {}.get(1, TimeUnit.SECONDS)
            assertEquals(1, previous.emitted)
            previous.online = false
            previous.listeners(Socket.EVENT_DISCONNECT).single().call("transport close")
            assertNull(economy(gateway))

            update.join()

            assertTrue(updateFailure is IllegalStateException)
            assertNull(economy(gateway))
            assertFalse(gateway.isProbablyConnected())
        } finally {
            release.countDown()
            gateway.close()
        }
    }

    @Test
    fun completedOldEconomyFlushCannotWriteItsBalanceIntoAReplacementSession() = runBlocking {
        val gateway = RazorGatewayImpl()
        val transport = gatewayField(gateway, "transport") as RazorSocketTransport
        val pipeline = gatewayField(gateway, "outbound") as RazorOutboundPipeline
        val executor = RazorOutboundPipeline::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(pipeline) as ScheduledExecutorService
        val previous = RecordingSocket()
        setTransportSocket(transport, previous)
        authenticate(gateway)
        val release = freezeExecutor(executor)
        var updateFailure: Throwable? = null
        try {
            val update = launch(start = CoroutineStart.UNDISPATCHED) {
                updateFailure = runCatching {
                    gateway.applyRelationshipEconomyEffect(BcRelationshipEconomyEffect.OwnershipOfferEndTrial)
                }.exceptionOrNull()
            }
            release.countDown()
            executor.submit {}.get(1, TimeUnit.SECONDS)
            assertEquals(1, previous.emitted)
            assertTrue(runCatching {
                gateway.connect(BcServerConfig(serverUrl = "://", version = "R130"))
            }.isFailure)
            val replacement = RecordingSocket()
            setTransportSocket(transport, replacement)
            authenticate(gateway)

            update.join()

            assertTrue(updateFailure is IllegalStateException)
            assertEquals(500, (economy(gateway) as BcRelationshipEconomyState).money)
            assertSame(replacement, transportSocket(transport))
            assertEquals(0, replacement.emitted)
        } finally {
            release.countDown()
            gateway.close()
        }
    }

    @Test
    fun completedOldFlushCannotCloseAReplacementWhileItsCallerIsStillResuming() = runBlocking {
        val gateway = RazorGatewayImpl()
        val transport = gatewayField(gateway, "transport") as RazorSocketTransport
        val pipeline = gatewayField(gateway, "outbound") as RazorOutboundPipeline
        val executor = RazorOutboundPipeline::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(pipeline) as ScheduledExecutorService
        val previous = RecordingSocket()
        setTransportSocket(transport, previous)
        authenticate(gateway)
        pipeline.queueAccountAppearance(emptyList())
        val release = freezeExecutor(executor)
        var disconnectFailure: Throwable? = null
        try {
            val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                disconnectFailure = runCatching { gateway.disconnect() }.exceptionOrNull()
            }
            // Complete the socket emit while this runBlocking thread keeps the suspended
            // disconnect continuation queued, then replace the session before it resumes.
            release.countDown()
            executor.submit {}.get(1, TimeUnit.SECONDS)
            assertEquals(1, previous.emitted)
            assertTrue(runCatching {
                gateway.connect(BcServerConfig(serverUrl = "://", version = "R130"))
            }.isFailure)
            val replacement = RecordingSocket()
            setTransportSocket(transport, replacement)
            authenticate(gateway)

            disconnect.join()

            assertNull(disconnectFailure)
            assertSame(replacement, transportSocket(transport))
            assertNotNull(economy(gateway))
            assertTrue(gateway.isProbablyConnected())
            assertEquals(0, replacement.emitted)
        } finally {
            release.countDown()
            gateway.close()
        }
    }

    @Test
    fun explicitDisconnectClearsAuthenticatedEconomy() = runBlocking {
        val gateway = RazorGatewayImpl()
        try {
            authenticate(gateway)
            assertNotNull(economy(gateway))

            gateway.disconnect()

            assertNull(economy(gateway))
        } finally {
            gateway.close()
        }
    }

    @Test
    fun replacementConnectClearsPreviousEconomyBeforeLogin() = runBlocking {
        val gateway = RazorGatewayImpl()
        try {
            authenticate(gateway)
            assertNotNull(economy(gateway))

            val attempt = runCatching {
                gateway.connect(BcServerConfig(serverUrl = "://", version = "R130"))
            }

            assertTrue(attempt.isFailure)
            assertNull(economy(gateway))
        } finally {
            gateway.close()
        }
    }

    @Test
    fun terminalCloseIsIdempotentAndEndsBothStreamsAndPreventsReopening() = runBlocking {
        val gateway = RazorGatewayImpl()
        val pipeline = gatewayField(gateway, "outbound") as RazorOutboundPipeline
        val executor = RazorOutboundPipeline::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(pipeline) as ScheduledExecutorService
        pipeline.queueAccountAppearance(emptyList())

        gateway.close()
        gateway.close()

        assertTrue(executor.isShutdown)
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertFalse(gateway.isProbablyConnected())
        assertTrue(gateway.events.toList().isEmpty())
        assertTrue(gateway.inboundFrames.toList().isEmpty())
        val failure = runCatching { gateway.connect(BcServerConfig(version = "R130")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private fun authenticate(gateway: RazorGatewayImpl) = runBlocking {
        val requests = gatewayField(gateway, "requests") as RazorRequestCoordinator
        val response = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        RazorGatewayImpl::class.java.getDeclaredMethod("handleIncomingEvent", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(gateway, "LoginResponse", """{"ID":"1001","MemberNumber":1001,"Name":"Alice","Money":500}""")
        response.await()
    }

    private fun bindSocketCallbacks(transport: RazorSocketTransport, socket: Socket) {
        RazorSocketTransport::class.java.getDeclaredMethod(
            "bindSocketCallbacks",
            Socket::class.java,
            Long::class.javaPrimitiveType,
            CompletableDeferred::class.java,
        ).apply { isAccessible = true }
            .invoke(transport, socket, transport.captureGeneration(), CompletableDeferred<Unit>())
    }

    private fun economy(gateway: RazorGatewayImpl): Any? =
        gatewayField(gateway, "relationshipEconomy")

    private fun freezeExecutor(executor: ScheduledExecutorService): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        return release
    }

    private fun gatewayField(gateway: RazorGatewayImpl, name: String): Any? =
        RazorGatewayImpl::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(gateway)

    private fun setTransportSocket(transport: RazorSocketTransport, socket: Socket) {
        RazorSocketTransport::class.java.getDeclaredField("socket")
            .apply { isAccessible = true }.set(transport, socket)
    }

    private fun transportSocket(transport: RazorSocketTransport): Any? =
        RazorSocketTransport::class.java.getDeclaredField("socket")
            .apply { isAccessible = true }.get(transport)

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        var emitted = 0
        var online = true

        override fun connected(): Boolean = online

        override fun emit(event: String, vararg args: Any?): Emitter = this.also { emitted++ }

        override fun disconnect(): Socket = this
    }
}
