package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversationId

data class PersistedOutboxMessage(
    val clientMsgId: String,
    val ownerMemberNumber: Int,
    val conversationId: BcConversationId,
    val transport: String,
    val payloadJson: String,
    val state: OutboxMessageState,
    val attempts: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String? = null,
    val nextAttemptAtMillis: Long? = null,
)

enum class OutboxMessageState {
    Pending,
    Sending,
    Submitted,
    Confirmed,
    Failed,
}
