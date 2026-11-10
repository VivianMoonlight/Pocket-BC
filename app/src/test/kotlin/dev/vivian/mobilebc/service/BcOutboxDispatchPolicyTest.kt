package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcOutboxDispatchPolicyTest {
    private val messages = BcSessionMessageCoordinator()
    private val room = BcRoom(BcRoomName("Saved Room"), "", 2, 10, false, null)
    private val online = BcSessionSnapshot(
        state = BcSessionState.Ready,
        desiredOnline = true,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        account = BcAccount(1001, "Alice", "alice"),
        currentRoom = room,
        roomMembers = listOf(BcMember(2002, "Bob", false)),
    )

    @Test
    fun roomChatWaitsForItsOriginalRoomAfterReconnect() {
        val command = command(BcMessageTransport.RoomChat, messages.roomConversationId(room))

        assertTrue(canDispatchQueuedCommand(command, online, messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(currentRoom = null), messages))
        assertFalse(canDispatchQueuedCommand(
            command,
            online.copy(currentRoom = room.copy(name = BcRoomName("Other Room"))),
            messages,
        ))
    }

    @Test
    fun whisperWaitsUntilTheTargetIsInItsOriginalRoom() {
        val command = command(BcMessageTransport.RoomWhisper, BcConversationId("message:2002"), 2002)

        assertTrue(canDispatchQueuedCommand(command, online, messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(currentRoom = null), messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(roomMembers = emptyList()), messages))
        assertFalse(canDispatchQueuedCommand(command,
            online.copy(currentRoom = room.copy(name = BcRoomName("Other Room"))), messages))
    }

    @Test
    fun unscopedLegacyWhisperIsNeverGuessedToBelongToTheCurrentRoom() {
        val unscoped = command(BcMessageTransport.RoomWhisper, BcConversationId("message:2002"), 2002)
            .copy(originRoomName = null)
        assertFalse(canDispatchQueuedCommand(unscoped, online, messages))
        assertTrue(canDispatchQueuedCommand(unscoped.copy(replyToId = "previous", replyRoomName = room.name.value), online, messages))
        assertFalse(canDispatchQueuedCommand(unscoped.copy(replyToId = "previous", replyRoomName = "Other Room"), online, messages))
    }

    @Test
    fun beepCanDrainWithoutARoomButEveryTransportRequiresVerifiedOwner() {
        val command = command(BcMessageTransport.AccountBeep, BcConversationId("message:2002"), 2002)

        assertTrue(canDispatchQueuedCommand(command, online.copy(currentRoom = null), messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(desiredOnline = false), messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(account = null), messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(connectionHealth = BcConnectionHealth.Offline), messages))
    }

    @Test
    fun aNewLoginCannotDispatchMessagesUsingThePreviousVerifiedHealthAndOwner() {
        val command = command(BcMessageTransport.AccountBeep, BcConversationId("message:2002"), 2002)

        assertFalse(canDispatchQueuedCommand(command, online.copy(state = BcSessionState.LoggingIn), messages))
        assertFalse(canDispatchQueuedCommand(command, online.copy(state = BcSessionState.LoginQueued(2)), messages))
        assertTrue(canDispatchQueuedCommand(command, online, messages))
    }

    private fun command(
        transport: BcMessageTransport,
        conversationId: BcConversationId,
        targetMemberNumber: Int? = null,
    ): BcOutboundCommand {
        val kind = when (transport) {
            BcMessageTransport.RoomWhisper -> BcMessageKind.Whisper
            BcMessageTransport.AccountBeep -> BcMessageKind.Beep
            else -> BcMessageKind.Chat
        }
        return BcOutboundCommand(
            "message", conversationId, kind, transport, "hello", targetMemberNumber, 1L,
            originRoomName = room.name.value.takeIf { transport == BcMessageTransport.RoomWhisper },
        )
    }
}
