package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessage
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore

internal class StorageBackedBcOutboundMessageStore(
    private val outboxRepository: RoomOutboxRepository,
) : BcOutboundMessageStore {
    override suspend fun enqueue(message: BcOutboundMessage, localMessage: BcMessage) {
        require(localMessage.id.value == message.clientMsgId) {
            "Optimistic message ID must equal clientMsgId: message=${localMessage.id.value}, client=${message.clientMsgId}"
        }
        require(localMessage.conversationId == message.conversationId) {
            "Optimistic message conversation mismatch: message=${localMessage.conversationId.value}, outbox=${message.conversationId.value}"
        }
        require(localMessage.replyToId == message.replyToId) { "Optimistic message reply mismatch." }
        require(message.replyToId == null || localMessage.raw?.roomName == message.replyRoomName) {
            "Optimistic message reply room mismatch."
        }
        if (message.transport == BcMessageTransport.RoomWhisper) {
            require(!message.originRoomName.isNullOrBlank() && localMessage.raw?.roomName == message.originRoomName) {
                "Optimistic whisper origin room is missing or does not match its outbound command."
            }
        }
        outboxRepository.enqueue(message.toPersistedOutboxMessage(), localMessage)
    }

    private fun BcOutboundMessage.toPersistedOutboxMessage(): PersistedOutboxMessage =
        PersistedOutboxMessage(
            clientMsgId = clientMsgId,
            ownerMemberNumber = ownerMemberNumber,
            conversationId = conversationId,
            transport = transport.name,
            payloadJson = toOutboundCommand().toOutboxPayloadJson(),
            state = OutboxMessageState.Pending,
            attempts = 0,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = createdAtMillis,
        )

    private fun BcOutboundMessage.toOutboundCommand(): BcOutboundCommand =
        BcOutboundCommand(
            clientMsgId = clientMsgId,
            conversationId = conversationId,
            kind = kind,
            transport = transport,
            text = text,
            targetMemberNumber = targetMemberNumber,
            createdAtMillis = createdAtMillis,
            replyToId = replyToId,
            replyRoomName = replyRoomName,
            originRoomName = originRoomName,
        )
}
