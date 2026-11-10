package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.sync.MemberLeaveMessage
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorQueuedRoomMessageTest {
    @Test
    fun queuedChatCannotFollowTheAccountIntoAnotherRoom() = assertQueuedMessageDeferred {
        it.handle(roomSync("B"))
    }

    @Test
    fun queuedChatCannotSurviveALocalLeave() = assertQueuedMessageDeferred {
        it.emitRoomTransition {}
    }

    @Test
    fun queuedChatCannotSurviveAServerKick() = assertQueuedMessageDeferred {
        it.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = OWNER)))
    }

    @Test
    fun queuedChatCannotSurviveLeavingAndRejoiningTheSameRoomName() = assertQueuedMessageDeferred {
        it.emitRoomTransition {}
        it.handle(roomSync("A"))
    }

    @Test
    fun queuedChatCannotSurviveAnAccountChange() = assertQueuedMessageDeferred {
        it.selfMemberNumber = 3003
    }

    @Test
    fun queuedWhisperRechecksMembershipEvenWhenTheRoomEpochIsUnchanged() = assertQueuedMessageDeferred(
        command = command(transport = BcMessageTransport.RoomWhisper, target = TARGET),
    ) {
        it.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = TARGET)))
    }

    @Test
    fun originalConversationMustMatchTheRoomAtEmission() = assertQueuedMessageDeferred(
        command = command(roomName = "B"),
    ) { }

    @Test
    fun unchangedRoomEmitsTheQueuedMessageExactlyOnce() = runBlocking {
        val store = store()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val limiter = RazorRateLimiter(executor) { _, payload -> emitted += payload }
        try {
            limiter.send(OutboundPacket("ChatRoomChat", "valid", OutboundLane.Chat, emitGuard = store.captureRoomMessageGuard(command())))
            assertEquals(listOf("valid"), emitted)
        } finally {
            limiter.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun roomControlInvalidatesOlderQueuedChatBeforeTheServerReplies() = runBlocking {
        val store = store()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { blocked.countDown(); release.await() }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val limiter = RazorRateLimiter(executor) { event, _ -> emitted += event }
        var failure: Throwable? = null
        try {
            val chat = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    limiter.send(OutboundPacket("ChatRoomChat", "old", OutboundLane.Chat, emitGuard = store.captureRoomMessageGuard(command())))
                }.exceptionOrNull()
            }
            val join = launch(start = CoroutineStart.UNDISPATCHED) {
                limiter.send(OutboundPacket("ChatRoomJoin", "B", OutboundLane.RoomControl, emitGuard = { emit -> store.emitRoomJoin(emit); Unit }))
            }
            release.countDown()
            join.join()
            chat.join()
            assertEquals(listOf("ChatRoomJoin"), emitted)
            assertTrue(failure is OutboxDispatchDeferredException)
        } finally {
            release.countDown()
            limiter.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun itemUpdateAndItsActionMustShareTheOriginalRoomContext() {
        val store = store()
        val guard = store.captureRoomActionGuard(TARGET)
        val emitted = mutableListOf<String>()
        guard { emitted += "item" }
        store.handle(roomSync("B"))

        val failure = runCatching { guard { emitted += "action" } }.exceptionOrNull()

        assertEquals(listOf("item"), emitted)
        assertTrue(failure is OutboxDispatchDeferredException)
    }

    @Test
    fun targetedRoomActionCannotEmitAfterItsTargetLeaves() {
        val store = store()
        val guard = store.captureRoomActionGuard(TARGET)
        store.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = TARGET)))

        val failure = runCatching { guard { error("The activity must not emit.") } }.exceptionOrNull()

        assertTrue(failure is OutboxDispatchDeferredException)
    }

    @Test
    fun roomCannotChangeBetweenTheGuardCheckAndTheActualEmit() {
        val store = store()
        val emitting = CountDownLatch(1)
        val releaseEmit = CountDownLatch(1)
        val leaveStarted = CountDownLatch(1)
        val left = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val send = executor.submit {
                store.captureRoomMessageGuard(command()).invoke {
                    emitting.countDown()
                    assertTrue(releaseEmit.await(2, TimeUnit.SECONDS))
                    assertEquals("A", store.captureReceiveContext().roomName)
                }
            }
            assertTrue(emitting.await(1, TimeUnit.SECONDS))
            val leave = executor.submit { leaveStarted.countDown(); store.emitRoomTransition {}; left.countDown() }
            assertTrue(leaveStarted.await(1, TimeUnit.SECONDS))
            assertFalse(left.await(50, TimeUnit.MILLISECONDS))
            releaseEmit.countDown()
            send.get(1, TimeUnit.SECONDS)
            leave.get(1, TimeUnit.SECONDS)
            assertEquals(null, store.captureReceiveContext().roomName)
        } finally {
            releaseEmit.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertQueuedMessageDeferred(
        command: BcOutboundCommand = command(),
        changeRoom: (RazorRoomStateStore) -> Unit,
    ) = runBlocking {
        val store = store()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { blocked.countDown(); release.await() }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val limiter = RazorRateLimiter(executor) { _, payload -> emitted += payload }
        var failure: Throwable? = null
        try {
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    limiter.send(OutboundPacket("ChatRoomChat", "queued", OutboundLane.Chat, emitGuard = store.captureRoomMessageGuard(command)))
                }.exceptionOrNull()
            }
            changeRoom(store)
            release.countDown()
            sending.join()
            assertTrue(emitted.isEmpty())
            assertTrue(failure is OutboxDispatchDeferredException)
        } finally {
            release.countDown()
            limiter.close()
            executor.shutdownNow()
        }
    }

    private fun store() = RazorRoomStateStore(Gson(), {}, {}).apply {
        selfMemberNumber = OWNER
        handle(roomSync("A"))
    }

    private fun roomSync(name: String): RazorWireEvent.ChatRoomSync = RazorWireEvent.ChatRoomSync(
        value = RoomSyncMessage(name = name, character = listOf(OWNER, TARGET).map { member ->
            JsonObject().apply {
                addProperty("ID", member.toString())
                addProperty("MemberNumber", member)
                addProperty("Name", "Member $member")
            }
        }),
    )

    private fun command(
        roomName: String = "A",
        transport: BcMessageTransport = BcMessageTransport.RoomChat,
        target: Int? = null,
    ) = BcOutboundCommand(
        "queued", bcRoomConversationId(roomName), BcMessageKind.Chat, transport, "hello", target, 1L,
        originRoomName = roomName.takeIf { transport == BcMessageTransport.RoomWhisper },
    )

    private companion object {
        const val OWNER = 1001
        const val TARGET = 2002
    }
}
