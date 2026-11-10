package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.core.RoomGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionRoomManagementTest {
    @Test
    fun creationUsesItsAuthoritativeReceiptWithoutInventingDomainRoomState() = runTest {
        val state = onlineState()
        val created = room("Created", 8)
        val targets = mutableListOf<String?>()
        val controller = controller(state, FakeGateway(create = { created }), targets = targets)

        assertSame(created, controller.createRoom(BcRoomCreateCommand(settings("Created"))))

        assertNull(state.current.currentRoom)
        assertNull(state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())
        assertEquals(BcSessionState.Ready, state.current.state)

        controller.handleGatewayEvent(joined(created, "login"))

        assertEquals(created, state.current.currentRoom)
        assertEquals(8L, state.current.gatewayRoomPresenceEpoch)
        assertEquals("Created", state.lastJoinedRoomName)
        assertEquals(listOf("Created"), targets)
    }

    @Test
    fun renameReceiptCannotMoveTheRecoveryTargetAheadOfTheDomainSync() = runTest {
        val original = room("Original", 4)
        val renamed = room("Renamed", 4)
        val state = onlineState(original).apply { rememberRoomTarget("Original") }
        val targets = mutableListOf<String?>()
        val controller = controller(state, FakeGateway(update = { renamed }), targets = targets)

        assertSame(renamed, controller.updateRoom(update(original, "Renamed")))

        assertEquals(original, state.current.currentRoom)
        assertEquals("Original", state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())

        controller.handleGatewayEvent(updated(renamed, "login"))

        assertEquals(renamed, state.current.currentRoom)
        assertEquals("Renamed", state.lastJoinedRoomName)
        assertEquals(listOf("Renamed"), targets)
    }

    @Test
    fun roomUpdateCannotTransferAPasswordFromAnUnrelatedRecoveryTarget() = runTest {
        val original = room("Original", 4)
        val state = onlineState(original).apply { rememberRoomTarget("Other Room", "other-secret") }
        val targets = mutableListOf<String?>()
        val controller = controller(state, targets = targets)

        controller.handleGatewayEvent(updated(room("New Name", 4), "login"))

        assertEquals("New Name", state.lastJoinedRoomName)
        assertNull(state.lastJoinedRoomPassword)
        assertEquals(listOf("New Name"), targets)
    }

    @Test
    fun anotherAdministratorsRenameBecomesTheReconnectTarget() = runTest {
        val original = room("Original", 4)
        val state = onlineState(original).apply {
            rememberRoomTarget("Original", "saved-password")
            updateSession { it.copy(autoJoinRoomAfterReconnect = true) }
        }
        val joinedNames = mutableListOf<String>()
        val targets = mutableListOf<String?>()
        val controller = controller(state, FakeGateway(join = { joinedNames += it }), targets = targets)

        controller.handleGatewayEvent(updated(room("New Name", 4), "login"))
        state.updateSession { it.copy(currentRoom = null, roomMembers = emptyList(), roomCharacters = emptyList()) }
        controller.autoJoinLastRoomAfterReconnect(requireNotNull(state.current.account))

        assertEquals(listOf("New Name"), joinedNames)
        assertEquals(listOf("New Name"), targets)
        assertEquals("saved-password", state.lastJoinedRoomPassword)
    }

    @Test
    fun queuedRoomEventsFromThePreviousLoginCannotChangeCurrentPresence() = runTest {
        val current = room("Current", 8)
        val state = onlineState(current).apply { rememberRoomTarget("Current") }
        val targets = mutableListOf<String?>()
        var upsertCalls = 0
        val controller = controller(state, targets = targets, upsertRoom = { upsertCalls += 1 })
        val before = state.current

        controller.handleGatewayEvent(joined(room("Old Join", 9), "old-login"))
        controller.handleGatewayEvent(updated(room("Old Rename", 8), "old-login"))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomPresenceReset(10, "old-login", 10))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(room("Unowned", 11), emptyList()))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(
            room("Missing Epoch", 11), emptyList(), accountMemberNumber = 10, selfRuleLogSessionId = "login",
        ))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomUpdated(
            room("Missing Epoch", 8), emptyList(), accountMemberNumber = 10, selfRuleLogSessionId = "login",
        ))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomPresenceReset(10, "login"))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomLeft)

        assertEquals(before, state.current)
        assertEquals("Current", state.lastJoinedRoomName)
        assertEquals(0, upsertCalls)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun opaqueRoomEpochRejectsOldVisitUpdatesAndDuplicateFullSyncs() = runTest {
        val current = room("Current", 8)
        val state = onlineState(current)
        val controller = controller(state)
        val before = state.current

        controller.handleGatewayEvent(updated(room("Old Visit", 7), "login"))
        controller.handleGatewayEvent(joined(current, "login"))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomPresenceReset(10, "login", 7))
        assertEquals(before, state.current)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RoomPresenceReset(10, "login", 9))
        assertNull(state.current.currentRoom)
        assertEquals(9L, state.current.gatewayRoomPresenceEpoch)
        controller.handleGatewayEvent(updated(current, "login"))
        assertNull(state.current.currentRoom)

        controller.handleGatewayEvent(joined(room("Current", 10), "login"))
        assertEquals(10L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(before.roomPresenceEpoch + 1, state.current.roomPresenceEpoch)
    }

    @Test
    fun roomCreationAndSearchShareTheOperationFlight() = runTest {
        val state = onlineState()
        val created = CompletableDeferred<BcRoom>()
        var searchCalls = 0
        val controller = controller(state, FakeGateway(
            create = { created.await() },
            search = { searchCalls += 1; emptyList() },
        ))
        val create = async { controller.createRoom(BcRoomCreateCommand(settings("Created"))) }
        runCurrent()
        val search = async { controller.searchRooms(BcRoomSearchFilter()) }
        runCurrent()

        assertEquals(0, searchCalls)
        assertEquals(BcSessionState.CreatingRoom("Created"), state.current.state)
        created.complete(room("Created", 8))
        create.await()
        search.await()
        assertEquals(1, searchCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun searchCompletesBeforeAQueuedRoomUpdateCanDispatch() = runTest {
        val current = room("Current", 4)
        val state = onlineState(current)
        val searched = CompletableDeferred<List<BcRoomSummary>>()
        val updated = CompletableDeferred<BcRoom>()
        var updateCalls = 0
        val controller = controller(state, FakeGateway(
            search = { searched.await() },
            update = { updateCalls += 1; updated.await() },
        ))
        val search = async { controller.searchRooms(BcRoomSearchFilter()) }
        runCurrent()
        val update = async { controller.updateRoom(update(current, "Renamed")) }
        runCurrent()

        assertEquals(0, updateCalls)
        searched.complete(emptyList())
        search.await()
        runCurrent()
        assertEquals(1, updateCalls)
        assertEquals(BcSessionState.UpdatingRoom("Current"), state.current.state)
        updated.complete(room("Renamed", 4))
        update.await()
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun definiteRejectionPreservesTheSessionAndRecoveryTarget() = runTest {
        val current = room("Current", 4)
        val state = onlineState(current).apply { rememberRoomTarget("Current") }
        val rejection = BcRoomMutationRejectedException(
            BcRoomMutationRejection(BcRoomMutationRejectionReason.ServerRejected, serverCode = "PermissionDenied"),
        )
        var recoveryCalls = 0
        val controller = controller(
            state,
            FakeGateway(update = { throw rejection }),
            failure = { _, _ -> recoveryCalls += 1 },
        )

        assertSame(rejection, runCatching { controller.updateRoom(update(current, "Renamed")) }.exceptionOrNull())
        assertEquals(0, recoveryCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(current, state.current.currentRoom)
        assertEquals("Current", state.lastJoinedRoomName)
    }

    @Test
    fun unknownMutationOutcomeStartsRecoveryWithoutPretendingTheWriteFailed() = runTest {
        val state = onlineState()
        val unknown = BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.TimedOut)
        val reconnecting = BcSessionState.Reconnecting(ReconnectReason.OperationFailed, 1)
        var receivedFailure: Throwable? = null
        val controller = controller(
            state,
            FakeGateway(create = { throw unknown }),
            failure = { _, failure ->
                receivedFailure = failure
                state.updateSession { it.copy(state = reconnecting, connectionHealth = BcConnectionHealth.Offline) }
            },
        )

        assertSame(unknown, runCatching { controller.createRoom(BcRoomCreateCommand(settings("Created"))) }.exceptionOrNull())
        assertSame(unknown, receivedFailure)
        assertEquals(reconnecting, state.current.state)
        assertNull(state.current.currentRoom)
        assertNull(state.lastJoinedRoomName)
    }

    @Test
    fun aQuarantinedResponseChannelStartsRecoveryBeforeAnotherMutationCanBeRetried() = runTest {
        val state = onlineState()
        val rejection = BcRoomMutationRejectedException(
            BcRoomMutationRejection(BcRoomMutationRejectionReason.PendingResponse),
        )
        val reconnecting = BcSessionState.Reconnecting(ReconnectReason.OperationFailed, 1)
        var recoveries = 0
        val controller = controller(
            state,
            FakeGateway(create = { throw rejection }),
            failure = { _, failure ->
                assertSame(rejection, failure)
                recoveries += 1
                state.updateSession { it.copy(state = reconnecting, connectionHealth = BcConnectionHealth.Offline) }
            },
        )

        assertSame(rejection, runCatching { controller.createRoom(BcRoomCreateCommand(settings("Created"))) }.exceptionOrNull())

        assertEquals(1, recoveries)
        assertEquals(reconnecting, state.current.state)
        assertNull(state.current.currentRoom)
        assertNull(state.lastJoinedRoomName)
    }

    @Test
    fun oldLoginCannotFinishItsManagementOperationOrDispatchItsQueuedWork() = runTest {
        val state = onlineState()
        val completed = CompletableDeferred<BcRoom>()
        var createCalls = 0
        var joinCalls = 0
        val controller = controller(state, FakeGateway(
            create = { createCalls += 1; completed.await() },
            join = { joinCalls += 1 },
        ))
        val active = async { controller.createRoom(BcRoomCreateCommand(settings("Created"))) }
        runCurrent()
        val queued = async { controller.joinRoom("Queued") }
        runCurrent()
        val newOperation = BcSessionState.UpdatingRoom("New Login Room")
        state.updateSession { it.copy(selfRuleLogSessionId = "new-login", state = newOperation) }
        state.rememberRoomTarget("New Login Room")

        completed.complete(room("Created", 8))
        active.join()
        queued.join()

        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(1, createCalls)
        assertEquals(0, joinCalls)
        assertSame(newOperation, state.current.state)
        assertEquals("New Login Room", state.lastJoinedRoomName)
    }

    @Test
    fun leavePersistenceWaitsForTheEarlierRenamePersistence() = runTest {
        val current = room("Current", 4)
        val state = onlineState(current).apply { rememberRoomTarget("Current") }
        val renamedPersistence = CompletableDeferred<Unit>()
        val persistedTargets = mutableListOf<String?>()
        val controller = BcSessionRoomsController(
            stateHolder = state,
            gateway = FakeGateway(),
            onOperationFailure = { _, _ -> },
            emitRoomTargetChanged = { target ->
                if (target == "Renamed") renamedPersistence.await()
                persistedTargets += target
            },
            upsertRoomConversation = {},
            upsertRoomState = {},
            upsertSameRoomDirectConversations = {},
        )
        val rename = launch { controller.handleGatewayEvent(updated(room("Renamed", 4), "login")) }
        runCurrent()
        val leave = async { controller.leaveRoom() }
        runCurrent()

        assertTrue(persistedTargets.isEmpty())
        assertNull(state.lastJoinedRoomName)
        renamedPersistence.complete(Unit)
        rename.join()
        leave.await()

        assertEquals(listOf("Renamed", null), persistedTargets)
    }

    @Test
    fun queuedUpdateRechecksTheAdminAndEditedFieldBaselineBeforeDispatch() = runTest {
        for (reason in listOf(BcRoomMutationRejectionReason.NotAdministrator, BcRoomMutationRejectionReason.EditConflict)) {
            val current = room("Current", 4)
            val state = onlineState(current)
            val searched = CompletableDeferred<List<BcRoomSummary>>()
            var updateCalls = 0
            val controller = controller(state, FakeGateway(
                search = { searched.await() },
                update = { updateCalls += 1; current },
            ))
            val command = BcRoomUpdateCommand(
                requireNotNull(current.adminSettings).base,
                requireNotNull(current.adminSettings).base.settings.copy(description = "My edit"),
            )
            val search = async { controller.searchRooms(BcRoomSearchFilter()) }
            runCurrent()
            val update = async { runCatching { controller.updateRoom(command) } }
            runCurrent()
            val changed = when (reason) {
                BcRoomMutationRejectionReason.NotAdministrator -> current.copy(
                    adminSettings = requireNotNull(current.adminSettings).copy(adminMemberNumbers = listOf(20)),
                )
                else -> current.copy(
                    description = "Another edit",
                    adminSettings = requireNotNull(current.adminSettings).let { administration ->
                        administration.copy(base = administration.base.copy(
                            settings = administration.base.settings.copy(description = "Another edit"),
                        ))
                    },
                )
            }
            state.updateSession { it.copy(currentRoom = changed) }
            searched.complete(emptyList())
            search.await()

            val rejected = update.await().exceptionOrNull() as BcRoomMutationRejectedException
            assertEquals(reason, rejected.rejection.reason)
            assertEquals(0, updateCalls)
        }
    }

    @Test
    fun changedVisitAndExistingRoomRejectMutationsBeforeTheGatewayCall() = runTest {
        val current = room("Current", 4)
        val state = onlineState(current)
        var calls = 0
        var recoveries = 0
        val controller = controller(
            state,
            FakeGateway(create = { calls += 1; current }, update = { calls += 1; current }),
            failure = { _, _ -> recoveries += 1 },
        )

        val createFailure = runCatching { controller.createRoom(BcRoomCreateCommand(settings("New"))) }
            .exceptionOrNull() as BcRoomMutationRejectedException
        assertEquals(BcRoomMutationRejectionReason.AlreadyInRoom, createFailure.rejection.reason)

        val command = update(current, "Renamed")
        state.updateSession { it.copy(gatewayRoomPresenceEpoch = 5) }
        val updateFailure = runCatching { controller.updateRoom(command) }
            .exceptionOrNull() as BcRoomMutationRejectedException
        assertEquals(BcRoomMutationRejectionReason.RoomChanged, updateFailure.rejection.reason)
        assertEquals(0, calls)
        assertEquals(0, recoveries)
    }

    @Test
    fun anAcceptedRenameCallsTheConversationTransitionBeforeUpsertingTheNewRoom() = runTest {
        val current = room("Current", 4)
        val renamed = room("Renamed", 4)
        val state = onlineState(current)
        val transitions = mutableListOf<String>()
        val controller = BcSessionRoomsController(
            stateHolder = state,
            gateway = FakeGateway(),
            onOperationFailure = { _, _ -> },
            emitRoomTargetChanged = {},
            upsertRoomConversation = { transitions += "upsert:${it.name.value}" },
            upsertRoomState = {},
            upsertSameRoomDirectConversations = {},
            onRoomRenamed = { before, after -> transitions += "rename:${before.name.value}:${after.name.value}" },
        )

        controller.handleGatewayEvent(updated(renamed, "login"))
        controller.handleGatewayEvent(updated(current, "old-login"))

        assertEquals(listOf("rename:Current:Renamed", "upsert:Renamed"), transitions)
    }

    private fun onlineState(room: BcRoom? = null): BcSessionRuntimeState = BcSessionRuntimeState().apply {
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(10, "Self", "self"),
                relationships = it.relationships.nextLoaded(10, BcSelfRelations()),
                selfRuleLogSessionId = "login",
                currentRoom = room,
                gatewayRoomPresenceEpoch = room?.adminSettings?.base?.roomPresenceEpoch,
            )
        }
    }

    private fun controller(
        state: BcSessionRuntimeState,
        gateway: RoomGateway = FakeGateway(),
        targets: MutableList<String?> = mutableListOf(),
        failure: (ReconnectReason, Throwable?) -> Unit = { _, _ -> },
        upsertRoom: (BcRoom) -> Unit = {},
    ) = BcSessionRoomsController(
        stateHolder = state,
        gateway = gateway,
        onOperationFailure = failure,
        emitRoomTargetChanged = { targets += it },
        upsertRoomConversation = upsertRoom,
        upsertRoomState = {},
        upsertSameRoomDirectConversations = {},
    )

    private class FakeGateway(
        private val search: suspend () -> List<BcRoomSummary> = { emptyList() },
        private val join: suspend (String) -> Unit = {},
        private val create: suspend () -> BcRoom = { error("Unexpected create") },
        private val update: suspend () -> BcRoom = { error("Unexpected update") },
    ) : RoomGateway {
        override suspend fun searchRooms(filter: BcRoomSearchFilter) = search()
        override suspend fun joinRoom(roomName: String, password: String?) = join(roomName)
        override suspend fun leaveRoom() = Unit
        override suspend fun createRoom(command: BcRoomCreateCommand) = create()
        override suspend fun updateRoom(command: BcRoomUpdateCommand) = update()
    }

    private fun settings(name: String) = BcRoomBasicSettings(name = name)

    private fun room(name: String, epoch: Long) = BcRoom(
        name = BcRoomName(name),
        description = "",
        memberCount = 1,
        memberLimit = 10,
        isLocked = false,
        language = "EN",
        adminSettings = BcRoomAdministration(BcRoomEditBase(BcRoomName(name), epoch, settings(name)), listOf(10)),
    )

    private fun update(current: BcRoom, name: String) = BcRoomUpdateCommand(
        requireNotNull(current.adminSettings).base,
        requireNotNull(current.adminSettings).base.settings.copy(name = name),
    )

    private fun joined(room: BcRoom, token: String) = BcSessionGatewayEvent.RoomJoined(
        room,
        emptyList(),
        accountMemberNumber = 10,
        selfRuleLogSessionId = token,
        roomPresenceEpoch = requireNotNull(room.adminSettings).base.roomPresenceEpoch,
    )

    private fun updated(room: BcRoom, token: String) = BcSessionGatewayEvent.RoomUpdated(
        room,
        emptyList(),
        accountMemberNumber = 10,
        selfRuleLogSessionId = token,
        roomPresenceEpoch = requireNotNull(room.adminSettings).base.roomPresenceEpoch,
    )
}
