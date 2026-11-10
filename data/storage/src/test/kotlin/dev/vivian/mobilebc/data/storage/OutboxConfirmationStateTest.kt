package dev.vivian.mobilebc.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxConfirmationStateTest {
    @Test
    fun submittedAndConfirmedStatesRoundTrip() {
        assertEquals(
            OutboxMessageState.Submitted,
            entity(state = "Submitted").toPersisted().state,
        )
        assertEquals(
            OutboxMessageState.Confirmed,
            entity(state = "Confirmed").toPersisted().state,
        )
    }

    @Test
    fun unknownPersistedStateFailsClosedInsteadOfBecomingPending() {
        assertEquals(
            OutboxMessageState.Failed,
            entity(state = "FutureState").toPersisted().state,
        )
    }

    private fun entity(state: String): OutboxMessageEntity =
        OutboxMessageEntity(
            ownerMemberNumber = 100,
            clientMsgId = "client-1",
            conversationId = "room:test",
            transport = "RoomChat",
            payloadJson = "{}",
            state = state,
            attempts = 1,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            lastError = null,
            nextAttemptAtMillis = null,
        )
}
