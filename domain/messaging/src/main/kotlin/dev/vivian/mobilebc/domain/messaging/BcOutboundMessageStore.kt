package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport

data class BcOutboundMessage(
    val clientMsgId: String,
    val ownerMemberNumber: Int,
    val conversationId: BcConversationId,
    val kind: BcMessageKind,
    val transport: BcMessageTransport,
    val text: String,
    val targetMemberNumber: Int?,
    val createdAtMillis: Long,
    val replyToId: String? = null,
    val replyRoomName: String? = null,
    /** Local routing scope; never included in the server message body. */
    val originRoomName: String? = null,
)

interface BcOutboundMessageStore {
    /**
     * Atomically persists the durable outbox command and its optimistic timeline row.
     *
     * Production implementations must not commit only one side of this pair.
     */
    suspend fun enqueue(message: BcOutboundMessage, localMessage: BcMessage)
}
