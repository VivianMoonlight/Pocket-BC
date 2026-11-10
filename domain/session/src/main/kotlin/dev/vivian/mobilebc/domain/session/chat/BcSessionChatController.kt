package dev.vivian.mobilebc.domain.session.chat

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatMaxLength
import dev.vivian.mobilebc.core.model.BcRoomChatRejectedException
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import java.util.UUID
import kotlinx.coroutines.CancellationException

class BcSessionChatController(
    private val stateHolder: BcSessionRuntimeState,
    private val outboundStore: BcOutboundMessageStore,
    private val dispatchQueuedOutbound: suspend (clientMsgId: String) -> QueuedOutboundDispatchResult,
    private val messageCoordinator: BcSessionMessageCoordinator,
    private val onOperationFailure: (ReconnectReason, Throwable?) -> Unit,
    private val isVerifiedFresh: () -> Boolean,
    private val validateRoomChat: suspend (BcMessageKind, String, Int?) -> Unit = { _, _, _ -> },
) : BcSessionChat {
    override suspend fun sendChat(text: String) {
        sendRoomMessage(BcMessageKind.Chat, text)
    }

    override suspend fun sendChat(text: String, replyTo: BcMessageReplyReference) {
        sendRoomMessage(BcMessageKind.Chat, text, replyTo)
    }

    override suspend fun sendEmote(text: String) {
        sendRoomMessage(BcMessageKind.Emote, text)
    }

    override suspend fun sendEmote(text: String, replyTo: BcMessageReplyReference) {
        sendRoomMessage(BcMessageKind.Emote, text, replyTo)
    }

    override suspend fun sendAction(text: String) {
        sendRoomMessage(BcMessageKind.Action, text)
    }

    override suspend fun sendWhisper(targetMemberNumber: Int, text: String) {
        sendWhisperMessage(targetMemberNumber, text)
    }

    override suspend fun sendWhisper(targetMemberNumber: Int, text: String, replyTo: BcMessageReplyReference) {
        sendWhisperMessage(targetMemberNumber, text, replyTo)
    }

    private suspend fun sendWhisperMessage(
        targetMemberNumber: Int,
        text: String,
        replyTo: BcMessageReplyReference? = null,
    ) {
        val snapshot = stateHolder.current
        val roomName = snapshot.currentRoom?.name?.value
        if (roomName == null ||
            snapshot.roomMembers.none { it.memberNumber == targetMemberNumber }
        ) {
            error("Whisper requires the target to be in the same room.")
        }
        requireRoomChatLength(text)
        ensureVerifiedForSend()
        val account = snapshot.account ?: error("Login is required before sending.")
        validateRoomChat(BcMessageKind.Whisper, text, targetMemberNumber)
        val latest = stateHolder.current
        check(latest.account?.memberNumber == account.memberNumber &&
            latest.selfRuleLogSessionId == snapshot.selfRuleLogSessionId &&
            latest.roomPresenceEpoch == snapshot.roomPresenceEpoch && latest.currentRoom?.name?.value == roomName &&
            latest.roomMembers.any { it.memberNumber == targetMemberNumber }) {
            "The room or whisper target changed before sending."
        }
        val clientMsgId = newClientMsgId()
        val conversationId = messageCoordinator.directConversationId(targetMemberNumber)
        replyTo?.validateScope(roomName, conversationId, allowWhisper = true)
        val localMessage = localOutgoing(
            clientMsgId, conversationId, BcMessageKind.Whisper, text, targetMemberNumber,
            account, replyTo?.messageId, roomName,
        )
        enqueueOutbound(clientMsgId, conversationId, BcMessageKind.Whisper, text, targetMemberNumber, localMessage, account.memberNumber)
        dispatchLocalOutgoing(clientMsgId)
    }

    override suspend fun sendBeep(targetMemberNumber: Int, text: String) {
        if (!stateHolder.current.friendMemberNumbers.contains(targetMemberNumber)) {
            error("Private message requires a friend.")
        }
        ensureVerifiedForSend()
        val clientMsgId = newClientMsgId()
        val conversationId = messageCoordinator.directConversationId(targetMemberNumber)
        val localMessage = localOutgoing(clientMsgId, conversationId, BcMessageKind.Beep, text, targetMemberNumber)
        enqueueOutbound(clientMsgId, conversationId, BcMessageKind.Beep, text, targetMemberNumber, localMessage)
        dispatchLocalOutgoing(clientMsgId)
    }

    private suspend fun sendRoomMessage(kind: BcMessageKind, text: String, replyTo: BcMessageReplyReference? = null) {
        val snapshot = stateHolder.current
        val currentRoom = snapshot.currentRoom
        if (currentRoom == null) {
            error("Join a room before sending room chat.")
        }
        if (kind == BcMessageKind.Chat || kind == BcMessageKind.Emote) requireRoomChatLength(text)
        ensureVerifiedForSend()
        val account = snapshot.account ?: error("Login is required before sending.")
        if (kind == BcMessageKind.Chat || kind == BcMessageKind.Emote) validateRoomChat(kind, text, null)
        val latest = stateHolder.current
        check(latest.account?.memberNumber == account.memberNumber &&
            latest.selfRuleLogSessionId == snapshot.selfRuleLogSessionId &&
            latest.roomPresenceEpoch == snapshot.roomPresenceEpoch && latest.currentRoom?.name == currentRoom.name) {
            "The room changed before sending."
        }
        val clientMsgId = newClientMsgId()
        val conversationId = messageCoordinator.roomConversationId(currentRoom)
        replyTo?.validateScope(currentRoom.name.value, conversationId, allowWhisper = false)
        val localMessage = localOutgoing(
            clientMsgId, conversationId, kind, text, targetMemberNumber = null,
            account = account, replyToId = replyTo?.messageId, roomName = currentRoom.name.value,
        )
        enqueueOutbound(clientMsgId, conversationId, kind, text, targetMemberNumber = null, localMessage, account.memberNumber)
        dispatchLocalOutgoing(clientMsgId)
    }

    private fun ensureVerifiedForSend() {
        if (stateHolder.current.connectionHealth == BcConnectionHealth.VerifiedOnline && isVerifiedFresh()) return
        onOperationFailure(ReconnectReason.SilentDisconnect, null)
        error("BC connection is not verified. Reconnecting.")
    }

    private suspend fun enqueueOutbound(
        clientMsgId: String,
        conversationId: BcConversationId,
        kind: BcMessageKind,
        text: String,
        targetMemberNumber: Int?,
        localMessage: BcMessage,
        ownerMemberNumber: Int = stateHolder.current.account?.memberNumber
            ?: error("Login is required before sending."),
    ) {
        check(stateHolder.current.account?.memberNumber == ownerMemberNumber) { "The active account changed before sending." }
        try {
            outboundStore.enqueue(
                message = messageCoordinator.outboundMessage(
                    clientMsgId = clientMsgId,
                    ownerMemberNumber = ownerMemberNumber,
                    conversationId = conversationId,
                    kind = kind,
                    text = text,
                    targetMemberNumber = targetMemberNumber,
                    replyToId = localMessage.replyToId,
                    replyRoomName = localMessage.raw?.roomName.takeIf { localMessage.replyToId != null },
                    originRoomName = localMessage.raw?.roomName.takeIf { kind == BcMessageKind.Whisper },
                ),
                localMessage = localMessage,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Could not queue message locally: ${throwable.message ?: "unknown error"}",
                throwable,
            )
        }
    }

    private fun localOutgoing(
        clientMsgId: String,
        conversationId: BcConversationId,
        kind: BcMessageKind,
        text: String,
        targetMemberNumber: Int?,
        account: BcAccount? = stateHolder.current.account,
        replyToId: String? = null,
        roomName: String? = null,
    ): BcMessage =
        messageCoordinator.localOutgoingMessage(
            clientMsgId = clientMsgId,
            account = account,
            conversationId = conversationId,
            kind = kind,
            text = text,
            targetMemberNumber = targetMemberNumber,
            replyToId = replyToId,
            roomName = roomName,
        )

    private fun BcMessageReplyReference.validateScope(
        currentRoomName: String,
        destinationConversationId: BcConversationId,
        allowWhisper: Boolean,
    ) {
        require(messageId.isNotBlank()) { "Reply message ID is missing." }
        require(roomName == currentRoomName) { "The reply belongs to another room." }
        when (transport) {
            BcMessageTransport.RoomChat -> require(conversationId == messageCoordinator.roomConversationId(currentRoomName)) {
                "The reply belongs to another room conversation."
            }
            BcMessageTransport.RoomWhisper -> require(allowWhisper && conversationId == destinationConversationId) {
                "A whisper reply must stay in the same private conversation."
            }
            else -> error("This message transport does not support replies.")
        }
    }

    private fun requireRoomChatLength(text: String) {
        require(text.length <= BcRoomChatMaxLength) { "Room chat is limited to $BcRoomChatMaxLength characters." }
    }

    private suspend fun dispatchLocalOutgoing(clientMsgId: String) {
        when (val result = dispatchQueuedOutbound(clientMsgId)) {
            QueuedOutboundDispatchResult.Submitted,
            QueuedOutboundDispatchResult.Confirmed,
            -> Unit
            is QueuedOutboundDispatchResult.Failed -> {
                result.roomChatRejection?.let { throw BcRoomChatRejectedException(it) }
                onOperationFailure(
                    ReconnectReason.OperationFailed,
                    IllegalStateException(result.message ?: "Queued message dispatch failed."),
                )
            }
            QueuedOutboundDispatchResult.NotDispatched -> Unit
        }
    }

    private fun newClientMsgId(): String = UUID.randomUUID().toString()
}
