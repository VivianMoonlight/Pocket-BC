package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
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
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.RoomRole
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsMemberAdministrationViewModelTest {
    @Test
    fun cancellationBeforeExecutionReleasesMemberSubmissionWithoutSending() {
        listOf(true, false).forEach { cancelBeforeSubmit ->
            val queued = ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    queued.addLast(block)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val fixture = Fixture(snapshot(), true, { BcRoomMemberAdminObservation.Unconfirmed }, { room(it.settings) }, scope)
            try {
                with(fixture) {
                    while (queued.isNotEmpty()) queued.removeFirst().run()
                    viewModel.openMemberManagement(TargetMemberNumber)
                    viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
                    assertEquals(BcRoomMemberAdminAction.Kick, management().selectedAction)
                    if (cancelBeforeSubmit) scope.cancel()
                    viewModel.confirmMemberAdminAction()
                    scope.cancel()
                    while (queued.isNotEmpty()) queued.removeFirst().run()

                    assertTrue(rooms.memberCommands.isEmpty())
                    assertFalse(viewModel.uiState.value.memberAdministrationBusy)
                    assertFalse(management().busy)
                    assertNull(management().selectedAction)
                    assertNull(management().result)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun entryRequiresVerifiedOnlineSelfAdministratorAndCurrentTargetData() {
        val invalidSnapshots = listOf(
            snapshot().copy(connectionHealth = BcConnectionHealth.ConnectedUnverified),
            snapshot().copy(desiredOnline = false),
            snapshot().copy(account = null),
            snapshot().copy(currentRoom = null),
            snapshot().copy(gatewayRoomPresenceEpoch = null),
            snapshot().copy(selfRuleLogSessionId = null),
            snapshot(selfRole = RoomRole.Member),
            snapshot(selfRole = RoomRole.Owner),
            snapshot(selfRole = null),
            snapshot(targetPresenceEpoch = null),
            snapshot().let { it.copy(roomMembers = it.roomMembers.filterNot { member -> member.isSelf }) },
            snapshot().let { it.copy(roomCharacters = it.roomCharacters.filter { character -> character.isSelf }) },
        )
        invalidSnapshots.forEach { initial ->
            withFixture(initial = initial) {
                assertTrue(viewModel.uiState.value.manageableMemberNumbers.isEmpty())
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
                viewModel.confirmMemberAdminAction()
                assertTrue(rooms.memberCommands.isEmpty())
            }
        }
    }

    @Test
    fun missingRoomsCapabilityHasNoManagementEntry() = withFixture(injectRooms = false) {
        assertTrue(viewModel.uiState.value.manageableMemberNumbers.isEmpty())
        viewModel.openMemberManagement(TargetMemberNumber)
        assertNull(viewModel.uiState.value.memberManagement)
    }

    @Test
    fun unknownTargetRoleExplainsUnavailabilityWithoutOfferingAnAction() {
        listOf<RoomRole?>(null, RoomRole.Owner).forEach { role ->
            withFixture(targetRole = role) {
                assertEquals(setOf(TargetMemberNumber), viewModel.uiState.value.manageableMemberNumbers)
                viewModel.openMemberManagement(TargetMemberNumber)
                assertTrue(management().visible)
                assertTrue(management().availableActions.isEmpty())
                assertEquals(BcRoomMemberAdminRejectionReason.SettingsUnavailable, management().unavailableReason)
                BcRoomMemberAdminAction.entries.forEach(viewModel::selectMemberAdminAction)
                assertNull(management().selectedAction)
                viewModel.confirmMemberAdminAction()
                assertTrue(rooms.memberCommands.isEmpty())
            }
        }
    }

    @Test
    fun selfTargetIsExcludedEvenWhenAnotherAdministratorIsManageable() = withFixture(targetRole = RoomRole.Admin) {
        assertEquals(setOf(TargetMemberNumber), viewModel.uiState.value.manageableMemberNumbers)
        viewModel.openMemberManagement(SelfMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
        viewModel.confirmMemberAdminAction()
        assertTrue(rooms.memberCommands.isEmpty())

        viewModel.openMemberManagement(TargetMemberNumber)
        assertEquals(
            listOf(BcRoomMemberAdminAction.Kick, BcRoomMemberAdminAction.Ban, BcRoomMemberAdminAction.Demote),
            management().availableActions,
        )
    }

    @Test
    fun selectingAndCancellingAnActionNeverSendsARequest() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.confirmMemberAdminAction()
        assertTrue(rooms.memberCommands.isEmpty())

        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
        assertEquals(BcRoomMemberAdminAction.Ban, management().selectedAction)
        assertTrue(rooms.memberCommands.isEmpty())

        viewModel.cancelMemberAdminAction()
        assertNull(management().selectedAction)
        viewModel.confirmMemberAdminAction()
        viewModel.closeMemberManagement()
        assertTrue(rooms.memberCommands.isEmpty())
    }

    @Test
    fun kickingSendsCapturedWireRoomAndTargetEpochOnlyAfterConfirmation() = assertConfirmedAction(
        action = BcRoomMemberAdminAction.Kick,
        observation = BcRoomMemberAdminObservation.TargetLeft,
    )

    @Test
    fun banningAnotherAdministratorIsAllowedAndKeepsTheCapturedRole() = assertConfirmedAction(
        action = BcRoomMemberAdminAction.Ban,
        observation = BcRoomMemberAdminObservation.TargetBanned,
        targetRole = RoomRole.Admin,
    )

    @Test
    fun promotingANonAdministratorUsesThePromoteAction() = assertConfirmedAction(
        action = BcRoomMemberAdminAction.Promote,
        observation = BcRoomMemberAdminObservation.TargetPromoted,
    )

    @Test
    fun demotingAnAdministratorUsesTheDemoteAction() = assertConfirmedAction(
        action = BcRoomMemberAdminAction.Demote,
        observation = BcRoomMemberAdminObservation.TargetDemoted,
        targetRole = RoomRole.Admin,
    )

    @Test
    fun unsupportedRoleActionCannotBeSelectedOrSent() {
        listOf(RoomRole.Member to BcRoomMemberAdminAction.Demote, RoomRole.Admin to BcRoomMemberAdminAction.Promote)
            .forEach { (role, action) ->
                withFixture(targetRole = role) {
                    viewModel.openMemberManagement(TargetMemberNumber)
                    assertFalse(action in management().availableActions)
                    viewModel.selectMemberAdminAction(action)
                    assertNull(management().selectedAction)
                    viewModel.confirmMemberAdminAction()
                    assertTrue(rooms.memberCommands.isEmpty())
                }
            }
    }

    @Test
    fun pendingCloseDoesNotCancelTheRequestAndDuplicateConfirmationIsIgnored() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            assertTrue(management().busy)
            assertTrue(viewModel.uiState.value.memberAdministrationBusy)
            assertTrue(requireNotNull(rooms.memberJob).isActive)

            viewModel.confirmMemberAdminAction()
            viewModel.closeMemberManagement()
            assertFalse(management().visible)
            assertTrue(requireNotNull(rooms.memberJob).isActive)
            assertTrue(viewModel.uiState.value.memberAdministrationBusy)
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            assertEquals(1, rooms.memberCommands.size)

            publishObservationFacts(BcRoomMemberAdminObservation.TargetBanned)
            completion.complete(BcRoomMemberAdminObservation.TargetBanned)
            assertFalse(viewModel.uiState.value.memberAdministrationBusy)
        }
    }

    @Test
    fun pendingMemberRequestBlocksBasicRoomSettingsSubmission() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            viewModel.closeMemberManagement()
            viewModel.openRoomSettings()
            viewModel.uiState.value.roomForm?.let { form ->
                viewModel.changeRoomSettingsDraft(form.draft.copy(description = "Overlapping edit"))
            }
            viewModel.submitRoomSettings()

            assertEquals(1, rooms.memberCommands.size)
            assertTrue(rooms.updateCommands.isEmpty())
            assertTrue(requireNotNull(rooms.memberJob).isActive)
            completion.complete(BcRoomMemberAdminObservation.Unconfirmed)
        }
    }

    @Test
    fun pendingBasicRoomSettingsSubmissionBlocksMemberConfirmation() {
        val completion = CompletableDeferred<BcRoom>()
        withFixture(update = { completion.await() }) {
            viewModel.openRoomSettings()
            val form = requireNotNull(viewModel.uiState.value.roomForm)
            viewModel.changeRoomSettingsDraft(form.draft.copy(description = "Pending edit"))
            viewModel.submitRoomSettings()
            assertTrue(requireNotNull(viewModel.uiState.value.roomForm).busy)

            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            assertEquals(1, rooms.updateCommands.size)
            assertTrue(rooms.memberCommands.isEmpty())
            completion.complete(room(Settings.copy(description = "Pending edit")))
        }
    }

    @Test
    fun unconfirmedObservationIsShownWithoutAutomaticRetryOrRosterMutation() = withFixture {
        val before = connection.mutableSnapshot.value
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        viewModel.confirmMemberAdminAction()

        assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.Unconfirmed), management().result)
        assertFalse(management().busy)
        assertEquals(before, connection.mutableSnapshot.value)
        assertNull(management().selectedAction)
        viewModel.confirmMemberAdminAction()
        viewModel.clearMemberAdminResult()
        assertNull(management().result)
        assertEquals(1, rooms.memberCommands.size)
    }

    @Test
    fun unconfirmedOutcomeRequiresExplicitActionSelectionAndConfirmationForAManualRetry() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        viewModel.confirmMemberAdminAction()
        assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.Unconfirmed), management().result)

        viewModel.clearMemberAdminResult()
        viewModel.confirmMemberAdminAction()
        assertEquals(1, rooms.memberCommands.size)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        assertNull(management().result)
        assertEquals(1, rooms.memberCommands.size)
        viewModel.confirmMemberAdminAction()
        assertEquals(2, rooms.memberCommands.size)
    }

    @Test
    fun everyTypedRejectionUsesTheTypedUiModelWithoutRetry() {
        BcRoomMemberAdminRejectionReason.entries.forEach { reason ->
            withFixture(manage = { throw BcRoomMemberAdminRejectedException(reason) }) {
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
                viewModel.confirmMemberAdminAction()

                assertEquals(RoomMemberManagementResult.Rejected(reason), management().result)
                assertFalse(management().busy)
                assertNull(management().selectedAction)
                viewModel.confirmMemberAdminAction()
                assertEquals(1, rooms.memberCommands.size)
            }
        }
    }

    @Test
    fun unexpectedFailureUsesTheLocalizedFailureModelAndDoesNotRetry() = withFixture(
        manage = { throw IllegalStateException("Raw transport implementation detail") },
    ) {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
        viewModel.confirmMemberAdminAction()

        assertEquals(RoomMemberManagementResult.Failed, management().result)
        assertFalse(management().busy)
        viewModel.confirmMemberAdminAction()
        viewModel.closeMemberManagement()
        viewModel.openMemberManagement(TargetMemberNumber)
        assertEquals(1, rooms.memberCommands.size)
    }

    @Test
    fun targetRoleChangeClearsConfirmationAndRequiresExplicitReselection() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Promote)
        publish { current ->
            current.copy(roomMembers = current.roomMembers.map { member ->
                if (member.memberNumber == TargetMemberNumber) member.copy(role = RoomRole.Admin) else member
            })
        }

        assertNull(management().selectedAction)
        assertEquals(RoomMemberManagementResult.Rejected(BcRoomMemberAdminRejectionReason.TargetRoleChanged), management().result)
        assertFalse(BcRoomMemberAdminAction.Promote in management().availableActions)
        assertTrue(BcRoomMemberAdminAction.Demote in management().availableActions)
        viewModel.confirmMemberAdminAction()
        assertTrue(rooms.memberCommands.isEmpty())

        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Demote)
        viewModel.confirmMemberAdminAction()
        assertEquals(BcRoomMemberAdminAction.Demote, rooms.memberCommands.single().action)
        assertTrue(rooms.memberCommands.single().targetIsAdministrator)
    }

    @Test
    fun roleChangeRequiresReselectionEvenWhenKickIsAvailableForBothRoles() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        publish { current -> current.copy(roomMembers = current.roomMembers.map { member ->
            if (member.memberNumber == TargetMemberNumber) member.copy(role = RoomRole.Admin) else member
        }) }
        assertNull(management().selectedAction)
        assertEquals(RoomMemberManagementResult.Rejected(BcRoomMemberAdminRejectionReason.TargetRoleChanged), management().result)
        viewModel.confirmMemberAdminAction()
        assertTrue(rooms.memberCommands.isEmpty())

        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        viewModel.confirmMemberAdminAction()
        assertTrue(rooms.memberCommands.single().targetIsAdministrator)
    }

    @Test
    fun temporarilyUnknownTargetRoleCannotReviveAnOldConfirmationWhenTheRoleReturns() {
        listOf<RoomRole?>(null, RoomRole.Owner).forEach { unavailableRole ->
            withFixture {
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
                publish { current -> current.copy(roomMembers = current.roomMembers.map { member ->
                    if (member.memberNumber == TargetMemberNumber) member.copy(role = unavailableRole) else member
                }) }
                assertNull(management().selectedAction)
                assertTrue(management().availableActions.isEmpty())
                publish { current -> current.copy(roomMembers = current.roomMembers.map { member ->
                    if (member.memberNumber == TargetMemberNumber) member.copy(role = RoomRole.Member) else member
                }) }
                viewModel.confirmMemberAdminAction()

                assertTrue(rooms.memberCommands.isEmpty())
                viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
                viewModel.confirmMemberAdminAction()
                assertEquals(1, rooms.memberCommands.size)
            }
        }
    }

    @Test
    fun administratorLossBeforeConfirmationCannotSendTheSelectedAction() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        publish { current ->
            current.copy(roomMembers = current.roomMembers.map { member ->
                if (member.memberNumber == SelfMemberNumber) member.copy(role = RoomRole.Member) else member
            })
        }
        viewModel.confirmMemberAdminAction()

        assertTrue(viewModel.uiState.value.manageableMemberNumbers.isEmpty())
        assertTrue(rooms.memberCommands.isEmpty())
    }

    @Test
    fun targetLeavingBeforeConfirmationCannotSend() = withFixture {
        viewModel.openMemberManagement(TargetMemberNumber)
        viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
        removeTarget()
        viewModel.confirmMemberAdminAction()

        assertTrue(rooms.memberCommands.isEmpty())
        assertTrue(viewModel.uiState.value.memberManagement?.selectedAction == null)
    }

    @Test
    fun targetLeavingDuringPendingKickCanStillProduceTheDepartureObservation() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            removeTarget()
            assertTrue(requireNotNull(rooms.memberJob).isActive)
            completion.complete(BcRoomMemberAdminObservation.TargetLeft)

            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.TargetLeft), management().result)
            assertFalse(management().busy)
            assertFalse(TargetMemberNumber in viewModel.uiState.value.manageableMemberNumbers)
        }
    }

    @Test
    fun targetReturningWithANewPresenceEpochCannotReceiveTheOldPendingResult() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            removeTarget()
            publish { current -> current.copy(
                roomMembers = current.roomMembers + targetMember(presenceEpoch = TargetPresenceEpoch + 1),
                roomCharacters = current.roomCharacters + TargetCharacter.copy(displayName = "Returned target"),
            ) }
            assertTrue(viewModel.uiState.value.memberAdministrationBusy)
            assertTrue(requireNotNull(rooms.memberJob).isActive)
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            assertEquals(1, rooms.memberCommands.size)
            completion.complete(BcRoomMemberAdminObservation.TargetBanned)

            assertTrue(viewModel.uiState.value.memberManagement?.selectedAction == null)
            assertTrue(viewModel.uiState.value.memberManagement?.result == null)
            viewModel.openMemberManagement(TargetMemberNumber)
            assertNull(management().result)
            assertEquals("Returned target", management().target.displayName)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun anAlreadyObservedResultCannotFollowTheTargetIntoANewVisit() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            publishObservationFacts(BcRoomMemberAdminObservation.TargetBanned)
            completion.complete(BcRoomMemberAdminObservation.TargetBanned)
            assertNotNull(management().result)
            publish { current -> current.copy(roomMembers = current.roomMembers.map { member ->
                if (member.memberNumber == TargetMemberNumber) member.copy(roomMemberPresenceEpoch = TargetPresenceEpoch + 1) else member
            }) }

            assertTrue(viewModel.uiState.value.memberManagement?.result == null)
            assertTrue(viewModel.uiState.value.memberManagement?.selectedAction == null)
        }
    }

    @Test
    fun aReturnedSuccessWithoutCurrentAuthoritativeEvidenceIsNotShown() {
        listOf(
            Triple(BcRoomMemberAdminAction.Kick, BcRoomMemberAdminObservation.TargetLeft, RoomRole.Member),
            Triple(BcRoomMemberAdminAction.Ban, BcRoomMemberAdminObservation.TargetBanned, RoomRole.Member),
            Triple(BcRoomMemberAdminAction.Promote, BcRoomMemberAdminObservation.TargetPromoted, RoomRole.Member),
            Triple(BcRoomMemberAdminAction.Demote, BcRoomMemberAdminObservation.TargetDemoted, RoomRole.Admin),
        ).forEach { (action, observation, role) ->
            withFixture(targetRole = role, manage = { observation }) {
                val before = connection.mutableSnapshot.value
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(action)
                viewModel.confirmMemberAdminAction()

                assertNull(management().result)
                assertFalse(management().busy)
                assertNull(management().selectedAction)
                assertEquals(before, connection.mutableSnapshot.value)
                assertEquals(1, rooms.memberCommands.size)
            }
        }
    }

    @Test
    fun promotionFeedbackClearsWhenTheSameTargetIsAuthoritativelyDemotedAgain() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Promote)
            viewModel.confirmMemberAdminAction()
            publishObservationFacts(BcRoomMemberAdminObservation.TargetPromoted)
            completion.complete(BcRoomMemberAdminObservation.TargetPromoted)
            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.TargetPromoted), management().result)
            assertTrue(BcRoomMemberAdminAction.Demote in management().availableActions)

            publishObservationFacts(BcRoomMemberAdminObservation.TargetDemoted)

            assertNull(management().result)
            assertTrue(BcRoomMemberAdminAction.Promote in management().availableActions)
            assertFalse(BcRoomMemberAdminAction.Demote in management().availableActions)
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun promotionAndDemotionFeedbackClearWhenTheTargetRoleBecomesUnknown() {
        listOf(
            Triple(BcRoomMemberAdminAction.Promote, BcRoomMemberAdminObservation.TargetPromoted, RoomRole.Member),
            Triple(BcRoomMemberAdminAction.Demote, BcRoomMemberAdminObservation.TargetDemoted, RoomRole.Admin),
        ).forEach { (action, observation, initialRole) ->
            val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
            withFixture(targetRole = initialRole, manage = { completion.await() }) {
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(action)
                viewModel.confirmMemberAdminAction()
                publishObservationFacts(observation)
                completion.complete(observation)
                assertEquals(RoomMemberManagementResult.Observed(observation), management().result)

                publish { current -> current.copy(roomMembers = current.roomMembers.map { member ->
                    if (member.memberNumber == TargetMemberNumber) member.copy(role = null) else member
                }) }

                assertNull(management().result)
                assertEquals(BcRoomMemberAdminRejectionReason.SettingsUnavailable, management().unavailableReason)
                assertTrue(management().availableActions.isEmpty())
            }
        }
    }

    @Test
    fun banFeedbackAndTheDepartedTargetViewClearWhenAnAuthoritativeUnbanArrives() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            publishObservationFacts(BcRoomMemberAdminObservation.TargetBanned)
            removeTarget()
            completion.complete(BcRoomMemberAdminObservation.TargetBanned)
            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.TargetBanned), management().result)

            publish { current -> current.copy(
                currentRoom = requireNotNull(current.currentRoom).copy(banMemberNumbers = emptyList()),
            ) }

            assertNull(viewModel.uiState.value.memberManagement)
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun banFeedbackAndTheDepartedTargetViewClearWhenTheBanListBecomesUnknown() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            publishObservationFacts(BcRoomMemberAdminObservation.TargetBanned)
            removeTarget()
            completion.complete(BcRoomMemberAdminObservation.TargetBanned)
            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.TargetBanned), management().result)

            publish { current -> current.copy(
                currentRoom = requireNotNull(current.currentRoom).copy(banMemberNumbers = null),
            ) }

            assertNull(viewModel.uiState.value.memberManagement)
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun validDepartureFeedbackSurvivesUnrelatedAuthoritativeRoomUpdates() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            removeTarget()
            completion.complete(BcRoomMemberAdminObservation.TargetLeft)

            publish { current -> current.copy(
                currentRoom = requireNotNull(current.currentRoom).copy(description = "Updated description", banMemberNumbers = emptyList()),
                roomCharacters = current.roomCharacters.map { it.copy(displayName = "Updated self name") },
            ) }

            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.TargetLeft), management().result)
            assertEquals(BcRoomMemberAdminRejectionReason.TargetUnavailable, management().unavailableReason)
            assertTrue(management().availableActions.isEmpty())
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun unconfirmedOutcomeForADepartedTargetKeepsFeedbackWithoutAllowingAnotherSend() {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(manage = { completion.await() }) {
            viewModel.openMemberManagement(TargetMemberNumber)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Kick)
            viewModel.confirmMemberAdminAction()
            removeTarget()
            assertTrue(requireNotNull(rooms.memberJob).isActive)
            completion.complete(BcRoomMemberAdminObservation.Unconfirmed)

            assertEquals(RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.Unconfirmed), management().result)
            assertEquals(BcRoomMemberAdminRejectionReason.TargetUnavailable, management().unavailableReason)
            assertTrue(management().availableActions.isEmpty())
            assertFalse(management().busy)
            viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
            viewModel.confirmMemberAdminAction()
            assertEquals(1, rooms.memberCommands.size)
        }
    }

    @Test
    fun ownerChangesClearSelectionAndSuppressTheOldPendingResultWithoutCancellingTheRequest() {
        val ownerChanges: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(account = BcAccount(30, "Other", "other")) },
            { it.copy(relationships = it.relationships.copy(accountGeneration = it.relationships.accountGeneration + 1)) },
            { it.copy(selfRuleLogSessionId = "another-login") },
            { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) },
            { it.copy(gatewayRoomPresenceEpoch = requireNotNull(it.gatewayRoomPresenceEpoch) + 1) },
            { it.copy(currentRoom = requireNotNull(it.currentRoom).copy(name = BcRoomName("Another Room"))) },
        )
        ownerChanges.forEach { change ->
            val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
            withFixture(manage = { completion.await() }) {
                viewModel.openMemberManagement(TargetMemberNumber)
                viewModel.selectMemberAdminAction(BcRoomMemberAdminAction.Ban)
                viewModel.confirmMemberAdminAction()
                publish(change)

                assertTrue(viewModel.uiState.value.memberManagement?.selectedAction == null)
                assertTrue(viewModel.uiState.value.memberManagement?.result == null)
                assertTrue(requireNotNull(rooms.memberJob).isActive)
                completion.complete(BcRoomMemberAdminObservation.TargetBanned)
                assertTrue(viewModel.uiState.value.memberManagement?.result == null)
                assertFalse(viewModel.uiState.value.memberAdministrationBusy)
                assertEquals(1, rooms.memberCommands.size)
            }
        }
    }

    private fun assertConfirmedAction(
        action: BcRoomMemberAdminAction,
        observation: BcRoomMemberAdminObservation,
        targetRole: RoomRole = RoomRole.Member,
    ) {
        val completion = CompletableDeferred<BcRoomMemberAdminObservation>()
        withFixture(targetRole = targetRole, manage = { completion.await() }) {
            val before = connection.mutableSnapshot.value
            viewModel.openMemberManagement(TargetMemberNumber)
            assertTrue(management().visible)
            assertEquals(TargetCharacter, management().target)
            assertTrue(action in management().availableActions)
            viewModel.selectMemberAdminAction(action)
            assertTrue(rooms.memberCommands.isEmpty())
            viewModel.confirmMemberAdminAction()

            assertEquals(
                BcRoomMemberAdminCommand(
                    roomName = BcRoomName(Settings.name),
                    roomPresenceEpoch = GatewayRoomPresenceEpoch,
                    targetMemberNumber = TargetMemberNumber,
                    targetPresenceEpoch = TargetPresenceEpoch,
                    action = action,
                    targetIsAdministrator = targetRole == RoomRole.Admin,
                ),
                rooms.memberCommands.single(),
            )
            assertEquals(before, connection.mutableSnapshot.value)
            assertEquals(before.roomCharacters, viewModel.uiState.value.characters)
            assertNull(management().result)

            publishObservationFacts(observation)
            val authoritative = connection.mutableSnapshot.value
            completion.complete(observation)

            assertEquals(RoomMemberManagementResult.Observed(observation), management().result)
            assertNull(management().selectedAction)
            assertFalse(management().busy)
            assertFalse(viewModel.uiState.value.memberAdministrationBusy)
            assertEquals(authoritative, connection.mutableSnapshot.value)
            assertEquals(authoritative.roomCharacters, viewModel.uiState.value.characters)
        }
    }

    private fun withFixture(
        targetRole: RoomRole? = RoomRole.Member,
        initial: BcSessionSnapshot = snapshot(targetRole = targetRole),
        injectRooms: Boolean = true,
        manage: suspend (BcRoomMemberAdminCommand) -> BcRoomMemberAdminObservation = { BcRoomMemberAdminObservation.Unconfirmed },
        update: suspend (BcRoomUpdateCommand) -> BcRoom = { room(it.settings) },
        test: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(initial, injectRooms, manage, update)
        try {
            fixture.test()
        } finally {
            fixture.scope.cancel()
        }
    }

    private class Fixture(
        initial: BcSessionSnapshot,
        injectRooms: Boolean,
        manage: suspend (BcRoomMemberAdminCommand) -> BcRoomMemberAdminObservation,
        update: suspend (BcRoomUpdateCommand) -> BcRoom,
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) {
        val connection = FakeConnectionStatus(initial)
        val rooms = FakeRooms(manage, update)
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

        fun management(): RoomMemberManagementUiState = requireNotNull(viewModel.uiState.value.memberManagement)

        fun publish(change: (BcSessionSnapshot) -> BcSessionSnapshot) {
            connection.mutableSnapshot.value = change(connection.mutableSnapshot.value)
        }

        fun removeTarget() = publish { current -> current.copy(
            roomMembers = current.roomMembers.filterNot { it.memberNumber == TargetMemberNumber },
            roomCharacters = current.roomCharacters.filterNot { it.memberNumber == TargetMemberNumber },
        ) }

        fun publishObservationFacts(observation: BcRoomMemberAdminObservation) {
            when (observation) {
                BcRoomMemberAdminObservation.TargetLeft -> removeTarget()
                BcRoomMemberAdminObservation.TargetBanned -> publish { current -> current.copy(
                    currentRoom = requireNotNull(current.currentRoom).copy(banMemberNumbers = listOf(TargetMemberNumber)),
                ) }
                BcRoomMemberAdminObservation.TargetPromoted, BcRoomMemberAdminObservation.TargetDemoted -> publish { current ->
                    val role = if (observation == BcRoomMemberAdminObservation.TargetPromoted) RoomRole.Admin else RoomRole.Member
                    current.copy(roomMembers = current.roomMembers.map { member ->
                        if (member.memberNumber == TargetMemberNumber) member.copy(role = role) else member
                    })
                }
                BcRoomMemberAdminObservation.Unconfirmed -> Unit
            }
        }
    }

    private class FakeConnectionStatus(initial: BcSessionSnapshot) : ConnectionStatusReader {
        val mutableSnapshot = MutableStateFlow(initial)
        override val snapshot: StateFlow<BcSessionSnapshot> = mutableSnapshot
    }

    private class FakeRooms(
        private val manage: suspend (BcRoomMemberAdminCommand) -> BcRoomMemberAdminObservation,
        private val update: suspend (BcRoomUpdateCommand) -> BcRoom,
    ) : BcSessionRooms {
        val memberCommands = mutableListOf<BcRoomMemberAdminCommand>()
        val updateCommands = mutableListOf<BcRoomUpdateCommand>()
        var memberJob: Job? = null
            private set

        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand): BcRoomMemberAdminObservation {
            memberCommands += command
            memberJob = currentCoroutineContext()[Job]
            return manage(command)
        }
        override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom {
            updateCommands += command
            return update(command)
        }
    }

    private companion object {
        const val SelfMemberNumber = 10
        const val TargetMemberNumber = 20
        const val LocalRoomPresenceEpoch = 3L
        const val GatewayRoomPresenceEpoch = 81L
        const val TargetPresenceEpoch = 19L
        val Settings = BcRoomBasicSettings(name = "Test Room", description = "Original description")
        val SelfCharacter = BcCharacterSnapshot(
            id = "self", memberNumber = SelfMemberNumber, name = "Self", displayName = "Self", isSelf = true,
        )
        val TargetCharacter = BcCharacterSnapshot(
            id = "target", memberNumber = TargetMemberNumber, name = "Target", displayName = "Target", isSelf = false,
        )

        fun targetMember(role: RoomRole? = RoomRole.Member, presenceEpoch: Long? = TargetPresenceEpoch) = BcMember(
            memberNumber = TargetMemberNumber, name = "Target", isSelf = false,
            role = role, roomMemberPresenceEpoch = presenceEpoch,
        )

        fun room(settings: BcRoomBasicSettings = Settings) = BcRoom(
            name = BcRoomName(settings.name), description = settings.description, memberCount = 2,
            memberLimit = settings.memberLimit, isLocked = false, language = settings.language,
            adminSettings = BcRoomAdministration(
                BcRoomEditBase(BcRoomName(settings.name), GatewayRoomPresenceEpoch, settings), listOf(SelfMemberNumber),
            ),
        )

        fun snapshot(
            selfRole: RoomRole? = RoomRole.Admin,
            targetRole: RoomRole? = RoomRole.Member,
            targetPresenceEpoch: Long? = TargetPresenceEpoch,
        ) = BcSessionSnapshot(
            state = BcSessionState.Ready,
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            account = BcAccount(SelfMemberNumber, "Self", "self"),
            currentRoom = room(),
            roomPresenceEpoch = LocalRoomPresenceEpoch,
            gatewayRoomPresenceEpoch = GatewayRoomPresenceEpoch,
            roomMembers = listOf(
                BcMember(SelfMemberNumber, "Self", isSelf = true, role = selfRole, roomMemberPresenceEpoch = 11),
                targetMember(targetRole, targetPresenceEpoch),
            ),
            roomCharacters = listOf(SelfCharacter, TargetCharacter),
            relationships = BcRelationshipSnapshot(accountMemberNumber = SelfMemberNumber, accountGeneration = 7),
            selfRuleLogSessionId = "current-login",
        )
    }
}
