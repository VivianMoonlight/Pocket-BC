package dev.vivian.mobilebc.domain.sync

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxDrainCoordinatorTest {
    @Test
    fun drainSendsPendingMessagesForRequestedOwnerOnly() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(OWNER_A, "a-pending-1"),
                message(OWNER_B, "b-pending-1"),
            ),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store) { sent += it.clientMsgId }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(listOf("a-pending-1"), sent)
        assertEquals(listOf("a-pending-1"), result.submittedClientMsgIds)
        assertEquals(listOf(OWNER_A to "a-pending-1"), store.submitted)
        assertFalse(result.shouldRetry)
    }

    @Test
    fun beepSubmissionIsTerminalConfirmation() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(
                    ownerMemberNumber = OWNER_A,
                    clientMsgId = "beep-1",
                    kind = BcMessageKind.Beep,
                    transport = BcMessageTransport.AccountBeep,
                ),
            ),
        )
        val coordinator = coordinator(store) { }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(emptyList<String>(), result.submittedClientMsgIds)
        assertEquals(listOf("beep-1"), result.confirmedClientMsgIds)
        assertEquals(listOf(OWNER_A to "beep-1"), store.confirmed)
        assertFalse(result.shouldRetry)
    }

    @Test
    fun whisperSubmissionIsTerminalConfirmationBecauseNativeBcUsesLocalEcho() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(
                    ownerMemberNumber = OWNER_A,
                    clientMsgId = "whisper-1",
                    kind = BcMessageKind.Whisper,
                    transport = BcMessageTransport.RoomWhisper,
                ),
            ),
        )
        val coordinator = coordinator(store) { }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(emptyList<String>(), result.submittedClientMsgIds)
        assertEquals(listOf("whisper-1"), result.confirmedClientMsgIds)
        assertEquals(listOf(OWNER_A to "whisper-1"), store.confirmed)
    }

    @Test
    fun whisperConfirmationReceivesEmittedTextWithoutChangingTheDurableSource() = runTest {
        val source = message(
            ownerMemberNumber = OWNER_A,
            clientMsgId = "whisper-1",
            kind = BcMessageKind.Whisper,
            transport = BcMessageTransport.RoomWhisper,
        )
        val receipt = BcOutboundDispatchReceipt(
            content = "h-hello",
            dictionaryJson = "[{\"Tag\":\"Effects\",\"Effects\":[\"stutter\"]}]",
        )
        val store = FakeOutboxDrainStore(pending = mutableListOf(source))
        val receivedSources = mutableListOf<String>()
        val coordinator = coordinator(store, prepare = {
            receivedSources += it.text
            receipt
        }) { error("The prepared dispatch path must be used.") }

        val result = coordinator.drain(OWNER_A)

        assertEquals(listOf("hello"), receivedSources)
        assertEquals("hello", source.command.text)
        assertEquals(listOf("whisper-1"), result.confirmedClientMsgIds)
        assertEquals(listOf(Triple(OWNER_A, "whisper-1", receipt)), store.confirmedReceipts)
        assertTrue(store.submittedReceipts.isEmpty())
    }

    @Test
    fun chatSubmissionReceivesEmittedTextWhileWaitingForServerEcho() = runTest {
        val receipt = BcOutboundDispatchReceipt(content = "h-hello", dictionaryJson = "[]")
        val store = FakeOutboxDrainStore(pending = mutableListOf(message(OWNER_A, "chat-1")))
        val coordinator = coordinator(store, prepare = { receipt }) {
            error("The prepared dispatch path must be used.")
        }

        val result = coordinator.drain(OWNER_A)

        assertEquals(listOf("chat-1"), result.submittedClientMsgIds)
        assertEquals(listOf(Triple(OWNER_A, "chat-1", receipt)), store.submittedReceipts)
        assertTrue(store.confirmedReceipts.isEmpty())
    }

    @Test
    fun ownerRuleRejectionIsTerminalOnTheFirstAttemptAndPreservesItsReason() = runTest {
        val source = message(OWNER_A, "blocked-1")
        val rejection = BcRoomChatRejection(BcRoomChatRejectionReason.ForbiddenWord, forbiddenWord = "HELLO")
        val store = FakeOutboxDrainStore(pending = mutableListOf(source))
        var dispatchCount = 0
        val coordinator = coordinator(store, prepare = {
            dispatchCount += 1
            throw OutboxDispatchRejectedException(rejection)
        }) { error("The prepared dispatch path must be used.") }

        val result = coordinator.drain(OWNER_A)
        val nextDrain = coordinator.drain(OWNER_A)

        assertEquals(1, dispatchCount)
        assertEquals(listOf("blocked-1"), result.terminalFailedClientMsgIds)
        assertEquals(mapOf("blocked-1" to rejection), result.failedRoomChatRejections)
        assertEquals(listOf(OWNER_A to "blocked-1"), store.rejected)
        assertTrue(store.retryableFailed.isEmpty())
        assertTrue(store.submittedReceipts.isEmpty())
        assertTrue(store.confirmedReceipts.isEmpty())
        assertFalse(result.shouldRetry)
        assertEquals(0, nextDrain.scanned)
        assertEquals("hello", source.command.text)
    }

    @Test
    fun transportRetryPreparesTheOriginalTextAndPublishesOnlyTheSuccessfulReceipt() = runTest {
        val source = message(
            ownerMemberNumber = OWNER_A,
            clientMsgId = "retry-1",
            kind = BcMessageKind.Whisper,
            transport = BcMessageTransport.RoomWhisper,
        )
        val pending = mutableListOf(source)
        val store = object : FakeOutboxDrainStore(pending = pending) {
            override suspend fun markRetryableFailure(
                ownerMemberNumber: Int,
                clientMsgId: String,
                error: String?,
                nextAttemptAtMillis: Long,
                updatedAtMillis: Long,
            ) {
                super.markRetryableFailure(ownerMemberNumber, clientMsgId, error, nextAttemptAtMillis, updatedAtMillis)
                pending += source.copy(attempts = source.attempts + 1)
            }
        }
        val receivedSources = mutableListOf<String>()
        val receipt = BcOutboundDispatchReceipt(content = "h-hello", dictionaryJson = "[]")
        val coordinator = coordinator(store, prepare = {
            receivedSources += it.text
            if (receivedSources.size == 1) error("The transport did not emit the prepared message.")
            receipt
        }) { error("The prepared dispatch path must be used.") }

        val failed = coordinator.drain(OWNER_A)
        assertEquals(listOf("retry-1"), failed.retryableFailedClientMsgIds)
        assertTrue(store.confirmedReceipts.isEmpty())
        assertTrue(store.submittedReceipts.isEmpty())

        val succeeded = coordinator.drain(OWNER_A)

        assertEquals(listOf("hello", "hello"), receivedSources)
        assertEquals("hello", source.command.text)
        assertEquals(listOf("retry-1"), succeeded.confirmedClientMsgIds)
        assertEquals(listOf(Triple(OWNER_A, "retry-1", receipt)), store.confirmedReceipts)
    }

    @Test
    fun transientFailureMarksRetryablePendingForRequestedOwnerOnly() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(OWNER_A, "a-pending-1", attempts = 1),
                message(OWNER_B, "b-pending-1", attempts = 1),
            ),
        )
        val coordinator = coordinator(store) { error("temporary") }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(listOf("a-pending-1"), result.retryableFailedClientMsgIds)
        assertEquals(listOf(OWNER_A to "a-pending-1"), store.retryableFailed)
        assertEquals(110_000L, store.nextAttemptAtMillis[OWNER_A to "a-pending-1"])
        assertTrue(result.shouldRetry)
    }

    @Test
    fun maxAttemptsFailureMarksTerminalForRequestedOwnerOnly() = runTest {
        val store = FakeOutboxDrainStore(pending = mutableListOf(message(OWNER_A, "pending-1", attempts = 4)))
        val coordinator = coordinator(store) { error("permanent") }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(listOf("pending-1"), result.terminalFailedClientMsgIds)
        assertEquals(listOf(OWNER_A to "pending-1"), store.terminalFailed)
        assertFalse(result.shouldRetry)
    }

    @Test
    fun staleSendingMessagesAreReturnedToRetryablePendingForRequestedOwnerOnly() = runTest {
        val store = FakeOutboxDrainStore(
            sending = mutableListOf(
                message(OWNER_A, "a-sending-1"),
                message(OWNER_B, "b-sending-1"),
            ),
            sendingUpdatedAtMillis = mutableMapOf(
                (OWNER_A to "a-sending-1") to 1L,
                (OWNER_B to "b-sending-1") to 1L,
            ),
        )
        val coordinator = coordinator(store) { }

        val result = coordinator.drain(ownerMemberNumber = OWNER_A)

        assertEquals(0, result.scanned)
        assertEquals(listOf(OWNER_A to "a-sending-1"), store.retryableFailed)
        assertEquals(100_000L, store.nextAttemptAtMillis[OWNER_A to "a-sending-1"])
    }

    @Test
    fun clientMsgIdDrainOnlySendsRequestedMessageForRequestedOwner() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(OWNER_A, "shared-client-id"),
                message(OWNER_B, "shared-client-id"),
                message(OWNER_A, "a-pending-2"),
            ),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store) { sent += "${it.createdAtMillis}:${it.clientMsgId}" }

        val result = coordinator.drain(ownerMemberNumber = OWNER_B, clientMsgId = "shared-client-id")

        assertEquals(listOf("2:shared-client-id"), sent)
        assertEquals(1, result.scanned)
        assertEquals(listOf("shared-client-id"), result.submittedClientMsgIds)
        assertEquals(listOf(OWNER_B to "shared-client-id"), store.submitted)
    }

    @Test
    fun drainFailsFastIfStoreReturnsMessageForWrongOwner() = runTest {
        val store = object : FakeOutboxDrainStore(
            pending = mutableListOf(message(OWNER_B, "wrong-owner")),
            ignoreOwnerFilter = true,
        ) {}
        val coordinator = coordinator(store) { }

        val failure = runCatching { coordinator.drain(ownerMemberNumber = OWNER_A) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun cancellationDoesNotCountAsFailureOrSendTheNextMessage() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(message(OWNER_A, "first"), message(OWNER_A, "second")),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store) {
            sent += it.clientMsgId
            throw CancellationException("session replaced")
        }

        val failure = runCatching { coordinator.drain(OWNER_A) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("first"), sent)
        assertTrue(store.retryableFailed.isEmpty())
        assertTrue(store.terminalFailed.isEmpty())
        assertTrue(store.submitted.isEmpty())
    }

    @Test
    fun closingSessionCancelsInFlightDrainAndRejectsLaterDrains() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(message(OWNER_A, "first"), message(OWNER_A, "second")),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store) {
            sent += it.clientMsgId
            awaitCancellation()
        }
        val drain = launch { coordinator.drain(OWNER_A) }
        runCurrent()

        coordinator.close()
        runCurrent()
        val failure = runCatching { coordinator.drain(OWNER_A) }.exceptionOrNull()

        assertTrue(drain.isCancelled)
        assertTrue(failure is CancellationException)
        assertEquals(listOf("first"), sent)
        assertTrue(store.retryableFailed.isEmpty())
    }

    @Test
    fun unavailableTargetIsDeferredWithoutBlockingAnotherTransport() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(
                message(OWNER_A, "old-room"),
                message(OWNER_A, "beep", kind = BcMessageKind.Beep, transport = BcMessageTransport.AccountBeep),
            ),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(
            store,
            canDispatch = { it.transport == BcMessageTransport.AccountBeep },
        ) { sent += it.clientMsgId }

        val result = coordinator.drain(OWNER_A)

        assertEquals(listOf("beep"), sent)
        assertEquals(listOf("old-room"), result.deferredClientMsgIds)
        assertTrue(result.shouldRetry)
        assertTrue(store.retryableFailed.isEmpty())
        assertEquals(listOf("old-room"), store.loadPending(OWNER_A, 100_000L, 50).map { it.clientMsgId })
    }

    @Test
    fun fullBatchRequestsAnotherDrainForRemainingMessages() = runTest {
        val store = FakeOutboxDrainStore(
            pending = mutableListOf(message(OWNER_A, "first"), message(OWNER_A, "second"), message(OWNER_A, "third")),
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store) { sent += it.clientMsgId }

        val first = coordinator.drain(OWNER_A, limit = 2)
        val second = coordinator.drain(OWNER_A, limit = 2)

        assertTrue(first.shouldRetry)
        assertFalse(second.shouldRetry)
        assertEquals(listOf("first", "second", "third"), sent)
    }

    @Test
    fun deferredFirstBatchDoesNotHideDispatchableMessagesFurtherInTheQueue() = runTest {
        val store = FakeOutboxDrainStore(
            pending = (1..50).map { message(OWNER_A, "old-room-$it") }.toMutableList().apply {
                add(message(OWNER_A, "beep", kind = BcMessageKind.Beep, transport = BcMessageTransport.AccountBeep))
            },
        )
        val sent = mutableListOf<String>()
        val coordinator = coordinator(store, canDispatch = { it.transport == BcMessageTransport.AccountBeep }) {
            sent += it.clientMsgId
        }

        val result = coordinator.drain(OWNER_A)

        assertEquals(listOf("beep"), sent)
        assertEquals(51, result.scanned)
        assertEquals(50, result.deferredClientMsgIds.size)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun messageConfirmedDuringLoadIsNotSentAgainWhenAtomicClaimFails() = runTest {
        val store = object : FakeOutboxDrainStore(
            pending = mutableListOf(message(OWNER_A, "already-confirmed")),
        ) {
            override suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean = false
        }
        val sent = mutableListOf<String>()

        val result = coordinator(store) { sent += it.clientMsgId }.drain(OWNER_A)

        assertTrue(sent.isEmpty())
        assertEquals(0, result.submitted)
        assertEquals(0, result.failed)
    }

    @Test
    fun outstandingServerEchoKeepsTheWorkerScheduledEvenWithNoDispatchableMessages() = runTest {
        val store = object : FakeOutboxDrainStore() {
            override suspend fun hasPendingConfirmation(ownerMemberNumber: Int): Boolean = true
        }

        val result = coordinator(store) { error("No pending message may be sent.") }.drain(OWNER_A)

        assertEquals(0, result.scanned)
        assertTrue(result.awaitingConfirmation)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun roomChangedWhileQueuedRestoresPendingWithoutTreatingItAsAFailedAttempt() = runTest {
        val store = FakeOutboxDrainStore(pending = mutableListOf(message(OWNER_A, "queued", attempts = 4)))
        val deferredResult = coordinator(store) {
            throw OutboxDispatchDeferredException("The queued room session changed.")
        }.drain(OWNER_A)

        assertEquals(listOf("queued"), deferredResult.deferredClientMsgIds)
        assertEquals(listOf(OWNER_A to "queued"), store.deferred)
        assertEquals(0, deferredResult.failed)
        assertTrue(deferredResult.shouldRetry)
        assertTrue(store.retryableFailed.isEmpty())
        assertTrue(store.terminalFailed.isEmpty())

        val retried = coordinator(store) { }.drain(OWNER_A)
        assertEquals(listOf("queued"), retried.submittedClientMsgIds)
    }

    private fun coordinator(
        store: FakeOutboxDrainStore,
        canDispatch: (BcOutboundCommand) -> Boolean = { true },
        prepare: (suspend (BcOutboundCommand) -> BcOutboundDispatchReceipt)? = null,
        send: suspend (BcOutboundCommand) -> Unit,
    ): OutboxDrainCoordinator =
        OutboxDrainCoordinator(
            store = store,
            dispatcher = object : OutboxCommandDispatcher {
                override suspend fun send(command: BcOutboundCommand) = send(command)

                override suspend fun sendPrepared(command: BcOutboundCommand): BcOutboundDispatchReceipt =
                    prepare?.invoke(command) ?: super.sendPrepared(command)
            },
            nowMillis = { 100_000L },
            canDispatch = canDispatch,
            policy = OutboxDispatchPolicy(
                maxAttempts = 5,
                staleSendingMillis = 60_000L,
                baseRetryDelayMillis = 5_000L,
                maxRetryDelayMillis = 300_000L,
            ),
        )

    private open class FakeOutboxDrainStore(
        private val pending: MutableList<OutboxDrainMessage> = mutableListOf(),
        private val sending: MutableList<OutboxDrainMessage> = mutableListOf(),
        private val sendingUpdatedAtMillis: MutableMap<Pair<Int, String>, Long> = mutableMapOf(),
        private val ignoreOwnerFilter: Boolean = false,
    ) : OutboxDrainStore {
        val submitted = mutableListOf<Pair<Int, String>>()
        val deferred = mutableListOf<Pair<Int, String>>()
        val confirmed = mutableListOf<Pair<Int, String>>()
        val retryableFailed = mutableListOf<Pair<Int, String>>()
        val terminalFailed = mutableListOf<Pair<Int, String>>()
        val rejected = mutableListOf<Pair<Int, String>>()
        val submittedReceipts = mutableListOf<Triple<Int, String, BcOutboundDispatchReceipt>>()
        val confirmedReceipts = mutableListOf<Triple<Int, String, BcOutboundDispatchReceipt>>()
        val nextAttemptAtMillis = mutableMapOf<Pair<Int, String>, Long>()

        override suspend fun findDispatchable(
            ownerMemberNumber: Int,
            clientMsgId: String,
            nowMillis: Long,
        ): OutboxDrainMessage? =
            pending.firstOrNull {
                (ignoreOwnerFilter || it.ownerMemberNumber == ownerMemberNumber) && it.clientMsgId == clientMsgId
            }

        override suspend fun loadPending(ownerMemberNumber: Int, nowMillis: Long, limit: Int): List<OutboxDrainMessage> =
            pending
                .filter { ignoreOwnerFilter || it.ownerMemberNumber == ownerMemberNumber }
                .take(limit)

        override suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean {
            val item = pending.firstOrNull { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId } ?: return false
            pending.remove(item)
            sending += item
            sendingUpdatedAtMillis[ownerMemberNumber to clientMsgId] = updatedAtMillis
            return true
        }

        override suspend fun markSubmitted(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
            submitted += ownerMemberNumber to clientMsgId
            sending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sendingUpdatedAtMillis.remove(ownerMemberNumber to clientMsgId)
        }

        override suspend fun markSubmitted(
            ownerMemberNumber: Int,
            clientMsgId: String,
            updatedAtMillis: Long,
            receipt: BcOutboundDispatchReceipt,
        ) {
            submittedReceipts += Triple(ownerMemberNumber, clientMsgId, receipt)
            markSubmitted(ownerMemberNumber, clientMsgId, updatedAtMillis)
        }

        override suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
            deferred += ownerMemberNumber to clientMsgId
            val item = sending.firstOrNull { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId } ?: return
            sending.remove(item)
            pending += item
            sendingUpdatedAtMillis.remove(ownerMemberNumber to clientMsgId)
        }

        override suspend fun markConfirmed(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
            confirmed += ownerMemberNumber to clientMsgId
            sending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sendingUpdatedAtMillis.remove(ownerMemberNumber to clientMsgId)
        }

        override suspend fun markConfirmed(
            ownerMemberNumber: Int,
            clientMsgId: String,
            updatedAtMillis: Long,
            receipt: BcOutboundDispatchReceipt,
        ) {
            confirmedReceipts += Triple(ownerMemberNumber, clientMsgId, receipt)
            markConfirmed(ownerMemberNumber, clientMsgId, updatedAtMillis)
        }

        override suspend fun markRejected(
            ownerMemberNumber: Int,
            clientMsgId: String,
            error: String?,
            updatedAtMillis: Long,
        ) {
            rejected += ownerMemberNumber to clientMsgId
            markTerminalFailure(ownerMemberNumber, clientMsgId, error, updatedAtMillis)
        }

        override suspend fun markRetryableFailure(
            ownerMemberNumber: Int,
            clientMsgId: String,
            error: String?,
            nextAttemptAtMillis: Long,
            updatedAtMillis: Long,
        ) {
            retryableFailed += ownerMemberNumber to clientMsgId
            this.nextAttemptAtMillis[ownerMemberNumber to clientMsgId] = nextAttemptAtMillis
            pending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sendingUpdatedAtMillis.remove(ownerMemberNumber to clientMsgId)
        }

        override suspend fun markTerminalFailure(
            ownerMemberNumber: Int,
            clientMsgId: String,
            error: String?,
            updatedAtMillis: Long,
        ) {
            terminalFailed += ownerMemberNumber to clientMsgId
            pending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sending.removeAll { it.ownerMemberNumber == ownerMemberNumber && it.clientMsgId == clientMsgId }
            sendingUpdatedAtMillis.remove(ownerMemberNumber to clientMsgId)
        }

        override suspend fun markStaleSendingRetryable(
            ownerMemberNumber: Int,
            cutoffUpdatedAtMillis: Long,
            error: String,
            nextAttemptAtMillis: Long,
            updatedAtMillis: Long,
        ) {
            sendingUpdatedAtMillis
                .filterKeys { it.first == ownerMemberNumber }
                .filterValues { it <= cutoffUpdatedAtMillis }
                .keys
                .toList()
                .forEach { (_, clientMsgId) ->
                    markRetryableFailure(ownerMemberNumber, clientMsgId, error, nextAttemptAtMillis, updatedAtMillis)
                }
        }
    }

    private companion object {
        private const val OWNER_A = 1001
        private const val OWNER_B = 2002

        fun message(
            ownerMemberNumber: Int,
            clientMsgId: String,
            attempts: Int = 0,
            kind: BcMessageKind = BcMessageKind.Chat,
            transport: BcMessageTransport = BcMessageTransport.RoomChat,
        ): OutboxDrainMessage =
            OutboxDrainMessage(
                ownerMemberNumber = ownerMemberNumber,
                clientMsgId = clientMsgId,
                command = BcOutboundCommand(
                    clientMsgId = clientMsgId,
                    conversationId = BcConversationId("room:test"),
                    kind = kind,
                    transport = transport,
                    text = "hello",
                    targetMemberNumber = null,
                    createdAtMillis = ownerMemberNumber.toLong() / 1000L,
                ),
                attempts = attempts,
            )
    }
}
