package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcPoseRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRequest
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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

class RoomDetailsSelfStateViewModelTest {
    @Test
    fun blankVersionOpensPreparingMenuWithoutReadingOrSyncingADefaultVersion() = withFixture(version = "") {
        viewModel.openSelfState()

        val sheet = requireNotNull(viewModel.uiState.value.selfStateSheet)
        assertEquals(BcSelfStateUnavailableReason.CatalogPreparing, sheet.projection.unavailableReason)
        assertTrue(sheet.projection.poseGroups.isEmpty())
        assertTrue(sheet.projection.expressionGroups.isEmpty())
        assertEquals(SelfMemberNumber, sheet.projection.character?.memberNumber)
        assertTrue(rules.requestedVersions.isEmpty())
        assertTrue(catalog.requestedVersions.isEmpty())
        assertTrue(syncedVersions.isEmpty())

        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.CatalogPreparing), sheet().result)
        assertTrue(interactions.requests.isEmpty())
    }

    @Test
    fun openingSelfMenuClosesEveryOtherInteractionMode() = withFixture {
        val openers = listOf<() -> Unit>(
            { viewModel.openInteract(TargetMemberNumber) },
            { viewModel.openActionPicker(TargetMemberNumber) },
            { viewModel.openItemPicker(TargetMemberNumber) },
            { viewModel.openClothingPicker(TargetMemberNumber) },
        )
        openers.forEach { open ->
            open()
            assertNotNull(viewModel.uiState.value.interactionMode)

            viewModel.openSelfState()

            assertNull(viewModel.uiState.value.interactionMode)
            assertNull(viewModel.uiState.value.interactionSheet)
            assertEquals(SelfMemberNumber, sheet().projection.character?.memberNumber)
        }
    }

    @Test
    fun allSelfChangesUseTheAuthenticatedMemberAndCapturedAccountAndRoomVisit() = withFixture {
        viewModel.openInteract(TargetMemberNumber)
        viewModel.openSelfState()
        val changes = listOf(
            BcSelfStateChange.Pose("Kneel"),
            BcSelfStateChange.ResetPose,
            BcSelfStateChange.Expression("Mouth", "Open"),
            BcSelfStateChange.ResetExpressions,
        )

        changes.forEach(viewModel::changeSelfState)

        assertEquals(changes, interactions.requests.map { it.change })
        interactions.requests.forEach { request ->
            assertEquals(SelfMemberNumber, request.targetMemberNumber)
            assertEquals(BcRoomName("Test Room"), request.roomName)
            assertEquals(RoomEpoch, request.roomPresenceEpoch)
            assertEquals(AccountGeneration, request.accountGeneration)
            assertEquals(TestRules, request.rules)
        }
        assertFalse(sheet().busy)
        assertEquals(BcSelfStateResult.Sent, sheet().result)
    }

    @Test
    fun authenticatedVersionArrivalPopulatesTheAlreadyOpenMenu() = withFixture(version = "") {
        viewModel.openSelfState()
        assertEquals(BcSelfStateUnavailableReason.CatalogPreparing, sheet().projection.unavailableReason)

        currentVersion.value = "R129"

        assertNull(sheet().projection.unavailableReason)
        assertEquals(listOf("BodyUpper", "BodyLower"), sheet().projection.poseGroups.map { it.category })
        assertEquals(listOf("Mouth"), sheet().projection.expressionGroups.map { it.group })
        assertEquals(listOf(null, "Open"), sheet().projection.expressionGroups.single().options.map { it.name })
        assertTrue(rules.requestedVersions.all { it == "R129" })
    }

    @Test
    fun alreadyCancelledScopeCannotLeaveSelfStateBusy() = withFixture {
        viewModel.openSelfState()
        scope.cancel()
        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
        assertTrue(interactions.requests.isEmpty())
        assertFalse(sheet().busy)
        assertNull(sheet().result)
    }

    @Test
    fun busyMenuDoesNotSendDuplicateChanges() = withFixture(send = { awaitCancellation() }) {
        viewModel.openSelfState()
        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
        assertTrue(sheet().busy)

        viewModel.changeSelfState(BcSelfStateChange.Pose("OverTheHead"))

        assertEquals(listOf(BcSelfStateChange.Pose("Kneel")), interactions.requests.map { it.change })
        assertNull(sheet().result)
    }

    @Test
    fun typedRejectionIsShownAndCanBeCleared() = withFixture(
        send = { BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.PoseBlockedByOwner) },
    ) {
        viewModel.openSelfState()
        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.PoseBlockedByOwner), sheet().result)
        assertFalse(sheet().busy)

        viewModel.clearSelfStateResult()

        assertNull(sheet().result)
        assertNotNull(viewModel.uiState.value.selfStateSheet)
    }

    @Test
    fun returnedFailureRemainsVisibleAndClearsBusyState() = withFixture(
        send = { BcSelfStateResult.Failed("server unavailable") },
    ) {
        viewModel.openSelfState()
        viewModel.changeSelfState(BcSelfStateChange.Expression("Mouth", "Open"))

        assertEquals(BcSelfStateResult.Failed("server unavailable"), sheet().result)
        assertFalse(sheet().busy)
    }

    @Test
    fun thrownFailureBecomesAVisibleResultAndAllowsRetry() = withFixture(
        send = { throw IllegalStateException("socket closed") },
    ) {
        viewModel.openSelfState()
        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

        assertEquals(BcSelfStateResult.Failed("socket closed"), sheet().result)
        assertFalse(sheet().busy)

        viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

        assertEquals(2, interactions.requests.size)
    }

    @Test
    fun latestEquipmentRefreshesDisabledPoseAndRejectsThePreviouslyAvailableChoice() = withFixture {
        viewModel.openSelfState()
        assertNull(sheet().projection.poseGroups.single { it.category == "BodyUpper" }
            .options.single { it.name == "OverTheHead" }.unavailableReason)
        val self = connection.mutableSnapshot.value.roomCharacters.first { it.isSelf }
        connection.mutableSnapshot.value = snapshot(
            self.copy(appearance = self.appearance + BcCharacterItem(
                group = "ItemArms",
                name = "Cuffs",
                propertyJson = "{\"AllowActivePose\":[\"BaseUpper\"]}",
            )),
        )

        assertEquals(BcSelfStateUnavailableReason.PoseRestrictedByItem,
            sheet().projection.poseGroups.single { it.category == "BodyUpper" }
                .options.single { it.name == "OverTheHead" }.unavailableReason)
        viewModel.changeSelfState(BcSelfStateChange.Pose("OverTheHead"))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.PoseRestrictedByItem), sheet().result)
        assertTrue(interactions.requests.isEmpty())
    }

    @Test
    fun closingCancelsPendingSendAndLateResultCannotUpdateReopenedMenu() {
        var continuation: Continuation<BcSelfStateResult>? = null
        var requestJob: Job? = null
        withFixture(send = {
            requestJob = currentCoroutineContext()[Job]
            suspendCoroutine { continuation = it }
        }) {
            viewModel.openSelfState()
            viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
            assertTrue(sheet().busy)

            viewModel.closeSelfState()

            assertTrue(requestJob?.isCancelled == true)
            assertNull(viewModel.uiState.value.selfStateSheet)
            viewModel.openSelfState()
            requireNotNull(continuation).resume(BcSelfStateResult.Failed("stale"))

            assertNull(sheet().result)
            assertFalse(sheet().busy)
        }
    }

    @Test
    fun leavingRoomClosesMenuAndCancelsPendingSend() = assertOwnerChangeCancels { current ->
        current.copy(currentRoom = null, roomCharacters = emptyList(), roomPresenceEpoch = RoomEpoch + 1)
    }

    @Test
    fun rejoiningTheSameNamedRoomClosesMenuAndCancelsOldVisitSend() = assertOwnerChangeCancels { current ->
        current.copy(roomPresenceEpoch = RoomEpoch + 1)
    }

    @Test
    fun accountSwitchClosesMenuAndCancelsOldAccountSend() = assertOwnerChangeCancels { current ->
        current.copy(
            account = BcAccount(TargetMemberNumber, "Target", "target"),
            roomCharacters = current.roomCharacters.map { it.copy(isSelf = it.memberNumber == TargetMemberNumber) },
            relationships = current.relationships.copy(accountMemberNumber = TargetMemberNumber, accountGeneration = AccountGeneration + 1),
        )
    }

    @Test
    fun sameMemberNewAccountGenerationClosesMenuAndCancelsPendingSend() = assertOwnerChangeCancels { current ->
        current.copy(relationships = current.relationships.copy(accountGeneration = AccountGeneration + 1))
    }

    @Test
    fun pickerBackClosesSelfMenuAndCancelsPendingSend() {
        var requestJob: Job? = null
        withFixture(send = {
            requestJob = currentCoroutineContext()[Job]
            awaitCancellation()
        }) {
            viewModel.openSelfState()
            viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

            assertTrue(viewModel.handlePickerBack())

            assertTrue(requestJob?.isCancelled == true)
            assertNull(viewModel.uiState.value.selfStateSheet)
            assertFalse(viewModel.handlePickerBack())
        }
    }

    @Test
    fun openingAnotherInteractionClosesSelfMenuAndCancelsPendingSend() {
        var requestJob: Job? = null
        withFixture(send = {
            requestJob = currentCoroutineContext()[Job]
            awaitCancellation()
        }) {
            viewModel.openSelfState()
            viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))

            viewModel.openActionPicker(TargetMemberNumber)

            assertTrue(requestJob?.isCancelled == true)
            assertNull(viewModel.uiState.value.selfStateSheet)
            assertEquals(RoomDetailsInteractionMode.ActionPicker, viewModel.uiState.value.interactionMode)
        }
    }

    private fun assertOwnerChangeCancels(change: (BcSessionSnapshot) -> BcSessionSnapshot) {
        var requestJob: Job? = null
        withFixture(send = {
            requestJob = currentCoroutineContext()[Job]
            awaitCancellation()
        }) {
            viewModel.openSelfState()
            viewModel.changeSelfState(BcSelfStateChange.Pose("Kneel"))
            assertTrue(sheet().busy)

            connection.mutableSnapshot.value = change(connection.mutableSnapshot.value)

            assertTrue(requestJob?.isCancelled == true)
            assertNull(viewModel.uiState.value.selfStateSheet)
            viewModel.changeSelfState(BcSelfStateChange.Pose("OverTheHead"))
            assertEquals(1, interactions.requests.size)
        }
    }

    private fun withFixture(
        version: String = "R129",
        send: suspend (BcSelfStateRequest) -> BcSelfStateResult = { BcSelfStateResult.Sent },
        test: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(version, send)
        try {
            fixture.test()
        } finally {
            fixture.scope.cancel()
        }
    }

    private class Fixture(version: String, send: suspend (BcSelfStateRequest) -> BcSelfStateResult) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val currentVersion = MutableStateFlow(version)
        val connection = FakeConnectionStatusReader()
        val interactions = FakeCharacterInteractions(send)
        val catalog = FakeInteractionCatalogRepository()
        val rules = FakeRuleCatalogRepository()
        val syncedVersions = mutableListOf<String>()
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = interactions,
            interactionCatalogRepository = catalog,
            ruleCatalogRepository = rules,
            currentVersion = currentVersion,
            syncResources = { syncedVersions += it },
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        fun sheet(): SelfStateSheetUiState = requireNotNull(viewModel.uiState.value.selfStateSheet)
    }

    private class FakeConnectionStatusReader : ConnectionStatusReader {
        val mutableSnapshot = MutableStateFlow(snapshot())
        override val snapshot: StateFlow<BcSessionSnapshot> = mutableSnapshot
    }

    private class FakeCharacterInteractions(
        private val send: suspend (BcSelfStateRequest) -> BcSelfStateResult,
    ) : BcCharacterInteractions {
        val requests = mutableListOf<BcSelfStateRequest>()

        override suspend fun updateSelfState(request: BcSelfStateRequest): BcSelfStateResult {
            requests += request
            return send(request)
        }

        override suspend fun sendInteraction(request: BcPermissionRequest, activityClientMsgId: String?): BcCharacterInteractionResult =
            BcCharacterInteractionResult.Sent
    }

    private class FakeInteractionCatalogRepository : BcInteractionCatalogRepository {
        val requestedVersions = mutableListOf<String>()

        override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? {
            requestedVersions += version
            return TestCatalog.takeIf { it.version == version }
        }

        override fun refresh(version: String): BcInteractionCatalogLoadResult {
            requestedVersions += version
            return BcInteractionCatalogLoadResult.Ready(TestCatalog)
        }
    }

    private class FakeRuleCatalogRepository : BcRuleCatalogRepository {
        val requestedVersions = mutableListOf<String>()

        override fun currentSnapshot(version: String): BcRuleCatalog? {
            requestedVersions += version
            return TestRules.takeIf { it.version == version }
        }

        override fun refresh(version: String): BcRuleCatalogLoadResult {
            requestedVersions += version
            return BcRuleCatalogLoadResult.Ready(TestRules)
        }
    }

    private companion object {
        const val SelfMemberNumber = 10
        const val TargetMemberNumber = 20
        const val RoomEpoch = 3L
        const val AccountGeneration = 7L

        val SelfCharacter = BcCharacterSnapshot(
            id = "self",
            memberNumber = SelfMemberNumber,
            name = "Self",
            displayName = "Self",
            isSelf = true,
            appearance = listOf(BcCharacterItem("Mouth", "Default")),
        )
        val TargetCharacter = BcCharacterSnapshot(
            id = "target",
            memberNumber = TargetMemberNumber,
            name = "Target",
            displayName = "Target",
            isSelf = false,
        )

        fun snapshot(self: BcCharacterSnapshot = SelfCharacter) = BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            bcVersion = "R129",
            account = BcAccount(SelfMemberNumber, "Self", "self"),
            currentRoom = BcRoom(BcRoomName("Test Room"), "", 2, 10, false, null),
            roomPresenceEpoch = RoomEpoch,
            roomCharacters = listOf(self, TargetCharacter),
            relationships = BcRelationshipSnapshot(accountMemberNumber = SelfMemberNumber, accountGeneration = AccountGeneration),
        )

        val TestCatalog = BcInteractionCatalogSnapshot(
            version = "R129",
            generatedAtEpochMillis = 0,
            groups = emptyList(),
            activities = emptyList(),
            items = emptyList(),
            sourcePaths = emptyList(),
        )
        val TestRules = BcRuleCatalog(
            version = "R129",
            groups = mapOf("Mouth" to BcGroupRule("Mouth", allowExpression = listOf("Open"))),
            activities = emptyMap(),
            assets = emptyMap(),
            poses = listOf(
                BcPoseRule("BaseUpper", "BodyUpper", allowMenu = true),
                BcPoseRule("OverTheHead", "BodyUpper", allowMenu = true),
                BcPoseRule("BaseLower", "BodyLower", allowMenu = true),
                BcPoseRule("Kneel", "BodyLower", allowMenu = true),
            ).associateBy { it.name },
        )
    }
}
