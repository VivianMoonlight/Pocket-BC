package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.domain.sync.OutboxDrainMessage
import dev.vivian.mobilebc.domain.sync.OutboxDrainStore
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt
import kotlinx.coroutines.CancellationException

internal class StorageBackedOutboxDrainStore(
    private val repository: RoomOutboxRepository,
) : OutboxDrainStore {
    override suspend fun findDispatchable(
        ownerMemberNumber: Int,
        clientMsgId: String,
        nowMillis: Long,
    ): OutboxDrainMessage? =
        repository.findDispatchable(ownerMemberNumber, clientMsgId, nowMillis)?.validatedOrFail(nowMillis)

    override suspend fun loadPending(
        ownerMemberNumber: Int,
        nowMillis: Long,
        limit: Int,
    ): List<OutboxDrainMessage> {
        while (true) {
            val pending = repository.loadPending(ownerMemberNumber, nowMillis, limit)
            val valid = pending.mapNotNull { it.validatedOrFail(nowMillis) }
            if (valid.size == pending.size || pending.size < limit) return valid
        }
    }

    override suspend fun hasPendingConfirmation(ownerMemberNumber: Int): Boolean =
        repository.hasPendingConfirmation(ownerMemberNumber)

    override suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean =
        repository.markSending(ownerMemberNumber, clientMsgId, updatedAtMillis)

    override suspend fun markSubmitted(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
        repository.markSubmitted(ownerMemberNumber, clientMsgId, updatedAtMillis)
    }

    override suspend fun markSubmitted(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt,
    ) {
        repository.markSubmitted(ownerMemberNumber, clientMsgId, updatedAtMillis, receipt)
    }

    override suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
        repository.markDeferred(ownerMemberNumber, clientMsgId, updatedAtMillis)
    }

    override suspend fun markConfirmed(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
        repository.markConfirmed(ownerMemberNumber, clientMsgId, updatedAtMillis)
    }

    override suspend fun markConfirmed(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt,
    ) {
        repository.markConfirmed(ownerMemberNumber, clientMsgId, updatedAtMillis, receipt)
    }

    override suspend fun markRetryableFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    ) {
        repository.markRetryableFailure(
            ownerMemberNumber,
            clientMsgId,
            error,
            nextAttemptAtMillis,
            updatedAtMillis,
        )
    }

    override suspend fun markTerminalFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    ) {
        repository.markTerminalFailure(ownerMemberNumber, clientMsgId, error, updatedAtMillis)
    }

    override suspend fun markRejected(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    ) {
        repository.markRejected(ownerMemberNumber, clientMsgId, error, updatedAtMillis)
    }

    override suspend fun markStaleSendingRetryable(
        ownerMemberNumber: Int,
        cutoffUpdatedAtMillis: Long,
        error: String,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    ) {
        repository.markStaleSendingRetryable(
            ownerMemberNumber,
            cutoffUpdatedAtMillis,
            error,
            nextAttemptAtMillis,
            updatedAtMillis,
        )
    }

    private suspend fun PersistedOutboxMessage.validatedOrFail(nowMillis: Long): OutboxDrainMessage? =
        try {
            toValidatedOutboxDrainMessage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            repository.markInvalidPending(
                ownerMemberNumber = ownerMemberNumber,
                clientMsgId = clientMsgId,
                error = "Invalid persisted outbound command: ${exception.message}",
                updatedAtMillis = nowMillis,
            )
            null
        }
}
