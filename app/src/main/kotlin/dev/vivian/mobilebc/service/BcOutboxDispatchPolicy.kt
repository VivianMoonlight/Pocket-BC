package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot

internal fun canDispatchQueuedCommand(
    command: BcOutboundCommand,
    session: BcSessionSnapshot,
    messageCoordinator: BcSessionMessageCoordinator,
): Boolean {
    if (!session.isVerifiedOnline) return false
    return when (command.transport) {
        BcMessageTransport.RoomChat ->
            messageCoordinator.currentRoomConversationId(session.currentRoom) == command.conversationId
        BcMessageTransport.RoomWhisper ->
            !(command.originRoomName ?: command.replyRoomName).isNullOrBlank() &&
                session.currentRoom?.name?.value == (command.originRoomName ?: command.replyRoomName) &&
                (command.replyRoomName == null || command.replyRoomName == session.currentRoom?.name?.value) &&
                session.roomMembers.any { it.memberNumber == command.targetMemberNumber }
        BcMessageTransport.AccountBeep -> true
        BcMessageTransport.LocalSystem,
        BcMessageTransport.ProtocolEvent,
        -> false
    }
}
