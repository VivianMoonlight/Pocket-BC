package dev.vivian.mobilebc.feature.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomBasicField
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
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAdministrationFormControllerTest {
    @Test
    fun cancellationBeforeExecutionReleasesSubmissionWithoutSending() {
        listOf(true, false).forEach { cancelBeforeSubmit ->
            val queued = ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    queued.addLast(block)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val form = FormHarness(null, { listOf("MainHall") }, scope)
            try {
                form.controller.openCreate(BcRoomSpace.Mixed)
                form.change { copy(name = "Quiet room") }
                assertTrue(form.state.canSubmit)
                if (cancelBeforeSubmit) scope.cancel()
                form.controller.submit()
                scope.cancel()
                while (queued.isNotEmpty()) queued.removeFirst().run()

                assertFalse(form.state.busy)
                assertTrue(form.rooms.creates.isEmpty())
                assertTrue(form.completed.isEmpty())
                assertNull(form.state.failure)
                form.change { copy(description = "Still editable") }
                assertEquals("Still editable", form.state.draft.description)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun capacityRejectsDecimalsSignsWhitespaceAndOverflowWithoutSending() = runBlocking {
        withForm { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            form.change { copy(name = "Quiet room") }
            listOf("", "2.5", "+2", "-2", " 2", "2 ", "1", "21", "9999999999999999999").forEach { value ->
                form.change { copy(memberLimit = value) }
                form.controller.submit()
                assertFalse(value, form.state.canSubmit)
                assertEquals(BcRoomMutationRejectionReason.InvalidMemberLimit, form.state.validation?.reason)
            }
            assertEquals(0, form.rooms.creates.size)
            form.change { copy(memberLimit = "2") }
            assertTrue(form.state.canSubmit)
            form.change { copy(memberLimit = "20") }
            form.controller.submit()
            assertEquals(20, form.rooms.creates.single().settings.memberLimit)
        }
    }

    @Test
    fun createRequiresBackgroundCatalogAndSelectsVerifiedFirstOrMainHall() = runBlocking {
        withForm(backgrounds = { emptyList() }) { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            form.change { copy(name = "Quiet room") }
            assertEquals("", form.state.draft.background)
            assertEquals(BcRoomMutationRejectionReason.BackgroundCatalogUnavailable, form.state.validation?.reason)
            form.controller.submit()
            assertTrue(form.rooms.creates.isEmpty())
        }
        withForm(backgrounds = { listOf("Garden", "MainHall") }) { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            assertEquals("MainHall", form.state.draft.background)
        }
        withForm(backgrounds = { listOf("Garden", "Beach") }) { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            assertEquals("Garden", form.state.draft.background)
        }
    }

    @Test
    fun preservedAsylumDraftReopensWithItsOwnCatalogAndSpaceChangesRefreshIt() = runBlocking {
        val queriedSpaces = mutableListOf<BcRoomSpace>()
        withForm(backgrounds = { space ->
            queriedSpaces += space
            if (space == BcRoomSpace.Asylum) listOf("AsylumHall") else listOf("MainHall")
        }) { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            form.change { copy(name = "Quiet room", space = BcRoomSpace.Asylum) }
            assertEquals("AsylumHall", form.state.draft.background)
            assertEquals(listOf("AsylumHall"), form.state.backgroundNames)
            form.controller.close()
            form.controller.openCreate(BcRoomSpace.Mixed)
            assertEquals(BcRoomSpace.Asylum, form.state.draft.space)
            assertEquals(listOf("AsylumHall"), form.state.backgroundNames)
            assertEquals(BcRoomSpace.Asylum, queriedSpaces.last())
            assertTrue(form.state.canSubmit)
            form.change { copy(space = BcRoomSpace.Mixed) }
            assertEquals("MainHall", form.state.draft.background)
            assertEquals(listOf("MainHall"), form.state.backgroundNames)
        }
    }

    @Test
    fun descriptionEditKeepsUnknownSettingsAndDoesNotRequireUnusedBackgroundCatalog() = runBlocking {
        val settings = basicSettings().copy(
            background = "CustomHall", language = "ZZ", visibility = listOf("FutureRole"), access = emptyList(),
        )
        withForm(room = room(settings), backgrounds = { emptyList() }) { form ->
            form.controller.openEdit()
            form.change { copy(description = "New description") }
            assertTrue(form.state.canSubmit)
            form.controller.submit()
            val command = form.rooms.updates.single()
            assertEquals(setOf(BcRoomBasicField.Description), command.editedFields)
            assertEquals(settings.copy(description = "New description"), command.settings)
        }
    }

    @Test
    fun closeDuringPendingWriteKeepsDraftBusyAndDoesNotCancelOrRepeatTheWrite() = runBlocking {
        withForm { form ->
            form.controller.openCreate(BcRoomSpace.Mixed)
            form.change { copy(name = "Quiet room") }
            form.controller.submit()
            form.controller.close()
            assertTrue(form.state.busy)
            assertFalse(form.state.visible)
            form.controller.submit()
            form.controller.openCreate(BcRoomSpace.Female)
            assertTrue(form.state.visible)
            assertEquals("Quiet room", form.state.draft.name)
            assertEquals(1, form.rooms.creates.size)
            assertFalse(form.rooms.createResult.isCancelled)
            val authority = room(basicSettings().copy(name = "Quiet room"))
            form.status.snapshot.value = form.status.snapshot.value.copy(currentRoom = authority, roomPresenceEpoch = 1)
            form.rooms.createResult.complete(authority)
            assertNull(form.controller.state.value)
            assertEquals(listOf(authority), form.completed)
        }
    }

    @Test
    fun allUnknownOutcomesRetainDraftAndRequireExplicitReopenAfterANewLoginBeforeRetry() = runBlocking {
        BcRoomMutationUnknownReason.entries.forEach { reason ->
            withForm { form ->
                form.controller.openCreate(BcRoomSpace.Mixed)
                form.change { copy(name = "Quiet room", description = "Keep this") }
                form.controller.submit()
                form.rooms.createResult.completeExceptionally(BcRoomMutationOutcomeUnknownException(reason))
                assertEquals(RoomAdministrationFormFailure.OutcomeUnknown(reason), form.state.failure)
                assertFalse(form.state.canSubmit)
                form.controller.close()
                form.controller.openCreate(BcRoomSpace.Mixed)
                form.change { copy(description = "Corrected") }
                form.controller.submit()
                assertEquals(1, form.rooms.creates.size)
                assertEquals(RoomAdministrationFormFailure.OutcomeUnknown(reason), form.state.failure)
                form.status.snapshot.value = form.status.snapshot.value.copy(selfRuleLogSessionId = "second-login")
                form.controller.submit()
                assertEquals(1, form.rooms.creates.size)
                form.controller.openCreate(BcRoomSpace.Mixed)
                assertEquals("Corrected", form.state.draft.description)
                assertNull(form.state.failure)
                assertTrue(form.state.canSubmit)
                form.rooms.createResult = CompletableDeferred()
                form.controller.submit()
                assertEquals(2, form.rooms.creates.size)
            }
        }
    }

    @Test
    fun oldAccountFailureAndSuccessCannotOverwriteANewAccountsFormOrNavigate() = runBlocking {
        listOf(false, true).forEach { succeeds ->
            withForm { form ->
                form.controller.openCreate(BcRoomSpace.Mixed)
                form.change { copy(name = "Old room") }
                form.controller.submit()
                form.status.snapshot.value = form.status.snapshot.value.copy(account = BcAccount(8, "Other", "other"), selfRuleLogSessionId = "other-login")
                assertNull(form.controller.state.value)
                form.controller.openCreate(BcRoomSpace.Female)
                if (succeeds) form.rooms.createResult.complete(room(basicSettings().copy(name = "Old room")))
                else form.rooms.createResult.completeExceptionally(IllegalStateException("Old failure"))
                assertEquals("", form.state.draft.name)
                assertEquals(BcRoomSpace.Female, form.state.draft.space)
                assertNull(form.state.failure)
                assertFalse(form.state.busy)
                assertTrue(form.completed.isEmpty())
            }
        }
    }

    @Test
    fun newLoginAndSameNamedRoomRevisitCannotCompleteTheOldEdit() = runBlocking {
        listOf(false, true).forEach { newLogin ->
            withForm(room = room()) { form ->
                form.controller.openEdit()
                form.change { copy(description = "Old change") }
                form.controller.submit()
                val updatedRoom = room(basicSettings().copy(description = "Old change"), epoch = 2)
                form.status.snapshot.value = form.status.snapshot.value.copy(
                    currentRoom = updatedRoom,
                    roomPresenceEpoch = if (newLogin) 1 else 2,
                    selfRuleLogSessionId = if (newLogin) "second-login" else "first-login",
                )
                form.rooms.updateResult.complete(updatedRoom)
                assertTrue(form.completed.isEmpty())
                assertEquals("Old change", form.state.draft.description)
                assertFalse(form.state.canSubmit)
                form.controller.openEdit()
                assertEquals(BcRoomMutationRejectionReason.RoomChanged, form.state.validation?.reason)
                form.controller.reloadCurrentSettings()
                assertEquals(updatedRoom.adminSettings?.base, form.state.base)
                assertEquals(BcRoomMutationRejectionReason.NothingToUpdate, form.state.validation?.reason)
            }
        }
    }

    @Test
    fun reopenKeepsOriginalBaseAndRawInvalidCapacityWhenAnUneditedBackgroundChanges() = runBlocking {
        withForm(room = room()) { form ->
            form.controller.openEdit()
            val originalBase = form.state.base
            form.change { copy(memberLimit = "", description = "My edit") }
            form.controller.close()
            form.status.snapshot.value = form.status.snapshot.value.copy(currentRoom = room(basicSettings().copy(background = "Garden")))
            form.controller.openEdit()
            assertEquals(originalBase, form.state.base)
            assertEquals("", form.state.draft.memberLimit)
            assertEquals("My edit", form.state.draft.description)
            form.change { copy(memberLimit = "10") }
            form.controller.submit()
            assertEquals(setOf(BcRoomBasicField.Description), form.rooms.updates.single().editedFields)
            assertEquals(originalBase, form.rooms.updates.single().base)
        }
    }

    @Test
    fun explicitReloadDiscardsConflictingDraftAndTakesTheCurrentAuthority() = runBlocking {
        withForm(room = room()) { form ->
            form.controller.openEdit()
            form.change { copy(description = "My edit") }
            val updated = room(basicSettings().copy(description = "Other admin edit"))
            form.status.snapshot.value = form.status.snapshot.value.copy(currentRoom = updated)
            assertEquals(BcRoomMutationRejectionReason.EditConflict, form.state.validation?.reason)
            form.controller.submit()
            assertTrue(form.rooms.updates.isEmpty())
            form.controller.reloadCurrentSettings()
            assertEquals("Other admin edit", form.state.draft.description)
            assertEquals(updated.adminSettings?.base, form.state.base)
            assertEquals(BcRoomMutationRejectionReason.NothingToUpdate, form.state.validation?.reason)
        }
    }

    @Test
    fun liveAdministratorLossBlocksSendingButRetainsTheDraft() = runBlocking {
        withForm(room = room()) { form ->
            form.controller.openEdit()
            form.change { copy(description = "Keep my edit") }
            val current = form.status.snapshot.value.currentRoom!!
            form.status.snapshot.value = form.status.snapshot.value.copy(currentRoom = current.copy(adminSettings = current.adminSettings!!.copy(adminMemberNumbers = listOf(8))))
            assertEquals(BcRoomMutationRejectionReason.NotAdministrator, form.state.validation?.reason)
            form.controller.submit()
            assertTrue(form.rooms.updates.isEmpty())
            assertEquals("Keep my edit", form.state.draft.description)
        }
    }

    @Test
    fun typedAndGenericFailuresKeepDraftAcrossCloseAndSameAccountLoginRecovery() = runBlocking {
        listOf(
            BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.ServerRejected, serverCode = "FutureCode")),
            IllegalStateException("Do not show this server error"),
        ).forEach { failure ->
            withForm { form ->
                form.controller.openCreate(BcRoomSpace.Mixed)
                form.change { copy(name = "Quiet room", description = "Keep this") }
                form.controller.submit()
                form.rooms.createResult.completeExceptionally(failure)
                val typedFailure = form.state.failure
                if (failure is BcRoomMutationRejectedException) {
                    assertEquals(RoomAdministrationFormFailure.Rejected(failure.rejection), typedFailure)
                } else assertEquals(RoomAdministrationFormFailure.Failed, typedFailure)
                form.controller.close()
                form.controller.openCreate(BcRoomSpace.Mixed)
                assertEquals(typedFailure, form.state.failure)
                assertEquals("Keep this", form.state.draft.description)
                form.status.snapshot.value = form.status.snapshot.value.copy(selfRuleLogSessionId = "second-login")
                form.controller.openCreate(BcRoomSpace.Mixed)
                assertEquals("Keep this", form.state.draft.description)
                assertTrue(form.state.canSubmit)
            }
        }
    }

    private suspend fun withForm(
        room: BcRoom? = null,
        backgrounds: (BcRoomSpace) -> List<String> = { listOf("MainHall", "Garden") },
        action: suspend (FormHarness) -> Unit,
    ) {
        val harness = FormHarness(room, backgrounds)
        try { action(harness) } finally { harness.scope.cancel() }
    }

    private class FormHarness(
        room: BcRoom?,
        backgrounds: (BcRoomSpace) -> List<String>,
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) {
        val status = FakeStatus(BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = BcAccount(7, "Self", "self"),
            selfRuleLogSessionId = "first-login",
            currentRoom = room,
            roomPresenceEpoch = if (room == null) 0 else 1,
        ))
        val rooms = FakeRooms()
        val completed = mutableListOf<BcRoom>()
        val controller = RoomAdministrationFormController(rooms, status, scope, backgrounds, { completed += it })
        val state get() = requireNotNull(controller.state.value)
        fun change(change: RoomAdministrationDraft.() -> RoomAdministrationDraft) = controller.changeDraft(state.draft.change())
    }

    private class FakeStatus(initial: BcSessionSnapshot) : ConnectionStatusReader {
        override val snapshot = MutableStateFlow(initial)
    }

    private class FakeRooms : BcSessionRooms {
        val creates = mutableListOf<BcRoomCreateCommand>()
        val updates = mutableListOf<BcRoomUpdateCommand>()
        var createResult = CompletableDeferred<BcRoom>()
        var updateResult = CompletableDeferred<BcRoom>()
        override suspend fun createRoom(command: BcRoomCreateCommand): BcRoom { creates += command; return createResult.await() }
        override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom { updates += command; return updateResult.await() }
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
    }

    companion object {
        private fun basicSettings() = BcRoomBasicSettings(name = "Current room", description = "Current description")
        private fun room(settings: BcRoomBasicSettings = basicSettings(), epoch: Long = 1) = BcRoom(
            name = BcRoomName(settings.name), description = settings.description, memberCount = 1,
            memberLimit = settings.memberLimit, isLocked = false, language = settings.language,
            adminSettings = BcRoomAdministration(BcRoomEditBase(BcRoomName(settings.name), epoch, settings), listOf(7)),
            space = BcRoomSpace.Mixed.wireValue,
        )
    }
}
