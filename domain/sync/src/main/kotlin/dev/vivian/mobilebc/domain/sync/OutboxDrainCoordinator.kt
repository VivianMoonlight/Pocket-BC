package dev.vivian.mobilebc.domain.sync

import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

data class OutboxDispatchPolicy(
    val maxAttempts: Int = 5,
    val staleSendingMillis: Long = 60_000L,
    val baseRetryDelayMillis: Long = 5_000L,
    val maxRetryDelayMillis: Long = 5 * 60_000L,
)

class OutboxDrainCoordinator(
    private val store: OutboxDrainStore,
    private val dispatcher: OutboxCommandDispatcher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val policy: OutboxDispatchPolicy = OutboxDispatchPolicy(),
    private val canDispatch: (BcOutboundCommand) -> Boolean = { true },
) {
    private val drainMutex = Mutex()
    private val lifecycleLock = Any()
    private val activeDrains = mutableSetOf<Job>()
    private var closed = false

    fun close() {
        val jobs = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeDrains.toList()
        }
        jobs.forEach { it.cancel() }
    }

    suspend fun drain(
        ownerMemberNumber: Int,
        limit: Int = DEFAULT_LIMIT,
        clientMsgId: String? = null,
    ): OutboxDrainResult =
        coroutineScope {
            require(limit > 0) { "Outbox drain limit must be positive." }
            val drainJob = coroutineContext.job
            synchronized(lifecycleLock) {
                if (closed) throw CancellationException("BC outbox session is closed.")
                activeDrains += drainJob
            }
            try {
                drainMutex.withLock {
                    drainPending(ownerMemberNumber, limit, clientMsgId)
                }
            } finally {
                synchronized(lifecycleLock) { activeDrains -= drainJob }
            }
        }

    private suspend fun drainPending(ownerMemberNumber: Int, limit: Int, clientMsgId: String?): OutboxDrainResult {
        val drainStartedAt = nowMillis()
        store.markStaleSendingRetryable(
            ownerMemberNumber = ownerMemberNumber,
            cutoffUpdatedAtMillis = drainStartedAt - policy.staleSendingMillis,
            error = "Previous send attempt did not complete.",
            nextAttemptAtMillis = drainStartedAt,
            updatedAtMillis = drainStartedAt,
        )
        val submitted = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val retryableFailed = mutableListOf<String>()
        val terminalFailed = mutableListOf<String>()
        val failedReasons = mutableMapOf<String, String?>()
        val failedRoomChatRejections = mutableMapOf<String, BcRoomChatRejection>()
        val deferred = mutableListOf<String>()
        val scanned = mutableSetOf<String>()
        var claimedCount = 0
        var windowSize = limit
        var mayHaveMorePending = false

        while (true) {
            coroutineContext.ensureActive()
            val pending = if (clientMsgId == null) {
                store.loadPending(ownerMemberNumber, drainStartedAt, windowSize)
            } else {
                listOfNotNull(store.findDispatchable(ownerMemberNumber, clientMsgId, drainStartedAt))
            }
            val unseen = pending.filter { it.clientMsgId !in scanned }
            if (unseen.isEmpty()) break
            for (item in unseen) {
                coroutineContext.ensureActive()
                if (claimedCount >= limit) break
                check(item.ownerMemberNumber == ownerMemberNumber) {
                    "Outbox owner mismatch: requested=$ownerMemberNumber, item=${item.ownerMemberNumber}, clientMsgId=${item.clientMsgId}"
                }
                scanned += item.clientMsgId
                if (!canDispatch(item.command)) {
                    deferred += item.clientMsgId
                    continue
                }
                val confirmationPolicy = item.command.confirmationPolicy()
                if (!store.markSending(ownerMemberNumber, item.clientMsgId, nowMillis())) continue
                claimedCount += 1
                runCatching {
                    val receipt = dispatcher.sendPrepared(item.command)
                    coroutineContext.ensureActive()
                    receipt
                }.onSuccess { receipt ->
                    val completedAt = nowMillis()
                    when (confirmationPolicy) {
                        OutboxConfirmationPolicy.AwaitServerEcho -> {
                            submitted += item.clientMsgId
                            store.markSubmitted(ownerMemberNumber, item.clientMsgId, completedAt, receipt)
                        }
                        OutboxConfirmationPolicy.SubmissionIsConfirmation -> {
                            confirmed += item.clientMsgId
                            store.markConfirmed(ownerMemberNumber, item.clientMsgId, completedAt, receipt)
                        }
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    val failureAt = nowMillis()
                    if (throwable is OutboxDispatchDeferredException) {
                        deferred += item.clientMsgId
                        store.markDeferred(ownerMemberNumber, item.clientMsgId, failureAt)
                        return@onFailure
                    }
                    failedReasons[item.clientMsgId] = throwable.message
                    if (throwable is OutboxDispatchRejectedException) {
                        failedRoomChatRejections[item.clientMsgId] = throwable.rejection
                        terminalFailed += item.clientMsgId
                        store.markRejected(ownerMemberNumber, item.clientMsgId, throwable.message, failureAt)
                        return@onFailure
                    }
                    if (item.attempts + 1 >= policy.maxAttempts) {
                        terminalFailed += item.clientMsgId
                        store.markTerminalFailure(ownerMemberNumber, item.clientMsgId, throwable.message, failureAt)
                    } else {
                        retryableFailed += item.clientMsgId
                        store.markRetryableFailure(
                            ownerMemberNumber, item.clientMsgId, throwable.message,
                            failureAt + retryDelayMillis(item.attempts), failureAt,
                        )
                    }
                }
            }
            mayHaveMorePending = clientMsgId == null && claimedCount >= limit
            if (clientMsgId != null || claimedCount >= limit || pending.size < windowSize) break
            // Deferred room messages stay pending. Extend the fixed-time window so they cannot
            // hide a dispatchable beep behind the first batch, and yield between storage reads.
            windowSize += limit
            yield()
        }
        return OutboxDrainResult(
            scanned = scanned.size,
            submittedClientMsgIds = submitted,
            confirmedClientMsgIds = confirmed,
            retryableFailedClientMsgIds = retryableFailed,
            terminalFailedClientMsgIds = terminalFailed,
            failedReasons = failedReasons,
            failedRoomChatRejections = failedRoomChatRejections,
            deferredClientMsgIds = deferred,
            mayHaveMorePending = mayHaveMorePending,
            awaitingConfirmation = store.hasPendingConfirmation(ownerMemberNumber),
        )
    }

    private fun BcOutboundCommand.confirmationPolicy(): OutboxConfirmationPolicy =
        when (transport) {
            BcMessageTransport.AccountBeep,
            BcMessageTransport.RoomWhisper,
            -> OutboxConfirmationPolicy.SubmissionIsConfirmation
            BcMessageTransport.RoomChat -> OutboxConfirmationPolicy.AwaitServerEcho
            BcMessageTransport.LocalSystem,
            BcMessageTransport.ProtocolEvent,
            -> error("Outbox transport $transport cannot be dispatched.")
        }

    private fun retryDelayMillis(attempts: Int): Long {
        val multiplier = 1L shl attempts.coerceAtLeast(0).coerceAtMost(20)
        return (policy.baseRetryDelayMillis * multiplier).coerceAtMost(policy.maxRetryDelayMillis)
    }

    private companion object {
        private const val DEFAULT_LIMIT = 50
    }
}

data class OutboxDrainMessage(
    val ownerMemberNumber: Int,
    val clientMsgId: String,
    val command: BcOutboundCommand,
    val attempts: Int,
)

enum class OutboxConfirmationPolicy {
    AwaitServerEcho,
    SubmissionIsConfirmation,
}

interface OutboxDrainStore {
    suspend fun findDispatchable(ownerMemberNumber: Int, clientMsgId: String, nowMillis: Long): OutboxDrainMessage?
    suspend fun loadPending(ownerMemberNumber: Int, nowMillis: Long, limit: Int = 50): List<OutboxDrainMessage>
    suspend fun hasPendingConfirmation(ownerMemberNumber: Int): Boolean = false
    suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean
    suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long)
    suspend fun markSubmitted(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long)
    suspend fun markConfirmed(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long)
    suspend fun markSubmitted(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt,
    ) = markSubmitted(ownerMemberNumber, clientMsgId, updatedAtMillis)
    suspend fun markConfirmed(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt,
    ) = markConfirmed(ownerMemberNumber, clientMsgId, updatedAtMillis)
    suspend fun markRetryableFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    )
    suspend fun markTerminalFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    )
    suspend fun markRejected(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    ) = markTerminalFailure(ownerMemberNumber, clientMsgId, error, updatedAtMillis)
    suspend fun markStaleSendingRetryable(
        ownerMemberNumber: Int,
        cutoffUpdatedAtMillis: Long,
        error: String,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    )
}

interface OutboxCommandDispatcher {
    suspend fun send(command: BcOutboundCommand)
    suspend fun sendPrepared(command: BcOutboundCommand): BcOutboundDispatchReceipt {
        send(command)
        return BcOutboundDispatchReceipt()
    }
}

data class BcOutboundDispatchReceipt(
    val content: String? = null,
    val dictionaryJson: String? = null,
)

/** The command was not emitted, and can safely remain pending without consuming an attempt. */
class OutboxDispatchDeferredException(message: String) : IllegalStateException(message)

/** A room rule rejected the command before emission; retries cannot make this send valid. */
class OutboxDispatchRejectedException(val rejection: BcRoomChatRejection) :
    IllegalStateException(rejection.reason.name)

data class OutboxDrainResult(
    val scanned: Int,
    val submittedClientMsgIds: List<String>,
    val confirmedClientMsgIds: List<String>,
    val retryableFailedClientMsgIds: List<String>,
    val terminalFailedClientMsgIds: List<String>,
    val failedReasons: Map<String, String?> = emptyMap(),
    val failedRoomChatRejections: Map<String, BcRoomChatRejection> = emptyMap(),
    val deferredClientMsgIds: List<String> = emptyList(),
    val mayHaveMorePending: Boolean = false,
    val awaitingConfirmation: Boolean = false,
) {
    val submitted: Int
        get() = submittedClientMsgIds.size

    val confirmed: Int
        get() = confirmedClientMsgIds.size

    val retryableFailed: Int
        get() = retryableFailedClientMsgIds.size

    val terminalFailed: Int
        get() = terminalFailedClientMsgIds.size

    val failed: Int
        get() = retryableFailed + terminalFailed

    val shouldRetry: Boolean
        get() = retryableFailed > 0 || deferredClientMsgIds.isNotEmpty() || mayHaveMorePending || awaitingConfirmation
}
