package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorSocketCallbackFenceTest {
    @Test
    fun malformedFullRoomSyncClearsTheRoomAndPendingJoinAndLaterSyncRecovers() {
        val gateway = RazorGatewayImpl()
        val transport = field(gateway, "transport") as RazorSocketTransport
        val incoming = incomingListener(bindCurrentSocket(transport))
        val store = roomStore(gateway)
        val malformed = listOf(
            """{"Character":[]}""",
            """{"Name":"Invalid","Visibility":[null],"Character":[]}""",
            """{"Name":"Invalid","Access":[null],"Character":[]}""",
            """{"Name":"Invalid","BlockCategory":[null],"Character":[]}""",
        )
        try {
            expectLoginResponse(gateway)
            incoming.call("LoginResponse", JSONObject(loginResponse(1001)))
            incoming.call("ChatRoomSync", JSONObject("""{"Name":"Room A","Character":[]}"""))
            val guard = store.captureRoomActionGuard()
            val chatGuard = store.captureRoomMessageGuard(roomChat("Room A"))
            var emitted = 0
            malformed.forEach { payload ->
                incoming.call("ChatRoomSync", JSONObject("""{"Name":"Room A","Character":[]}"""))
                val active = store.captureReceiveContext()
                val currentGuard = store.captureRoomActionGuard()
                val currentChatGuard = store.captureRoomMessageGuard(roomChat("Room A"))
                incoming.call("ChatRoomSync", JSONObject(payload))
                assertNull(store.captureRoomProjection())
                assertNull(store.captureReceiveContext().roomName)
                assertTrue(store.captureReceiveContext().roomPresenceEpoch > active.roomPresenceEpoch)
                assertThrows(OutboxDispatchDeferredException::class.java) { currentGuard { emitted++ } }
                assertThrows(OutboxDispatchDeferredException::class.java) { currentChatGuard { emitted++ } }
            }

            malformed.forEach { payload ->
                incoming.call("ChatRoomSync", JSONObject("""{"Name":"Room A","Character":[]}"""))
                store.emitRoomJoin {}
                val pending = store.captureReceiveContext()
                incoming.call("ChatRoomSync", JSONObject(payload))
                assertNull(store.captureRoomProjection())
                assertNull(store.captureReceiveContext().roomName)
                assertTrue(store.captureReceiveContext().roomPresenceEpoch > pending.roomPresenceEpoch)
                assertThrows(OutboxDispatchDeferredException::class.java) {
                    store.captureRoomActionGuard().invoke { emitted++ }
                }
            }
            assertEquals(0, emitted)

            incoming.call("ChatRoomSync", JSONObject("""{"Name":"Room B","Character":[]}"""))
            assertEquals("Room B", store.captureReceiveContext().roomName)
            store.captureRoomActionGuard().invoke { emitted++ }
            store.captureRoomMessageGuard(roomChat("Room B")).invoke { emitted++ }
            assertEquals(2, emitted)
            assertThrows(OutboxDispatchDeferredException::class.java) { guard { emitted++ } }
            assertThrows(OutboxDispatchDeferredException::class.java) { chatGuard { emitted++ } }
            assertEquals(2, emitted)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun admittedLoginResponseCannotRestoreAuthenticationAfterSessionReset() {
        val gateway = RazorGatewayImpl()
        val transport = field(gateway, "transport") as RazorSocketTransport
        val candidate = bindCurrentSocket(transport)
        val incoming = incomingListener(candidate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = mutableListOf<TestWorker>()
        try {
            expectLoginResponse(gateway)
            workers += worker {
                incoming.call("LoginResponse", blockingValue(loginResponse(1001), entered, release))
            }
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val reset = worker { reset(gateway) }.also(workers::add)
            awaitBlockedOrCompleted(reset.thread, field(gateway, "inboundLock")!!)

            release.countDown()
            workers.forEach(TestWorker::awaitCompletion)

            assertNull(roomStore(gateway).captureReceiveContext().ownerMemberNumber)
            assertNull(economy(gateway))
            val packetCount = gateway.packetLog.entries.value.size
            incoming.call("LoginResponse", JSONObject(loginResponse(1001)))
            assertNull(roomStore(gateway).captureReceiveContext().ownerMemberNumber)
            assertNull(economy(gateway))
            assertEquals(packetCount, gateway.packetLog.entries.value.size)
        } finally {
            release.countDown()
            workers.forEach(TestWorker::join)
            gateway.close()
        }
    }

    @Test
    fun admittedDisconnectCannotClearTheReplacementSessionAfterReset() {
        val gateway = RazorGatewayImpl()
        val transport = field(gateway, "transport") as RazorSocketTransport
        val previous = bindCurrentSocket(transport)
        val disconnected = previous.listeners(Socket.EVENT_DISCONNECT).single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = mutableListOf<TestWorker>()
        try {
            workers += worker {
                disconnected.call(blockingValue("old connection", entered, release))
            }
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val replacement = worker {
                reset(gateway)
                val incoming = incomingListener(bindCurrentSocket(transport))
                expectLoginResponse(gateway)
                incoming.call("LoginResponse", JSONObject(loginResponse(2002)))
                incoming.call("ChatRoomSync", JSONObject("""{"Name":"Replacement","Character":[]}"""))
            }.also(workers::add)
            awaitBlockedOrCompleted(replacement.thread, field(gateway, "inboundLock")!!)

            release.countDown()
            workers.forEach(TestWorker::awaitCompletion)

            val context = roomStore(gateway).captureReceiveContext()
            val replacementEconomy = economy(gateway)
            assertEquals(2002, context.ownerMemberNumber)
            assertEquals("Replacement", context.roomName)
            assertNotNull(replacementEconomy)
            disconnected.call("late old disconnect")
            assertEquals(context, roomStore(gateway).captureReceiveContext())
            assertEquals(replacementEconomy, economy(gateway))
        } finally {
            release.countDown()
            workers.forEach(TestWorker::join)
            gateway.close()
        }
    }

    @Test
    fun closeWaitsForCallbackEffectsWithoutHoldingTheTransportLifecycleLock() {
        val effects = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var transport: RazorSocketTransport
        fun recordEffect() {
            assertFalse(Thread.holdsLock(requireNotNull(field(transport, "lifecycleLock"))))
            effects.incrementAndGet()
        }
        transport = RazorSocketTransport(
            packetLog = PacketLogStore(),
            onConnected = { recordEffect() },
            onDisconnected = { recordEffect() },
            onIncoming = { _, _ ->
                recordEffect()
                entered.countDown()
                assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            },
        )
        val candidate = bindCurrentSocket(transport)
        val connected = candidate.listeners(Socket.EVENT_CONNECT).single()
        val disconnected = candidate.listeners(Socket.EVENT_DISCONNECT).single()
        val incoming = incomingListener(candidate)
        val workers = mutableListOf<TestWorker>()
        try {
            connected.call()
            disconnected.call("current connection")
            workers += worker { incoming.call("ServerInfo", JSONObject()) }
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val closing = worker { transport.close() }.also(workers::add)
            awaitBlockedOrCompleted(closing.thread, field(transport, "callbackLock")!!)
            assertEquals(Thread.State.BLOCKED, closing.thread.state)

            release.countDown()
            workers.forEach(TestWorker::awaitCompletion)

            connected.call()
            disconnected.call("late old disconnect")
            incoming.call("ServerInfo", JSONObject())
            assertEquals(3, effects.get())
        } finally {
            release.countDown()
            workers.forEach(TestWorker::join)
            transport.close()
        }
    }

    private fun bindCurrentSocket(transport: RazorSocketTransport): Socket {
        val candidate = IO.socket(URI.create("http://localhost"), IO.Options().apply { forceNew = true })
        val generation = transport.captureGeneration()
        setField(transport, "socket", candidate)
        RazorSocketTransport::class.java.getDeclaredMethod(
            "bindSocketCallbacks",
            Socket::class.java,
            Long::class.javaPrimitiveType,
            CompletableDeferred::class.java,
        ).apply { isAccessible = true }.invoke(transport, candidate, generation, CompletableDeferred<Unit>())
        return candidate
    }

    @Suppress("UNCHECKED_CAST")
    private fun incomingListener(candidate: Socket): Emitter.Listener =
        (field(candidate, "onAnyIncomingListeners") as Collection<Emitter.Listener>).single()

    private fun reset(gateway: RazorGatewayImpl) {
        RazorGatewayImpl::class.java.getDeclaredMethod(
            "closeSocket",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            java.lang.Long::class.java,
            Throwable::class.java,
        ).apply { isAccessible = true }.invoke(gateway, false, false, null, IllegalStateException("Socket sender queue was cleared."))
    }

    private fun roomStore(gateway: RazorGatewayImpl): RazorRoomStateStore =
        field(gateway, "roomStateStore") as RazorRoomStateStore

    private fun economy(gateway: RazorGatewayImpl): BcRelationshipEconomyState? =
        field(gateway, "relationshipEconomy") as BcRelationshipEconomyState?

    @Suppress("UNCHECKED_CAST")
    private fun expectLoginResponse(gateway: RazorGatewayImpl) {
        val requests = field(gateway, "requests") as RazorRequestCoordinator
        (field(requests, "login") as PendingRequest<BcSessionLoginResult>).begin()
    }

    private fun loginResponse(memberNumber: Int): String =
        """{"ID":"$memberNumber","MemberNumber":$memberNumber,"Name":"Alice","Money":500}"""

    private fun roomChat(roomName: String): BcOutboundCommand = BcOutboundCommand(
        clientMsgId = "queued-room-chat",
        conversationId = bcRoomConversationId(roomName),
        kind = BcMessageKind.Chat,
        transport = BcMessageTransport.RoomChat,
        text = "Hello",
        targetMemberNumber = null,
        createdAtMillis = 0L,
    )

    private fun blockingValue(value: String, entered: CountDownLatch, release: CountDownLatch): Any =
        object {
            override fun toString(): String {
                entered.countDown()
                assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                return value
            }
        }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun worker(action: () -> Unit): TestWorker {
        val task = FutureTask(Callable { action() })
        val thread = Thread(task, "razor-callback-fence-test").apply {
            isDaemon = true
            start()
        }
        return TestWorker(thread, task)
    }

    private fun awaitBlockedOrCompleted(thread: Thread, callbackLock: Any) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        val threadBean = ManagementFactory.getThreadMXBean()
        while (thread.isAlive) {
            val info = threadBean.getThreadInfo(thread.id)
            if (
                info?.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(callbackLock) &&
                info.lockInfo?.className == callbackLock.javaClass.name
            ) return
            assertTrue("Lifecycle operation did not reach the callback fence.", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private class TestWorker(val thread: Thread, private val task: FutureTask<Unit>) {
        fun awaitCompletion() {
            task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        fun join() {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    private companion object {
        private const val TIMEOUT_SECONDS = 5L
    }
}
