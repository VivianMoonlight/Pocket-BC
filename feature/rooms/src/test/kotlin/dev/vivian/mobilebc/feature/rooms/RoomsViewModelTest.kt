package dev.vivian.mobilebc.feature.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomTypeFilter
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import dev.vivian.mobilebc.feature.rooms.R
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsViewModelTest {
    @Test
    fun cancellationBeforeExecutionReleasesEachRoomOperationWithoutSending() {
        listOf("search", "join", "leave").forEach { operation ->
            listOf(true, false).forEach { cancelBeforeSubmit ->
                val dispatcher = QueuedDispatcher()
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val rooms = FakeRooms()
                val status = FakeConnectionStatusReader(onlineSnapshot().copy(
                    currentRoom = CURRENT_ROOM.takeIf { operation == "leave" },
                ))
                val navigations = mutableListOf<String>()
                val model = RoomsViewModel(rooms, status, scope, navigations::add)
                try {
                    dispatcher.runQueued()
                    if (cancelBeforeSubmit) scope.cancel()
                    when (operation) {
                        "search" -> model.searchRooms()
                        "join" -> model.joinRoom(TEST_ROOM)
                        "leave" -> model.leaveRoom()
                    }
                    scope.cancel()
                    dispatcher.runQueued()

                    assertFalse(model.uiState.value.isSearching)
                    assertNull(model.uiState.value.joiningRoomName)
                    assertNull(model.uiState.value.leavingRoomName)
                    assertNull(model.uiState.value.errorMessageRes)
                    assertEquals(0, rooms.searchCalls.get())
                    assertEquals(0, rooms.joinCalls.get())
                    assertEquals(0, rooms.leaveCalls.get())
                    assertTrue(navigations.isEmpty())
                } finally {
                    scope.cancel()
                    dispatcher.runQueued()
                }
            }
        }
    }

    @Test
    fun queuedJoinCannotUseAnotherAccountLoginOrRoomContext() {
        val changes: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(account = BcAccount(99, "Other", "other")) },
            { it.copy(relationships = it.relationships.nextUnloaded()) },
            { it.copy(connectionHealth = BcConnectionHealth.Offline) },
            { it.copy(currentRoom = CURRENT_ROOM) },
            { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) },
        )
        changes.forEach { change ->
            val dispatcher = QueuedDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val rooms = FakeRooms()
            val status = FakeConnectionStatusReader(onlineSnapshot())
            val navigations = mutableListOf<String>()
            val model = RoomsViewModel(rooms, status, scope, navigations::add)
            try {
                model.joinRoom(TEST_ROOM)
                assertEquals(TEST_ROOM.name.value, model.uiState.value.joiningRoomName)
                status.snapshot.value = change(status.snapshot.value)
                dispatcher.runQueued()
                assertEquals(0, rooms.joinCalls.get())
                assertTrue(navigations.isEmpty())
                assertNull(model.uiState.value.joiningRoomName)
                assertNull(model.uiState.value.errorMessageRes)
            } finally { scope.cancel(); dispatcher.runQueued() }
        }
    }

    @Test
    fun draftChangesCannotMixOldOnlineStateWithNewRoomPermissions() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val model = RoomsViewModel(FakeRooms(), status, scope, {})
        val observed = mutableListOf<RoomsUiState>()
        scope.launch(Dispatchers.Unconfined) { model.uiState.collect { observed += it } }
        try {
            dispatcher.runQueued()
            status.snapshot.value = status.snapshot.value.copy(
                connectionHealth = BcConnectionHealth.Offline,
                currentRoom = CURRENT_ROOM,
            )
            model.onQueryChange("new query")
            dispatcher.runQueued()

            assertEquals("new query", model.uiState.value.query)
            assertFalse(model.uiState.value.online)
            assertEquals(CURRENT_ROOM.name.value, model.uiState.value.currentRoomName)
            assertTrue(observed.all { state ->
                if (state.online) state.currentRoomName == null && state.canCreateRoom
                else state.currentRoomName == CURRENT_ROOM.name.value && !state.canCreateRoom
            })
        } finally { scope.cancel(); dispatcher.runQueued() }
    }

    @Test
    fun commandsUsePendingSearchStateBeforeUiProjectionRuns() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val rooms = FakeRooms()
        val model = RoomsViewModel(rooms, FakeConnectionStatusReader(onlineSnapshot()), scope, {})
        try {
            model.onQueryChange("draft before collection")
            model.searchRooms()
            model.searchRooms()
            model.joinRoom(TEST_ROOM)
            dispatcher.runQueued()
            assertEquals(1, rooms.searchCalls.get())
            assertEquals("draft before collection", rooms.searchFilters.single().query)
            assertEquals(0, rooms.joinCalls.get())
            assertTrue(model.uiState.value.isSearching)
        } finally { scope.cancel(); dispatcher.runQueued() }
    }

    @Test
    fun cannotJoinAndFullRoomsStayLocalWhileUnknownPermissionAllowsServerDecision() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = RoomsViewModel(rooms, FakeConnectionStatusReader(onlineSnapshot()), scope, {})
        try {
            viewModel.joinRoom(TEST_ROOM.copy(canJoin = false))
            assertEquals(R.string.rooms_cannot_join, viewModel.uiState.value.errorMessageRes)
            viewModel.joinRoom(TEST_ROOM.copy(memberCount = TEST_ROOM.memberLimit, canJoin = true))
            assertEquals(R.string.rooms_room_full, viewModel.uiState.value.errorMessageRes)
            assertEquals(0, rooms.joinCalls.get())

            viewModel.joinRoom(TEST_ROOM.copy(canJoin = null, isLocked = true))
            assertEquals(1, rooms.joinCalls.get())
            assertEquals(TEST_ROOM.name.value, viewModel.uiState.value.joiningRoomName)
            rooms.joinResult.complete(Unit)
            awaitState { viewModel.uiState.value.joiningRoomName == null }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun createUsesTheSelectedSpaceAndOnlyAuthorityCompletionNavigates() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val openedRooms = mutableListOf<String>()
        val viewModel = RoomsViewModel(rooms, status, scope, openedRooms::add, { listOf("MainHall") })
        try {
            viewModel.onSpaceChange(RoomSpaceOption.FemaleOnly)
            viewModel.openCreateRoom()
            val form = checkNotNull(viewModel.uiState.value.roomForm)
            viewModel.changeRoomFormDraft(form.draft.copy(name = "New room"))
            viewModel.submitRoomForm()
            assertEquals(1, rooms.createCalls.get())
            assertEquals("", rooms.createCommands.single().space.wireValue)
            assertTrue(checkNotNull(viewModel.uiState.value.roomForm).busy)
            assertTrue(openedRooms.isEmpty())

            viewModel.joinRoom(TEST_ROOM)
            viewModel.searchRooms()
            viewModel.openCreateRoom()
            assertEquals(0, rooms.joinCalls.get())
            assertEquals(0, rooms.searchCalls.get())
            assertEquals(1, rooms.createCalls.get())

            val authority = CURRENT_ROOM.copy(name = BcRoomName("New room"))
            status.snapshot.value = status.snapshot.value.copy(currentRoom = authority, roomPresenceEpoch = 1)
            rooms.createResult.complete(authority)
            awaitState { viewModel.uiState.value.roomForm == null && !viewModel.uiState.value.roomMutationBusy }
            assertEquals(listOf("New room"), openedRooms)
            assertEquals("New room", viewModel.uiState.value.currentRoomName)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownCreateOutcomeKeepsDraftAcrossCloseAndReopenWithoutAutomaticRetry() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {}, { listOf("MainHall") })
        try {
            viewModel.openCreateRoom()
            viewModel.changeRoomFormDraft(checkNotNull(viewModel.uiState.value.roomForm).draft.copy(name = "New room", description = "Keep description"))
            viewModel.submitRoomForm()
            rooms.createResult.completeExceptionally(BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.TimedOut))
            awaitState { viewModel.uiState.value.roomForm?.busy == false }
            val retained = checkNotNull(viewModel.uiState.value.roomForm)
            assertEquals("New room", retained.draft.name)
            assertEquals("Keep description", retained.draft.description)
            assertFalse(retained.canSubmit)

            viewModel.closeRoomForm()
            assertNull(viewModel.uiState.value.roomForm)
            viewModel.openCreateRoom()
            viewModel.submitRoomForm()
            assertEquals(retained.draft, checkNotNull(viewModel.uiState.value.roomForm).draft)
            assertEquals(1, rooms.createCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun leaveWaitsForRoomPublicationOrNewPresenceAndRejectsDuplicateOfflineCalls() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot().copy(currentRoom = CURRENT_ROOM))
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.leaveRoom()
            viewModel.leaveRoom()
            viewModel.searchRooms()
            viewModel.joinRoom(TEST_ROOM)
            awaitState { rooms.leaveCalls.get() == 1 }
            assertEquals(CURRENT_ROOM.name.value, viewModel.uiState.value.leavingRoomName)
            assertEquals(0, rooms.searchCalls.get())
            assertEquals(0, rooms.joinCalls.get())

            rooms.leaveResult.complete(Unit)
            awaitState { rooms.leaveReturned.get() == 1 }
            assertEquals(CURRENT_ROOM.name.value, viewModel.uiState.value.currentRoomName)
            assertEquals(CURRENT_ROOM.name.value, viewModel.uiState.value.leavingRoomName)
            status.snapshot.value = status.snapshot.value.copy(currentRoom = null)
            awaitState { viewModel.uiState.value.currentRoomName == null && viewModel.uiState.value.leavingRoomName == null }

            status.snapshot.value = status.snapshot.value.copy(currentRoom = CURRENT_ROOM, roomPresenceEpoch = 1)
            rooms.leaveResult = CompletableDeferred()
            viewModel.leaveRoom()
            awaitState { rooms.leaveCalls.get() == 2 }
            rooms.leaveResult.complete(Unit)
            awaitState { rooms.leaveReturned.get() == 2 }
            status.snapshot.value = status.snapshot.value.copy(roomPresenceEpoch = 2)
            awaitState { viewModel.uiState.value.leavingRoomName == null }
            assertEquals(CURRENT_ROOM.name.value, viewModel.uiState.value.currentRoomName)

            status.snapshot.value = status.snapshot.value.copy(
                currentRoom = CURRENT_ROOM,
                connectionHealth = BcConnectionHealth.Offline,
            )
            viewModel.leaveRoom()
            assertEquals(2, rooms.leaveCalls.get())
            assertNull(viewModel.uiState.value.errorMessageRes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun leaveFailureKeepsTheRoomForRetryAndOldAccountFailureDoesNotPublish() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot().copy(currentRoom = CURRENT_ROOM))
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.leaveRoom()
            awaitState { rooms.leaveCalls.get() == 1 }
            rooms.leaveResult.completeExceptionally(IllegalStateException("Leave failed"))
            awaitState { viewModel.uiState.value.leavingRoomName == null }
            assertEquals(CURRENT_ROOM.name.value, viewModel.uiState.value.currentRoomName)
            assertEquals(R.string.rooms_leave_failed, viewModel.uiState.value.errorMessageRes)

            rooms.leaveResult = CompletableDeferred()
            viewModel.leaveRoom()
            awaitState { rooms.leaveCalls.get() == 2 }
            assertNull(viewModel.uiState.value.errorMessageRes)
            status.snapshot.value = status.snapshot.value.copy(
                account = BcAccount(2, "Other", "other"),
                relationships = status.snapshot.value.relationships.nextUnloaded(),
                currentRoom = CURRENT_ROOM.copy(name = BcRoomName("Other room")),
            )
            rooms.leaveResult.completeExceptionally(IllegalStateException("Old leave failed"))
            awaitState { viewModel.uiState.value.leavingRoomName == null && viewModel.uiState.value.currentRoomName == "Other room" }
            assertNull(viewModel.uiState.value.errorMessageRes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun filtersRefreshResultsAfterSearchAndCannotChangeDuringARequest() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.onQueryChange("  Quiet  ")
            viewModel.searchRooms()
            awaitState { rooms.searchCalls.get() == 1 }
            viewModel.onSpaceChange(RoomSpaceOption.FemaleOnly)
            viewModel.onRoomTypeChange(BcRoomTypeFilter.Map)
            viewModel.onIncludeFullRoomsChange(true)
            viewModel.onIncludeLockedRoomsChange(true)
            viewModel.onSearchDescriptionChange(false)
            assertEquals(RoomSpaceOption.Mixed, viewModel.uiState.value.space)
            assertEquals(BcRoomTypeFilter.All, viewModel.uiState.value.roomType)
            assertFalse(viewModel.uiState.value.includeFullRooms)
            assertFalse(viewModel.uiState.value.includeLockedRooms)
            assertTrue(viewModel.uiState.value.searchDescription)

            rooms.searchResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }
            viewModel.onSpaceChange(RoomSpaceOption.FemaleOnly)
            awaitState { rooms.searchCalls.get() == 2 && !viewModel.uiState.value.isSearching }
            viewModel.onRoomTypeChange(BcRoomTypeFilter.Map)
            awaitState { rooms.searchCalls.get() == 3 && !viewModel.uiState.value.isSearching }
            viewModel.onRoomTypeChange(BcRoomTypeFilter.Map)

            assertEquals(3, rooms.searchCalls.get())
            assertEquals("Quiet", rooms.searchFilters.first().query)
            assertEquals("", rooms.searchFilters.toList()[1].space)
            assertEquals(BcRoomTypeFilter.Map, rooms.searchFilters.last().roomType)

            status.snapshot.value = status.snapshot.value.copy(connectionHealth = BcConnectionHealth.Offline)
            awaitState { !viewModel.uiState.value.online }
            viewModel.onSpaceChange(RoomSpaceOption.MaleOnly)
            assertTrue(viewModel.uiState.value.rooms.isEmpty())
            assertFalse(viewModel.uiState.value.hasSearched)
            assertEquals(3, rooms.searchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun searchOptionsKeepProtocolDefaultsAndRefreshResultsWhenChanged() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.searchRooms()
            assertEquals(BcRoomSearchFilter(), rooms.searchFilters.single())
            rooms.searchResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }

            viewModel.onIncludeFullRoomsChange(true)
            viewModel.onIncludeLockedRoomsChange(true)
            viewModel.onSearchDescriptionChange(false)
            assertEquals(4, rooms.searchCalls.get())
            val filters = rooms.searchFilters.toList()
            assertTrue(filters[1].includeFullRooms)
            assertFalse(filters[1].includeLockedRooms)
            assertTrue(filters[1].searchDescription)
            assertTrue(filters[2].includeFullRooms)
            assertTrue(filters[2].includeLockedRooms)
            assertTrue(filters[2].searchDescription)
            assertTrue(filters[3].includeFullRooms)
            assertTrue(filters[3].includeLockedRooms)
            assertFalse(filters[3].searchDescription)
            assertEquals(listOf(TEST_ROOM), viewModel.uiState.value.rooms)

            viewModel.onSearchDescriptionChange(false)
            assertEquals(4, rooms.searchCalls.get())
            status.snapshot.value = status.snapshot.value.copy(connectionHealth = BcConnectionHealth.Offline)
            viewModel.onIncludeFullRoomsChange(false)
            assertFalse(viewModel.uiState.value.hasSearched)
            assertTrue(viewModel.uiState.value.rooms.isEmpty())
            assertEquals(4, rooms.searchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun searchOptionsCannotChangeDuringJoinOrLeave() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.joinRoom(TEST_ROOM)
            viewModel.onIncludeFullRoomsChange(true)
            viewModel.onIncludeLockedRoomsChange(true)
            viewModel.onSearchDescriptionChange(false)
            assertFalse(viewModel.uiState.value.includeFullRooms)
            assertFalse(viewModel.uiState.value.includeLockedRooms)
            assertTrue(viewModel.uiState.value.searchDescription)
            rooms.joinResult.complete(Unit)
            awaitState { viewModel.uiState.value.joiningRoomName == null }

            status.snapshot.value = status.snapshot.value.copy(currentRoom = CURRENT_ROOM)
            viewModel.leaveRoom()
            viewModel.onIncludeFullRoomsChange(true)
            viewModel.onIncludeLockedRoomsChange(true)
            viewModel.onSearchDescriptionChange(false)
            assertFalse(viewModel.uiState.value.includeFullRooms)
            assertFalse(viewModel.uiState.value.includeLockedRooms)
            assertTrue(viewModel.uiState.value.searchDescription)
            assertEquals(0, rooms.searchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun accountAndRoomPresenceChangesDiscardSearchResultsWithoutOverlappingRequests() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.searchRooms()
            val firstResult = rooms.searchResult
            status.snapshot.value = status.snapshot.value.copy(
                account = BcAccount(2, "Bob", "bob"),
                relationships = status.snapshot.value.relationships.nextUnloaded(),
            )
            assertTrue(viewModel.uiState.value.isSearching)
            assertFalse(viewModel.uiState.value.hasSearched)
            assertNull(viewModel.uiState.value.errorMessageRes)
            viewModel.onIncludeFullRoomsChange(true)
            viewModel.searchRooms()
            assertEquals(1, rooms.searchCalls.get())
            assertFalse(viewModel.uiState.value.includeFullRooms)
            firstResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }
            assertTrue(viewModel.uiState.value.rooms.isEmpty())

            rooms.searchResult = CompletableDeferred()
            viewModel.onIncludeFullRoomsChange(true)
            viewModel.searchRooms()
            assertEquals(2, rooms.searchCalls.get())
            val secondResult = rooms.searchResult
            status.snapshot.value = status.snapshot.value.copy(roomPresenceEpoch = 1, currentRoom = CURRENT_ROOM)
            viewModel.searchRooms()
            assertEquals(2, rooms.searchCalls.get())
            secondResult.completeExceptionally(IllegalStateException("Old search failed"))
            awaitState { !viewModel.uiState.value.isSearching }
            assertNull(viewModel.uiState.value.errorMessageRes)
            assertFalse(viewModel.uiState.value.hasSearched)
            rooms.searchResult = CompletableDeferred()
            viewModel.searchRooms()
            assertEquals(3, rooms.searchCalls.get())
            rooms.searchResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }
            assertEquals(listOf(TEST_ROOM), viewModel.uiState.value.rooms)

            status.snapshot.value = status.snapshot.value.copy(
                account = BcAccount(3, "Carol", "carol"),
                relationships = status.snapshot.value.relationships.nextUnloaded(),
            )
            awaitState { viewModel.uiState.value.rooms.isEmpty() }
            assertFalse(viewModel.uiState.value.hasSearched)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun searchExposesProgressAndSuppressesDuplicateRequests() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = RoomsViewModel(
            rooms = rooms,
            connectionStatus = FakeConnectionStatusReader(onlineSnapshot()),
            scope = scope,
            onJoinedRoom = {},
        )

        try {
            viewModel.searchRooms()
            viewModel.searchRooms()

            awaitState { rooms.searchCalls.get() == 1 && viewModel.uiState.value.isSearching }
            assertFalse(viewModel.uiState.value.hasSearched)

            rooms.searchResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }

            assertTrue(viewModel.uiState.value.hasSearched)
            assertEquals(listOf(TEST_ROOM), viewModel.uiState.value.rooms)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun joinExposesTargetAndSuppressesDuplicateRequests() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val joinedRooms = mutableListOf<String>()
        val viewModel = RoomsViewModel(
            rooms = rooms,
            connectionStatus = FakeConnectionStatusReader(onlineSnapshot()),
            scope = scope,
            onJoinedRoom = joinedRooms::add,
        )

        try {
            viewModel.joinRoom(TEST_ROOM)
            viewModel.joinRoom(TEST_ROOM)

            awaitState { rooms.joinCalls.get() == 1 && viewModel.uiState.value.joiningRoomName != null }
            assertEquals(TEST_ROOM.name.value, viewModel.uiState.value.joiningRoomName)

            rooms.joinResult.complete(Unit)
            awaitState { viewModel.uiState.value.joiningRoomName == null && joinedRooms.isNotEmpty() }

            assertNull(viewModel.uiState.value.joiningRoomName)
            assertEquals(listOf(TEST_ROOM.name.value), joinedRooms)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestUsesCurrentConnectionBeforeUiCollectorCatchesUp() {
        val rooms = FakeRooms()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            status.snapshot.value = BcSessionSnapshot()
            assertTrue(viewModel.uiState.value.online)

            viewModel.searchRooms()
            viewModel.joinRoom(TEST_ROOM)
            dispatcher.runQueued()

            assertEquals(0, rooms.searchCalls.get())
            assertEquals(0, rooms.joinCalls.get())
        } finally {
            scope.cancel()
            dispatcher.runQueued()
        }
    }

    @Test
    fun searchFromPreviousAccountDoesNotPublishResults() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val viewModel = RoomsViewModel(rooms, status, scope, {})
        try {
            viewModel.searchRooms()
            awaitState { rooms.searchCalls.get() == 1 }
            status.snapshot.value = status.snapshot.value.copy(
                account = BcAccount(2, "Bob", "bob"),
                relationships = status.snapshot.value.relationships.nextUnloaded(),
            )
            rooms.searchResult.complete(listOf(TEST_ROOM))
            awaitState { !viewModel.uiState.value.isSearching }

            assertTrue(viewModel.uiState.value.rooms.isEmpty())
            assertFalse(viewModel.uiState.value.hasSearched)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun joiningDoesNotNavigateAfterConnectionIsLost() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val status = FakeConnectionStatusReader(onlineSnapshot())
        val joinedRooms = mutableListOf<String>()
        val viewModel = RoomsViewModel(rooms, status, scope, joinedRooms::add)
        try {
            viewModel.joinRoom(TEST_ROOM)
            awaitState { rooms.joinCalls.get() == 1 }
            status.snapshot.value = status.snapshot.value.copy(connectionHealth = BcConnectionHealth.Offline)
            rooms.joinResult.complete(Unit)
            awaitState { viewModel.uiState.value.joiningRoomName == null }

            assertTrue(joinedRooms.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledSearchClearsProgressWithoutPublishingError() = runBlocking {
        val rooms = FakeRooms()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = RoomsViewModel(rooms, FakeConnectionStatusReader(onlineSnapshot()), scope, {})
        viewModel.searchRooms()
        awaitState { rooms.searchCalls.get() == 1 }

        scope.cancel()
        awaitState { !viewModel.uiState.value.isSearching }

        assertNull(viewModel.uiState.value.errorMessageRes)
        assertFalse(viewModel.uiState.value.hasSearched)
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) yield()
        }
    }

    private class FakeRooms : BcSessionRooms {
        val searchCalls = AtomicInteger()
        val searchFilters = ConcurrentLinkedQueue<BcRoomSearchFilter>()
        val joinCalls = AtomicInteger()
        val leaveCalls = AtomicInteger()
        val leaveReturned = AtomicInteger()
        val createCalls = AtomicInteger()
        val createCommands = ConcurrentLinkedQueue<BcRoomCreateCommand>()
        var searchResult = CompletableDeferred<List<BcRoomSummary>>()
        val joinResult = CompletableDeferred<Unit>()
        var leaveResult = CompletableDeferred<Unit>()
        val createResult = CompletableDeferred<BcRoom>()

        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> {
            searchFilters += filter
            searchCalls.incrementAndGet()
            return searchResult.await()
        }

        override suspend fun joinRoom(roomName: String, password: String?) {
            joinCalls.incrementAndGet()
            joinResult.await()
        }

        override suspend fun leaveRoom() {
            leaveCalls.incrementAndGet()
            leaveResult.await()
            leaveReturned.incrementAndGet()
        }

        override suspend fun createRoom(command: BcRoomCreateCommand): BcRoom {
            createCommands += command
            createCalls.incrementAndGet()
            return createResult.await()
        }
    }

    private class FakeConnectionStatusReader(
        initialSnapshot: BcSessionSnapshot,
    ) : ConnectionStatusReader {
        override val snapshot = MutableStateFlow(initialSnapshot)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private companion object {
        val CURRENT_ROOM = BcRoom(
            name = BcRoomName("Current room"),
            description = "",
            memberCount = 1,
            memberLimit = 10,
            isLocked = false,
            language = null,
        )

        val TEST_ROOM = BcRoomSummary(
            name = BcRoomName("Quiet room"),
            description = "A test room",
            memberCount = 1,
            memberLimit = 10,
            isLocked = false,
            language = "EN",
            creatorName = "Alice",
            mapType = BcRoomMapType.ChatRoom,
        )

        fun onlineSnapshot(): BcSessionSnapshot = BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = BcAccount(
                memberNumber = 1,
                displayName = "Alice",
                accountName = "alice",
            ),
        )
    }
}
