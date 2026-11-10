package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.protocol.BcChatMessageCodec
import dev.vivian.mobilebc.core.protocol.DecodeResult
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorGatewayEventQueueTest {
    @Test
    fun roomEventsRemainOrderedWhenTheRuntimeStartsAfterABurst() = runBlocking {
        val gateway = RazorGatewayImpl()
        authenticate(gateway)
        receive(gateway, "ChatRoomSync", """{"Name":"Test room","Character":[]}""")
        val descriptions = (1..256).map { "Update $it" }
        descriptions.forEach { description ->
            receive(
                gateway,
                "ChatRoomSyncRoomProperties",
                """{"Description":"$description"}""",
            )
        }

        val events = withTimeout(5_000L) { gateway.events.take(257).toList() }

        assertTrue(events.first() is BcSessionGatewayEvent.RoomJoined)
        assertEquals(
            descriptions,
            events.drop(1).map { (it as BcSessionGatewayEvent.RoomUpdated).room.description },
        )
    }

    @Test
    fun queuedConnectionEventsSurviveBeyondTheFormerBufferSize() = runBlocking {
        val gateway = RazorGatewayImpl()
        val positions = (1..256).toList()
        positions.forEach { position -> receive(gateway, "LoginQueue", position.toString()) }
        disconnect(gateway, "test disconnect")

        val events = withTimeout(5_000L) { gateway.events.take(257).toList() }

        assertEquals(
            positions,
            events.dropLast(1).map { (it as BcSessionGatewayEvent.LoginQueued).position },
        )
        assertEquals(BcSessionGatewayEvent.Disconnected("test disconnect"), events.last())
    }

    @Test
    fun gatewayAttachesReceiveContextBeforeTheFrameWaitsForItsConsumer() = runBlocking {
        val gateway = RazorGatewayImpl()
        authenticate(gateway)
        receive(
            gateway,
            "ChatRoomSync",
            """{"Name":"Room A","Character":[{"ID":"2002","MemberNumber":2002,"Name":"Bob"}]}""",
        )
        val raw = """{"Type":"Chat","Sender":2002,"Content":"hello","Dictionary":[]}"""
        receive(gateway, "ChatRoomChat", raw)
        receive(
            gateway,
            "ChatRoomSync",
            """{"Name":"Room B","Character":[{"ID":"2002","MemberNumber":2002,"Name":"Robert"}]}""",
        )

        val frame = withTimeout(5_000L) { gateway.inboundFrames.first() }
        val context = requireNotNull(frame.receiveContext)
        val decoded = BcChatMessageCodec.decode(frame)

        assertEquals("Room A", context.roomName)
        assertEquals("Bob", context.roomMembers[2002])
        assertFalse(gateway.isCurrentRoomContext(context))
        assertEquals(raw, frame.raw)
        assertEquals(listOf(raw), frame.args)
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(decoded, BcChatMessageCodec.decode(frame.copy(receiveContext = null)))
    }

    private suspend fun authenticate(gateway: RazorGatewayImpl) = coroutineScope {
        val socket = AuthenticationSocket()
        val transport = RazorGatewayImpl::class.java.getDeclaredField("transport")
            .apply { isAccessible = true }.get(gateway) as RazorSocketTransport
        RazorSocketTransport::class.java.getDeclaredField("socket")
            .apply { isAccessible = true }.set(transport, socket)
        val login = async(start = CoroutineStart.UNDISPATCHED) { gateway.login("Alice", "password") }
        withTimeout(5_000L) { socket.loginEmitted.await() }
        receive(gateway, "LoginResponse", """{"ID":"1001","MemberNumber":1001,"Name":"Alice"}""")
        val result = withTimeout(5_000L) { login.await() }
        assertTrue(result is BcSessionLoginResult.Success)
        assertEquals(1001, (result as BcSessionLoginResult.Success).memberNumber)
        val requests = RazorGatewayImpl::class.java.getDeclaredField("requests")
            .apply { isAccessible = true }.get(gateway) as RazorRequestCoordinator
        assertEquals(1001, requests.authenticatedPrivateChatState?.memberNumber)
        val roomStore = RazorGatewayImpl::class.java.getDeclaredField("roomStateStore")
            .apply { isAccessible = true }.get(gateway) as RazorRoomStateStore
        assertEquals(1001, roomStore.selfMemberNumber)
        val initial = withTimeout(5_000L) { gateway.events.first() }
        assertTrue(initial is BcSessionGatewayEvent.RoomPresenceReset)
        assertEquals(1001, (initial as BcSessionGatewayEvent.RoomPresenceReset).accountMemberNumber)
        assertEquals(requests.authenticatedSelfRuleLogSessionId, initial.selfRuleLogSessionId)
        assertEquals(roomStore.captureReceiveContext().roomPresenceEpoch, initial.roomPresenceEpoch)
    }

    private fun receive(gateway: RazorGatewayImpl, eventName: String, payload: String) {
        val method = RazorGatewayImpl::class.java.getDeclaredMethod(
            "handleIncomingEvent",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(gateway, eventName, payload)
    }

    private fun disconnect(gateway: RazorGatewayImpl, reason: String) {
        val method = RazorGatewayImpl::class.java.getDeclaredMethod("handleDisconnected", String::class.java)
        method.isAccessible = true
        method.invoke(gateway, reason)
    }

    private class AuthenticationSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        val loginEmitted = CompletableDeferred<Unit>()
        override fun connected(): Boolean = true
        override fun emit(event: String, vararg args: Any?): Emitter {
            if (event == "AccountLogin") loginEmitted.complete(Unit)
            return this
        }
        override fun disconnect(): Socket = this
    }
}
