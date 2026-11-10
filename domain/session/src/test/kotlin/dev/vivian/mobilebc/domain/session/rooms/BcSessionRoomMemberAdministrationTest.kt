package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomRemovalReason
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.RoomRole
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionRoomMemberAdministrationTest {
    @Test
    fun kickWaitsForAnAuthoritativeDepartureWithoutRemovingTheMemberOptimistically() = runTest {
        val current = room()
        val state = onlineState(current)
        val sent = mutableListOf<BcRoomMemberAdminCommand>()
        val controller = controller(state, FakeGateway(manage = { sent += it }))
        val command = command(BcRoomMemberAdminAction.Kick)

        val request = async { controller.manageRoomMember(command) }
        runCurrent()

        assertEquals(listOf(command), sent)
        assertEquals(BcSessionState.ManagingRoomMember("Current", 20), state.current.state)
        assertEquals(listOf(10, 20), state.current.roomMembers.map { it.memberNumber })
        assertFalse(request.isCompleted)

        controller.handleGatewayEvent(updated(current, listOf(self())))

        assertEquals(BcRoomMemberAdminObservation.TargetLeft, request.await())
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(listOf(10), state.current.roomMembers.map { it.memberNumber })
    }

    @Test
    fun banRequiresBothAnAuthoritativeDepartureAndAnAuthoritativeBanEntry() = runTest {
        val state = onlineState()
        val controller = controller(state)
        val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Ban)) }
        runCurrent()

        controller.handleGatewayEvent(updated(room(), listOf(self())))
        runCurrent()
        assertFalse(request.isCompleted)
        assertEquals(emptyList<Int>(), state.current.currentRoom?.banMemberNumbers)

        controller.handleGatewayEvent(updated(room(bans = listOf(20)), listOf(self())))

        assertEquals(BcRoomMemberAdminObservation.TargetBanned, request.await())
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun aBanEntryAloneDoesNotReportThatThePresentTargetHasBeenBanned() = runTest {
        val state = onlineState()
        val controller = controller(state)
        val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Ban)) }
        runCurrent()

        controller.handleGatewayEvent(updated(room(bans = listOf(20)), listOf(self(), target())))
        runCurrent()
        assertFalse(request.isCompleted)

        controller.handleGatewayEvent(updated(room(bans = listOf(20)), listOf(self())))

        assertEquals(BcRoomMemberAdminObservation.TargetBanned, request.await())
    }

    @Test
    fun aDepartureWithoutReliableBanMetadataTimesOutAsUnconfirmed() = runTest {
        val state = onlineState()
        var recoveries = 0
        val controller = controller(state, failure = { _, _ -> recoveries += 1 })
        val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Ban)) }
        runCurrent()

        controller.handleGatewayEvent(updated(room(bans = null), listOf(self())))
        runCurrent()
        assertFalse(request.isCompleted)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(BcRoomMemberAdminObservation.Unconfirmed, request.await())
        assertEquals(0, recoveries)
        assertNull(state.current.currentRoom?.banMemberNumbers)
    }

    @Test
    fun promoteAndDemoteWaitForAuthoritativeRoleUpdatesForTheSameTargetPresence() = runTest {
        for (action in listOf(BcRoomMemberAdminAction.Promote, BcRoomMemberAdminAction.Demote)) {
            val wasAdministrator = action == BcRoomMemberAdminAction.Demote
            val current = room(admins = if (wasAdministrator) listOf(10, 20) else listOf(10))
            val state = onlineState(current)
            val controller = controller(state)
            val request = async { controller.manageRoomMember(command(action, targetIsAdministrator = wasAdministrator)) }
            runCurrent()

            val unchangedRole = target().copy(role = if (wasAdministrator) RoomRole.Admin else RoomRole.Member)
            controller.handleGatewayEvent(updated(current, listOf(self(), unchangedRole)))
            runCurrent()
            assertFalse(action.name, request.isCompleted)

            val changed = room(admins = if (wasAdministrator) listOf(10) else listOf(10, 20))
            val changedRole = target().copy(role = if (wasAdministrator) RoomRole.Member else RoomRole.Admin)
            controller.handleGatewayEvent(updated(changed, listOf(self(), changedRole)))

            assertEquals(
                action.name,
                if (wasAdministrator) BcRoomMemberAdminObservation.TargetDemoted else BcRoomMemberAdminObservation.TargetPromoted,
                request.await(),
            )
            assertEquals(action.name, BcSessionState.Ready, state.current.state)
        }
    }

    @Test
    fun anAuthoritativeSyncReceivedBeforeTheTransportReturnsStillCompletesTheObservation() = runTest {
        val state = onlineState()
        lateinit var controller: BcSessionRoomsController
        controller = controller(state, FakeGateway(manage = {
            controller.handleGatewayEvent(updated(room(), listOf(self())))
        }))

        assertEquals(
            BcRoomMemberAdminObservation.TargetLeft,
            controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)),
        )
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun missingSyncReturnsUnconfirmedWithoutRecoveryResendingOrInventingState() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Current") }
        var sends = 0
        var recoveries = 0
        val controller = controller(
            state,
            FakeGateway(manage = { sends += 1 }),
            failure = { _, _ -> recoveries += 1 },
        )
        val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertFalse(request.isCompleted)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(BcRoomMemberAdminObservation.Unconfirmed, request.await())
        assertEquals(1, sends)
        assertEquals(0, recoveries)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(BcConnectionHealth.VerifiedOnline, state.current.connectionHealth)
        assertEquals("Current", state.lastJoinedRoomName)
        assertEquals(listOf(10, 20), state.current.roomMembers.map { it.memberNumber })
    }

    @Test
    fun memberAdministrationAndSearchShareTheSameOperationFlight() = runTest {
        val state = onlineState()
        var searchCalls = 0
        val controller = controller(state, FakeGateway(search = { searchCalls += 1; emptyList() }))
        val administration = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
        runCurrent()
        val search = async { controller.searchRooms(BcRoomSearchFilter()) }
        runCurrent()

        assertEquals(0, searchCalls)
        assertEquals(BcSessionState.ManagingRoomMember("Current", 20), state.current.state)

        controller.handleGatewayEvent(updated(room(), listOf(self())))

        assertEquals(BcRoomMemberAdminObservation.TargetLeft, administration.await())
        search.await()
        assertEquals(1, searchCalls)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    @Test
    fun queuedMemberWorkRechecksAdministratorTargetPresenceAndRoleAfterTheEarlierOperation() = runTest {
        for (reason in listOf(
            BcRoomMemberAdminRejectionReason.NotAdministrator,
            BcRoomMemberAdminRejectionReason.TargetUnavailable,
            BcRoomMemberAdminRejectionReason.TargetChanged,
            BcRoomMemberAdminRejectionReason.TargetRoleChanged,
        )) {
            val state = onlineState()
            val searched = CompletableDeferred<List<BcRoomSummary>>()
            var sends = 0
            val controller = controller(state, FakeGateway(
                search = { searched.await() },
                manage = { sends += 1 },
            ))
            val search = async { controller.searchRooms(BcRoomSearchFilter()) }
            runCurrent()
            val request = async { runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) } }
            runCurrent()

            when (reason) {
                BcRoomMemberAdminRejectionReason.NotAdministrator -> state.updateSession {
                    it.copy(roomMembers = listOf(self().copy(role = RoomRole.Member), target()))
                }
                BcRoomMemberAdminRejectionReason.TargetUnavailable -> state.updateSession {
                    it.copy(roomMembers = listOf(self()))
                }
                BcRoomMemberAdminRejectionReason.TargetChanged -> state.updateSession {
                    it.copy(roomMembers = listOf(self(), target(epoch = 8)))
                }
                else -> state.updateSession { it.copy(roomMembers = listOf(self(), target().copy(role = RoomRole.Admin))) }
            }
            searched.complete(emptyList())
            search.await()

            val rejected = request.await().exceptionOrNull() as BcRoomMemberAdminRejectedException
            assertEquals(reason.name, reason, rejected.reason)
            assertEquals(reason.name, 0, sends)
            assertEquals(reason.name, BcSessionState.Ready, state.current.state)
        }
    }

    @Test
    fun selfTargetsAndInvalidRoleTransitionsAreRejectedBeforeTheGatewayCall() = runTest {
        val state = onlineState()
        var sends = 0
        var recoveries = 0
        val controller = controller(
            state,
            FakeGateway(manage = { sends += 1 }),
            failure = { _, _ -> recoveries += 1 },
        )

        val selfFailure = runCatching {
            controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick).copy(targetMemberNumber = 10, targetPresenceEpoch = 1))
        }.exceptionOrNull() as BcRoomMemberAdminRejectedException
        assertEquals(BcRoomMemberAdminRejectionReason.SelfTarget, selfFailure.reason)

        val demoteFailure = runCatching {
            controller.manageRoomMember(command(BcRoomMemberAdminAction.Demote))
        }.exceptionOrNull() as BcRoomMemberAdminRejectedException
        assertEquals(BcRoomMemberAdminRejectionReason.TargetRoleChanged, demoteFailure.reason)

        state.updateSession { it.copy(roomMembers = listOf(self(), target().copy(role = RoomRole.Admin))) }
        val promoteFailure = runCatching {
            controller.manageRoomMember(command(BcRoomMemberAdminAction.Promote, targetIsAdministrator = true))
        }.exceptionOrNull() as BcRoomMemberAdminRejectedException
        assertEquals(BcRoomMemberAdminRejectionReason.TargetRoleChanged, promoteFailure.reason)
        assertEquals(0, sends)
        assertEquals(0, recoveries)
    }

    @Test
    fun staleRoomNameVisitAndUnknownRolesRejectBeforeDispatch() = runTest {
        for (reason in listOf(
            BcRoomMemberAdminRejectionReason.RoomUnavailable,
            BcRoomMemberAdminRejectionReason.RoomChanged,
            BcRoomMemberAdminRejectionReason.SettingsUnavailable,
        )) {
            val state = onlineState()
            var sends = 0
            val controller = controller(state, FakeGateway(manage = { sends += 1 }))
            when (reason) {
                BcRoomMemberAdminRejectionReason.RoomUnavailable -> state.updateSession { it.copy(currentRoom = null) }
                BcRoomMemberAdminRejectionReason.RoomChanged -> state.updateSession { it.copy(gatewayRoomPresenceEpoch = 5) }
                else -> state.updateSession { it.copy(roomMembers = listOf(self(), target().copy(role = null))) }
            }

            val rejected = runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
                .exceptionOrNull() as BcRoomMemberAdminRejectedException

            assertEquals(reason.name, reason, rejected.reason)
            assertEquals(reason.name, 0, sends)
        }

        val state = onlineState(room(name = "Different"))
        var sends = 0
        val controller = controller(state, FakeGateway(manage = { sends += 1 }))
        val rejected = runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
            .exceptionOrNull() as BcRoomMemberAdminRejectedException
        assertEquals(BcRoomMemberAdminRejectionReason.RoomChanged, rejected.reason)
        assertEquals(0, sends)
    }

    @Test
    fun missingFullRoomEditorSettingsDoesNotPreventMemberAdministration() = runTest {
        val state = onlineState().apply {
            updateSession { it.copy(currentRoom = room().copy(adminSettings = null)) }
        }
        lateinit var controller: BcSessionRoomsController
        controller = controller(state, FakeGateway(manage = {
            controller.handleGatewayEvent(updated(room().copy(adminSettings = null), listOf(self())))
        }))

        assertEquals(
            BcRoomMemberAdminObservation.TargetLeft,
            controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)),
        )
    }

    @Test
    fun aReenteredTargetReturnsUnconfirmedWithoutApplyingTheNewPresenceRoleToTheOldRequest() = runTest {
        for (action in BcRoomMemberAdminAction.entries) {
            val targetWasAdministrator = action == BcRoomMemberAdminAction.Demote
            val state = onlineState(room(admins = if (targetWasAdministrator) listOf(10, 20) else listOf(10)))
            val controller = controller(state)
            val request = async {
                controller.manageRoomMember(command(action, targetIsAdministrator = targetWasAdministrator))
            }
            runCurrent()

            val newTarget = target(epoch = 8).copy(role = if (action == BcRoomMemberAdminAction.Promote) RoomRole.Admin else RoomRole.Member)
            controller.handleGatewayEvent(updated(room(bans = listOf(20)), listOf(self(), newTarget)))
            runCurrent()

            assertTrue(action.name, request.isCompleted)
            assertEquals(action.name, BcRoomMemberAdminObservation.Unconfirmed, request.await())
            assertEquals(action.name, 8L, state.current.roomMembers.single { it.memberNumber == 20 }.roomMemberPresenceEpoch)
        }
    }

    @Test
    fun untrustedOwnOrTargetRolesCannotAuthorizeACommand() = runTest {
        for (invalidRole in listOf(null, RoomRole.Owner)) {
            for (invalidSelf in listOf(true, false)) {
                val state = onlineState().apply {
                    updateSession {
                        it.copy(roomMembers = listOf(
                            self().copy(role = if (invalidSelf) invalidRole else RoomRole.Admin),
                            target().copy(role = if (invalidSelf) RoomRole.Member else invalidRole),
                        ))
                    }
                }
                var sends = 0
                val controller = controller(state, FakeGateway(manage = { sends += 1 }))

                val rejected = runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
                    .exceptionOrNull() as BcRoomMemberAdminRejectedException

                assertEquals(BcRoomMemberAdminRejectionReason.SettingsUnavailable, rejected.reason)
                assertEquals(0, sends)
            }
        }
    }

    @Test
    fun aNewLoginOrVisitCancelsOldObservationWithoutClearingTheNewBusyState() = runTest {
        for (change in listOf("account", "login", "visit", "room")) {
            val state = onlineState().apply { rememberRoomTarget("Current") }
            var recoveries = 0
            val controller = controller(state, failure = { _, _ -> recoveries += 1 })
            val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
            runCurrent()
            val newBusy = BcSessionState.JoiningRoom("New target")
            state.updateSession {
                when (change) {
                    "account" -> it.copy(
                        account = BcAccount(30, "Other", "other"),
                        relationships = it.relationships.nextLoaded(30, BcSelfRelations()),
                        state = newBusy,
                    )
                    "login" -> it.copy(selfRuleLogSessionId = "new-login", state = newBusy)
                    "visit" -> it.copy(gatewayRoomPresenceEpoch = 5, state = newBusy)
                    else -> it.copy(currentRoom = room(name = "Other"), state = newBusy)
                }
            }
            state.rememberRoomTarget("New target")
            runCurrent()
            request.join()

            assertTrue(change, request.isCancelled)
            assertSame(change, newBusy, state.current.state)
            assertEquals(change, "New target", state.lastJoinedRoomName)
            assertEquals(change, 0, recoveries)
        }
    }

    @Test
    fun cancelledMemberAdministrationDoesNotReconnectAndReleasesOnlyItsOwnBusyState() = runTest {
        val state = onlineState()
        var recoveries = 0
        val controller = controller(state, failure = { _, _ -> recoveries += 1 })
        val request = async { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }
        runCurrent()

        request.cancelAndJoin()

        assertEquals(0, recoveries)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(listOf(10, 20), state.current.roomMembers.map { it.memberNumber })
    }

    @Test
    fun aDefiniteGatewayRejectionPreservesOnlineStateAndItsRecoveryTarget() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Current") }
        val rejected = BcRoomMemberAdminRejectedException(BcRoomMemberAdminRejectionReason.NotAdministrator)
        var recoveries = 0
        val controller = controller(
            state,
            FakeGateway(manage = { throw rejected }),
            failure = { _, _ -> recoveries += 1 },
        )

        assertSame(rejected, runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }.exceptionOrNull())
        assertEquals(0, recoveries)
        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals("Current", state.lastJoinedRoomName)
        assertEquals(BcConnectionHealth.VerifiedOnline, state.current.connectionHealth)
    }

    @Test
    fun aPendingResponseStartsRecoveryWithoutItsCleanupRestoringReady() = runTest {
        val state = onlineState()
        val rejected = BcRoomMemberAdminRejectedException(BcRoomMemberAdminRejectionReason.PendingResponse)
        val recovering = BcSessionState.Reconnecting(ReconnectReason.OperationFailed, 1)
        var received: Throwable? = null
        val controller = controller(
            state,
            FakeGateway(manage = { throw rejected }),
            failure = { _, throwable ->
                received = throwable
                state.updateSession { it.copy(state = recovering, connectionHealth = BcConnectionHealth.Offline) }
            },
        )

        assertSame(rejected, runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }.exceptionOrNull())
        assertSame(rejected, received)
        assertEquals(recovering, state.current.state)
    }

    @Test
    fun transportFailureStartsRecoveryAndItsFinallyDoesNotRestoreReady() = runTest {
        val state = onlineState()
        val failure = IllegalStateException("Transport closed")
        val recovering = BcSessionState.Reconnecting(ReconnectReason.OperationFailed, 1)
        var received: Throwable? = null
        val controller = controller(
            state,
            FakeGateway(manage = { throw failure }),
            failure = { _, throwable ->
                received = throwable
                state.updateSession { it.copy(state = recovering, connectionHealth = BcConnectionHealth.Offline) }
            },
        )

        assertSame(failure, runCatching { controller.manageRoomMember(command(BcRoomMemberAdminAction.Kick)) }.exceptionOrNull())
        assertSame(failure, received)
        assertEquals(recovering, state.current.state)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
    }

    @Test
    fun kickedAndBannedEventsClearCurrentPresenceAndTheMatchingRecoveryTarget() = runTest {
        for (reason in BcRoomRemovalReason.entries) {
            val state = onlineState().apply {
                rememberRoomTarget("Current", "saved-password")
            }
            val targets = mutableListOf<String?>()
            val directPresence = mutableListOf<List<BcMember>>()
            val removedRooms = mutableListOf<BcRoom>()
            val controller = controller(state, targets = targets, directPresence = directPresence, removedRooms = removedRooms)

            controller.handleGatewayEvent(removed(reason = reason))

            assertNull(reason.name, state.current.currentRoom)
            assertTrue(reason.name, state.current.roomMembers.isEmpty())
            assertTrue(reason.name, state.current.roomCharacters.isEmpty())
            assertEquals(reason.name, 5L, state.current.gatewayRoomPresenceEpoch)
            assertNull(reason.name, state.lastJoinedRoomName)
            assertNull(reason.name, state.lastJoinedRoomPassword)
            assertEquals(reason.name, listOf<String?>(null), targets)
            assertEquals(reason.name, listOf(emptyList<BcMember>()), directPresence)
            assertEquals(reason.name, listOf(room()), removedRooms)
        }
    }

    @Test
    fun roomRemovalDoesNotClearARecoveryTargetThatAlreadyBelongsToAnotherRoom() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("New target", "new-password")
        }
        val targets = mutableListOf<String?>()
        val controller = controller(state, targets = targets)

        controller.handleGatewayEvent(removed())

        assertNull(state.current.currentRoom)
        assertEquals("New target", state.lastJoinedRoomName)
        assertEquals("new-password", state.lastJoinedRoomPassword)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun queuedRemovalPersistenceCannotEraseTheTargetOfANewerAuthoritativeJoin() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Current") }
        val renamePersistence = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String?>()
        val controller = BcSessionRoomsController(
            stateHolder = state,
            gateway = FakeGateway(),
            onOperationFailure = { _, _ -> },
            emitRoomTargetChanged = {
                if (it == "Renamed") renamePersistence.await()
                persisted += it
            },
            upsertRoomConversation = {},
            upsertRoomState = {},
            upsertSameRoomDirectConversations = {},
            roomMemberObservationTimeoutMillis = 100,
        )
        val rename = async {
            controller.handleGatewayEvent(updated(room(name = "Renamed"), listOf(self(), target())))
        }
        runCurrent()
        val removal = async {
            controller.handleGatewayEvent(removed().copy(roomName = BcRoomName("Renamed")))
        }
        runCurrent()
        val nextRoom = room(name = "Next", epoch = 6)
        val joined = async {
            controller.handleGatewayEvent(BcSessionGatewayEvent.RoomJoined(
                room = nextRoom,
                members = listOf(self(), target()),
                accountMemberNumber = 10,
                selfRuleLogSessionId = "login",
                roomPresenceEpoch = 6,
            ))
        }
        runCurrent()
        assertEquals("Next", state.lastJoinedRoomName)

        renamePersistence.complete(Unit)
        rename.await()
        removal.await()
        joined.await()

        assertEquals(listOf("Renamed", "Next"), persisted)
        assertEquals("Next", state.lastJoinedRoomName)
        assertEquals(nextRoom, state.current.currentRoom)
    }

    @Test
    fun staleRemovalEventsCannotClearTheCurrentAccountOrANewerSameNameVisit() = runTest {
        val state = onlineState().apply { rememberRoomTarget("Current") }
        val targets = mutableListOf<String?>()
        val removedRooms = mutableListOf<BcRoom>()
        val controller = controller(state, targets = targets, removedRooms = removedRooms)
        val before = state.current

        for (event in listOf(
            removed().copy(accountMemberNumber = 30),
            removed().copy(selfRuleLogSessionId = "old-login"),
            removed().copy(roomName = BcRoomName("Old room")),
            removed().copy(previousRoomPresenceEpoch = 3),
            removed().copy(roomPresenceEpoch = 4),
        )) {
            controller.handleGatewayEvent(event)
            assertEquals(event.toString(), before, state.current)
        }
        assertEquals("Current", state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())
        assertTrue(removedRooms.isEmpty())
    }

    private fun onlineState(currentRoom: BcRoom = room()) = BcSessionRuntimeState().apply {
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(10, "Self", "self"),
                relationships = it.relationships.nextLoaded(10, BcSelfRelations()),
                selfRuleLogSessionId = "login",
                currentRoom = currentRoom,
                roomPresenceEpoch = 1,
                gatewayRoomPresenceEpoch = requireNotNull(currentRoom.adminSettings).base.roomPresenceEpoch,
                roomMembers = listOf(
                    self(),
                    target().copy(role = if (20 in requireNotNull(currentRoom.adminSettings).adminMemberNumbers) RoomRole.Admin else RoomRole.Member),
                ),
            )
        }
    }

    private fun controller(
        state: BcSessionRuntimeState,
        gateway: RoomGateway = FakeGateway(),
        targets: MutableList<String?> = mutableListOf(),
        directPresence: MutableList<List<BcMember>> = mutableListOf(),
        removedRooms: MutableList<BcRoom> = mutableListOf(),
        failure: (ReconnectReason, Throwable?) -> Unit = { _, _ -> },
    ) = BcSessionRoomsController(
        stateHolder = state,
        gateway = gateway,
        onOperationFailure = failure,
        emitRoomTargetChanged = { targets += it },
        upsertRoomConversation = {},
        upsertRoomState = {},
        upsertSameRoomDirectConversations = { directPresence += it.roomMembers },
        roomMemberObservationTimeoutMillis = 100,
        onRoomRemoved = { removedRooms += it },
    )

    private class FakeGateway(
        private val manage: suspend (BcRoomMemberAdminCommand) -> Unit = {},
        private val search: suspend () -> List<BcRoomSummary> = { emptyList() },
    ) : RoomGateway {
        override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand) = manage(command)
        override suspend fun searchRooms(filter: BcRoomSearchFilter) = search()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
    }

    private fun command(
        action: BcRoomMemberAdminAction,
        targetIsAdministrator: Boolean = false,
    ) = BcRoomMemberAdminCommand(
        roomName = BcRoomName("Current"),
        roomPresenceEpoch = 4,
        targetMemberNumber = 20,
        targetPresenceEpoch = 7,
        action = action,
        targetIsAdministrator = targetIsAdministrator,
    )

    private fun room(
        name: String = "Current",
        epoch: Long = 4,
        admins: List<Int> = listOf(10),
        bans: List<Int>? = emptyList(),
    ) = BcRoom(
        name = BcRoomName(name),
        description = "",
        memberCount = 2,
        memberLimit = 10,
        isLocked = false,
        language = "EN",
        adminSettings = BcRoomAdministration(
            BcRoomEditBase(BcRoomName(name), epoch, BcRoomBasicSettings(name = name)),
            adminMemberNumbers = admins,
        ),
        banMemberNumbers = bans,
    )

    private fun self() = BcMember(10, "Self", isSelf = true, role = RoomRole.Admin, roomMemberPresenceEpoch = 1)

    private fun target(epoch: Long = 7) = BcMember(20, "Target", isSelf = false, role = RoomRole.Member, roomMemberPresenceEpoch = epoch)

    private fun updated(currentRoom: BcRoom, members: List<BcMember>) = BcSessionGatewayEvent.RoomUpdated(
        room = currentRoom,
        members = members,
        accountMemberNumber = 10,
        selfRuleLogSessionId = "login",
        roomPresenceEpoch = currentRoom.adminSettings?.base?.roomPresenceEpoch ?: 4,
    )

    private fun removed(reason: BcRoomRemovalReason = BcRoomRemovalReason.Kicked) = BcSessionGatewayEvent.RoomRemoved(
        roomName = BcRoomName("Current"),
        reason = reason,
        accountMemberNumber = 10,
        selfRuleLogSessionId = "login",
        previousRoomPresenceEpoch = 4,
        roomPresenceEpoch = 5,
    )
}
