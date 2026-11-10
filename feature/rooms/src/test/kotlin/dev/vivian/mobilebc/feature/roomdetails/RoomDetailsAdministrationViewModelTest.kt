package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomBasicField
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationFormFailure
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationFormState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsAdministrationViewModelTest {
    @Test
    fun onlyVerifiedCurrentAdministratorsCanOpenSettings() = withFixture {
        assertTrue(viewModel.uiState.value.canEditRoomSettings)

        val adminRoom = requireNotNull(connection.mutableSnapshot.value.currentRoom)
        connection.mutableSnapshot.value = connection.mutableSnapshot.value.copy(
            currentRoom = adminRoom.copy(adminSettings = adminRoom.adminSettings?.copy(adminMemberNumbers = listOf(20))),
        )
        assertFalse(viewModel.uiState.value.canEditRoomSettings)
        viewModel.openRoomSettings()
        assertNull(viewModel.uiState.value.roomForm)

        connection.mutableSnapshot.value = snapshot().copy(connectionHealth = BcConnectionHealth.ConnectedUnverified)
        assertFalse(viewModel.uiState.value.canEditRoomSettings)
        viewModel.openRoomSettings()
        assertNull(viewModel.uiState.value.roomForm)

        connection.mutableSnapshot.value = snapshot().copy(currentRoom = adminRoom.copy(adminSettings = null))
        assertFalse(viewModel.uiState.value.canEditRoomSettings)
    }

    @Test
    fun missingRoomsCapabilityDoesNotExposeAnEmptySettingsEntry() = withFixture(injectRooms = false) {
        assertFalse(viewModel.uiState.value.canEditRoomSettings)
        viewModel.openRoomSettings()
        assertNull(viewModel.uiState.value.roomForm)
    }

    @Test
    fun openingRoomSettingsClosesSelfAndInteractionPanels() = withFixture {
        viewModel.openSelfState()
        assertNotNull(viewModel.uiState.value.selfStateSheet)
        viewModel.openRoomSettings()
        assertNull(viewModel.uiState.value.selfStateSheet)
        assertTrue(form().visible)

        viewModel.openInteract(20)
        assertFalse(form().visible)
        assertNotNull(viewModel.uiState.value.interactionSheet)
        viewModel.openRoomSettings()

        assertNull(viewModel.uiState.value.interactionMode)
        assertNull(viewModel.uiState.value.interactionSheet)
        assertTrue(form().visible)
    }

    @Test
    fun descriptionOnlyEditPreservesUnrecognizedRoomSettings() = withFixture(
        initialSettings = Settings.copy(language = "UNKNOWN", visibility = listOf("CustomRole"), access = listOf("FutureRole")),
    ) {
        viewModel.openRoomSettings()
        val draft = form().draft
        viewModel.changeRoomSettingsDraft(draft.copy(description = "Updated description"))
        viewModel.submitRoomSettings()

        val command = rooms.commands.single()
        assertEquals(setOf(BcRoomBasicField.Description), command.editedFields)
        assertEquals("UNKNOWN", command.settings.language)
        assertEquals(listOf("CustomRole"), command.settings.visibility)
        assertEquals(listOf("FutureRole"), command.settings.access)
    }

    @Test
    fun sameFieldConflictKeepsDraftAndTypedFailureVisible() = withFixture(
        update = { throw BcRoomMutationRejectedException(BcRoomMutationRejection(
            BcRoomMutationRejectionReason.EditConflict, BcRoomBasicField.Description,
        )) },
    ) {
        viewModel.openRoomSettings()
        val edited = form().draft.copy(description = "My description")
        viewModel.changeRoomSettingsDraft(edited)
        viewModel.submitRoomSettings()

        assertTrue(form().visible)
        assertFalse(form().busy)
        assertEquals(edited, form().draft)
        assertEquals(RoomAdministrationFormFailure.Rejected(BcRoomMutationRejection(
            BcRoomMutationRejectionReason.EditConflict, BcRoomBasicField.Description,
        )), form().failure)
        assertEquals(Settings.description, viewModel.uiState.value.roomDescription)
    }

    @Test
    fun serverAdministratorRejectionKeepsDraftForRecovery() = withFixture(
        update = { throw BcRoomMutationRejectedException(BcRoomMutationRejection(
            BcRoomMutationRejectionReason.NotAdministrator,
        )) },
    ) {
        viewModel.openRoomSettings()
        val edited = form().draft.copy(description = "My description")
        viewModel.changeRoomSettingsDraft(edited)
        viewModel.submitRoomSettings()

        assertEquals(RoomAdministrationFormFailure.Rejected(BcRoomMutationRejection(
            BcRoomMutationRejectionReason.NotAdministrator,
        )), form().failure)
        assertEquals(edited, form().draft)
        assertTrue(form().visible)
        assertFalse(form().busy)
    }

    @Test
    fun liveAdministratorLossBlocksSubmissionWithoutDiscardingDraft() = withFixture {
        viewModel.openRoomSettings()
        val edited = form().draft.copy(description = "My description")
        viewModel.changeRoomSettingsDraft(edited)
        val room = requireNotNull(connection.mutableSnapshot.value.currentRoom)
        connection.mutableSnapshot.value = connection.mutableSnapshot.value.copy(
            currentRoom = room.copy(adminSettings = room.adminSettings?.copy(adminMemberNumbers = listOf(20))),
        )
        viewModel.submitRoomSettings()

        assertFalse(viewModel.uiState.value.canEditRoomSettings)
        assertTrue(form().visible)
        assertEquals(edited, form().draft)
        assertEquals(BcRoomMutationRejectionReason.NotAdministrator, form().validation?.reason)
        assertTrue(rooms.commands.isEmpty())
    }

    @Test
    fun explicitReloadUsesCurrentSettingsAndDiscardsTheConflictingDraft() = withFixture(
        update = { throw BcRoomMutationRejectedException(BcRoomMutationRejection(
            BcRoomMutationRejectionReason.EditConflict, BcRoomBasicField.Description,
        )) },
    ) {
        viewModel.openRoomSettings()
        viewModel.changeRoomSettingsDraft(form().draft.copy(description = "My description"))
        viewModel.submitRoomSettings()
        val latest = Settings.copy(description = "Another administrator's description")
        connection.mutableSnapshot.value = connection.mutableSnapshot.value.copy(currentRoom = room(latest))
        assertEquals("My description", form().draft.description)

        viewModel.reloadRoomSettings()

        assertEquals(latest.description, form().draft.description)
        assertEquals(latest, form().base?.settings)
        assertNull(form().failure)
        assertFalse(form().canSubmit)
        assertTrue(form().visible)
    }

    @Test
    fun submittingKeepsFormOpenAndRoomSnapshotAuthoritativeUntilCompletion() {
        val completion = CompletableDeferred<BcRoom>()
        withFixture(update = { completion.await() }) {
            viewModel.openRoomSettings()
            viewModel.changeRoomSettingsDraft(form().draft.copy(description = "Pending description"))
            viewModel.submitRoomSettings()

            assertTrue(form().visible)
            assertTrue(form().busy)
            assertEquals(Settings.description, viewModel.uiState.value.roomDescription)
            viewModel.submitRoomSettings()
            assertEquals(1, rooms.commands.size)

            val authoritative = room(Settings.copy(description = "Pending description"))
            connection.mutableSnapshot.value = connection.mutableSnapshot.value.copy(currentRoom = authoritative)
            completion.complete(authoritative)

            assertFalse(viewModel.uiState.value.roomForm?.visible == true)
            assertEquals("Pending description", viewModel.uiState.value.roomDescription)
        }
    }

    private fun withFixture(
        initialSettings: BcRoomBasicSettings = Settings,
        injectRooms: Boolean = true,
        update: suspend (BcRoomUpdateCommand) -> BcRoom = { room(it.settings) },
        test: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(initialSettings, injectRooms, update)
        try {
            fixture.test()
        } finally {
            fixture.scope.cancel()
        }
    }

    private class Fixture(
        initialSettings: BcRoomBasicSettings,
        injectRooms: Boolean,
        update: suspend (BcRoomUpdateCommand) -> BcRoom,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connection = FakeConnectionStatus(snapshot(initialSettings))
        val rooms = FakeRooms(update)
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = object : BcCharacterInteractions {
                override suspend fun sendInteraction(request: BcPermissionRequest, activityClientMsgId: String?) =
                    BcCharacterInteractionResult.Sent
            },
            interactionCatalogRepository = object : BcInteractionCatalogRepository {
                override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? = null
                override fun refresh(version: String) = BcInteractionCatalogLoadResult.Failed("unused")
            },
            ruleCatalogRepository = object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String): BcRuleCatalog? = null
                override fun refresh(version: String) = BcRuleCatalogLoadResult.Failed("unused")
            },
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
            rooms = rooms.takeIf { injectRooms },
            backgroundNamesProvider = { listOf("MainHall") },
        )

        fun form(): RoomAdministrationFormState = requireNotNull(viewModel.uiState.value.roomForm)
    }

    private class FakeConnectionStatus(initial: BcSessionSnapshot) : ConnectionStatusReader {
        val mutableSnapshot = MutableStateFlow(initial)
        override val snapshot: StateFlow<BcSessionSnapshot> = mutableSnapshot
    }

    private class FakeRooms(private val update: suspend (BcRoomUpdateCommand) -> BcRoom) : BcSessionRooms {
        val commands = mutableListOf<BcRoomUpdateCommand>()
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom {
            commands += command
            return update(command)
        }
    }

    private companion object {
        val Settings = BcRoomBasicSettings(name = "Test Room", description = "Original description")
        fun room(settings: BcRoomBasicSettings) = BcRoom(
            name = BcRoomName(settings.name), description = settings.description, memberCount = 2,
            memberLimit = settings.memberLimit, isLocked = false, language = settings.language,
            adminSettings = BcRoomAdministration(BcRoomEditBase(BcRoomName(settings.name), 3, settings), listOf(10)),
        )
        fun snapshot(settings: BcRoomBasicSettings = Settings) = BcSessionSnapshot(
            state = BcSessionState.Ready,
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            account = BcAccount(10, "Self", "self"),
            currentRoom = room(settings),
            roomPresenceEpoch = 3,
            roomCharacters = listOf(
                BcCharacterSnapshot(id = "self", memberNumber = 10, name = "Self", displayName = "Self", isSelf = true),
                BcCharacterSnapshot(id = "target", memberNumber = 20, name = "Target", displayName = "Target", isSelf = false),
            ),
            relationships = BcRelationshipSnapshot(accountMemberNumber = 10, accountGeneration = 7),
        )
    }
}
