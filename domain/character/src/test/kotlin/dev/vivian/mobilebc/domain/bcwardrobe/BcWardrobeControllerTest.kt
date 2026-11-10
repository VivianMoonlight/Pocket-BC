package dev.vivian.mobilebc.domain.bcwardrobe

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfAppearanceUpdateCommand
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.character.BcCharacterInteractionsController
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BcWardrobeControllerTest {
    private val planner = BcWardrobePlanner(serverTimeMillis = { 1_000L })

    @Test
    fun savePassesFullAppearanceBundleAndExactLoginExpectationToProtectedGateway() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))

        val result = controller(gateway, MutableStateFlow(started)).saveWardrobe(request(started))

        assertEquals(BcWardrobeResult.Sent(), result)
        assertEquals(BcWardrobeSaveRequest(0, 5), gateway.savedRequest)
        assertEquals(setOf("BodyUpper", "HairFront", "Cloth", "Eyes"), gateway.savedAppearance?.map { it.group }?.toSet())
        assertTrue(checkNotNull(gateway.savedAppearance).all { BcItemPropertyJson.readPropertyValue(it.propertyJson, "Expression") == null })
        assertEquals(expectation(started), gateway.expectations.single())
        assertEquals(listOf("save"), gateway.emitted)
    }

    @Test
    fun renamePassesNameAndRevisionToProtectedGateway() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))

        val result = controller(gateway, MutableStateFlow(started)).renameWardrobe(request(started), "Evening 2")

        assertEquals(BcWardrobeResult.Sent(), result)
        assertEquals(BcWardrobeRenameRequest(0, 5, "Evening 2"), gateway.renamedRequest)
        assertEquals(expectation(started), gateway.expectations.single())
        assertEquals(listOf("rename"), gateway.emitted)
    }

    @Test
    fun renameRejectsInvalidNameAndUnknownNamesWithoutGatewayMutation() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))
        val snapshots = MutableStateFlow(started)
        val controller = controller(gateway, snapshots)
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.InvalidName), controller.renameWardrobe(request(started), "礼服"))
        snapshots.value = started.copy(wardrobeState = started.wardrobeState?.copy(namesAvailable = false))
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.NamesUnavailable), controller.renameWardrobe(request(started), "Outfit"))
        assertTrue(gateway.emitted.isEmpty())
        assertTrue(gateway.expectations.isEmpty())
    }

    @Test
    fun applyingReviewedPlanSendsWholeAppearanceAndRetainsCurrentPoseAndItems() = runTest {
        val started = snapshot()
        val request = request(started)
        val plan = planner.plan(request, started)
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))

        val result = controller(gateway, MutableStateFlow(started)).applyWardrobe(request, plan)

        assertEquals(BcWardrobeResult.Sent(plan), result)
        assertEquals(BcSelfAppearanceUpdateCommand(plan.appearance, started.roomCharacters.single().activePose, 5, 0), gateway.restoredCommand)
        assertEquals(started.roomCharacters.single().appearance.single { it.group == "ItemArms" }, gateway.restoredCommand?.appearance?.single { it.group == "ItemArms" })
        assertEquals("\"Happy\"", BcItemPropertyJson.readPropertyValue(gateway.restoredCommand?.appearance?.single { it.group == "Eyes" }?.propertyJson, "Expression"))
        assertEquals(expectation(started), gateway.expectations.single())
    }

    @Test
    fun applyRejectsPreviewFromOlderCharacterStateAndForgedAppearance() = runTest {
        val started = snapshot()
        val request = request(started)
        val plan = planner.plan(request, started)
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))
        val snapshots = MutableStateFlow(started.withSelf { it.copy(appearance = it.appearance + BcCharacterItem("ItemNeck", "Collar")) })
        val controller = controller(gateway, snapshots)
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), controller.applyWardrobe(request, plan))
        snapshots.value = started
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), controller.applyWardrobe(request, plan.copy(appearance = emptyList())))
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun noChangePreviewDoesNotEmitWholeAppearanceAgain() = runTest {
        val started = snapshot().withSelf { self ->
            self.copy(appearance = self.appearance.map { if (it.group == "ItemArms") it else it.copy(craftJson = null) })
        }.let { current ->
            val unchanged = current.roomCharacters.single().appearance.filter { it.group != "ItemArms" }.map {
                BcWardrobeItem(it.group, it.name, it.colorJson, it.propertyJson)
            }
            current.copy(wardrobeState = current.wardrobeState?.copy(slots = listOf(BcWardrobeSlot(0, "Same", BcWardrobeSlotStatus.Saved, unchanged))))
        }
        val request = request(started)
        val plan = planner.plan(request, started)
        assertEquals(false, plan.hasChanges)
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))

        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.NoChanges), controller(gateway, MutableStateFlow(started)).applyWardrobe(request, plan))
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun unsupportedExistingSlotCannotBeOverwrittenBySave() = runTest {
        val started = snapshot().let { it.copy(wardrobeState = it.wardrobeState?.copy(slots = listOf(BcWardrobeSlot(0, "Legacy", BcWardrobeSlotStatus.Unsupported, null)))) }
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState))
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.UnsupportedSlot), controller(gateway, MutableStateFlow(started)).saveWardrobe(request(started)))
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun staleAccountAndRevisionRejectEveryWardrobeAction() = runTest {
        val started = snapshot()
        val request = request(started)
        val plan = planner.plan(request, started)
        listOf(
            started.copy(relationships = started.relationships.copy(accountGeneration = 8)) to BcWardrobeUnavailableReason.AccountChanged,
            started.copy(wardrobeState = started.wardrobeState?.copy(revision = 6)) to BcWardrobeUnavailableReason.RevisionChanged,
        ).forEach { (latest, reason) ->
            val gateway = RecordingGateway(checkNotNull(started.wardrobeState))
            val controller = controller(gateway, MutableStateFlow(latest))
            assertEquals(BcWardrobeResult.Rejected(reason), controller.saveWardrobe(request))
            assertEquals(BcWardrobeResult.Rejected(reason), controller.renameWardrobe(request, "Outfit"))
            assertEquals(BcWardrobeResult.Rejected(reason), controller.applyWardrobe(request, plan))
            assertTrue(gateway.emitted.isEmpty())
        }
    }

    @Test
    fun queuedApplyCannotEmitAfterLeavingAndRejoiningSameRoom() = runTest {
        val (result, gateway) = queuedAction(Action.Apply) { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) }
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.RoomChanged), result)
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun queuedSaveCannotEmitAfterSameAccountLoginGenerationChanges() = runTest {
        val (result, gateway) = queuedAction(Action.Save) {
            it.copy(relationships = it.relationships.copy(accountGeneration = it.relationships.accountGeneration + 1))
        }
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.AccountChanged), result)
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun queuedRenameCannotOverwriteANewerWardrobeRevision() = runTest {
        val (result, gateway) = queuedAction(Action.Rename) { it.copy(wardrobeState = it.wardrobeState?.copy(revision = 6)) }
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.RevisionChanged), result)
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun queuedActionsRecheckPrivateRulesAndDoNotTreatNewBlockAsGatewayFailure() = runTest {
        Action.entries.forEach { action ->
            val (result, gateway) = queuedAction(action) { it.copy(selfRuleLog = checkNotNull(it.selfRuleLog) + BcOwnerRuleLogRecord("BlockChange", "Rule")) }
            assertEquals(action.name, BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.BlockChangeRule), result)
            assertTrue(action.name, gateway.emitted.isEmpty())
        }
    }

    @Test
    fun queuedWholeAppearanceCannotOverwriteEquipmentColorPoseOrExpressionChanges() = runTest {
        val changes: Map<String, (BcCharacterSnapshot) -> BcCharacterSnapshot> = linkedMapOf(
            "equipment" to { self -> self.copy(appearance = self.appearance + BcCharacterItem("ItemNeck", "Collar")) },
            "color" to { self -> self.copy(appearance = self.appearance.map { if (it.group == "HairFront") it.copy(colorJson = "\"Blue\"") else it }) },
            "pose" to { self -> self.copy(activePose = listOf("AllFours")) },
            "expression" to { self -> self.copy(appearance = self.appearance.map { if (it.group == "Eyes") it.copy(propertyJson = "{\"Expression\":\"Sad\"}") else it }) },
        )
        changes.forEach { (description, change) ->
            val (result, gateway) = queuedAction(Action.Apply) { it.withSelf(change) }
            assertEquals(description, BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), result)
            assertTrue(description, gateway.emitted.isEmpty())
        }
    }

    @Test
    fun changedPrivateInventoryInvalidatesReviewedPlanBeforeActualEmit() = runTest {
        val (result, gateway) = queuedAction(Action.Apply) { current ->
            current.copy(wardrobeState = current.wardrobeState?.let { it.copy(privateInputs = it.privateInputs.copy(inventoryIds = listOf(42))) })
        }
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged), result)
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun gatewayCancellationPropagatesAndFailureRetainsUsefulMessage() = runTest {
        val started = snapshot()
        val cancellation = CancellationException("Disconnected")
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState)).apply { beforeValidate = { throw cancellation } }
        var caught: CancellationException? = null
        try {
            controller(gateway, MutableStateFlow(started)).saveWardrobe(request(started))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        gateway.beforeValidate = { throw IllegalStateException("Socket unavailable") }
        assertEquals(BcWardrobeResult.Failed("Socket unavailable"), controller(gateway, MutableStateFlow(started)).renameWardrobe(request(started), "Outfit"))
        assertTrue(gateway.emitted.isEmpty())
    }

    @Test
    fun saveAndApplyShareTheMutexSoOldRevisionCannotProceedAfterSaveSync() = runTest {
        val started = snapshot()
        val snapshots = MutableStateFlow(started)
        val request = request(started)
        val plan = planner.plan(request, started)
        val queued = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState)).apply {
            beforeValidate = { queued.complete(Unit); resume.await() }
            afterEmit = { snapshots.value = started.copy(wardrobeState = started.wardrobeState?.copy(revision = 6)) }
        }
        val controller = controller(gateway, snapshots)
        val save = async { controller.saveWardrobe(request) }
        queued.await()
        val applyStarted = CompletableDeferred<Unit>()
        val apply = async { applyStarted.complete(Unit); controller.applyWardrobe(request, plan) }
        applyStarted.await()
        assertTrue(gateway.emitted.isEmpty())
        resume.complete(Unit)
        assertEquals(BcWardrobeResult.Sent(), save.await())
        assertEquals(BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.RevisionChanged), apply.await())
        assertEquals(listOf("save"), gateway.emitted)
    }

    private enum class Action { Save, Rename, Apply }

    private suspend fun TestScope.queuedAction(
        action: Action,
        change: (BcSessionSnapshot) -> BcSessionSnapshot,
    ): Pair<BcWardrobeResult, RecordingGateway> {
        val started = snapshot()
        val snapshots = MutableStateFlow(started)
        val request = request(started)
        val plan = planner.plan(request, started)
        val queued = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = RecordingGateway(checkNotNull(started.wardrobeState)).apply { beforeValidate = { queued.complete(Unit); resume.await() } }
        val controller = controller(gateway, snapshots)
        val result = async {
            when (action) {
                Action.Save -> controller.saveWardrobe(request)
                Action.Rename -> controller.renameWardrobe(request, "Outfit")
                Action.Apply -> controller.applyWardrobe(request, plan)
            }
        }
        queued.await()
        snapshots.value = change(snapshots.value)
        resume.complete(Unit)
        return result.await() to gateway
    }

    private fun controller(gateway: InteractionGateway, snapshot: MutableStateFlow<BcSessionSnapshot>) = BcCharacterInteractionsController(
        gateway = gateway, sessionSnapshot = snapshot, wardrobePlanner = planner,
    )

    private fun request(snapshot: BcSessionSnapshot) = BcWardrobeRequest(
        10, checkNotNull(snapshot.currentRoom).name, snapshot.roomPresenceEpoch, snapshot.relationships.accountGeneration,
        0, checkNotNull(snapshot.wardrobeState).revision, catalog(),
    )

    private fun expectation(snapshot: BcSessionSnapshot) = BcSelfStateEmitExpectation(
        snapshot.roomCharacters.single(), ownerPresent = false, selfRuleLogSessionId = snapshot.selfRuleLogSessionId,
    )

    private fun BcSessionSnapshot.withSelf(change: (BcCharacterSnapshot) -> BcCharacterSnapshot) = copy(roomCharacters = listOf(change(roomCharacters.single())))

    private fun snapshot(): BcSessionSnapshot {
        val character = BcCharacterSnapshot(
            "10", 10, "Self", "Self", true, activePose = listOf("Kneel", "Yoked"),
            appearance = listOf(
                BcCharacterItem("BodyUpper", "Normal", "[\"Default\"]", difficulty = 0),
                BcCharacterItem("HairFront", "Short", "[\"Default\"]", difficulty = 0),
                BcCharacterItem("Cloth", "OldDress", "[\"Default\"]", difficulty = 0, craftJson = "{\"Name\":\"Handmade\"}"),
                BcCharacterItem("Eyes", "Eyes1", "[\"Default\"]", difficulty = 0, propertyJson = "{\"Expression\":\"Happy\"}"),
                BcCharacterItem("ItemArms", "Rope", "\"Green\"", difficulty = 5, craftJson = "{\"Name\":\"Rope\"}"),
            ),
        )
        val wardrobe = BcWardrobeState(
            10, "login-7", 5, "[]", "[]",
            listOf(BcWardrobeSlot(0, "Outfit", BcWardrobeSlotStatus.Saved, listOf(
                BcWardrobeItem("BodyUpper", "Normal"), BcWardrobeItem("HairFront", "Long"),
                BcWardrobeItem("Cloth", "NewDress"), BcWardrobeItem("Eyes", "Eyes1"),
            ))), true, true,
            BcWardrobePrivateInputs(gameAvailable = true, inventoryAvailable = true, inventoryItems = emptyList(), privateCharacterAvailable = true),
        )
        return BcSessionSnapshot(
            state = BcSessionState.Ready, connectionHealth = BcConnectionHealth.VerifiedOnline, desiredOnline = true,
            bcVersion = "R129", account = BcAccount(10, "Self", "self"),
            currentRoom = BcRoom(BcRoomName("Room"), "", 1, 10, false, null, space = "", game = ""),
            roomPresenceEpoch = 3, roomCharacters = listOf(character),
            relationships = BcRelationshipSnapshot(accountMemberNumber = 10, accountGeneration = 7),
            selfRuleLog = listOf(BcOwnerRuleLogRecord("Wardrobe", "PrivateRoom")), selfRuleLogSessionId = "login-7", wardrobeState = wardrobe,
        )
    }

    private fun catalog(): BcRuleCatalog {
        val groups = listOf(
            BcGroupRule("BodyUpper", category = "Appearance"), BcGroupRule("HairFront", category = "Appearance"),
            BcGroupRule("Cloth", category = "Appearance", clothing = true),
            BcGroupRule("Eyes", category = "Appearance", allowExpression = listOf("Happy", "Sad")),
            BcGroupRule("ItemArms", category = "Item"), BcGroupRule("ItemNeck", category = "Item"),
        )
        val assets = listOf("BodyUpper" to "Normal", "HairFront" to "Short", "HairFront" to "Long", "Cloth" to "OldDress", "Cloth" to "NewDress", "Eyes" to "Eyes1", "ItemArms" to "Rope", "ItemNeck" to "Collar")
            .map { (group, name) -> BcAssetRule(group, name, value = 0.0, defaultColor = listOf("Default")) }
        return BcRuleCatalog("R129", groups.associateBy { it.name }, emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) })
    }

    private class RecordingGateway(private val state: BcWardrobeState) : InteractionGateway {
        val emitted = mutableListOf<String>()
        val expectations = mutableListOf<BcSelfStateEmitExpectation>()
        var savedRequest: BcWardrobeSaveRequest? = null
        var savedAppearance: List<BcWardrobeItem>? = null
        var renamedRequest: BcWardrobeRenameRequest? = null
        var restoredCommand: BcSelfAppearanceUpdateCommand? = null
        var beforeValidate: suspend () -> Unit = {}
        var afterEmit: () -> Unit = {}

        override suspend fun queryAllowItem(memberNumber: Int): Boolean = error("Unexpected item query")
        override suspend fun sendActivity(command: BcActivityCommand): Unit = error("Unexpected activity")
        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand): Unit = error("Unexpected single item update")

        override suspend fun saveWardrobe(request: BcWardrobeSaveRequest, appearance: List<BcWardrobeItem>, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit): BcWardrobeState {
            expectations += expectedSelfState
            beforeValidate()
            validateBeforeEmit()
            emitted += "save"
            savedRequest = request
            savedAppearance = appearance
            afterEmit()
            return state.copy(revision = state.revision + 1)
        }

        override suspend fun renameWardrobe(request: BcWardrobeRenameRequest, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit): BcWardrobeState {
            expectations += expectedSelfState
            beforeValidate()
            validateBeforeEmit()
            emitted += "rename"
            renamedRequest = request
            afterEmit()
            return state.copy(revision = state.revision + 1)
        }

        override suspend fun restoreSelfAppearance(command: BcSelfAppearanceUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit) {
            expectations += expectedSelfState
            beforeValidate()
            validateBeforeEmit()
            emitted += "apply"
            restoredCommand = command
            afterEmit()
        }
    }
}
