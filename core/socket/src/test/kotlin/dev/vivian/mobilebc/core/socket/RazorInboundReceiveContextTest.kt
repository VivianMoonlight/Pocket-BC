package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorInboundReceiveContextTest {
    @Test
    fun queuedMessageKeepsTheRoomAndNamesFromReceiveTime() = runBlocking {
        val store = store()
        val queue = RazorInboundMessageQueue()
        store.selfMemberNumber = 1001
        syncRoom(store, "Room A", "Alice", "Bob")
        val raw = """{"Type":"Chat","Sender":2002,"Content":"hello","Dictionary":[]}"""
        val frame = CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomChat",
            args = listOf(raw),
            receivedAtMillis = 1L,
            raw = raw,
            occurrenceId = 7L,
            receiveContext = store.captureReceiveContext(),
        )
        assertTrue(queue.offer(frame))

        syncRoom(store, "Room B", "Alice in B", "Bob in B")
        val queued = queue.frames.first()
        val context = requireNotNull(queued.receiveContext)

        assertEquals("Room A", context.roomName)
        assertEquals(1001, context.ownerMemberNumber)
        assertEquals("Alice", context.ownerDisplayName)
        assertEquals(mapOf(1001 to "Alice", 2002 to "Bob"), context.roomMembers)
        assertFalse(store.isCurrentRoomContext(context))
        assertEquals(raw, queued.raw)
        assertEquals(listOf(raw), queued.args)
        assertEquals(7L, queued.occurrenceId)
    }

    @Test
    fun characterUpdatesPublishNewNamesWithoutChangingRoomPresence() {
        val store = store()
        store.selfMemberNumber = 1001
        syncRoom(store, "Room A", "Alice", "Bob")
        val captured = store.captureReceiveContext()

        store.handle(
            RazorWireParser.parse(
                "ChatRoomSyncCharacter",
                """{"SourceMemberNumber":2002,"Character":{"ID":"2002","MemberNumber":2002,"Name":"Robert","Nickname":"Bobby"}}""",
            ),
        )

        val updated = store.captureReceiveContext()
        assertEquals("Bob", captured.roomMembers[2002])
        assertEquals("Bobby", updated.roomMembers[2002])
        assertEquals(captured.roomPresenceEpoch, updated.roomPresenceEpoch)
        assertTrue(store.isCurrentRoomContext(captured))
    }

    @Test
    fun leavingAndRejoiningTheSameRoomInvalidatesTheOldPresence() {
        val store = store()
        store.selfMemberNumber = 1001
        syncRoom(store, "Same room")
        val captured = store.captureReceiveContext()

        store.emitRoomTransition {}
        assertFalse(store.isCurrentRoomContext(captured))
        syncRoom(store, "Same room")

        assertEquals(captured.roomName, store.captureReceiveContext().roomName)
        assertTrue(store.captureReceiveContext().roomPresenceEpoch > captured.roomPresenceEpoch)
        assertFalse(store.isCurrentRoomContext(captured))
    }

    @Test
    fun serverSelfLeaveInvalidatesTheRoomPresence() {
        val store = store()
        store.selfMemberNumber = 1001
        syncRoom(store, "Room A")
        val captured = store.captureReceiveContext()

        store.handle(
            RazorWireParser.parse("ChatRoomSyncMemberLeave", """{"SourceMemberNumber":1001}"""),
        )

        assertNull(store.captureReceiveContext().roomName)
        assertFalse(store.isCurrentRoomContext(captured))
    }

    @Test
    fun disconnectAndSessionResetInvalidateContextsAcrossTheSameAccountAndRoom() {
        val store = store()
        store.selfMemberNumber = 1001
        syncRoom(store, "Same room")
        val beforeDisconnect = store.captureReceiveContext()

        store.clearRoom()
        assertFalse(store.isCurrentRoomContext(beforeDisconnect))
        syncRoom(store, "Same room")
        val beforeReset = store.captureReceiveContext()
        assertFalse(store.isCurrentRoomContext(beforeDisconnect))

        store.resetSession()
        assertNull(store.captureReceiveContext().ownerMemberNumber)
        assertNull(store.captureReceiveContext().roomName)
        store.selfMemberNumber = 1001
        syncRoom(store, "Same room")

        assertFalse(store.isCurrentRoomContext(beforeReset))
        assertTrue(store.isCurrentRoomContext(store.captureReceiveContext()))
    }

    @Test
    fun accountChangeCannotReuseThePreviousOwnersContext() {
        val store = store()
        store.selfMemberNumber = 1001
        syncRoom(store, "Room A")
        val captured = store.captureReceiveContext()

        store.selfMemberNumber = 2002

        assertEquals(1001, captured.ownerMemberNumber)
        assertEquals(2002, store.captureReceiveContext().ownerMemberNumber)
        assertFalse(store.isCurrentRoomContext(captured))
        store.selfMemberNumber = 1001
        assertFalse(store.isCurrentRoomContext(captured))
    }

    @Test
    fun capturedNamesAreImmutableAndOwnerIsAvailableOutsideARoom() {
        val store = store()
        store.selfMemberNumber = 1001
        val outsideRoom = store.captureReceiveContext()
        assertEquals(1001, outsideRoom.ownerMemberNumber)
        assertNull(outsideRoom.roomName)
        assertFalse(store.isCurrentRoomContext(outsideRoom))
        syncRoom(store, "Room A")
        val captured = store.captureReceiveContext()

        val mutation = runCatching {
            (captured.roomMembers as MutableMap<Int, String>)[2002] = "Changed"
        }

        assertTrue(mutation.exceptionOrNull() is UnsupportedOperationException)
        assertEquals("Bob", captured.roomMembers[2002])
    }

    private fun store(): RazorRoomStateStore = RazorRoomStateStore(
        gson = Gson(),
        emit = {},
        queueAccountAppearance = {},
    )

    private fun syncRoom(
        store: RazorRoomStateStore,
        roomName: String,
        ownerName: String = "Alice",
        otherName: String = "Bob",
    ) {
        val payload = Gson().toJson(
            mapOf(
                "Name" to roomName,
                "Character" to listOf(
                    mapOf("ID" to "1001", "MemberNumber" to 1001, "Name" to ownerName),
                    mapOf("ID" to "2002", "MemberNumber" to 2002, "Name" to otherName),
                ),
            ),
        )
        store.handle(RazorWireParser.parse("ChatRoomSync", payload))
    }
}
