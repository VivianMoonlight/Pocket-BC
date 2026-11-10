package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.sync.MemberLeaveMessage
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import bot.inker.bc.razor.state.CharacterState
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RazorRoomJoinFenceTest {
    private val gson = Gson()

    @Test
    fun rejectedJoinRestoresCurrentRoomForNewCommandsWithoutRevivingOldGuards() {
        val store = store()
        store.handle(roomSync("Room A"))
        val oldGuard = store.captureRoomMessageGuard(roomChat("Room A"))
        var emittedMessages = 0

        val joinEpoch = store.emitRoomJoin {}
        assertEquals("Room A", store.captureReceiveContext().roomName)
        store.rejectRoomJoin(joinEpoch)

        store.captureRoomMessageGuard(roomChat("Room A")).invoke { emittedMessages++ }
        assertThrows(OutboxDispatchDeferredException::class.java) {
            oldGuard { emittedMessages++ }
        }
        assertEquals(1, emittedMessages)
        assertEquals("Room A", store.captureReceiveContext().roomName)
    }

    @Test
    fun commandsCapturedDuringPendingJoinCannotEmitEvenWhilePreviousRoomIsRetained() {
        val store = store()
        store.handle(roomSync("Room A"))
        store.emitRoomJoin {}
        val newChatGuard = store.captureRoomMessageGuard(roomChat("Room A"))
        val newWhisperGuard = store.captureRoomMessageGuard(whisper())
        var emittedMessages = 0

        assertThrows(OutboxDispatchDeferredException::class.java) {
            newChatGuard { emittedMessages++ }
        }
        assertThrows(OutboxDispatchDeferredException::class.java) {
            newWhisperGuard { emittedMessages++ }
        }

        assertEquals(0, emittedMessages)
        assertEquals("Room A", store.captureReceiveContext().roomName)
        assertEquals(setOf(SELF, TARGET), store.captureReceiveContext().roomMembers.keys)
    }

    @Test
    fun lateJoinRejectionCannotRollbackNewSyncOrLaterPendingJoinAndKeepsMemberUpdates() {
        val store = store()
        store.handle(roomSync("Room A"))
        val oldJoinEpoch = store.emitRoomJoin {}
        store.handle(roomSync("Room B"))
        val syncedContext = store.captureReceiveContext()
        var emittedMessages = 0

        store.rejectRoomJoin(oldJoinEpoch)
        assertEquals(syncedContext, store.captureReceiveContext())
        store.captureRoomMessageGuard(roomChat("Room B")).invoke { emittedMessages++ }

        val laterJoinEpoch = store.emitRoomJoin {}
        store.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = TARGET)))
        val pendingContext = store.captureReceiveContext()
        assertFalse(TARGET in pendingContext.roomMembers)
        store.rejectRoomJoin(oldJoinEpoch)
        assertEquals(pendingContext, store.captureReceiveContext())
        assertThrows(OutboxDispatchDeferredException::class.java) {
            store.captureRoomMessageGuard(roomChat("Room B")).invoke { emittedMessages++ }
        }

        store.rejectRoomJoin(laterJoinEpoch)
        assertEquals("Room B", store.captureReceiveContext().roomName)
        assertEquals(setOf(SELF), store.captureReceiveContext().roomMembers.keys)
        store.captureRoomMessageGuard(roomChat("Room B")).invoke { emittedMessages++ }
        assertThrows(OutboxDispatchDeferredException::class.java) {
            store.captureRoomMessageGuard(whisper()).invoke { emittedMessages++ }
        }
        assertEquals(2, emittedMessages)
    }

    @Test
    fun ordinaryWhisperCannotFollowItsTargetIntoAnotherRoom() {
        val store = store()
        val command = whisper()
        store.handle(roomSync("Room A"))
        var emitted = 0
        val oldGuard = store.captureRoomMessageGuard(command)
        oldGuard { emitted++ }

        store.handle(roomSync("Room B"))
        // The target is still present. Even a freshly captured guard must honor the stored room.
        assertThrows(OutboxDispatchDeferredException::class.java) {
            store.captureRoomMessageGuard(command).invoke { emitted++ }
        }
        assertThrows(OutboxDispatchDeferredException::class.java) {
            oldGuard { emitted++ }
        }
        assertEquals(1, emitted)

        store.handle(roomSync("Room A"))
        store.captureRoomMessageGuard(command).invoke { emitted++ }
        assertEquals(2, emitted)
    }

    @Test
    fun finalGuardRejectsUnscopedLegacyWhispersButAcceptsTheirExistingReplyRoomScope() {
        val store = store()
        store.handle(roomSync("Room A"))
        var emitted = 0
        assertThrows(OutboxDispatchDeferredException::class.java) {
            store.captureRoomMessageGuard(whisper(originRoomName = null)).invoke { emitted++ }
        }
        store.captureRoomMessageGuard(whisper(originRoomName = null).copy(
            replyToId = "previous-message", replyRoomName = "Room A",
        )).invoke { emitted++ }
        assertEquals(1, emitted)
    }

    private fun store(): RazorRoomStateStore =
        RazorRoomStateStore(
            gson = gson,
            emit = {},
            queueAccountAppearance = {},
        ).apply { selfMemberNumber = SELF }

    private fun roomChat(roomName: String): BcOutboundCommand =
        BcOutboundCommand(
            clientMsgId = "room-chat",
            conversationId = bcRoomConversationId(roomName),
            kind = BcMessageKind.Chat,
            transport = BcMessageTransport.RoomChat,
            text = "hello",
            targetMemberNumber = null,
            createdAtMillis = 1_000L,
        )

    private fun whisper(originRoomName: String? = "Room A"): BcOutboundCommand =
        BcOutboundCommand(
            clientMsgId = "room-whisper",
            conversationId = BcConversationId("message:$TARGET"),
            kind = BcMessageKind.Whisper,
            transport = BcMessageTransport.RoomWhisper,
            text = "hello target",
            originRoomName = originRoomName,
            targetMemberNumber = TARGET,
            createdAtMillis = 1_000L,
        )

    private fun roomSync(roomName: String): RazorWireEvent.ChatRoomSync {
        val characters = listOf(SELF, TARGET).map { memberNumber ->
            CharacterState(
                id = memberNumber.toString(),
                memberNumber = memberNumber,
                name = "Member $memberNumber",
                appearance = emptyList(),
            )
        }
        return RazorWireEvent.ChatRoomSync(
            value = RoomSyncMessage(
                name = roomName,
                character = characters.map { character ->
                    JsonObject().apply {
                        addProperty("ID", character.id)
                        addProperty("MemberNumber", character.memberNumber)
                        addProperty("Name", character.name)
                        add("Appearance", gson.toJsonTree(character.appearance))
                    }
                },
            ),
        )
    }

    private companion object {
        const val SELF = 1001
        const val TARGET = 2002
    }
}
