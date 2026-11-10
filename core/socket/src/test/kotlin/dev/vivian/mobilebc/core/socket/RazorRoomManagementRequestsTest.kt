package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.room.RoomManagementResponse
import dev.vivian.mobilebc.core.socket.RazorRoomManagementRequests.Kind
import dev.vivian.mobilebc.core.socket.RazorRoomManagementRequests.Result
import dev.vivian.mobilebc.core.socket.RazorRoomManagementRequests.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRoomManagementRequestsTest {
    @Test
    fun acknowledgementWaitsForMatchingAuthoritativeCreate(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }

        assertTrue(requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated")))
        assertFalse(pending.isCompleted)
        assertTrue(requests.isOperationActive)
        assertFalse(requests.confirm(scope, "Other room", editedFieldsMatched = true))
        assertFalse(requests.confirm(scope, "Created", editedFieldsMatched = false))
        assertFalse(pending.isCompleted)

        assertTrue(requests.confirm(scope, "Created", editedFieldsMatched = true))
        assertEquals(Result.Authoritative(acknowledged = true), pending.await())
        assertFalse(requests.isOperationActive)
        requests.ensureRoomTransitionAvailable()
    }

    @Test
    fun updateRenameAcceptsAckInNewRoomButRequiresTheOriginalAuthorityScope(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope(roomName = "Old")
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Update, "Renamed") { it.markEmitting() }
        }

        assertTrue(requests.handleResponse(scope.copy(roomName = "Renamed"), Kind.Update, valid("Updated")))
        assertFalse(requests.confirm(scope.copy(roomName = "Renamed"), "Renamed", true))
        assertFalse(requests.confirm(scope, "Renamed", false))
        assertTrue(requests.confirm(scope, "Renamed", true))
        assertEquals(Result.Authoritative(acknowledged = true), pending.await())
    }

    @Test
    fun syncFirstReleasesBusyButRetainsTheOldAckOwner(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }

        assertTrue(requests.confirm(scope, "Created", true))
        assertEquals(Result.Authoritative(acknowledged = false), first.await())
        assertFalse(requests.isOperationActive)
        requests.ensureRoomTransitionAvailable()
        assertTrue(failure {
            requests.await(scope, Kind.Create, "Second") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)

        assertTrue(requests.handleResponse(scope, Kind.Create, valid("Late rejection")))
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Second") { it.markEmitting() }
        }
        assertFalse(second.isCompleted)
        requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated"))
        requests.confirm(scope, "Second", true)
        assertEquals(Result.Authoritative(acknowledged = true), second.await())
    }

    @Test
    fun retiredCreateAckCannotCompleteAnActiveUpdate(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val creating = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }
        requests.confirm(scope, "Created", true)
        creating.await()
        val updating = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope.copy(roomName = "Created"), Kind.Update, "Renamed") { it.markEmitting() }
        }

        assertTrue(requests.handleResponse(scope.copy(roomName = "Created"), Kind.Create, valid("ChatRoomCreated")))
        assertFalse(updating.isCompleted)
        assertTrue(requests.isOperationActive)
        requests.handleResponse(scope, Kind.Update, valid("Updated"))
        requests.confirm(scope.copy(roomName = "Created"), "Renamed", true)
        assertEquals(Result.Authoritative(acknowledged = true), updating.await())
    }

    @Test
    fun rawUnknownRejectionIsReturnedAndTheResponseChannelCanBeReused(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }

        requests.handleResponse(scope, Kind.Create, valid("FutureServerRejection"))
        assertEquals(Result.Rejected("FutureServerRejection"), pending.await())
        assertFalse(requests.confirm(scope, "Created", true))

        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        }
        requests.handleResponse(scope, Kind.Create, valid("Rejected again"))
        assertEquals(Result.Rejected("Rejected again"), next.await())
    }

    @Test
    fun nonStringResponsePreservesRawJsonAndDoesNotBecomeAnAck(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Update, "Updated room") { it.markEmitting() }
        }
        val malformed = "{\"Unexpected\":[true,null]}"

        requests.handleResponse(scope, Kind.Update, RoomManagementResponse.Invalid(malformed))

        assertEquals(Result.InvalidResponse(malformed), pending.await())
        assertFalse(requests.isOperationActive)
    }

    @Test
    fun invalidResponseKeepsUnknownOutcomeAfterALateAckUntilReset(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }

        requests.handleResponse(scope, Kind.Create, RoomManagementResponse.Invalid("null"))
        assertEquals(Result.InvalidResponse("null"), pending.await())
        assertTrue(requests.hasUnknownOutcome)
        assertTrue(requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated")))
        assertTrue(failure {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.ChannelQuarantinedException)

        requests.reset()
        assertFalse(requests.hasUnknownOutcome)
        val nextScope = scope.copy(connectionGeneration = scope.connectionGeneration + 1)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(nextScope, Kind.Create, "Next") { it.markEmitting() }
        }
        requests.handleResponse(nextScope, Kind.Create, valid("ChatRoomCreated"))
        requests.confirm(nextScope, "Next", true)
        assertEquals(Result.Authoritative(acknowledged = true), next.await())
    }

    @Test
    fun successTokensAreSpecificToTheirResponseChannel(): Unit = runBlocking {
        for ((kind, foreignToken) in listOf(Kind.Create to "Updated", Kind.Update to "ChatRoomCreated")) {
            val requests = RazorRoomManagementRequests()
            val scope = scope()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                requests.await(scope, kind, "Room") { it.markEmitting() }
            }
            requests.handleResponse(scope, kind, valid(foreignToken))
            assertEquals(Result.Rejected(foreignToken), pending.await())
        }
    }

    @Test
    fun ackOnlyTimesOutWithoutAuthoritativeSuccessAndKeepsTheChannelQuarantined(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests(requestTimeoutMs = 30L)
        val scope = scope()
        val result = requests.await(scope, Kind.Create, "Created") { ticket ->
            ticket.markEmitting()
            requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated"))
        }

        assertEquals(Result.TimedOut(emitted = true, acknowledged = true), result)
        assertFalse(requests.isOperationActive)
        assertTrue(requests.hasUnknownOutcome)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.ChannelQuarantinedException)
        assertTrue(failure {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)
    }

    @Test
    fun timeoutBeforeEmissionStopsAQueuedTicketAndAllowsAnotherRequest(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests(requestTimeoutMs = 30L)
        val scope = scope()
        lateinit var queuedTicket: RazorRoomManagementRequests.RequestTicket
        val result = requests.await(scope, Kind.Create, "Created") { ticket ->
            queuedTicket = ticket
            awaitCancellation()
        }

        assertEquals(Result.TimedOut(emitted = false, acknowledged = false), result)
        assertFalse(requests.hasUnknownOutcome)
        requests.ensureRoomTransitionAvailable()
        assertTrue(failure { queuedTicket.markEmitting() } is CancellationException)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        }
        requests.handleResponse(scope, Kind.Create, valid("Next rejected"))
        assertEquals(Result.Rejected("Next rejected"), next.await())
    }

    @Test
    fun cancellationBeforeEmissionRejectsTheTicketEvenBeforeTheWaiterFinallyRuns(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        lateinit var queuedTicket: RazorRoomManagementRequests.RequestTicket
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { ticket ->
                queuedTicket = ticket
                awaitCancellation()
            }
        }

        pending.cancel()
        assertTrue(failure { queuedTicket.markEmitting() } is CancellationException)
        pending.join()
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        }
        requests.handleResponse(scope, Kind.Create, valid("Rejected"))
        assertEquals(Result.Rejected("Rejected"), next.await())
    }

    @Test
    fun cancellationAfterEmissionRequiresResetEvenWhenTheOldAckArrives(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Update, "Updated room") { it.markEmitting() }
        }
        pending.cancelAndJoin()

        assertTrue(requests.handleResponse(scope, Kind.Update, valid("Updated")))
        assertFalse(requests.confirm(scope, "Updated room", true))
        assertTrue(requests.hasUnknownOutcome)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.ChannelQuarantinedException)
        assertTrue(failure {
            requests.await(scope, Kind.Update, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)

        requests.reset()
        assertFalse(requests.hasUnknownOutcome)
        requests.ensureRoomTransitionAvailable()
        val nextScope = scope.copy(loginSessionId = "new login")
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(nextScope, Kind.Update, "Next") { it.markEmitting() }
        }
        requests.handleResponse(nextScope, Kind.Update, valid("Updated"))
        requests.confirm(nextScope, "Next", true)
        assertEquals(Result.Authoritative(acknowledged = true), next.await())
    }

    @Test
    fun failedSocketEmissionKeepsUnknownOutcomeWhileARejectedLiveGuardDoesNot(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val liveGuardFailure = failure {
            requests.await(scope, Kind.Create, "Created") { error("No longer administrator") }
        }
        assertEquals("No longer administrator", liveGuardFailure.message)
        val socketFailure = failure {
            requests.await(scope, Kind.Create, "Created") {
                it.markEmitting()
                error("socket emit failed")
            }
        }
        assertEquals("socket emit failed", socketFailure.message)
        assertTrue(failure {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)
    }

    @Test
    fun createAndUpdateAreGloballyExclusiveAndBlockRoomTransitionsWhileActive(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }
        assertEquals("Created", requests.pendingTicket?.expectedRoomName)
        assertEquals(scope, requests.pendingTicket?.scope)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.BusyException)
        for (kind in Kind.entries) {
            assertTrue(failure {
                requests.await(scope, kind, "Second") { it.markEmitting() }
            } is RazorRoomManagementRequests.BusyException)
        }
        requests.handleResponse(scope, Kind.Create, valid("Rejected"))
        first.await()
        requests.ensureRoomTransitionAvailable()
    }

    @Test
    fun acknowledgementsAndAuthorityFromAnotherLoginCannotCompleteTheWaiter(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }
        val wrongScopes = listOf(
            scope.copy(connectionGeneration = scope.connectionGeneration + 1),
            scope.copy(accountMemberNumber = scope.accountMemberNumber + 1),
            scope.copy(loginSessionId = "another login"),
        )
        for (wrongScope in wrongScopes) {
            assertFalse(requests.handleResponse(wrongScope, Kind.Create, valid("ChatRoomCreated")))
            assertFalse(requests.confirm(wrongScope, "Created", true))
        }
        assertFalse(requests.confirm(scope.copy(roomPresenceEpoch = scope.roomPresenceEpoch + 1), "Created", true))
        assertFalse(pending.isCompleted)
        requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated"))
        requests.confirm(scope, "Created", true)
        assertEquals(Result.Authoritative(acknowledged = true), pending.await())
    }

    @Test
    fun aWrongLoginAckCannotReleaseTheRetiredChannel(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Update, "Room") { it.markEmitting() }
        }
        requests.confirm(scope, "Room", true)
        pending.await()

        assertFalse(requests.handleResponse(scope.copy(loginSessionId = "other"), Kind.Update, valid("Updated")))
        assertTrue(failure {
            requests.await(scope, Kind.Update, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)
        assertTrue(requests.handleResponse(scope, Kind.Update, RoomManagementResponse.Invalid("null")))
    }

    @Test
    fun incomingDataBeforeActualEmissionCannotCompleteAQueuedOperation(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val canEmit = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") {
                canEmit.await()
                it.markEmitting()
            }
        }
        assertFalse(requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated")))
        assertFalse(requests.confirm(scope, "Created", true))
        assertFalse(pending.isCompleted)
        canEmit.complete(Unit)
        pending.cancelAndJoin()
        assertFalse(requests.isOperationActive)
    }

    @Test
    fun resetCannotLetAnOldFinallyReleaseANewOwner(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val oldScope = scope()
        lateinit var oldTicket: RazorRoomManagementRequests.RequestTicket
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                requests.await(oldScope, Kind.Create, "Old room") {
                    oldTicket = it
                    it.markEmitting()
                }
            }
        }
        requests.reset(IllegalStateException("connection changed"))
        val newScope = oldScope.copy(connectionGeneration = oldScope.connectionGeneration + 1)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(newScope, Kind.Create, "New room") { it.markEmitting() }
        }

        assertEquals("connection changed", old.await().exceptionOrNull()?.message)
        assertTrue(requests.isOperationActive)
        assertEquals(newScope, requests.pendingTicket?.scope)
        assertTrue(failure { oldTicket.markEmitting() } is CancellationException)
        assertFalse(requests.handleResponse(oldScope, Kind.Create, valid("ChatRoomCreated")))
        requests.handleResponse(newScope, Kind.Create, valid("ChatRoomCreated"))
        requests.confirm(newScope, "New room", true)
        assertEquals(Result.Authoritative(acknowledged = true), next.await())
    }

    @Test
    fun roomLossAfterEmissionAbortsTheWaiterAndQuarantinesItsChannel(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope(roomName = "Current")
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                requests.await(scope, Kind.Update, "Renamed") { it.markEmitting() }
            }
        }

        requests.abortActive(IllegalStateException("Left room while updating"))

        assertEquals("Left room while updating", pending.await().exceptionOrNull()?.message)
        assertFalse(requests.isOperationActive)
        assertTrue(requests.hasUnknownOutcome)
        assertTrue(requests.handleResponse(scope.copy(roomName = null), Kind.Update, valid("Updated")))
        assertTrue(failure {
            requests.await(scope, Kind.Update, "Next") { it.markEmitting() }
        } is RazorRoomManagementRequests.ChannelQuarantinedException)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.ChannelQuarantinedException)
    }

    @Test
    fun roomLossBeforeEmissionAllowsAnotherRequestButRejectsTheOldQueuedTicket(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        lateinit var oldTicket: RazorRoomManagementRequests.RequestTicket
        val canEmit = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                requests.await(scope, Kind.Create, "Created") {
                    oldTicket = it
                    canEmit.await()
                    it.markEmitting()
                }
            }
        }
        requests.abortActive(IllegalStateException("Room transition changed"))
        assertTrue(failure { oldTicket.markEmitting() } is CancellationException)
        assertFalse(requests.hasUnknownOutcome)
        requests.ensureRoomTransitionAvailable()

        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Next") { it.markEmitting() }
        }
        canEmit.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertTrue(requests.isOperationActive)
        requests.handleResponse(scope, Kind.Create, valid("Rejected"))
        assertEquals(Result.Rejected("Rejected"), next.await())
    }

    @Test
    fun abortingAnotherChannelDoesNotClearAnAlreadyConfirmedRetiredOwner(): Unit = runBlocking {
        val requests = RazorRoomManagementRequests()
        val scope = scope()
        val creating = async(start = CoroutineStart.UNDISPATCHED) {
            requests.await(scope, Kind.Create, "Created") { it.markEmitting() }
        }
        requests.confirm(scope, "Created", true)
        creating.await()
        val updating = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.await(scope, Kind.Update, "Renamed") { it.markEmitting() } }
        }

        requests.abortActive(IllegalStateException("Kicked from room"))
        assertEquals("Kicked from room", updating.await().exceptionOrNull()?.message)
        assertTrue(requests.handleResponse(scope, Kind.Create, valid("ChatRoomCreated")))
        assertTrue(requests.handleResponse(scope, Kind.Update, valid("Updated")))
        assertTrue(requests.hasUnknownOutcome)
        assertTrue(failure { requests.ensureRoomTransitionAvailable() } is RazorRoomManagementRequests.ChannelQuarantinedException)
        requests.reset()
        assertFalse(requests.hasUnknownOutcome)
    }

    private fun scope(roomName: String? = null): Scope = Scope(
        connectionGeneration = 7L,
        accountMemberNumber = 123,
        loginSessionId = "login session",
        roomPresenceEpoch = 9L,
        roomName = roomName,
    )

    private fun valid(token: String): RoomManagementResponse = RoomManagementResponse.Valid(token)

    private suspend fun failure(action: suspend () -> Unit): Throwable =
        runCatching { action() }.exceptionOrNull() ?: error("Expected request failure")
}
