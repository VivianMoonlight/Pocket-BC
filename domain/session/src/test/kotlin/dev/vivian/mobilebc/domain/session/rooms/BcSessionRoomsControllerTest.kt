package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BlockedReason
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.core.RoomGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionRoomsControllerTest {
    @Test
    fun roomSearchHidesRoomsCreatedByIgnoredMembers() = runTest {
        val stateHolder = onlineState().apply {
            updateSession {
                it.copy(
                    account = BcAccount(10, "Self", "self"),
                    relationships = it.relationships.nextLoaded(
                        memberNumber = 10,
                        relations = BcSelfRelations(ignored = setOf(2002)),
                    ),
                )
            }
        }
        val visible = roomSummary("Visible", creatorMemberNumber = 3003)
        val hidden = roomSummary("Hidden", creatorMemberNumber = 2002)
        val controller = BcSessionRoomsController(
            stateHolder = stateHolder,
            gateway = object : RoomGateway {
                override suspend fun searchRooms(filter: BcRoomSearchFilter) = listOf(hidden, visible)
                override suspend fun joinRoom(roomName: String, password: String?) = Unit
                override suspend fun leaveRoom() = Unit
            },
            onOperationFailure = { _, _ -> },
            emitRoomTargetChanged = {},
            upsertRoomConversation = {},
            upsertRoomState = {},
            upsertSameRoomDirectConversations = {},
        )

        assertEquals(listOf(visible), controller.searchRooms(BcRoomSearchFilter()))
    }

    @Test
    fun roomEventsCommitAndClearTheWholeRoomSnapshotTogether() = runTest {
        val stateHolder = onlineState()
        val sameRoomMemberSnapshots = mutableListOf<List<BcMember>>()
        val controller = BcSessionRoomsController(
            stateHolder = stateHolder,
            gateway = FakeRoomGateway,
            onOperationFailure = { _, _ -> },
            emitRoomTargetChanged = {},
            upsertRoomConversation = {},
            upsertRoomState = {},
            upsertSameRoomDirectConversations = { sameRoomMemberSnapshots += it.roomMembers },
        )
        val room = room("Atomic Room")
        val members = listOf(BcMember(10, "Self", isSelf = true), BcMember(20, "Bob", isSelf = false))
        val characters = listOf(character(10, "Self"))

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RoomJoined(room, members, characters),
        )

        assertEquals(room, stateHolder.current.currentRoom)
        assertEquals(members, stateHolder.current.roomMembers)
        assertEquals(characters, stateHolder.current.roomCharacters)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)

        assertNull(stateHolder.current.currentRoom)
        assertTrue(stateHolder.current.roomMembers.isEmpty())
        assertTrue(stateHolder.current.roomCharacters.isEmpty())
        assertEquals(listOf(members, emptyList<BcMember>()), sameRoomMemberSnapshots)
        assertEquals(BcConnectionHealth.VerifiedOnline, stateHolder.current.connectionHealth)
        assertTrue(stateHolder.current.isVerifiedOnline)
    }

    @Test
    fun delayedSearchCannotRestoreReadyAfterReconnect() = runTest {
        val state = onlineState()
        val result = CompletableDeferred<List<BcRoomSummary>>()
        val controller = controller(state, TestRoomGateway(search = { result.await() }))
        val request = async { controller.searchRooms(BcRoomSearchFilter()) }
        runCurrent()
        val reconnecting = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1)
        state.updateSession { it.copy(state = reconnecting, connectionHealth = BcConnectionHealth.Offline) }

        result.complete(emptyList())
        request.join()

        assertTrue(request.isCancelled)
        assertEquals(reconnecting, state.current.state)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
    }

    @Test
    fun roomSearchAndJoinUseTheSameOperationFlight() = runTest {
        val state = onlineState()
        val searched = CompletableDeferred<List<BcRoomSummary>>()
        val joined = CompletableDeferred<Unit>()
        val controller = controller(state, TestRoomGateway(search = { searched.await() }, join = { joined.await() }))
        val search = async { controller.searchRooms(BcRoomSearchFilter()) }
        runCurrent()
        val join = async { controller.joinRoom("Next Room", null) }
        runCurrent()

        assertEquals(BcSessionState.SearchingRooms(BcRoomSearchFilter()), state.current.state)
        assertTrue(join.isActive)
        searched.complete(emptyList())
        search.await()
        runCurrent()

        assertEquals(BcSessionState.JoiningRoom("Next Room"), state.current.state)
        joined.complete(Unit)
        join.await()
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun roomSearchRequestsWithTheSameFilterAreSerialized() = runTest {
        val state = onlineState()
        val firstResult = CompletableDeferred<List<BcRoomSummary>>()
        val secondResult = CompletableDeferred<List<BcRoomSummary>>()
        var searchCalls = 0
        val controller = controller(state, TestRoomGateway(search = {
            if (searchCalls++ == 0) firstResult.await() else secondResult.await()
        }))
        val filter = BcRoomSearchFilter()
        val first = async { controller.searchRooms(filter) }
        runCurrent()
        val second = async { controller.searchRooms(filter) }
        runCurrent()

        assertEquals(1, searchCalls)

        firstResult.complete(emptyList())
        first.await()
        runCurrent()

        assertEquals(2, searchCalls)
        assertEquals(BcSessionState.SearchingRooms(filter), state.current.state)
        secondResult.complete(emptyList())
        second.await()
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun cancellingAQueuedSearchPreservesTheActiveSearchState() = runTest {
        val state = onlineState()
        val result = CompletableDeferred<List<BcRoomSummary>>()
        var searchCalls = 0
        var failureCalls = 0
        val controller = controller(
            state,
            TestRoomGateway(search = { searchCalls += 1; result.await() }),
            onOperationFailure = { _, _ -> failureCalls += 1 },
        )
        val filter = BcRoomSearchFilter()
        val active = async { controller.searchRooms(filter) }
        runCurrent()
        val queued = launch { controller.searchRooms(filter) }
        runCurrent()

        queued.cancelAndJoin()

        assertEquals(1, searchCalls)
        assertEquals(0, failureCalls)
        assertEquals(BcSessionState.SearchingRooms(filter), state.current.state)
        result.complete(emptyList())
        active.await()
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun aSearchQueuedByAnOldAccountDoesNotDispatchAfterRelog() = runTest {
        val state = onlineState()
        val result = CompletableDeferred<List<BcRoomSummary>>()
        var searchCalls = 0
        var failureCalls = 0
        val controller = controller(
            state,
            TestRoomGateway(search = { searchCalls += 1; result.await() }),
            onOperationFailure = { _, _ -> failureCalls += 1 },
        )
        val active = async { controller.searchRooms(BcRoomSearchFilter(query = "First")) }
        runCurrent()
        val queued = async { controller.searchRooms(BcRoomSearchFilter(query = "Queued")) }
        runCurrent()
        relog(state)

        result.complete(emptyList())
        active.join()
        queued.join()

        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(1, searchCalls)
        assertEquals(0, failureCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun joinAndLeaveSerializeTheirRoomTargetUpdates() = runTest {
        val state = onlineState()
        val joined = CompletableDeferred<Unit>()
        var leaveCalls = 0
        val targets = mutableListOf<String?>()
        val controller = controller(
            state,
            TestRoomGateway(join = { joined.await() }, leave = { leaveCalls += 1 }),
            emitRoomTargetChanged = { targets += it },
        )
        val join = async { controller.joinRoom("Next Room", null) }
        runCurrent()
        val leave = async { controller.leaveRoom() }
        runCurrent()

        assertEquals(0, leaveCalls)
        joined.complete(Unit)
        join.await()
        leave.await()

        assertEquals(1, leaveCalls)
        assertEquals(listOf("Next Room", null), targets)
        assertNull(state.lastJoinedRoomName)
    }

    @Test
    fun aJoinQueuedByAnOldAccountDoesNotDispatchAfterRelog() = runTest {
        val state = onlineState()
        val joined = CompletableDeferred<Unit>()
        var joinCalls = 0
        val controller = controller(state, TestRoomGateway(join = { joinCalls += 1; joined.await() }))
        val first = async { controller.joinRoom("First Room", null) }
        runCurrent()
        val queued = async { controller.joinRoom("Queued Room", null) }
        runCurrent()
        relog(state)

        joined.complete(Unit)
        first.join()
        queued.join()

        assertTrue(first.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(1, joinCalls)
        assertNull(state.lastJoinedRoomName)
    }

    @Test
    fun cancellingRoomOperationsDoesNotReportTransportFailure() = runTest {
        for (operation in listOf("search", "join", "leave")) {
            val state = onlineState().apply { rememberRoomTarget("Previous Room") }
            val suspended = CompletableDeferred<Unit>()
            var failureCalls = 0
            val targets = mutableListOf<String?>()
            val controller = controller(
                state,
                TestRoomGateway(
                    search = { suspended.await(); emptyList() },
                    join = { suspended.await() },
                    leave = { suspended.await() },
                ),
                onOperationFailure = { _, _ -> failureCalls += 1 },
                emitRoomTargetChanged = { targets += it },
            )
            val request = launch {
                when (operation) {
                    "search" -> controller.searchRooms(BcRoomSearchFilter())
                    "join" -> controller.joinRoom("Next Room", null)
                    "leave" -> controller.leaveRoom()
                }
            }
            runCurrent()

            request.cancelAndJoin()

            assertEquals(operation, 0, failureCalls)
            assertEquals(operation, BcSessionState.Ready, state.current.state)
            assertEquals(operation, "Previous Room", state.lastJoinedRoomName)
            assertTrue(operation, targets.isEmpty())
        }
    }

    @Test
    fun delayedJoinCannotRememberATargetAfterStop() = runTest {
        val state = onlineState()
        val joined = CompletableDeferred<Unit>()
        val targets = mutableListOf<String?>()
        val controller = controller(
            state,
            TestRoomGateway(join = { joined.await() }),
            emitRoomTargetChanged = { targets += it },
        )
        val request = async { controller.joinRoom("Old Target", "secret") }
        runCurrent()
        state.updateSession {
            it.copy(state = BcSessionState.Stopped, desiredOnline = false, connectionHealth = BcConnectionHealth.Offline)
        }

        joined.complete(Unit)
        request.join()

        assertTrue(request.isCancelled)
        assertEquals(BcSessionState.Stopped, state.current.state)
        assertNull(state.lastJoinedRoomName)
        assertNull(state.lastJoinedRoomPassword)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun delayedJoinCannotRememberATargetAfterTheSameAccountRelogs() = runTest {
        val state = onlineState()
        val joined = CompletableDeferred<Unit>()
        val controller = controller(state, TestRoomGateway(join = { joined.await() }))
        val request = async { controller.joinRoom("Old Target", null) }
        runCurrent()
        relog(state)
        state.rememberRoomTarget("New Target")

        joined.complete(Unit)
        request.join()

        assertTrue(request.isCancelled)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals("New Target", state.lastJoinedRoomName)
    }

    @Test
    fun aNewLoginTokenCancelsOldRoomWorkEvenWhenAccountGenerationIsUnchanged() = runTest {
        for (operation in listOf("search", "join", "leave")) {
            val state = onlineState().apply {
                rememberRoomTarget("Old Target")
                updateSession { it.copy(selfRuleLogSessionId = "old-login") }
            }
            val completed = CompletableDeferred<Unit>()
            var failureCalls = 0
            val targets = mutableListOf<String?>()
            val controller = controller(
                state,
                TestRoomGateway(
                    search = { completed.await(); emptyList() },
                    join = { completed.await() },
                    leave = { completed.await() },
                ),
                onOperationFailure = { _, _ -> failureCalls += 1 },
                emitRoomTargetChanged = { targets += it },
            )
            val request = async {
                when (operation) {
                    "search" -> controller.searchRooms(BcRoomSearchFilter())
                    "join" -> controller.joinRoom("Old Room")
                    else -> controller.leaveRoom()
                }
            }
            runCurrent()
            state.updateSession { it.copy(state = BcSessionState.Ready, selfRuleLogSessionId = "new-login") }
            state.rememberRoomTarget("New Target")

            completed.complete(Unit)
            request.join()

            assertTrue(operation, request.isCancelled)
            assertEquals(operation, "New Target", state.lastJoinedRoomName)
            assertEquals(operation, BcSessionState.Ready, state.current.state)
            assertEquals(operation, 0, failureCalls)
            assertTrue(operation, targets.isEmpty())
        }
    }

    @Test
    fun delayedLeaveCannotClearTheNewAccountRoomTarget() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Old Target") }
        val left = CompletableDeferred<Unit>()
        val targets = mutableListOf<String?>()
        val controller = controller(
            state,
            TestRoomGateway(leave = { left.await() }),
            emitRoomTargetChanged = { targets += it },
        )
        val request = async { controller.leaveRoom() }
        runCurrent()
        relog(state, BcAccount(20, "Other", "other"))
        state.rememberRoomTarget("New Target")

        left.complete(Unit)
        request.join()

        assertTrue(request.isCancelled)
        assertEquals("New Target", state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun oldAccountOperationFailureCannotReconnectTheNewAccount() = runTest {
        val state = onlineState()
        val joined = CompletableDeferred<Unit>()
        var failureCalls = 0
        val controller = controller(
            state,
            TestRoomGateway(join = { joined.await() }),
            onOperationFailure = { _, _ -> failureCalls += 1 },
        )
        val request = async { runCatching { controller.joinRoom("Old Target", null) } }
        runCurrent()
        relog(state, BcAccount(20, "Other", "other"))

        joined.completeExceptionally(IllegalStateException("Old transport closed"))

        assertTrue(request.await().exceptionOrNull() is IllegalStateException)
        assertEquals(0, failureCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun aRoomJoinRejectionDoesNotReconnectTheAuthenticatedAccount() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Previous Room") }
        val rejection = BcRoomJoinRejectedException("Room is locked")
        var failureCalls = 0
        val targets = mutableListOf<String?>()
        val controller = controller(
            state,
            TestRoomGateway(join = { throw rejection }),
            onOperationFailure = { _, _ -> failureCalls += 1 },
            emitRoomTargetChanged = { targets += it },
        )

        val failure = runCatching { controller.joinRoom("Locked Room", null) }.exceptionOrNull()

        assertSame(rejection, failure)
        assertEquals(0, failureCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(BcConnectionHealth.VerifiedOnline, state.current.connectionHealth)
        assertEquals("Previous Room", state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun aTransportJoinFailureStartsRecoveryWithoutItsCleanupRestoringReady() = runTest {
        val state = onlineState()
        var failureCalls = 0
        val reconnecting = BcSessionState.Reconnecting(ReconnectReason.OperationFailed, 1)
        val failure = IllegalStateException("Transport closed")
        val controller = controller(
            state,
            TestRoomGateway(join = { throw failure }),
            onOperationFailure = { _, _ ->
                failureCalls += 1
                state.updateSession { it.copy(state = reconnecting, connectionHealth = BcConnectionHealth.Offline) }
            },
        )

        assertSame(failure, runCatching { controller.joinRoom("Next Room", null) }.exceptionOrNull())

        assertEquals(1, failureCalls)
        assertEquals(reconnecting, state.current.state)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertNull(state.lastJoinedRoomName)
    }

    @Test
    fun autoJoinCancellationPropagatesAndClearsOnlyItsJoiningState() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("Saved Room")
            updateSession { it.copy(autoJoinRoomAfterReconnect = true) }
        }
        val joined = CompletableDeferred<Unit>()
        val controller = controller(state, TestRoomGateway(join = { joined.await() }))
        var cancellationPropagated = false
        val request = launch {
            try {
                controller.autoJoinLastRoomAfterReconnect(requireNotNull(state.current.account))
            } catch (cancelled: CancellationException) {
                cancellationPropagated = true
                throw cancelled
            }
        }
        runCurrent()

        request.cancelAndJoin()

        assertTrue(cancellationPropagated)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals("Saved Room", state.lastJoinedRoomName)
    }

    @Test
    fun autoJoinFailureCannotRestoreReadyAfterAnotherReconnectStarts() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("Saved Room")
            updateSession { it.copy(autoJoinRoomAfterReconnect = true) }
        }
        val joined = CompletableDeferred<Unit>()
        val controller = controller(state, TestRoomGateway(join = { joined.await() }))
        val request = launch { controller.autoJoinLastRoomAfterReconnect(requireNotNull(state.current.account)) }
        runCurrent()
        val reconnecting = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 2)
        state.updateSession { it.copy(state = reconnecting, connectionHealth = BcConnectionHealth.Offline) }

        joined.completeExceptionally(IllegalStateException("Saved room unavailable"))
        request.join()

        assertEquals(reconnecting, state.current.state)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
    }

    @Test
    fun autoJoinRejectsARecoveredAccountThatIsNoLongerCurrent() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("Saved Room")
            updateSession { it.copy(autoJoinRoomAfterReconnect = true) }
        }
        val oldAccount = requireNotNull(state.current.account)
        var joinCalls = 0
        val controller = controller(state, TestRoomGateway(join = { joinCalls += 1 }))
        relog(state)

        controller.autoJoinLastRoomAfterReconnect(oldAccount)

        assertEquals(0, joinCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun autoJoinQueuedDuringLeaveDoesNotRejoinTheClearedRoomTarget() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("Saved Room", "secret")
            updateSession { it.copy(autoJoinRoomAfterReconnect = true, currentRoom = room("Saved Room")) }
        }
        val left = CompletableDeferred<Unit>()
        var joinCalls = 0
        lateinit var roomsController: BcSessionRoomsController
        roomsController = controller(
            state,
            TestRoomGateway(
                join = { joinCalls += 1 },
                leave = {
                    left.await()
                    roomsController.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)
                },
            ),
        )
        val leave = async { roomsController.leaveRoom() }
        runCurrent()
        val autoJoin = launch { roomsController.autoJoinLastRoomAfterReconnect(requireNotNull(state.current.account)) }
        runCurrent()

        left.complete(Unit)
        leave.await()
        autoJoin.join()

        assertEquals(0, joinCalls)
        assertNull(state.current.currentRoom)
        assertNull(state.lastJoinedRoomName)
        assertNull(state.lastJoinedRoomPassword)
    }

    @Test
    fun roomEventsCannotRestoreOnlineStateDuringRecoveryOrAfterStop() = runTest {
        for (sessionState in listOf(
            BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1),
            BcSessionState.Blocked(BlockedReason.DuplicatedLogin),
            BcSessionState.Stopped,
        )) {
            val state = onlineState().apply {
                updateSession { it.copy(state = sessionState, connectionHealth = BcConnectionHealth.Offline) }
            }
            var upsertCalls = 0
            val controller = controller(state, upsertRoomConversation = { upsertCalls += 1 })
            val room = room("Late Room")

            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(room, emptyList()))
            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomUpdated(room, emptyList()))
            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)

            assertEquals(sessionState, state.current.state)
            assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
            assertNull(state.current.currentRoom)
            assertEquals(0, upsertCalls)
        }
    }

    @Test
    fun roomSnapshotEventsPreserveUnrelatedSearchAndJoinOperations() = runTest {
        for (operation in listOf(BcSessionState.SearchingRooms(BcRoomSearchFilter()), BcSessionState.JoiningRoom("Next Room"))) {
            val state = onlineState().apply {
                updateSession { it.copy(state = operation, currentRoom = room("Current Room")) }
            }
            val controller = controller(state)

            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomUpdated(room("Current Room"), emptyList()))
            assertEquals(operation, state.current.state)
            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)
            assertEquals(operation, state.current.state)
        }
    }

    @Test
    fun joinedRoomVerifiesTheAccountAndCompletesTheMatchingJoin() = runTest {
        val state = onlineState().apply {
            updateSession {
                it.copy(state = BcSessionState.JoiningRoom("Next Room"), connectionHealth = BcConnectionHealth.ConnectedUnverified)
            }
        }
        val controller = controller(state)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(room("NEXT ROOM"), emptyList()))

        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(BcConnectionHealth.VerifiedOnline, state.current.connectionHealth)
    }

    @Test
    fun roomPresenceEpochDistinguishesSameRoomReentryAndPreservesUpdates() = runTest {
        val state = onlineState()
        val controller = controller(state)
        val room = room("Same Room")
        val accountGeneration = state.current.relationships.accountGeneration

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(room, emptyList()))
        assertEquals(1L, state.current.roomPresenceEpoch)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomUpdated(room.copy(description = "Changed"), emptyList()))
        assertEquals(1L, state.current.roomPresenceEpoch)
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)
        assertNull(state.current.currentRoom)
        assertEquals(1L, state.current.roomPresenceEpoch)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(room, emptyList()))
        assertEquals(2L, state.current.roomPresenceEpoch)
        assertEquals(accountGeneration, state.current.relationships.accountGeneration)
        assertEquals(room, state.current.currentRoom)
    }

    private fun onlineState(): BcSessionRuntimeState = BcSessionRuntimeState().apply {
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(10, "Self", "self"),
                relationships = it.relationships.nextLoaded(10, BcSelfRelations()),
            )
        }
    }

    private fun relog(state: BcSessionRuntimeState, account: BcAccount = requireNotNull(state.current.account).copy()) {
        state.updateSession {
            it.copy(
                state = BcSessionState.Ready,
                account = account,
                relationships = it.relationships.nextLoaded(account.memberNumber, BcSelfRelations()),
            )
        }
    }

    private fun controller(
        state: BcSessionRuntimeState,
        gateway: RoomGateway = FakeRoomGateway,
        onOperationFailure: (ReconnectReason, Throwable?) -> Unit = { _, _ -> },
        emitRoomTargetChanged: suspend (String?) -> Unit = {},
        upsertRoomConversation: (BcRoom) -> Unit = {},
    ): BcSessionRoomsController = BcSessionRoomsController(
        stateHolder = state,
        gateway = gateway,
        onOperationFailure = onOperationFailure,
        emitRoomTargetChanged = emitRoomTargetChanged,
        upsertRoomConversation = upsertRoomConversation,
        upsertRoomState = {},
        upsertSameRoomDirectConversations = {},
    )

    private class TestRoomGateway(
        private val search: suspend () -> List<BcRoomSummary> = { emptyList() },
        private val join: suspend () -> Unit = {},
        private val leave: suspend () -> Unit = {},
    ) : RoomGateway {
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = search()
        override suspend fun joinRoom(roomName: String, password: String?) = join()
        override suspend fun leaveRoom() = leave()
    }

    private fun room(name: String): BcRoom = BcRoom(
        name = BcRoomName(name),
        description = "",
        memberCount = 1,
        memberLimit = 10,
        isLocked = false,
        language = null,
        mapType = BcRoomMapType.ChatRoom,
    )

    private fun roomSummary(name: String, creatorMemberNumber: Int): BcRoomSummary = BcRoomSummary(
        name = BcRoomName(name),
        description = "",
        memberCount = 1,
        memberLimit = 10,
        isLocked = false,
        language = null,
        creatorName = "Creator",
        creatorMemberNumber = creatorMemberNumber,
    )

    private fun character(memberNumber: Int, name: String): BcCharacterSnapshot =
        BcCharacterSnapshot(
            id = memberNumber.toString(),
            memberNumber = memberNumber,
            name = name,
            displayName = name,
            isSelf = true,
        )

    private object FakeRoomGateway : RoomGateway {
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
    }
}
