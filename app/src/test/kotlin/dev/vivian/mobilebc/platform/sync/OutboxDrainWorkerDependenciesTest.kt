package dev.vivian.mobilebc.platform.sync

import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.sync.OutboxCommandDispatcher
import dev.vivian.mobilebc.domain.sync.OutboxDrainCoordinator
import dev.vivian.mobilebc.domain.sync.OutboxDrainMessage
import dev.vivian.mobilebc.domain.sync.OutboxDrainStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OutboxDrainWorkerDependenciesTest {
    @Test
    fun replacementDuringOwnerLookupNeverMixesSessionCoordinatorAndOwner() {
        val previous = coordinator()
        val replacement = coordinator()
        try {
            OutboxDrainWorkerDependencies.register(previous) {
                OutboxDrainWorkerDependencies.register(replacement) { 2002 }
                1001
            }

            val captured = requireNotNull(OutboxDrainWorkerDependencies.currentTarget())
            val current = requireNotNull(OutboxDrainWorkerDependencies.currentTarget())

            assertSame(previous, captured.coordinator)
            assertEquals(1001, captured.ownerMemberNumber)
            assertSame(replacement, current.coordinator)
            assertEquals(2002, current.ownerMemberNumber)
            OutboxDrainWorkerDependencies.unregister(previous)
            assertSame(replacement, OutboxDrainWorkerDependencies.currentTarget()?.coordinator)
        } finally {
            OutboxDrainWorkerDependencies.unregister(previous)
            OutboxDrainWorkerDependencies.unregister(replacement)
        }
    }

    @Test
    fun unavailableOwnerDoesNotPublishADrainTarget() {
        val coordinator = coordinator()
        try {
            OutboxDrainWorkerDependencies.register(coordinator) { null }
            assertNull(OutboxDrainWorkerDependencies.currentTarget())
        } finally {
            OutboxDrainWorkerDependencies.unregister(coordinator)
        }
    }

    private fun coordinator() = OutboxDrainCoordinator(
        store = object : OutboxDrainStore {
            override suspend fun findDispatchable(ownerMemberNumber: Int, clientMsgId: String, nowMillis: Long): OutboxDrainMessage? = null
            override suspend fun loadPending(ownerMemberNumber: Int, nowMillis: Long, limit: Int): List<OutboxDrainMessage> = emptyList()
            override suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean = false
            override suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) = Unit
            override suspend fun markSubmitted(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) = Unit
            override suspend fun markConfirmed(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) = Unit
            override suspend fun markRetryableFailure(ownerMemberNumber: Int, clientMsgId: String, error: String?, nextAttemptAtMillis: Long, updatedAtMillis: Long) = Unit
            override suspend fun markTerminalFailure(ownerMemberNumber: Int, clientMsgId: String, error: String?, updatedAtMillis: Long) = Unit
            override suspend fun markStaleSendingRetryable(ownerMemberNumber: Int, cutoffUpdatedAtMillis: Long, error: String, nextAttemptAtMillis: Long, updatedAtMillis: Long) = Unit
        },
        dispatcher = object : OutboxCommandDispatcher {
            override suspend fun send(command: BcOutboundCommand) = Unit
        },
    )
}
