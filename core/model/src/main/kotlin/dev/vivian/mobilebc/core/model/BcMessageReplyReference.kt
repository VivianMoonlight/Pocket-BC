package dev.vivian.mobilebc.core.model

data class BcMessageReplyReference(
    val messageId: String,
    val conversationId: BcConversationId,
    val transport: BcMessageTransport,
    val roomName: String,
)
