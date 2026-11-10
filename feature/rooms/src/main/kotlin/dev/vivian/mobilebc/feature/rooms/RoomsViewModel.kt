package dev.vivian.mobilebc.feature.rooms

import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomTypeFilter
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomsUiState(
    val query: String = "",
    val space: RoomSpaceOption = RoomSpaceOption.Mixed,
    val roomType: BcRoomTypeFilter = BcRoomTypeFilter.All,
    val includeFullRooms: Boolean = false,
    val includeLockedRooms: Boolean = false,
    val searchDescription: Boolean = true,
    val rooms: List<BcRoomSummary> = emptyList(),
    val online: Boolean = false,
    val currentRoomName: String? = null,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val joiningRoomName: String? = null,
    val leavingRoomName: String? = null,
    val errorMessageRes: Int? = null,
    val canCreateRoom: Boolean = false,
    val roomMutationBusy: Boolean = false,
    val roomForm: RoomAdministrationFormState? = null,
)

enum class RoomSpaceOption(val wireValue: String) {
    Mixed("X"),
    FemaleOnly(""),
    MaleOnly("M"),
    Asylum("Asylum"),
}

class RoomsViewModel(
    private val rooms: BcSessionRooms,
    private val connectionStatus: ConnectionStatusReader,
    private val scope: CoroutineScope,
    private val onJoinedRoom: (String) -> Unit,
    backgroundNamesProvider: (BcRoomSpace) -> List<String> = { emptyList() },
) {
    private val roomForm = RoomAdministrationFormController(
        rooms = rooms,
        connectionStatus = connectionStatus,
        scope = scope,
        backgroundNamesProvider = backgroundNamesProvider,
        onCompleted = { room -> onJoinedRoom(room.name.value) },
    )
    private data class SearchState(
        val query: String = "",
        val space: RoomSpaceOption = RoomSpaceOption.Mixed,
        val roomType: BcRoomTypeFilter = BcRoomTypeFilter.All,
        val includeFullRooms: Boolean = false,
        val includeLockedRooms: Boolean = false,
        val searchDescription: Boolean = true,
        val rooms: List<BcRoomSummary> = emptyList(),
        val operation: RoomOperation? = null,
        val hasSearched: Boolean = false,
        val errorMessageRes: Int? = null,
    ) {
        val isSearching: Boolean get() = operation is RoomOperation.Search
        val joiningRoomName: String? get() = (operation as? RoomOperation.Join)?.roomName
        val leavingRoomName: String? get() = (operation as? RoomOperation.Leave)?.roomName
    }

    private sealed interface RoomOperation {
        class Search : RoomOperation
        class Join(val roomName: String) : RoomOperation
        class Leave(val roomName: String) : RoomOperation
    }

    private val searchState = MutableStateFlow(SearchState())
    private var observedSnapshot = connectionStatus.snapshot.value
    private val projectedState = MutableStateFlow(
        project(searchState.value, connectionStatus.snapshot.value, roomForm.state.value),
    )
    val uiState: StateFlow<RoomsUiState> = projectedState.asStateFlow()

    init {
        scope.launch { roomForm.state.collect { publish() } }
        scope.launch {
            connectionStatus.snapshot.collect { snapshot ->
                val accountChanged = snapshot.account?.memberNumber != observedSnapshot.account?.memberNumber ||
                    snapshot.relationships.accountGeneration != observedSnapshot.relationships.accountGeneration
                observedSnapshot = snapshot
                if (accountChanged) {
                    update { it.copy(rooms = emptyList(), hasSearched = false, errorMessageRes = null) }
                } else {
                    publish()
                }
            }
        }
    }

    fun openCreateRoom() {
        val current = searchState.value
        val session = connectionStatus.snapshot.value
        if (!session.isVerifiedOnline || session.currentRoom != null ||
            current.isSearching || current.joiningRoomName != null || current.leavingRoomName != null ||
            roomForm.state.value?.busy == true
        ) return
        roomForm.openCreate(
            when (current.space) {
                RoomSpaceOption.Mixed -> BcRoomSpace.Mixed
                RoomSpaceOption.FemaleOnly -> BcRoomSpace.Female
                RoomSpaceOption.MaleOnly -> BcRoomSpace.Male
                RoomSpaceOption.Asylum -> BcRoomSpace.Asylum
            },
        )
    }

    fun closeRoomForm() = roomForm.close()

    fun changeRoomFormDraft(draft: RoomAdministrationDraft) = roomForm.changeDraft(draft)

    fun submitRoomForm() = roomForm.submit()

    fun onQueryChange(value: String) {
        update { it.copy(query = value) }
    }

    fun onSpaceChange(value: RoomSpaceOption) {
        changeSearchFilter { it.copy(space = value) }
    }

    fun onRoomTypeChange(value: BcRoomTypeFilter) {
        changeSearchFilter { it.copy(roomType = value) }
    }

    fun onIncludeFullRoomsChange(value: Boolean) {
        changeSearchFilter { it.copy(includeFullRooms = value) }
    }

    fun onIncludeLockedRoomsChange(value: Boolean) {
        changeSearchFilter { it.copy(includeLockedRooms = value) }
    }

    fun onSearchDescriptionChange(value: Boolean) {
        changeSearchFilter { it.copy(searchDescription = value) }
    }

    private fun changeSearchFilter(transform: (SearchState) -> SearchState) {
        val state = searchState.value
        if (state.isSearching || state.joiningRoomName != null || state.leavingRoomName != null || roomFormBlocksActions()) return
        val next = transform(state)
        if (next == state) return
        update { next.copy(rooms = emptyList(), hasSearched = false, errorMessageRes = null) }
        if (state.hasSearched) searchRooms()
    }

    fun searchRooms() {
        val state = searchState.value
        val session = connectionStatus.snapshot.value
        if (!session.isVerifiedOnline || state.isSearching || state.joiningRoomName != null || state.leavingRoomName != null ||
            roomFormBlocksActions()
        ) return
        val operation = RoomOperation.Search()
        if (!searchState.compareAndSet(state, state.copy(operation = operation, rooms = emptyList(), hasSearched = false, errorMessageRes = null))) return
        publish()
        scope.launch {
            try {
                if (!isCurrentSearchSession(session)) return@launch
                val result = rooms.searchRooms(
                    BcRoomSearchFilter(
                        query = state.query.trim(),
                        space = state.space.wireValue,
                        roomType = state.roomType,
                        includeFullRooms = state.includeFullRooms,
                        includeLockedRooms = state.includeLockedRooms,
                        searchDescription = state.searchDescription,
                    )
                )
                if (isCurrentSearchSession(session)) update {
                    it.copy(
                        rooms = result,
                        hasSearched = true,
                        errorMessageRes = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentSearchSession(session)) update {
                    it.copy(
                        hasSearched = true,
                        errorMessageRes = R.string.rooms_search_failed,
                    )
                }
            }
        }.invokeOnCompletion { releaseOperation(operation) }
    }

    fun joinRoom(room: BcRoomSummary) {
        val state = searchState.value
        val session = connectionStatus.snapshot.value
        if (!session.isVerifiedOnline || state.isSearching || state.joiningRoomName != null || state.leavingRoomName != null ||
            roomFormBlocksActions()
        ) return
        if (room.canJoin == false) {
            update { it.copy(errorMessageRes = R.string.rooms_cannot_join) }
            return
        }
        if (room.memberCount >= room.memberLimit) {
            update { it.copy(errorMessageRes = R.string.rooms_room_full) }
            return
        }
        val operation = RoomOperation.Join(room.name.value)
        if (!searchState.compareAndSet(state, state.copy(operation = operation, errorMessageRes = null))) return
        publish()
        scope.launch {
            try {
                if (!isCurrentSearchSession(session)) return@launch
                rooms.joinRoom(room.name.value)
                if (isCurrentSession(session)) onJoinedRoom(room.name.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentSession(session)) update {
                    it.copy(
                        errorMessageRes = R.string.rooms_join_failed,
                    )
                }
            }
        }.invokeOnCompletion { releaseOperation(operation) }
    }

    fun leaveRoom() {
        val state = searchState.value
        val started = connectionStatus.snapshot.value
        val roomName = started.currentRoom?.name?.value ?: return
        if (!started.isVerifiedOnline || state.isSearching || state.joiningRoomName != null || state.leavingRoomName != null ||
            roomFormBlocksActions()
        ) return
        val operation = RoomOperation.Leave(roomName)
        if (!searchState.compareAndSet(state, state.copy(operation = operation, errorMessageRes = null))) return
        publish()
        scope.launch {
            try {
                val current = connectionStatus.snapshot.value
                if (!current.belongsToOnlineSession(started) || current.currentRoom?.name?.value != roomName ||
                    current.roomPresenceEpoch != started.roomPresenceEpoch
                ) return@launch
                rooms.leaveRoom()
                connectionStatus.snapshot.first { snapshot ->
                    !snapshot.belongsToOnlineSession(started) || snapshot.currentRoom?.name?.value != roomName ||
                        snapshot.roomPresenceEpoch != started.roomPresenceEpoch
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                val current = connectionStatus.snapshot.value
                if (current.belongsToOnlineSession(started) && current.currentRoom?.name?.value == roomName &&
                    current.roomPresenceEpoch == started.roomPresenceEpoch
                ) update {
                    it.copy(errorMessageRes = R.string.rooms_leave_failed)
                }
            }
        }.invokeOnCompletion { releaseOperation(operation) }
    }

    private fun releaseOperation(operation: RoomOperation) {
        update { if (it.operation === operation) it.copy(operation = null) else it }
    }

    private fun isCurrentSearchSession(started: BcSessionSnapshot): Boolean {
        val current = connectionStatus.snapshot.value
        return current.belongsToOnlineSession(started) &&
            current.roomPresenceEpoch == started.roomPresenceEpoch &&
            current.currentRoom?.name == started.currentRoom?.name
    }

    private fun isCurrentSession(started: BcSessionSnapshot): Boolean =
        connectionStatus.snapshot.value.belongsToOnlineSession(started)

    private fun BcSessionSnapshot.belongsToOnlineSession(started: BcSessionSnapshot): Boolean =
        isVerifiedOnline && account?.memberNumber == started.account?.memberNumber &&
            relationships.accountGeneration == started.relationships.accountGeneration

    private fun update(transform: (SearchState) -> SearchState) {
        searchState.update(transform)
        publish()
    }

    private fun publish() {
        projectedState.update { project(searchState.value, connectionStatus.snapshot.value, roomForm.state.value) }
    }

    private fun project(
        search: SearchState,
        session: BcSessionSnapshot,
        form: RoomAdministrationFormState?,
    ): RoomsUiState = RoomsUiState(
        query = search.query,
        space = search.space,
        roomType = search.roomType,
        includeFullRooms = search.includeFullRooms,
        includeLockedRooms = search.includeLockedRooms,
        searchDescription = search.searchDescription,
        rooms = search.rooms,
        online = session.isVerifiedOnline,
        currentRoomName = session.currentRoom?.name?.value,
        isSearching = search.isSearching,
        hasSearched = search.hasSearched,
        joiningRoomName = search.joiningRoomName,
        leavingRoomName = search.leavingRoomName,
        errorMessageRes = search.errorMessageRes,
        roomForm = form?.takeIf { it.visible },
        roomMutationBusy = form?.busy == true,
        canCreateRoom = session.isVerifiedOnline && session.currentRoom == null && !search.isSearching &&
            search.joiningRoomName == null && search.leavingRoomName == null && form?.busy != true,
    )

    private fun roomFormBlocksActions(): Boolean = roomForm.state.value?.let { it.visible || it.busy } == true
}
