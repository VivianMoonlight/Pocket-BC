package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
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
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.RoomGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSessionRoomScopeRestorationTest {
    @Test
    fun signedRestorationAdvancesOnlyTheGatewayScopeAndProjectsTheAuthoritativeRoom() = runTest {
        val state = onlineState()
        val operation = BcSessionState.JoiningRoom("B")
        state.updateSession { it.copy(state = operation) }
        val targets = mutableListOf<String?>()
        val renamed = mutableListOf<Pair<BcRoom, BcRoom>>()
        val projected = mutableListOf<BcRoom>()
        val controller = controller(state, targets = targets, renamed = renamed, projected = projected)
        val restored = room(epoch = 6, description = "Current authority")
        val members = members() + BcMember(30, "Arrived", false, role = RoomRole.Member, roomMemberPresenceEpoch = 12)
        val characters = listOf(character(20, "Fresh target"))

        controller.handleGatewayEvent(restoration(restored).copy(members = members, characters = characters))

        assertEquals(restored, state.current.currentRoom)
        assertEquals(members, state.current.roomMembers)
        assertEquals(characters, state.current.roomCharacters)
        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(9L, state.current.roomPresenceEpoch)
        assertSame(operation, state.current.state)
        assertEquals("A", state.lastJoinedRoomName)
        assertEquals("saved-password", state.lastJoinedRoomPassword)
        assertEquals(listOf(restored), projected)
        assertTrue(targets.isEmpty())
        assertTrue(renamed.isEmpty())

        controller.handleGatewayEvent(restoration(room(epoch = 7, description = "Late replay")))

        assertEquals(restored, state.current.currentRoom)
        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(listOf(restored), projected)
    }

    @Test
    fun ordinaryPropertiesCannotAdvanceScopeButStillUpdateWithinTheCurrentScope() = runTest {
        val state = onlineState()
        val projected = mutableListOf<BcRoom>()
        val controller = controller(state, projected = projected)
        val before = state.current

        controller.handleGatewayEvent(restoration(room(epoch = 6)).copy(previousRoomPresenceEpoch = null))

        assertEquals(before, state.current)
        assertTrue(projected.isEmpty())

        val currentProperties = room(epoch = 4, description = "Ordinary properties")
        controller.handleGatewayEvent(restoration(currentProperties).copy(previousRoomPresenceEpoch = null))

        assertEquals(currentProperties, state.current.currentRoom)
        assertEquals(4L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(9L, state.current.roomPresenceEpoch)
        assertEquals(listOf(currentProperties), projected)
    }

    @Test
    fun malformedRestorationTagsAreRejectedEvenWhenTheNextScopeEqualsTheCurrentScope() = runTest {
        val state = onlineState()
        val projected = mutableListOf<BcRoom>()
        val controller = controller(state, projected = projected)
        val before = state.current
        val event = restoration(room(epoch = 6))

        for (invalid in listOf(
            event.copy(previousRoomPresenceEpoch = 3),
            event.copy(roomPresenceEpoch = 4),
            event.copy(roomPresenceEpoch = 3),
            event.copy(roomPresenceEpoch = null),
            event.copy(previousRoomPresenceEpoch = 3, roomPresenceEpoch = 4),
            event.copy(previousRoomPresenceEpoch = 6),
            event.copy(previousRoomName = BcRoomName("Wrong previous room")),
            event.copy(previousRoomPresenceEpoch = null, previousRoomName = BcRoomName("A"), roomPresenceEpoch = 4),
        )) {
            controller.handleGatewayEvent(invalid)
            assertEquals(invalid.toString(), before, state.current)
        }

        assertTrue(projected.isEmpty())
    }

    @Test
    fun restorationRequiresTheExactOwnerLoginAndExistingRoomName() = runTest {
        val state = onlineState()
        val targets = mutableListOf<String?>()
        val projected = mutableListOf<BcRoom>()
        val controller = controller(state, targets = targets, projected = projected)
        val before = state.current
        val event = restoration(room(epoch = 6))

        for (invalid in listOf(
            event.copy(accountMemberNumber = 30),
            event.copy(accountMemberNumber = null),
            event.copy(selfRuleLogSessionId = "new-login"),
            event.copy(selfRuleLogSessionId = null),
            event.copy(room = room(name = "B", epoch = 6)),
        )) {
            controller.handleGatewayEvent(invalid)
            assertEquals(invalid.toString(), before, state.current)
        }

        assertEquals("A", state.lastJoinedRoomName)
        assertTrue(targets.isEmpty())
        assertTrue(projected.isEmpty())
    }

    @Test
    fun anOldSignedRestorationCannotAffectAReplacementAccountOrLogin() = runTest {
        for (changeAccount in listOf(false, true)) {
            val state = onlineState()
            val controller = controller(state)
            val oldEvent = restoration(room(epoch = 6))
            state.updateSession {
                if (changeAccount) it.copy(
                    account = BcAccount(30, "Other", "other"),
                    relationships = it.relationships.nextLoaded(30, BcSelfRelations()),
                    selfRuleLogSessionId = "other-login",
                ) else it.copy(selfRuleLogSessionId = "new-login")
            }
            state.rememberRoomTarget("Replacement target")
            val before = state.current

            controller.handleGatewayEvent(oldEvent)

            assertEquals(before, state.current)
            assertEquals("Replacement target", state.lastJoinedRoomName)
        }
    }

    @Test
    fun restorationPreservesTheSavedReplacementTargetAndTheJoinOperation() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("B", "replacement-password")
        }
        val operation = BcSessionState.JoiningRoom("B")
        state.updateSession { it.copy(state = operation) }
        val targets = mutableListOf<String?>()
        val renamed = mutableListOf<Pair<BcRoom, BcRoom>>()
        val controller = controller(state, targets = targets, renamed = renamed)

        controller.handleGatewayEvent(restoration(room(epoch = 6)))

        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals("A", state.current.currentRoom?.name?.value)
        assertSame(operation, state.current.state)
        assertEquals("B", state.lastJoinedRoomName)
        assertEquals("replacement-password", state.lastJoinedRoomPassword)
        assertTrue(targets.isEmpty())
        assertTrue(renamed.isEmpty())
    }

    @Test
    fun restoringAPendingRenameMovesItsMatchingRecoveryTargetWithoutChangingTheLocalVisitOrBusyState() = runTest {
        val state = onlineState()
        val original = requireNotNull(state.current.currentRoom)
        val operation = BcSessionState.UpdatingRoom("A")
        state.updateSession { it.copy(state = operation) }
        val targets = mutableListOf<String?>()
        val renamed = mutableListOf<Pair<BcRoom, BcRoom>>()
        val projected = mutableListOf<BcRoom>()
        val controller = controller(state, targets = targets, renamed = renamed, projected = projected)
        val authority = room(name = "C", epoch = 6, description = "Renamed authority")

        controller.handleGatewayEvent(restoration(authority).copy(previousRoomName = BcRoomName("A")))

        assertEquals(authority, state.current.currentRoom)
        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(9L, state.current.roomPresenceEpoch)
        assertSame(operation, state.current.state)
        assertEquals(listOf(original to authority), renamed)
        assertEquals(listOf(authority), projected)
        assertEquals("C", state.lastJoinedRoomName)
        assertEquals("saved-password", state.lastJoinedRoomPassword)
        assertEquals(listOf("C"), targets)
    }

    @Test
    fun restoringAPendingRenameKeepsTheReplacementTargetWhileStillPublishingTheAuthoritativeRename() = runTest {
        val state = onlineState().apply {
            rememberRoomTarget("B", "replacement-password")
        }
        val original = requireNotNull(state.current.currentRoom)
        val operation = BcSessionState.JoiningRoom("B")
        state.updateSession { it.copy(state = operation) }
        val targets = mutableListOf<String?>()
        val renamed = mutableListOf<Pair<BcRoom, BcRoom>>()
        val controller = controller(state, targets = targets, renamed = renamed)
        val authority = room(name = "C", epoch = 6)

        controller.handleGatewayEvent(restoration(authority).copy(previousRoomName = BcRoomName("A")))

        assertEquals(authority, state.current.currentRoom)
        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(9L, state.current.roomPresenceEpoch)
        assertSame(operation, state.current.state)
        assertEquals(listOf(original to authority), renamed)
        assertEquals("B", state.lastJoinedRoomName)
        assertEquals("replacement-password", state.lastJoinedRoomPassword)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun commandsCapturedBeforeRestorationCannotDispatchAgainstTheRenewedScope() = runTest {
        val state = onlineState()
        val oldRoom = requireNotNull(state.current.currentRoom)
        var updates = 0
        var memberCommands = 0
        val controller = controller(state, FakeGateway(
            update = { command -> updates += 1; room(epoch = command.base.roomPresenceEpoch) },
            manage = { memberCommands += 1 },
        ))

        controller.handleGatewayEvent(restoration(room(epoch = 6)))

        val updateRejected = runCatching { controller.updateRoom(updateCommand(oldRoom)) }
            .exceptionOrNull() as BcRoomMutationRejectedException
        val memberRejected = runCatching { controller.manageRoomMember(memberCommand(epoch = 4)) }
            .exceptionOrNull() as BcRoomMemberAdminRejectedException

        assertEquals(BcRoomMutationRejectionReason.RoomChanged, updateRejected.rejection.reason)
        assertEquals(BcRoomMemberAdminRejectionReason.RoomChanged, memberRejected.reason)
        assertEquals(0, updates)
        assertEquals(0, memberCommands)
    }

    @Test
    fun freshBasicAndMemberCommandsCanUseTheRestoredScope() = runTest {
        val state = onlineState()
        val updates = mutableListOf<BcRoomUpdateCommand>()
        val memberCommands = mutableListOf<BcRoomMemberAdminCommand>()
        lateinit var controller: BcSessionRoomsController
        controller = controller(state, FakeGateway(
            update = { command ->
                updates += command
                val authority = room(epoch = 6, description = command.settings.description)
                controller.handleGatewayEvent(restoration(authority).copy(previousRoomPresenceEpoch = null))
                authority
            },
            manage = { command ->
                memberCommands += command
                controller.handleGatewayEvent(restoration(requireNotNull(state.current.currentRoom)).copy(
                    members = listOf(self(), target().copy(role = RoomRole.Admin)),
                    previousRoomPresenceEpoch = null,
                ))
            },
        ))
        controller.handleGatewayEvent(restoration(room(epoch = 6)))
        val freshUpdate = updateCommand(requireNotNull(state.current.currentRoom))

        val updated = controller.updateRoom(freshUpdate)
        val promoted = controller.manageRoomMember(memberCommand(epoch = 6))

        assertEquals("My edit", updated.description)
        assertEquals(listOf(freshUpdate), updates)
        assertEquals(BcRoomMemberAdminObservation.TargetPromoted, promoted)
        assertEquals(listOf(memberCommand(epoch = 6)), memberCommands)
        assertEquals(6L, state.current.gatewayRoomPresenceEpoch)
        assertEquals(9L, state.current.roomPresenceEpoch)
        assertEquals(BcSessionState.Ready, state.current.state)
    }

    private fun onlineState() = BcSessionRuntimeState().apply {
        rememberRoomTarget("A", "saved-password")
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(10, "Self", "self"),
                relationships = it.relationships.nextLoaded(10, BcSelfRelations()),
                selfRuleLogSessionId = "login",
                currentRoom = room(),
                gatewayRoomPresenceEpoch = 4,
                roomPresenceEpoch = 9,
                roomMembers = members(),
            )
        }
    }

    private fun controller(
        state: BcSessionRuntimeState,
        gateway: RoomGateway = FakeGateway(),
        targets: MutableList<String?> = mutableListOf(),
        renamed: MutableList<Pair<BcRoom, BcRoom>> = mutableListOf(),
        projected: MutableList<BcRoom> = mutableListOf(),
    ) = BcSessionRoomsController(
        stateHolder = state,
        gateway = gateway,
        onOperationFailure = { _, _ -> },
        emitRoomTargetChanged = { targets += it },
        upsertRoomConversation = { projected += it },
        upsertRoomState = {},
        upsertSameRoomDirectConversations = {},
        onRoomRenamed = { before, after -> renamed += before to after },
        roomMemberObservationTimeoutMillis = 100,
    )

    private class FakeGateway(
        private val update: suspend (BcRoomUpdateCommand) -> BcRoom = { error("Unexpected update") },
        private val manage: suspend (BcRoomMemberAdminCommand) -> Unit = { error("Unexpected member command") },
    ) : RoomGateway {
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun updateRoom(command: BcRoomUpdateCommand) = update(command)
        override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand) = manage(command)
    }

    private fun room(name: String = "A", epoch: Long = 4, description: String = "") = BcRoom(
        name = BcRoomName(name),
        description = description,
        memberCount = 2,
        memberLimit = 10,
        isLocked = false,
        language = "EN",
        adminSettings = BcRoomAdministration(
            base = BcRoomEditBase(BcRoomName(name), epoch, BcRoomBasicSettings(name = name, description = description)),
            adminMemberNumbers = listOf(10),
        ),
    )

    private fun restoration(currentRoom: BcRoom) = BcSessionGatewayEvent.RoomUpdated(
        room = currentRoom,
        members = members(),
        accountMemberNumber = 10,
        selfRuleLogSessionId = "login",
        roomPresenceEpoch = requireNotNull(currentRoom.adminSettings).base.roomPresenceEpoch,
        previousRoomPresenceEpoch = 4,
    )

    private fun updateCommand(currentRoom: BcRoom) = BcRoomUpdateCommand(
        base = requireNotNull(currentRoom.adminSettings).base,
        settings = requireNotNull(currentRoom.adminSettings).base.settings.copy(description = "My edit"),
    )

    private fun memberCommand(epoch: Long) = BcRoomMemberAdminCommand(
        roomName = BcRoomName("A"),
        roomPresenceEpoch = epoch,
        targetMemberNumber = 20,
        targetPresenceEpoch = 7,
        action = BcRoomMemberAdminAction.Promote,
        targetIsAdministrator = false,
    )

    private fun members() = listOf(self(), target())

    private fun self() = BcMember(10, "Self", true, role = RoomRole.Admin, roomMemberPresenceEpoch = 1)

    private fun target() = BcMember(20, "Target", false, role = RoomRole.Member, roomMemberPresenceEpoch = 7)

    private fun character(number: Int, displayName: String) = BcCharacterSnapshot(
        id = number.toString(),
        memberNumber = number,
        name = "Target",
        displayName = displayName,
        isSelf = false,
    )
}
