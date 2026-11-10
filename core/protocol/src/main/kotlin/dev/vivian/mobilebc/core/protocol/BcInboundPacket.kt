package dev.vivian.mobilebc.core.protocol

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcRawAccountBeep
import dev.vivian.mobilebc.core.model.BcRawChatMessage

sealed interface BcInboundPacket {
    val packetId: String
    val eventName: String
    val conversationId: BcConversationId?
    val receivedAtMillis: Long
    val rawJson: String

    data class ChatMessage(
        override val packetId: String,
        override val conversationId: BcConversationId?,
        override val receivedAtMillis: Long,
        override val rawJson: String,
        val message: BcRawChatMessage,
    ) : BcInboundPacket {
        override val eventName: String = "chat.message.created"
    }

    data class AccountBeep(
        override val packetId: String,
        override val conversationId: BcConversationId?,
        override val receivedAtMillis: Long,
        override val rawJson: String,
        val beep: BcRawAccountBeep,
    ) : BcInboundPacket {
        override val eventName: String = "chat.direct_message.created"
    }
}
