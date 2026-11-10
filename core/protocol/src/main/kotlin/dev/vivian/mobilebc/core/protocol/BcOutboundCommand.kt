package dev.vivian.mobilebc.core.protocol

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport

data class BcOutboundCommand(
    val clientMsgId: String,
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
