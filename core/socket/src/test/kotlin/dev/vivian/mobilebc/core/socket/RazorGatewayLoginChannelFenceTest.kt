package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorGatewayLoginChannelFenceTest {
    @Test
    fun sameMemberReloginUsesAFreshChannelAndRejectsEveryOldCallback(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gateway.connect(fixture.config)
            val firstSocket = fixture.sockets.single()
            val firstLogin = beginLogin(fixture, "first-account")
            assertEquals("first-account", firstSocket.awaitEmission("AccountLogin").getString("AccountName"))
            firstSocket.receive("LoginResponse", loginResponse(10, "First"))
            val firstResult = firstLogin.await() as BcSessionLoginResult.Success
            firstSocket.receive("ChatRoomSync", roomSync("Original room"))

            val oldOwnershipQuery = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.gateway.queryOwnershipCapability(40) }
            }
            firstSocket.awaitEmission("AccountOwnership")
            val oldLovershipQuery = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.gateway.queryLovershipCapability(40) }
            }
            firstSocket.awaitEmission("AccountLovership")

            val secondLogin = beginLogin(fixture, "second-account")
            assertEquals(2, fixture.sockets.size)
            val secondSocket = fixture.sockets.last()
            assertEquals("second-account", secondSocket.awaitEmission("AccountLogin").getString("AccountName"))
            assertFalse(firstSocket.connected())
            assertTrue(secondSocket.connected())
            assertEquals(listOf(fixture.config.serverUrl, fixture.config.serverUrl), fixture.createdUris)
            assertEquals(listOf(fixture.config.referer, fixture.config.referer), fixture.createdReferers)
            assertEquals(1, firstSocket.emissions.count { it.first == "AccountLogin" })
            assertEquals(1, secondSocket.emissions.count { it.first == "AccountLogin" })
            assertTrue(oldOwnershipQuery.await().exceptionOrNull() is IllegalStateException)
            assertTrue(oldLovershipQuery.await().exceptionOrNull() is IllegalStateException)

            drainEvents(fixture.gateway)
            val beforeStaleLogin = fixture.gateway.packetLog.entries.value.size
            sendOldCallbacks(firstSocket)
            assertFalse(secondLogin.isCompleted)
            assertNull(fixture.requests.authenticatedPrivateChatState)
            assertNull(fixture.requests.authenticatedRelationships)
            assertNull(fixture.roomStore.captureRoomProjection())
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(beforeStaleLogin, fixture.gateway.packetLog.entries.value.size)

            secondSocket.receive("LoginResponse", loginResponse(10, "Second"))
            secondSocket.receive("AccountOwnership", JSONObject("""{"MemberNumber":40,"Result":"CurrentOwnership"}"""))
            secondSocket.receive("AccountLovership", JSONObject("""{"MemberNumber":40,"Result":"CurrentLovership"}"""))
            val secondResult = secondLogin.await() as BcSessionLoginResult.Success
            assertEquals("Second", secondResult.name)
            assertNotEquals(firstResult.selfRuleLogSessionId, secondResult.selfRuleLogSessionId)
            assertEquals("CurrentOwnership", secondResult.relationshipCapabilities[40]?.ownershipResult)
            assertEquals("CurrentLovership", secondResult.relationshipCapabilities[40]?.lovershipResult)
            assertEquals(2L, secondResult.relationshipRevision)
            secondSocket.receive("ChatRoomSync", roomSync("Current room"))

            val currentPrivate = requireNotNull(fixture.requests.authenticatedPrivateChatState)
            val currentRelationships = requireNotNull(fixture.requests.authenticatedRelationships)
            val currentRoom = fixture.roomStore.captureRoomProjection()
            val currentContext = fixture.roomStore.captureReceiveContext()
            val currentEconomy = field(fixture.gateway, "relationshipEconomy")
            drainEvents(fixture.gateway)
            val beforeOldSessionTraffic = fixture.gateway.packetLog.entries.value.size
            sendOldCallbacks(firstSocket)
            assertSame(currentPrivate, fixture.requests.authenticatedPrivateChatState)
            assertSame(currentRelationships, fixture.requests.authenticatedRelationships)
            assertEquals(currentRoom, fixture.roomStore.captureRoomProjection())
            assertEquals(currentContext, fixture.roomStore.captureReceiveContext())
            assertEquals(currentEconomy, field(fixture.gateway, "relationshipEconomy"))
            assertTrue(fixture.gateway.isProbablyConnected())
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(beforeOldSessionTraffic, fixture.gateway.packetLog.entries.value.size)
        } finally {
            fixture.gateway.close()
        }
    }

    @Test
    fun retryAfterRejectedLoginUsesAFreshChannelBeforeSendingAgain(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gateway.connect(fixture.config)
            val rejectedSocket = fixture.sockets.single()
            val rejected = beginLogin(fixture, "wrong-account")
            rejectedSocket.awaitEmission("AccountLogin")
            rejectedSocket.receive("LoginResponse", "Wrong password")
            assertEquals(BcSessionLoginResult.Failure("Wrong password"), rejected.await())
            assertNull(fixture.requests.authenticatedPrivateChatState)

            val retry = beginLogin(fixture, "correct-account")
            assertEquals(2, fixture.sockets.size)
            val current = fixture.sockets.last()
            assertEquals("correct-account", current.awaitEmission("AccountLogin").getString("AccountName"))
            assertFalse(rejectedSocket.connected())
            drainEvents(fixture.gateway)
            val packetCount = fixture.gateway.packetLog.entries.value.size
            rejectedSocket.receive("LoginResponse", loginResponse(99, "Late rejected account"))
            rejectedSocket.receive("LoginResponse", "Late failure")
            assertFalse(retry.isCompleted)
            assertNull(fixture.requests.authenticatedPrivateChatState)
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(packetCount, fixture.gateway.packetLog.entries.value.size)

            current.receive("LoginResponse", loginResponse(20, "Correct"))
            val result = retry.await() as BcSessionLoginResult.Success
            assertEquals(20, result.memberNumber)
            assertEquals("Correct", result.name)
            assertEquals(result.selfRuleLogSessionId, fixture.requests.authenticatedPrivateChatState?.selfRuleLogSessionId)
            assertEquals(1, rejectedSocket.emissions.count { it.first == "AccountLogin" })
            assertEquals(1, current.emissions.count { it.first == "AccountLogin" })
        } finally {
            fixture.gateway.close()
        }
    }

    @Test
    fun retryAfterCancelledLoginUsesAFreshChannelAndIgnoresTheLateResponse(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gateway.connect(fixture.config)
            val cancelledSocket = fixture.sockets.single()
            val cancelled = beginLogin(fixture, "cancelled-account")
            cancelledSocket.awaitEmission("AccountLogin")
            cancelled.cancelAndJoin()
            assertNull(fixture.requests.authenticatedPrivateChatState)

            val retry = beginLogin(fixture, "retry-account")
            assertEquals(2, fixture.sockets.size)
            val current = fixture.sockets.last()
            assertEquals("retry-account", current.awaitEmission("AccountLogin").getString("AccountName"))
            assertFalse(cancelledSocket.connected())
            drainEvents(fixture.gateway)
            val packetCount = fixture.gateway.packetLog.entries.value.size
            cancelledSocket.receive("LoginResponse", loginResponse(99, "Late cancelled account"))
            cancelledSocket.disconnected.call("Late old disconnect")
            assertFalse(retry.isCompleted)
            assertNull(fixture.requests.authenticatedPrivateChatState)
            assertTrue(current.connected())
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(packetCount, fixture.gateway.packetLog.entries.value.size)

            current.receive("LoginResponse", loginResponse(10, "Retried"))
            assertEquals("Retried", (retry.await() as BcSessionLoginResult.Success).name)
            assertEquals(1, cancelledSocket.emissions.count { it.first == "AccountLogin" })
            assertEquals(1, current.emissions.count { it.first == "AccountLogin" })
        } finally {
            fixture.gateway.close()
        }
    }

    @Test
    fun duplicateLoginKeepsTheCurrentChannelAndDoesNotClearAnAlreadyReceivedAccount(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gateway.connect(fixture.config)
            val socket = fixture.sockets.single()
            val pending = beginLogin(fixture, "first-account")
            socket.awaitEmission("AccountLogin")
            val generation = fixture.transport.captureGeneration()
            val duplicateFailure = BcSessionLoginResult.Failure("A login request is already pending.")
            assertEquals(duplicateFailure, fixture.gateway.login("duplicate-before-response", "password"))
            assertEquals(1, fixture.sockets.size)
            assertEquals(generation, fixture.transport.captureGeneration())
            assertTrue(socket.connected())
            assertFalse(pending.isCompleted)

            socket.receive("LoginResponse", loginResponse(10, "First"))
            assertFalse(pending.isCompleted)
            val authenticated = requireNotNull(fixture.requests.authenticatedPrivateChatState)
            val relationships = requireNotNull(fixture.requests.authenticatedRelationships)
            val context = fixture.roomStore.captureReceiveContext()
            drainEvents(fixture.gateway)
            val packetCount = fixture.gateway.packetLog.entries.value.size
            assertEquals(duplicateFailure, fixture.gateway.login("duplicate-after-response", "password"))
            assertEquals(1, fixture.sockets.size)
            assertEquals(generation, fixture.transport.captureGeneration())
            assertSame(authenticated, fixture.requests.authenticatedPrivateChatState)
            assertSame(relationships, fixture.requests.authenticatedRelationships)
            assertEquals(context, fixture.roomStore.captureReceiveContext())
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(packetCount, fixture.gateway.packetLog.entries.value.size)
            assertEquals(1, socket.emissions.count { it.first == "AccountLogin" })
            assertEquals(authenticated.selfRuleLogSessionId,
                (pending.await() as BcSessionLoginResult.Success).selfRuleLogSessionId)
        } finally {
            fixture.gateway.close()
        }
    }

    @Test
    fun concurrentConnectWaitsForTheHandshakeAndContinuesAfterCancellationOrDisconnect(): Unit = runBlocking {
        for (cancelFirst in listOf(true, false)) {
            val fixture = Fixture().apply { autoConnectNewSockets = false }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.connect(fixture.config) }
                }
                val firstSocket = fixture.awaitCreatedSocket()
                val firstGeneration = fixture.transport.captureGeneration()
                val firstContext = fixture.roomStore.captureReceiveContext()
                val replacementConfig = BcServerConfig("http://localhost:65531/", "R130")
                val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.gateway.connect(replacementConfig)
                }
                assertFalse(first.isCompleted)
                assertFalse(replacement.isCompleted)
                assertEquals(1, fixture.sockets.size)
                assertSame(firstSocket, field(fixture.transport, "socket"))
                assertEquals(firstGeneration, fixture.transport.captureGeneration())
                assertEquals(firstContext, fixture.roomStore.captureReceiveContext())
                assertEquals(fixture.config, field(fixture.gateway, "selectedServerConfig"))
                assertEquals("R129", fixture.gateway.currentBcVersion)

                if (cancelFirst) {
                    first.cancelAndJoin()
                    assertTrue(first.isCancelled)
                } else {
                    fixture.gateway.disconnect()
                    assertTrue(withTimeout(5_000L) { first.await() }.exceptionOrNull() is CancellationException)
                }
                val current = fixture.awaitCreatedSocket()
                assertEquals(2, fixture.sockets.size)
                assertFalse(firstSocket.connected())
                assertFalse(replacement.isCompleted)
                assertEquals(replacementConfig, field(fixture.gateway, "selectedServerConfig"))
                assertEquals("R130", fixture.gateway.currentBcVersion)
                current.fireConnected()
                withTimeout(5_000L) { replacement.await() }
                assertSame(current, field(fixture.transport, "socket"))
                assertTrue(current.connected())
                assertEquals(listOf(fixture.config.serverUrl, replacementConfig.serverUrl), fixture.createdUris)
                assertEquals(listOf(fixture.config.referer, replacementConfig.referer), fixture.createdReferers)

                val login = beginLogin(fixture, "replacement-account")
                assertEquals("replacement-account", current.awaitEmission("AccountLogin").getString("AccountName"))
                firstSocket.receive("LoginResponse", loginResponse(99, "Old unfinished connection"))
                assertFalse(login.isCompleted)
                assertNull(fixture.requests.authenticatedPrivateChatState)
                current.receive("LoginResponse", loginResponse(10, "Replacement"))
                assertEquals("Replacement", (login.await() as BcSessionLoginResult.Success).name)
                assertEquals(2, fixture.sockets.size)
            } finally {
                fixture.gateway.close()
            }
        }
    }

    @Test
    fun replacementAfterFreshHandshakeCannotReceiveThePreviousLoginPacket(): Unit = runBlocking {
        val fixture = Fixture()
        var release: CountDownLatch? = null
        try {
            fixture.gateway.connect(fixture.config)
            val initial = fixture.awaitCreatedSocket()
            val initialLogin = beginLogin(fixture, "initial-account")
            initial.awaitEmission("AccountLogin")
            initial.receive("LoginResponse", loginResponse(10, "Initial"))
            initialLogin.await()
            fixture.autoConnectNewSockets = false
            val executor = field(field(fixture.gateway, "outbound")!!, "executor") as ScheduledExecutorService
            release = freezeExecutor(executor)

            val staleLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.gateway.login("stale-fresh-account", "password") }
            }
            val fresh = fixture.awaitCreatedSocket()
            assertEquals(2, fixture.sockets.size)
            assertFalse(staleLogin.isCompleted)
            fresh.fireConnected()
            assertFalse(staleLogin.isCompleted)
            val freshGeneration = fixture.transport.captureGeneration()
            val replacementConfig = BcServerConfig("http://localhost:65531/", "R130")
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.gateway.connect(replacementConfig)
            }
            assertFalse(replacement.isCompleted)
            assertEquals(2, fixture.sockets.size)
            assertSame(fresh, field(fixture.transport, "socket"))
            assertEquals(freshGeneration, fixture.transport.captureGeneration())

            val current = fixture.awaitCreatedSocket()
            assertEquals(3, fixture.sockets.size)
            current.fireConnected()
            withTimeout(5_000L) { replacement.await() }
            val rejected = withTimeout(5_000L) { staleLogin.await() }
            assertTrue(rejected.exceptionOrNull() is IllegalStateException)
            release.countDown()
            executor.submit {}.get(5L, TimeUnit.SECONDS)
            assertTrue(fresh.emissions.none { it.first == "AccountLogin" })
            assertTrue(current.emissions.none { it.first == "AccountLogin" })
            assertSame(current, field(fixture.transport, "socket"))
            assertEquals(replacementConfig, field(fixture.gateway, "selectedServerConfig"))
            assertEquals("R130", fixture.gateway.currentBcVersion)
            assertNull(fixture.requests.authenticatedPrivateChatState)
            assertNull(fixture.requests.authenticatedRelationships)

            val currentLogin = beginLogin(fixture, "explicit-current-account")
            assertEquals("explicit-current-account", current.awaitEmission("AccountLogin").getString("AccountName"))
            drainEvents(fixture.gateway)
            val beforeStaleResponse = fixture.gateway.packetLog.entries.value.size
            fresh.receive("LoginResponse", loginResponse(99, "Stale fresh account"))
            assertFalse(currentLogin.isCompleted)
            assertNull(fixture.requests.authenticatedPrivateChatState)
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertEquals(beforeStaleResponse, fixture.gateway.packetLog.entries.value.size)
            current.receive("LoginResponse", loginResponse(10, "Current"))
            assertEquals("Current", (currentLogin.await() as BcSessionLoginResult.Success).name)
            assertEquals(1, current.emissions.count { it.first == "AccountLogin" })
            assertEquals(3, fixture.sockets.size)
        } finally {
            release?.countDown()
            fixture.gateway.close()
        }
    }

    @Test
    fun staleConnectionStartCannotReviveAfterDisconnectOrCloseItsReplacement(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val staleOrigin = fixture.transport.captureGeneration()
            fixture.transport.close()
            val disconnectedGeneration = fixture.transport.captureGeneration()
            val disconnectedFailure = runCatching {
                fixture.transport.connect(fixture.config, expectedGeneration = staleOrigin)
            }.exceptionOrNull()
            assertTrue(disconnectedFailure is CancellationException)
            assertTrue(fixture.sockets.isEmpty())
            assertNull(field(fixture.transport, "socket"))
            assertEquals(disconnectedGeneration, fixture.transport.captureGeneration())

            val currentGeneration = fixture.transport.connect(fixture.config)
            val current = fixture.sockets.single()
            drainEvents(fixture.gateway)
            val replacementFailure = runCatching {
                fixture.transport.connect(fixture.config, expectedGeneration = staleOrigin)
            }.exceptionOrNull()
            assertTrue(replacementFailure is CancellationException)
            assertEquals(1, fixture.sockets.size)
            assertSame(current, field(fixture.transport, "socket"))
            assertTrue(current.connected())
            assertEquals(currentGeneration, fixture.transport.captureGeneration())
            assertTrue(drainEvents(fixture.gateway).isEmpty())
            assertTrue(fixture.gateway.packetLog.entries.value.isEmpty())
        } finally {
            fixture.gateway.close()
        }
    }

    private fun CoroutineScope.beginLogin(fixture: Fixture, accountName: String): Deferred<BcSessionLoginResult> =
        async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login(accountName, "password") }

    private fun sendOldCallbacks(socket: FakeSocket) {
        socket.receive("AccountOwnership", JSONObject("""{"MemberNumber":40,"Result":"OldOwnership","ClearOwnership":true}"""))
        socket.receive("AccountLovership", JSONObject("""{"MemberNumber":40,"Result":"OldLovership","Lovership":[]}"""))
        socket.receive("LoginResponse", loginResponse(99, "Late old account"))
        socket.receive("ChatRoomSync", roomSync("Late old room"))
        socket.receive("ChatRoomMessage", JSONObject("""{"Type":"Hidden","Content":"OwnerRuleChangePoseBlock","Sender":20,"Target":10}"""))
        socket.disconnected.call("Late old disconnect")
    }

    private class Fixture {
        val gateway = RazorGatewayImpl()
        val config = BcServerConfig(serverUrl = "http://localhost:65530/", version = "R129")
        val transport = field(gateway, "transport") as RazorSocketTransport
        val requests = field(gateway, "requests") as RazorRequestCoordinator
        val roomStore = field(gateway, "roomStateStore") as RazorRoomStateStore
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        val createdUris = CopyOnWriteArrayList<String>()
        val createdReferers = CopyOnWriteArrayList<String>()
        var autoConnectNewSockets = true
        private val createdSockets = Channel<FakeSocket>(Channel.UNLIMITED)

        init {
            val factory: (URI, IO.Options) -> Socket = { uri, options ->
                assertTrue(options.forceNew)
                assertFalse(options.reconnection)
                createdReferers += requireNotNull(options.extraHeaders["Referer"]?.single())
                createdUris += uri.toString()
                FakeSocket(autoConnectNewSockets).also {
                    sockets += it
                    createdSockets.trySend(it)
                }
            }
            RazorSocketTransport::class.java.getDeclaredField("socketFactory")
                .apply { isAccessible = true }.set(transport, factory)
        }

        suspend fun awaitCreatedSocket(): FakeSocket = withTimeout(5_000L) { createdSockets.receive() }
    }

    private class FakeSocket(private val autoConnect: Boolean) : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        private var online = false
        private lateinit var incoming: Emitter.Listener
        private lateinit var connected: Emitter.Listener
        lateinit var disconnected: Emitter.Listener
            private set
        private val emissionsByEvent = ConcurrentHashMap<String, Channel<String>>()
        val emissions = CopyOnWriteArrayList<Pair<String, String>>()

        override fun connect(): Socket {
            connected = listeners(Socket.EVENT_CONNECT).single()
            disconnected = listeners(Socket.EVENT_DISCONNECT).single()
            if (autoConnect) fireConnected()
            return this
        }

        fun fireConnected() {
            online = true
            connected.call()
        }

        override fun connected(): Boolean = online

        override fun onAnyIncoming(listener: Emitter.Listener): Socket {
            incoming = listener
            return this
        }

        override fun emit(event: String, vararg args: Any?): Emitter {
            val payload = args.single().toString()
            emissions += event to payload
            emissionsByEvent.computeIfAbsent(event) { Channel(Channel.UNLIMITED) }.trySend(payload)
            return this
        }

        override fun disconnect(): Socket {
            online = false
            return this
        }

        fun receive(eventName: String, value: Any) {
            incoming.call(eventName, value)
        }

        suspend fun awaitEmission(eventName: String): JSONObject = JSONObject(withTimeout(5_000L) {
            emissionsByEvent.computeIfAbsent(eventName) { Channel(Channel.UNLIMITED) }.receive()
        })
    }

    private companion object {
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)

        fun freezeExecutor(executor: ScheduledExecutorService): CountDownLatch {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.submit {
                entered.countDown()
                assertTrue(release.await(5L, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            return release
        }

        @Suppress("UNCHECKED_CAST")
        fun drainEvents(gateway: RazorGatewayImpl): List<BcSessionGatewayEvent> {
            val events = field(gateway, "eventQueue") as Channel<BcSessionGatewayEvent>
            return generateSequence { events.tryReceive().getOrNull() }.toList()
        }

        fun loginResponse(memberNumber: Int, name: String): JSONObject = JSONObject(
            """{"ID":"$memberNumber","MemberNumber":$memberNumber,"Name":"$name","Money":500,"Log":[],"Owner":"Owner","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":3},"Lovership":[{"MemberNumber":30,"Name":"Lover","Stage":2}]}""",
        )

        fun roomSync(name: String): JSONObject = JSONObject(
            """{"Name":"$name","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":3}},{"ID":"20","MemberNumber":20,"Name":"Owner"}]}""",
        )
    }
}
