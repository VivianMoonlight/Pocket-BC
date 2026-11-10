package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionActivity
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionGroup
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcPoseRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRequest
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEntryAction
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEntryReason
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlan
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeRequest
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeResult
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsWardrobeViewModelTest {
    @Test
    fun cancellationBeforeExecutionReleasesWardrobeSubmissionWithoutSending() = withFixture {
        listOf(true, false).forEach { cancelledBeforeSubmit ->
            val queued = ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    queued.addLast(block)
                }
            }
            val job = Job()
            val controller = RoomWardrobeController(
                characterInteractions = interactions,
                connectionStatus = connection,
                currentVersion = currentVersion,
                ruleCatalogRepository = object : BcRuleCatalogRepository {
                    override fun currentSnapshot(version: String) = Rules
                    override fun refresh(version: String) = BcRuleCatalogLoadResult.Ready(Rules)
                },
                scope = CoroutineScope(job + dispatcher),
                isOtherMutationBusy = { false },
            )
            try {
                controller.open()
                controller.selectSlot(0)
                controller.beginSave()
                assertTrue(controller.state.value.canSave)
                if (cancelledBeforeSubmit) job.cancel()
                controller.confirmSave()
                job.cancel()
                while (queued.isNotEmpty()) queued.removeFirst().run()

                assertFalse(controller.isBusy)
                assertFalse(controller.state.value.busy)
                assertTrue(interactions.saveRequests.isEmpty())
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun openingRequiresVerifiedOnlineAndTheAuthenticatedSelfInACurrentRoom() {
        listOf(
            snapshot().copy(desiredOnline = false),
            snapshot().copy(connectionHealth = BcConnectionHealth.ConnectedUnverified),
            snapshot().copy(account = null),
            snapshot().copy(currentRoom = null),
            snapshot().copy(roomCharacters = listOf(TargetCharacter)),
        ).forEach { initial ->
            withFixture(initial = initial) {
                assertFalse(viewModel.uiState.value.canOpenWardrobe)
                viewModel.openWardrobe()
                viewModel.selectWardrobeSlot(0)
                viewModel.beginWardrobeSave()
                viewModel.confirmWardrobeSave()
                assertTrue(interactions.saveRequests.isEmpty())
            }
        }
    }

    @Test
    fun missingLoginOrVersionCannotCreateAnOwnedWardrobeAction() {
        withFixture(initial = snapshot().copy(selfRuleLogSessionId = null)) {
            viewModel.openWardrobe()
            assertFalse(sheet().visible)
            assertFalse(sheet().canSave)
        }
        withFixture(version = "") {
            viewModel.openWardrobe()
            assertFalse(sheet().visible)
            assertFalse(sheet().canSave)
        }
    }

    @Test
    fun theUiProjectsExactly24SlotsAndTreatsMissingSlotsAsUnsupported() {
        val state = wardrobe().copy(slots = listOf(savedSlot(0), savedSlot(26)))
        withFixture(initial = snapshot().copy(wardrobeState = state)) {
            viewModel.openWardrobe()
            assertEquals((0 until BcWardrobeSlotCount).toList(), sheet().slots.map { it.index })
            assertEquals(BcWardrobeSlotStatus.Unsupported, sheet().slots[1].status)
            assertNull(sheet().slots[1].appearance)
            viewModel.selectWardrobeSlot(1)
            assertFalse(sheet().canSave)
            assertFalse(sheet().canPreview)
            viewModel.selectWardrobeSlot(26)
            assertEquals(1, sheet().selectedSlotIndex)
            assertEquals(state, connection.mutableSnapshot.value.wardrobeState)
        }
    }

    @Test
    fun unavailableOrUnownedPrivateStateNeverBecomesAnEmptyWritableWardrobe() {
        val available = wardrobe()
        val unavailableStates = listOf(
            null,
            available.copy(memberNumber = TargetMemberNumber),
            available.copy(selfRuleLogSessionId = "other-login"),
            available.copy(wardrobeAvailable = false, rawWardrobeJson = null),
            available.copy(privateInputs = available.privateInputs.copy(gameAvailable = false)),
            available.copy(privateInputs = available.privateInputs.copy(inventoryAvailable = false)),
            available.copy(privateInputs = available.privateInputs.copy(inventoryItems = null, inventoryIds = null)),
        )
        unavailableStates.forEach { state ->
            withFixture(initial = snapshot().copy(wardrobeState = state)) {
                viewModel.openWardrobe()
                viewModel.selectWardrobeSlot(0)
                assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, sheet().unavailableReason)
                assertFalse(sheet().canSave)
                viewModel.beginWardrobeSave()
                viewModel.confirmWardrobeSave()
                assertTrue(interactions.saveRequests.isEmpty())
                assertEquals(state, connection.mutableSnapshot.value.wardrobeState)
            }
        }
    }

    @Test
    fun unavailableRulesPrivateLogOrWardrobePermissionBlockSaveAndPreview() {
        withFixture(rules = null) {
            openSlot()
            assertEquals(BcWardrobeUnavailableReason.CatalogPreparing, sheet().unavailableReason)
            assertFalse(sheet().canSave)
        }
        withFixture(initial = snapshot().copy(selfRuleLog = null)) {
            openSlot()
            assertEquals(BcWardrobeUnavailableReason.PrivateStateUnavailable, sheet().unavailableReason)
            assertFalse(sheet().canSave)
        }
        withFixture(initial = snapshot().copy(selfRuleLog = emptyList())) {
            openSlot()
            assertEquals(BcWardrobeUnavailableReason.WardrobeLocked, sheet().unavailableReason)
            assertFalse(sheet().canSave)
            assertFalse(sheet().canPreview)
        }
    }

    @Test
    fun savingAnEmptySlotStillRequiresExplicitConfirmationAndUsesTheLocalRoomEpoch() = withFixture {
        val before = connection.mutableSnapshot.value
        openSlot(1)
        assertTrue(sheet().canSave)
        viewModel.confirmWardrobeSave()
        assertTrue(interactions.saveRequests.isEmpty())
        viewModel.beginWardrobeSave()
        assertEquals(RoomWardrobeEditMode.Save, sheet().editMode)
        assertTrue(interactions.saveRequests.isEmpty())
        viewModel.confirmWardrobeSave()

        assertEquals(request(1), interactions.saveRequests.single())
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Save), sheet().result)
        assertNull(sheet().editMode)
        assertTrue(interactions.renameRequests.isEmpty())
        assertEquals(before, connection.mutableSnapshot.value)
    }

    @Test
    fun cancellingAnExistingSlotOverwriteDoesNotSendOrChangeItsName() = withFixture {
        val before = connection.mutableSnapshot.value
        openSlot()
        viewModel.beginWardrobeSave()
        assertEquals(RoomWardrobeEditMode.Save, sheet().editMode)
        viewModel.cancelWardrobeEdit()
        viewModel.confirmWardrobeSave()

        assertTrue(interactions.saveRequests.isEmpty())
        assertTrue(interactions.renameRequests.isEmpty())
        assertEquals(before, connection.mutableSnapshot.value)
    }

    @Test
    fun anUnsupportedSlotCanBeRenamedIndependentlyButCannotBeOverwrittenOrLoaded() {
        val unsupported = savedSlot().copy(status = BcWardrobeSlotStatus.Unsupported, appearance = null, rawSlotJson = "{\"future\":true}")
        val state = wardrobe().copy(slots = listOf(unsupported) + wardrobe().slots.drop(1))
        withFixture(initial = snapshot().copy(wardrobeState = state)) {
            openSlot()
            assertFalse(sheet().canSave)
            assertFalse(sheet().canPreview)
            assertTrue(sheet().canRename)
            viewModel.beginWardrobeSave()
            viewModel.confirmWardrobeSave()
            viewModel.previewWardrobeLoad()
            viewModel.confirmWardrobeLoad()
            assertTrue(interactions.saveRequests.isEmpty())
            assertTrue(interactions.loadRequests.isEmpty())

            viewModel.beginWardrobeRename()
            viewModel.changeWardrobeName("Future Slot")
            viewModel.confirmWardrobeRename()
            assertEquals(request() to "Future Slot", interactions.renameRequests.single())
            assertEquals(state, connection.mutableSnapshot.value.wardrobeState)
        }
    }

    @Test
    fun unknownNamesCannotBeRenamedButDoNotTriggerAChainedSaveNameUpdate() = withFixture(
        initial = snapshot().copy(wardrobeState = wardrobe().copy(namesAvailable = false, rawNamesJson = null)),
    ) {
        openSlot()
        assertFalse(sheet().canRename)
        viewModel.beginWardrobeRename()
        viewModel.changeWardrobeName("New Name")
        viewModel.confirmWardrobeRename()
        assertTrue(interactions.renameRequests.isEmpty())
        viewModel.beginWardrobeSave()
        viewModel.confirmWardrobeSave()
        assertEquals(1, interactions.saveRequests.size)
        assertTrue(interactions.renameRequests.isEmpty())
    }

    @Test
    fun renamePreservesRawAsciiSpacingIncludingAnEmptyName() {
        listOf("", "  Outfit 2  ", "A".repeat(20)).forEach { name ->
            withFixture {
                val before = connection.mutableSnapshot.value
                openSlot()
                viewModel.beginWardrobeRename()
                viewModel.changeWardrobeName(name)
                assertEquals(name, sheet().nameDraft)
                assertTrue(sheet().nameValid)
                assertTrue(interactions.renameRequests.isEmpty())
                viewModel.confirmWardrobeRename()

                assertEquals(request() to name, interactions.renameRequests.single())
                assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Rename), sheet().result)
                assertTrue(interactions.saveRequests.isEmpty())
                assertEquals(before, connection.mutableSnapshot.value)
            }
        }
    }

    @Test
    fun invalidRenameTextIsRetainedAndRejectedWithoutTruncationOrTransportCalls() {
        listOf("A".repeat(21), "衣服", "Outfit-1", "Outfit\n1").forEach { name ->
            withFixture {
                openSlot()
                viewModel.beginWardrobeRename()
                viewModel.changeWardrobeName(name)
                viewModel.confirmWardrobeRename()

                assertEquals(name, sheet().nameDraft)
                assertFalse(sheet().nameValid)
                assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.InvalidName), sheet().result)
                assertEquals(RoomWardrobeEditMode.Rename, sheet().editMode)
                assertTrue(interactions.renameRequests.isEmpty())
            }
        }
    }

    @Test
    fun loadPreviewContainsTheFullAppearanceAndExplainsSkippedGroups() = withFixture {
        val before = connection.mutableSnapshot.value
        openSlot()
        viewModel.previewWardrobeLoad()
        val preview = requireNotNull(sheet().preview)

        assertTrue(preview.hasChanges)
        assertTrue(preview.partial)
        assertEquals("NewShirt", preview.appearance.single { it.group == "Cloth" }.name)
        assertEquals(CurrentHair, preview.appearance.single { it.group == "HairFront" })
        assertEquals(CurrentRestraint, preview.appearance.single { it.group == "ItemHands" })
        assertEquals(BcWardrobeEntryAction.Applied, preview.entries.single { it.group == "Cloth" }.action)
        assertEquals(BcWardrobeEntryReason.UnknownAsset, preview.entries.single { it.group == "HairFront" }.reason)
        assertEquals(BcWardrobeEntryReason.ItemGroupPreserved, preview.entries.single { it.group == "ItemHands" }.reason)
        assertTrue(interactions.loadRequests.isEmpty())
        assertEquals(before, connection.mutableSnapshot.value)
    }

    @Test
    fun cancellingThePreviewCannotSendALoad() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        assertNotNull(sheet().preview)
        viewModel.cancelWardrobeLoad()
        viewModel.confirmWardrobeLoad()

        assertNull(sheet().preview)
        assertTrue(interactions.loadRequests.isEmpty())
    }

    @Test
    fun loadConfirmationPassesTheExactReviewedPlanToTheDomain() = withFixture {
        val before = connection.mutableSnapshot.value
        openSlot()
        viewModel.previewWardrobeLoad()
        val preview = requireNotNull(sheet().preview)
        viewModel.confirmWardrobeLoad()

        val (sentRequest, expectedPlan) = interactions.loadRequests.single()
        assertEquals(request(), sentRequest)
        assertSame(preview, expectedPlan)
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Load, partial = true), sheet().result)
        assertNull(sheet().preview)
        assertEquals(before, connection.mutableSnapshot.value)
    }

    @Test
    fun aPreviewWithNoAppearanceChangesCannotBeSubmitted() {
        val slot = savedSlot().copy(appearance = listOf(
            BcWardrobeItem(CurrentShirt.group, CurrentShirt.name, CurrentShirt.colorJson),
            BcWardrobeItem(CurrentHair.group, CurrentHair.name, CurrentHair.colorJson),
        ))
        withFixture(initial = snapshot().copy(wardrobeState = wardrobe().copy(slots = listOf(slot) + wardrobe().slots.drop(1)))) {
            openSlot()
            viewModel.previewWardrobeLoad()
            assertFalse(requireNotNull(sheet().preview).hasChanges)
            viewModel.confirmWardrobeLoad()
            assertTrue(interactions.loadRequests.isEmpty())
        }
    }

    @Test
    fun aChangedSelfSnapshotInvalidatesTheCapturedSaveConfirmation() = withFixture {
        openSlot()
        viewModel.beginWardrobeSave()
        publishSelf { it.copy(displayName = "Changed self") }
        viewModel.confirmWardrobeSave()

        assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), sheet().result)
        assertTrue(interactions.saveRequests.isEmpty())
    }

    @Test
    fun aChangedWardrobeRevisionInvalidatesTheCapturedRenameConfirmation() = withFixture {
        openSlot()
        viewModel.beginWardrobeRename()
        viewModel.changeWardrobeName("Unsaved Name")
        publish { it.copy(wardrobeState = requireNotNull(it.wardrobeState).copy(revision = WardrobeRevision + 1)) }
        viewModel.confirmWardrobeRename()

        assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.RevisionChanged), sheet().result)
        assertEquals("Unsaved Name", sheet().nameDraft)
        assertTrue(interactions.renameRequests.isEmpty())
    }

    @Test
    fun aChangedAppearanceCannotApplyAnOldPreview() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        publishSelf { it.copy(appearance = it.appearance.map { item -> if (item.group == "Cloth") item.copy(colorJson = "[\"Blue\"]") else item }) }
        viewModel.confirmWardrobeLoad()

        assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), sheet().result)
        assertTrue(interactions.loadRequests.isEmpty())
    }

    @Test
    fun changedRoomInputsInvalidateThePreviewWithoutApplyingADifferentPlan() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        val preview = requireNotNull(sheet().preview)
        publish { current -> current.copy(currentRoom = requireNotNull(current.currentRoom).copy(blockCategories = setOf("Changed"))) }
        viewModel.confirmWardrobeLoad()

        assertTrue(interactions.loadRequests.isEmpty())
        assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), sheet().result)
        assertSame(preview, sheet().preview)
    }

    @Test
    fun aChangedWardrobeRevisionCannotApplyThePreviouslyReviewedPlan() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        val preview = requireNotNull(sheet().preview)
        publish { it.copy(wardrobeState = requireNotNull(it.wardrobeState).copy(revision = WardrobeRevision + 1)) }
        viewModel.confirmWardrobeLoad()

        assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.RevisionChanged), sheet().result)
        assertSame(preview, sheet().preview)
        assertTrue(interactions.loadRequests.isEmpty())
    }

    @Test
    fun changedPrivateInputsOrRuleLogsCannotApplyThePreviouslyReviewedPlan() {
        val sourceChanges: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { current -> current.copy(wardrobeState = requireNotNull(current.wardrobeState).let { state ->
                state.copy(privateInputs = state.privateInputs.copy(gameJson = "{\"GGTS\":{\"Level\":1}}"))
            }) },
            { current -> current.copy(selfRuleLog = current.selfRuleLog.orEmpty() + BcOwnerRuleLogRecord("OtherRule", "Rule")) },
        )
        sourceChanges.forEach { change ->
            withFixture {
                openSlot()
                viewModel.previewWardrobeLoad()
                val preview = requireNotNull(sheet().preview)
                publish(change)
                viewModel.confirmWardrobeLoad()

                assertEquals(RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), sheet().result)
                assertSame(preview, sheet().preview)
                assertTrue(interactions.loadRequests.isEmpty())
            }
        }
    }

    @Test
    fun ownerChangesClearUnsentSelectionsDraftsAndPreviews() {
        ownerChanges().forEach { change ->
            withFixture {
                openSlot()
                viewModel.beginWardrobeRename()
                viewModel.changeWardrobeName("Unsaved Name")
                publish(change)
                assertFalse(sheet().visible)
                assertNull(sheet().selectedSlotIndex)
                assertNull(sheet().editMode)
                assertNull(sheet().preview)
                assertNull(sheet().result)
                viewModel.confirmWardrobeRename()
                assertTrue(interactions.renameRequests.isEmpty())
            }
        }
        withFixture {
            openSlot()
            viewModel.previewWardrobeLoad()
            currentVersion.value = "R130"
            assertFalse(sheet().visible)
            assertNull(sheet().preview)
            viewModel.confirmWardrobeLoad()
            assertTrue(interactions.loadRequests.isEmpty())
        }
    }

    @Test
    fun ownerChangesDoNotCancelAnEmittedWriteOrDeliverItsResultToANewView() {
        ownerChanges().forEach { change ->
            val completion = CompletableDeferred<BcWardrobeResult>()
            withFixture(save = { completion.await() }) {
                openSlot()
                viewModel.beginWardrobeSave()
                viewModel.confirmWardrobeSave()
                publish(change)
                assertTrue(requireNotNull(interactions.wardrobeJob).isActive)
                assertTrue(sheet().busy)
                assertFalse(sheet().visible)
                viewModel.openWardrobe()
                viewModel.confirmWardrobeSave()
                assertEquals(1, interactions.saveRequests.size)
                completion.complete(BcWardrobeResult.Sent())

                assertFalse(sheet().busy)
                assertNull(sheet().result)
                assertNull(sheet().selectedSlotIndex)
            }
        }
    }

    @Test
    fun versionChangesAlsoFenceAPendingWardrobeWrite() {
        val completion = CompletableDeferred<BcWardrobeResult>()
        withFixture(save = { completion.await() }) {
            openSlot()
            viewModel.beginWardrobeSave()
            viewModel.confirmWardrobeSave()
            currentVersion.value = "R130"
            assertTrue(requireNotNull(interactions.wardrobeJob).isActive)
            assertFalse(sheet().visible)
            completion.complete(BcWardrobeResult.Sent())
            assertNull(sheet().result)
            assertFalse(sheet().busy)
        }
    }

    @Test
    fun closingAndReopeningAPendingSaveDoesNotCancelOrDuplicateTheRequest() {
        val completion = CompletableDeferred<BcWardrobeResult>()
        withFixture(save = { completion.await() }) {
            openSlot()
            viewModel.beginWardrobeSave()
            viewModel.confirmWardrobeSave()
            viewModel.closeWardrobe()
            assertFalse(sheet().visible)
            assertTrue(sheet().busy)
            assertTrue(requireNotNull(interactions.wardrobeJob).isActive)
            viewModel.openWardrobe()
            viewModel.selectWardrobeSlot(1)
            viewModel.confirmWardrobeSave()
            assertEquals(0, sheet().selectedSlotIndex)
            assertEquals(1, interactions.saveRequests.size)
            completion.complete(BcWardrobeResult.Sent())
            assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Save), sheet().result)
            viewModel.confirmWardrobeSave()
            assertEquals(1, interactions.saveRequests.size)
        }
    }

    @Test
    fun aPendingWardrobeWriteBlocksAllOtherRoomMutationFlows() {
        val completion = CompletableDeferred<BcWardrobeResult>()
        withFixture(save = { completion.await() }) {
            openSlot()
            viewModel.beginWardrobeSave()
            viewModel.confirmWardrobeSave()
            viewModel.closeWardrobe()
            viewModel.openSelfState()
            viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
            viewModel.openRoomSettings()
            viewModel.submitRoomSettings()
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            viewModel.openInteract(TargetMemberNumber)
            viewModel.sendActivity("Cloth", "Kiss")

            assertTrue(interactions.selfRequests.isEmpty())
            assertTrue(interactions.interactionRequests.isEmpty())
            assertTrue(rooms.updateRequests.isEmpty())
            assertTrue(rooms.memberRequests.isEmpty())
            assertTrue(requireNotNull(interactions.wardrobeJob).isActive)
            completion.complete(BcWardrobeResult.Sent())
        }
    }

    @Test
    fun eachOtherPendingRoomMutationBlocksWardrobeOpeningAndSubmission() {
        OtherMutation.entries.forEach { operation ->
            val completion = CompletableDeferred<Unit>()
            withFixture(otherWait = { completion.await() }) {
                when (operation) {
                    OtherMutation.SelfState -> {
                        viewModel.openSelfState()
                        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
                        assertTrue(requireNotNull(viewModel.uiState.value.selfStateSheet).busy)
                    }
                    OtherMutation.RoomSettings -> {
                        viewModel.openRoomSettings()
                        val form = requireNotNull(viewModel.uiState.value.roomForm)
                        viewModel.changeRoomSettingsDraft(form.draft.copy(description = "Pending edit"))
                        viewModel.submitRoomSettings()
                        assertTrue(requireNotNull(viewModel.uiState.value.roomForm).busy)
                    }
                    OtherMutation.MemberAdmin -> {
                        viewModel.openMemberManagement(TargetMemberNumber)
                        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
                        viewModel.confirmMemberAdminAction()
                        assertTrue(viewModel.uiState.value.memberAdministrationBusy)
                    }
                    OtherMutation.Interaction -> {
                        viewModel.openInteract(TargetMemberNumber)
                        viewModel.sendActivity("Cloth", "Kiss")
                        assertTrue(requireNotNull(viewModel.uiState.value.interactionSheet).busy)
                    }
                }
                assertFalse(viewModel.uiState.value.canOpenWardrobe)
                viewModel.openWardrobe()
                viewModel.selectWardrobeSlot(0)
                viewModel.beginWardrobeSave()
                viewModel.confirmWardrobeSave()
                assertFalse(sheet().visible)
                assertTrue(interactions.saveRequests.isEmpty())
                completion.complete(Unit)
            }
        }
    }

    @Test
    fun typedRejectionsKeepTheRenameDraftAndNeverAutomaticallyResend() {
        BcWardrobeUnavailableReason.entries.forEach { reason ->
            withFixture(rename = { _, _ -> BcWardrobeResult.Rejected(reason) }) {
                openSlot()
                viewModel.beginWardrobeRename()
                viewModel.changeWardrobeName("Unsaved Name")
                viewModel.confirmWardrobeRename()
                assertEquals(RoomWardrobeResult.Rejected(reason), sheet().result)
                assertEquals("Unsaved Name", sheet().nameDraft)
                assertEquals(RoomWardrobeEditMode.Rename, sheet().editMode)
                assertFalse(sheet().busy)
                viewModel.closeWardrobe()
                viewModel.openWardrobe()
                assertEquals(1, interactions.renameRequests.size)
            }
        }
    }

    @Test
    fun domainAndUnexpectedFailuresUseTheLocalizedModelWithoutDiscardingTheDraft() {
        listOf<suspend (BcWardrobeRequest, String) -> BcWardrobeResult>(
            { _, _ -> BcWardrobeResult.Failed("Raw transport detail") },
            { _, _ -> throw IllegalStateException("Raw exception detail") },
        ).forEach { failure ->
            withFixture(rename = failure) {
                openSlot()
                viewModel.beginWardrobeRename()
                viewModel.changeWardrobeName("Unsaved Name")
                viewModel.confirmWardrobeRename()
                assertEquals(RoomWardrobeResult.Failed, sheet().result)
                assertEquals("Unsaved Name", sheet().nameDraft)
                assertFalse(sheet().busy)
                assertEquals(1, interactions.renameRequests.size)
            }
        }
    }

    @Test
    fun loadRemainsSentUntilTheRoomSnapshotShowsTheActualAppearanceAndDowngradesOnContradiction() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        val plan = requireNotNull(sheet().preview)
        viewModel.confirmWardrobeLoad()
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Load, partial = true), sheet().result)
        publishSelf { it.copy(displayName = "Other authoritative update") }
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Load, partial = true), sheet().result)

        publishSelf { it.copy(appearance = plan.appearance) }
        assertEquals(RoomWardrobeResult.AppearanceObserved(partial = true), sheet().result)
        publishSelf { it.copy(appearance = plan.appearance.reversed()) }
        assertEquals(RoomWardrobeResult.AppearanceObserved(partial = true), sheet().result)
        publishSelf { it.copy(appearance = SelfCharacter.appearance) }
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Load, partial = true), sheet().result)
        assertEquals(1, interactions.loadRequests.size)
    }

    @Test
    fun clearingAResultDoesNotRetryOrRestoreAnOldAppearanceObservation() = withFixture {
        openSlot()
        viewModel.previewWardrobeLoad()
        val plan = requireNotNull(sheet().preview)
        viewModel.confirmWardrobeLoad()
        publishSelf { it.copy(appearance = plan.appearance) }
        assertNotNull(sheet().result)
        viewModel.clearWardrobeResult()
        publishSelf { it.copy(displayName = "Updated self") }

        assertNull(sheet().result)
        assertEquals(1, interactions.loadRequests.size)
    }

    @Test
    fun aNewLoginDisplaysItsOwnWardrobeReadbackWithoutCarryingOldSentFeedback() = withFixture {
        openSlot()
        viewModel.beginWardrobeSave()
        viewModel.confirmWardrobeSave()
        assertEquals(RoomWardrobeResult.Sent(RoomWardrobeOperation.Save), sheet().result)
        val readback = wardrobe().copy(
            selfRuleLogSessionId = "new-login",
            revision = 1,
            slots = wardrobe().slots.map { if (it.index == 0) it.copy(name = "Server Readback") else it },
        )
        publish { it.copy(selfRuleLogSessionId = "new-login", wardrobeState = readback) }
        viewModel.openWardrobe()

        assertTrue(sheet().visible)
        assertEquals("Server Readback", sheet().slots.first().name)
        assertNull(sheet().result)
        assertNull(sheet().selectedSlotIndex)
        assertEquals(readback, connection.mutableSnapshot.value.wardrobeState)
    }

    private fun withFixture(
        initial: BcSessionSnapshot = snapshot(),
        version: String = "R129",
        rules: BcRuleCatalog? = Rules,
        save: suspend (BcWardrobeRequest) -> BcWardrobeResult = { BcWardrobeResult.Sent() },
        rename: suspend (BcWardrobeRequest, String) -> BcWardrobeResult = { _, _ -> BcWardrobeResult.Sent() },
        load: suspend (BcWardrobeRequest, BcWardrobePlan) -> BcWardrobeResult = { _, plan -> BcWardrobeResult.Sent(plan) },
        otherWait: suspend () -> Unit = {},
        test: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(initial, version, rules, save, rename, load, otherWait)
        try {
            fixture.test()
        } finally {
            fixture.scope.cancel()
        }
    }

    private class Fixture(
        initial: BcSessionSnapshot,
        version: String,
        rules: BcRuleCatalog?,
        save: suspend (BcWardrobeRequest) -> BcWardrobeResult,
        rename: suspend (BcWardrobeRequest, String) -> BcWardrobeResult,
        load: suspend (BcWardrobeRequest, BcWardrobePlan) -> BcWardrobeResult,
        otherWait: suspend () -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val currentVersion = MutableStateFlow(version)
        val connection = FakeConnectionStatus(initial)
        val interactions = FakeInteractions(save, rename, load, otherWait)
        val rooms = FakeRooms(otherWait)
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = interactions,
            interactionCatalogRepository = object : BcInteractionCatalogRepository {
                override fun currentSnapshot(version: String) = Catalog.takeIf { it.version == version }
                override fun refresh(version: String) = BcInteractionCatalogLoadResult.Ready(Catalog)
            },
            ruleCatalogRepository = object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String) = rules?.takeIf { it.version == version }
                override fun refresh(version: String) = rules?.let { BcRuleCatalogLoadResult.Ready(it) } ?: BcRuleCatalogLoadResult.Failed("unused")
            },
            currentVersion = currentVersion,
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
            rooms = rooms,
            backgroundNamesProvider = { listOf("MainHall") },
        )

        fun sheet(): RoomWardrobeUiState = requireNotNull(viewModel.uiState.value.wardrobe)
        fun openSlot(index: Int = 0) {
            viewModel.openWardrobe()
            viewModel.selectWardrobeSlot(index)
        }
        fun publish(change: (BcSessionSnapshot) -> BcSessionSnapshot) {
            connection.mutableSnapshot.value = change(connection.mutableSnapshot.value)
        }
        fun publishSelf(change: (BcCharacterSnapshot) -> BcCharacterSnapshot) = publish { current ->
            current.copy(roomCharacters = current.roomCharacters.map { if (it.isSelf) change(it) else it })
        }
    }

    private class FakeConnectionStatus(initial: BcSessionSnapshot) : ConnectionStatusReader {
        val mutableSnapshot = MutableStateFlow(initial)
        override val snapshot: StateFlow<BcSessionSnapshot> = mutableSnapshot
    }

    private class FakeInteractions(
        private val save: suspend (BcWardrobeRequest) -> BcWardrobeResult,
        private val rename: suspend (BcWardrobeRequest, String) -> BcWardrobeResult,
        private val load: suspend (BcWardrobeRequest, BcWardrobePlan) -> BcWardrobeResult,
        private val otherWait: suspend () -> Unit,
    ) : BcCharacterInteractions {
        val saveRequests = mutableListOf<BcWardrobeRequest>()
        val renameRequests = mutableListOf<Pair<BcWardrobeRequest, String>>()
        val loadRequests = mutableListOf<Pair<BcWardrobeRequest, BcWardrobePlan>>()
        val selfRequests = mutableListOf<BcSelfStateRequest>()
        val interactionRequests = mutableListOf<BcPermissionRequest>()
        var wardrobeJob: Job? = null
            private set

        override suspend fun saveWardrobe(request: BcWardrobeRequest): BcWardrobeResult {
            saveRequests += request
            wardrobeJob = currentCoroutineContext()[Job]
            return save(request)
        }
        override suspend fun renameWardrobe(request: BcWardrobeRequest, name: String): BcWardrobeResult {
            renameRequests += request to name
            wardrobeJob = currentCoroutineContext()[Job]
            return rename(request, name)
        }
        override suspend fun applyWardrobe(request: BcWardrobeRequest, expectedPlan: BcWardrobePlan): BcWardrobeResult {
            loadRequests += request to expectedPlan
            wardrobeJob = currentCoroutineContext()[Job]
            return load(request, expectedPlan)
        }
        override suspend fun updateSelfState(request: BcSelfStateRequest): BcSelfStateResult {
            selfRequests += request
            otherWait()
            return BcSelfStateResult.Sent
        }
        override suspend fun sendInteraction(request: BcPermissionRequest, activityClientMsgId: String?): BcCharacterInteractionResult {
            interactionRequests += request
            otherWait()
            return BcCharacterInteractionResult.Sent
        }
    }

    private class FakeRooms(private val otherWait: suspend () -> Unit) : BcSessionRooms {
        val updateRequests = mutableListOf<BcRoomUpdateCommand>()
        val memberRequests = mutableListOf<BcRoomMemberAdminCommand>()
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom {
            updateRequests += command
            otherWait()
            return room()
        }
        override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand): BcRoomMemberAdminObservation {
            memberRequests += command
            otherWait()
            return BcRoomMemberAdminObservation.Unconfirmed
        }
    }

    private enum class OtherMutation { SelfState, RoomSettings, MemberAdmin, Interaction }

    private companion object {
        const val SelfMemberNumber = 10
        const val TargetMemberNumber = 20
        const val LocalRoomEpoch = 3L
        const val GatewayRoomEpoch = 81L
        const val AccountGeneration = 7L
        const val WardrobeRevision = 11L
        const val Login = "current-login"
        val Settings = BcRoomBasicSettings(name = "Test Room", description = "Original description")
        val CurrentShirt = BcCharacterItem("Cloth", "OldShirt", "[\"Default\"]", difficulty = 0)
        val CurrentHair = BcCharacterItem("HairFront", "LongHair", "[\"Default\"]", difficulty = 0)
        val CurrentRestraint = BcCharacterItem("ItemHands", "Cuffs", "[\"Default\"]", difficulty = 0)
        val SelfCharacter = BcCharacterSnapshot(
            id = "self", memberNumber = SelfMemberNumber, name = "Self", displayName = "Self", isSelf = true,
            appearance = listOf(CurrentShirt, CurrentHair, CurrentRestraint),
        )
        val TargetCharacter = BcCharacterSnapshot(
            id = "target", memberNumber = TargetMemberNumber, name = "Target", displayName = "Target", isSelf = false,
        )
        val Rules = BcRuleCatalog(
            version = "R129",
            groups = listOf(BcGroupRule("Cloth", clothing = true), BcGroupRule("HairFront"), BcGroupRule("ItemHands"))
                .associateBy { it.name },
            assets = listOf("Cloth" to "OldShirt", "Cloth" to "NewShirt", "HairFront" to "LongHair", "ItemHands" to "Cuffs")
                .associate { (group, name) -> BcAssetKey(group, name) to BcAssetRule(group, name, value = 0.0, defaultColor = listOf("Default")) },
            activities = mapOf("Kiss" to BcActivityRule("Kiss", targetGroups = setOf("Cloth"), selfTargetGroups = setOf("Cloth"))),
            poses = mapOf("Kneel" to BcPoseRule("Kneel", "BodyLower", allowMenu = true)),
        )
        val Catalog = BcInteractionCatalogSnapshot(
            version = "R129", generatedAtEpochMillis = 0,
            groups = listOf(BcInteractionGroup("Cloth", "Clothing", hasActivities = true, hasItems = true)),
            activities = listOf(BcInteractionActivity("Cloth", "Kiss", "Kiss")),
            items = emptyList(), sourcePaths = emptyList(),
        )

        fun savedSlot(index: Int = 0) = BcWardrobeSlot(
            index = index, name = "Saved Outfit", status = BcWardrobeSlotStatus.Saved,
            appearance = listOf(BcWardrobeItem("Cloth", "NewShirt"), BcWardrobeItem("HairFront", "UnknownHair")),
            rawSlotJson = "[[\"NewShirt\",\"Cloth\",\"Default\"],[\"UnknownHair\",\"HairFront\",\"Default\"]]",
        )
        fun wardrobe() = BcWardrobeState(
            memberNumber = SelfMemberNumber, selfRuleLogSessionId = Login, revision = WardrobeRevision,
            rawWardrobeJson = (0 until BcWardrobeSlotCount).joinToString(",", "[", ", {\"future\":true}]") { index ->
                if (index == 0) requireNotNull(savedSlot().rawSlotJson) else "null"
            },
            rawNamesJson = (0 until BcWardrobeSlotCount).joinToString(",", "[", "]") { index -> if (index == 0) "\"Saved Outfit\"" else "\"\"" },
            slots = (0 until BcWardrobeSlotCount).map { index ->
                if (index == 0) savedSlot() else BcWardrobeSlot(index, "", BcWardrobeSlotStatus.Empty, emptyList(), "null")
            },
            wardrobeAvailable = true, namesAvailable = true,
            privateInputs = BcWardrobePrivateInputs(
                gameJson = "{}", inventoryJson = "[]", gameAvailable = true, inventoryAvailable = true,
                inventoryItems = emptyList(), privateCharacterJson = "[]", privateCharacterAvailable = true,
            ),
        )
        fun room() = BcRoom(
            name = BcRoomName(Settings.name), description = Settings.description, memberCount = 2,
            memberLimit = 10, isLocked = false, language = "EN", space = "X", game = "",
            adminSettings = BcRoomAdministration(BcRoomEditBase(BcRoomName(Settings.name), GatewayRoomEpoch, Settings), listOf(SelfMemberNumber)),
        )
        fun snapshot() = BcSessionSnapshot(
            state = BcSessionState.Ready, desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline,
            bcVersion = "R129", account = BcAccount(SelfMemberNumber, "Self", "self"),
            currentRoom = room(), roomPresenceEpoch = LocalRoomEpoch, gatewayRoomPresenceEpoch = GatewayRoomEpoch,
            roomCharacters = listOf(SelfCharacter, TargetCharacter),
            roomMembers = listOf(
                BcMember(SelfMemberNumber, "Self", isSelf = true, role = RoomRole.Admin, roomMemberPresenceEpoch = 5),
                BcMember(TargetMemberNumber, "Target", isSelf = false, role = RoomRole.Member, roomMemberPresenceEpoch = 9),
            ),
            relationships = BcRelationshipSnapshot(accountMemberNumber = SelfMemberNumber, accountGeneration = AccountGeneration),
            selfRuleLogSessionId = Login, selfRuleLog = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom")),
            wardrobeState = wardrobe(),
        )
        fun request(slot: Int = 0) = BcWardrobeRequest(
            targetMemberNumber = SelfMemberNumber, roomName = BcRoomName(Settings.name), roomPresenceEpoch = LocalRoomEpoch,
            accountGeneration = AccountGeneration, slotIndex = slot, expectedRevision = WardrobeRevision, rules = Rules,
        )
        fun ownerChanges(): List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(account = BcAccount(TargetMemberNumber, "Target", "target")) },
            { it.copy(relationships = it.relationships.copy(accountGeneration = AccountGeneration + 1)) },
            { it.copy(selfRuleLogSessionId = "another-login") },
            { it.copy(roomPresenceEpoch = LocalRoomEpoch + 1) },
            { it.copy(gatewayRoomPresenceEpoch = GatewayRoomEpoch + 1) },
            { it.copy(currentRoom = requireNotNull(it.currentRoom).copy(name = BcRoomName("Another Room"))) },
        )
    }
}
