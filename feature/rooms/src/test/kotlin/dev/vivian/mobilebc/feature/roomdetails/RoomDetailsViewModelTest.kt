package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionActivity
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionGroup
import dev.vivian.mobilebc.core.model.BcInteractionItem
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class RoomDetailsViewModelTest {
    @Test fun timerChangesRejectInvalidDurationsAndOldTargetsBeforeSending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val target = TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "BallGag", propertyJson =
            """{"LockedBy":"TimerPasswordPadlock","LockMemberNumber":${SelfCharacter.memberNumber},"RemoveTimer":10000,"ShowTimer":true}""")))
        val rules = TestRules.copy(assets = listOf(BcAssetRule(TestGroup, "BallGag", allowLock = true),
            BcAssetRule("ItemMisc", "TimerPasswordPadlock", isLock = true, removeTimerSeconds = 300, maxTimerSeconds = 3600)).associateBy { BcAssetKey(it.group, it.name) })
        val connection = FakeConnectionStatusReader(roomSnapshot("Test Room", listOf(SelfCharacter, target)).copy(
            serverTimeAnchor = dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(1000, System.nanoTime())))
        var sends = 0
        val viewModel = RoomDetailsViewModel(connection, FakeCharacterInteractions { sends++; BcCharacterInteractionResult.Sent }, ReadyInteractionCatalogRepository,
            object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String) = rules
                override fun refresh(version: String) = BcRuleCatalogLoadResult.Ready(rules)
            }, currentVersion = MutableStateFlow("R133"), scope = scope, catalogDispatcher = Dispatchers.Unconfined)
        try {
            viewModel.openItemPicker(TargetMemberNumber); viewModel.selectInteractionGroup(TestGroup)
            viewModel.changeCurrentItemTimer(target, TestGroup, dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange.Adjust(42))
            assertEquals(0, sends)
            assertEquals(InteractionUserMessage.Permission(dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode.InvalidLockTimerChange), viewModel.uiState.value.interactionSheet?.actionMessage)
            viewModel.changeCurrentItemTimer(target, TestGroup, dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange.Settings(true, false, true))
            assertEquals(1, sends)
            connection.mutableSnapshot.value = connection.mutableSnapshot.value.copy(roomCharacters = listOf(SelfCharacter, TargetCharacter))
            viewModel.changeCurrentItemTimer(target, TestGroup, dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange.Adjust(300))
            assertEquals(1, sends)
            assertEquals(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale), viewModel.uiState.value.interactionSheet?.actionMessage)
        } finally { scope.cancel() }
    }

    @Test fun passwordSettingsAreValidatedAndCannotSaveFromAnOldSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val target = TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "BallGag",
            propertyJson = """{"LockedBy":"PasswordPadlock","Password":"PASSWORD","LockMemberNumber":${SelfCharacter.memberNumber},"LockSet":false,"Effect":["Lock"]}""")))
        val rules = TestRules.copy(assets = listOf(BcAssetRule(TestGroup, "BallGag", allowLock = true), BcAssetRule("ItemMisc", "PasswordPadlock", isLock = true)).associateBy { BcAssetKey(it.group, it.name) })
        val connection = FakeConnectionStatusReader(roomSnapshot("Test Room", listOf(SelfCharacter, target)))
        var sends = 0
        val viewModel = RoomDetailsViewModel(connection, FakeCharacterInteractions { sends++; BcCharacterInteractionResult.Sent }, ReadyInteractionCatalogRepository,
            object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String) = rules
                override fun refresh(version: String) = BcRuleCatalogLoadResult.Ready(rules)
            }, currentVersion = MutableStateFlow("R129"), scope = scope, catalogDispatcher = Dispatchers.Unconfined)
        val valid = dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration.InitializePassword(dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential("secret"), "提示", true)
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            viewModel.configureCurrentItemLock(target, TestGroup, valid.copy(password = dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential("123")))
            assertEquals(0, sends)
            assertEquals(InteractionUserMessage.Permission(dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode.InvalidLockConfiguration), viewModel.uiState.value.interactionSheet?.actionMessage)
            viewModel.configureCurrentItemLock(target, TestGroup, valid)
            assertEquals(1, sends)
            connection.mutableSnapshot.value = roomSnapshot("Test Room", listOf(SelfCharacter, TargetCharacter))
            viewModel.configureCurrentItemLock(target, TestGroup, valid)
            assertEquals(1, sends)
            assertEquals(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale), viewModel.uiState.value.interactionSheet?.actionMessage)
        } finally { scope.cancel() }
    }

    @Test fun incorrectLockCodeShowsLocalizedReasonWithoutInvokingTheInteractionGateway() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val target = TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "BallGag",
            propertyJson = """{"LockedBy":"PasswordPadlock","Password":"SECRET","Effect":["Lock"]}""")))
        val rules = TestRules.copy(assets = listOf(BcAssetRule(TestGroup, "BallGag"), BcAssetRule("ItemMisc", "PasswordPadlock", isLock = true))
            .associateBy { BcAssetKey(it.group, it.name) })
        var sends = 0
        val viewModel = RoomDetailsViewModel(FakeConnectionStatusReader(roomSnapshot("Test Room", listOf(SelfCharacter, target))),
            FakeCharacterInteractions { sends++; BcCharacterInteractionResult.Sent }, ReadyInteractionCatalogRepository,
            object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String) = rules
                override fun refresh(version: String) = BcRuleCatalogLoadResult.Ready(rules)
            }, currentVersion = MutableStateFlow("R129"), scope = scope, catalogDispatcher = Dispatchers.Unconfined)
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            viewModel.unlockCurrentItemWithCode(target, TestGroup, "wrong")
            assertEquals(0, sends)
            assertEquals(InteractionUserMessage.Permission(dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode.IncorrectLockCredential),
                viewModel.uiState.value.interactionSheet?.actionMessage)
            viewModel.unlockCurrentItemWithCode(target, TestGroup, "secret")
            assertEquals(1, sends)
        } finally { scope.cancel() }
    }

    @Test fun codeFromAnOldLockDialogCannotUnlockAReplacementItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldTarget = TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "OldRope",
            propertyJson = """{"LockedBy":"PasswordPadlock","Password":"SECRET"}""")))
        val connection = FakeConnectionStatusReader(roomSnapshot("Test Room", listOf(SelfCharacter, oldTarget)))
        var sends = 0
        val viewModel = RoomDetailsViewModel(connection, FakeCharacterInteractions { sends++; BcCharacterInteractionResult.Sent },
            ReadyInteractionCatalogRepository, ReadyRuleCatalogRepository, currentVersion = MutableStateFlow("R129"), scope = scope,
            catalogDispatcher = Dispatchers.Unconfined)
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            connection.mutableSnapshot.value = roomSnapshot("Test Room", listOf(SelfCharacter, TargetCharacter))
            viewModel.unlockCurrentItemWithCode(oldTarget, TestGroup, "SECRET")
            assertEquals(0, sends)
            assertEquals(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale), viewModel.uiState.value.interactionSheet?.actionMessage)
        } finally { scope.cancel() }
    }

    @Test
    fun textSaveFromAnOldCharacterSnapshotCannotEditTheNewItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldTarget = TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "OldTag")))
        val connection = FakeConnectionStatusReader(roomSnapshot("Test Room", listOf(SelfCharacter, oldTarget)))
        var sends = 0
        val viewModel = RoomDetailsViewModel(connection, FakeCharacterInteractions { sends++; BcCharacterInteractionResult.Sent },
            ReadyInteractionCatalogRepository, ReadyRuleCatalogRepository, currentVersion = MutableStateFlow("R129"), scope = scope,
            catalogDispatcher = Dispatchers.Unconfined)
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            connection.mutableSnapshot.value = roomSnapshot("Test Room", listOf(SelfCharacter,
                TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "NewTag")))))
            viewModel.editCurrentItemText(oldTarget, TestGroup, mapOf("Text" to "Old draft"))
            assertEquals(0, sends)
            assertEquals(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale), viewModel.uiState.value.interactionSheet?.actionMessage)
        } finally { scope.cancel() }
    }

    @Test
    fun blankVersionDoesNotStartAResourceSyncUsingTheDefault() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val currentVersion = MutableStateFlow("")
        val refreshCount = AtomicInteger()
        val syncCount = AtomicInteger()
        val catalogRepository = object : BcInteractionCatalogRepository {
            override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? = null

            override fun refresh(version: String): BcInteractionCatalogLoadResult {
                refreshCount.incrementAndGet()
                return BcInteractionCatalogLoadResult.MissingResources(listOf("missing.csv"))
            }
        }

        try {
            RoomDetailsViewModel(
                connectionStatus = FakeConnectionStatusReader(),
                characterInteractions = FakeCharacterInteractions(),
                interactionCatalogRepository = catalogRepository,
                ruleCatalogRepository = ReadyRuleCatalogRepository,
                currentVersion = currentVersion,
                syncResources = { syncCount.incrementAndGet() },
                scope = scope,
                catalogDispatcher = Dispatchers.Unconfined,
            )

            assertEquals(0, refreshCount.get())
            assertEquals(0, syncCount.get())

            currentVersion.value = "R131"

            assertTrue(refreshCount.get() > 0)
            assertEquals(1, syncCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun olderCatalogRefreshCannotOverwriteTheLatestResult() {
        val executor = Executors.newFixedThreadPool(2)
        val catalogDispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val currentVersion = MutableStateFlow("R129")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val catalogRepository = object : BcInteractionCatalogRepository {
            override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot = TestCatalog

            override fun refresh(version: String): BcInteractionCatalogLoadResult = when (version) {
                "R129" -> {
                    firstStarted.countDown()
                    releaseFirst.awaitIgnoringInterrupts()
                    firstReturned.countDown()
                    BcInteractionCatalogLoadResult.Failed("old")
                }
                else -> {
                    secondReturned.countDown()
                    BcInteractionCatalogLoadResult.Failed("new")
                }
            }
        }

        try {
            val viewModel = RoomDetailsViewModel(
                connectionStatus = FakeConnectionStatusReader(),
                characterInteractions = FakeCharacterInteractions(),
                interactionCatalogRepository = catalogRepository,
                ruleCatalogRepository = ReadyRuleCatalogRepository,
                currentVersion = currentVersion,
                syncResources = {},
                scope = scope,
                catalogDispatcher = catalogDispatcher,
            )
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            viewModel.openInteract(TargetMemberNumber)

            currentVersion.value = "R130"
            assertTrue(secondReturned.await(5, TimeUnit.SECONDS))
            val expectedMessage = InteractionUserMessage.General(InteractionUserMessageKey.ResourceSyncFailed)
            awaitCatalogMessage(viewModel, expectedMessage)

            releaseFirst.countDown()
            assertTrue(firstReturned.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)

            assertEquals(
                expectedMessage,
                viewModel.uiState.value.interactionSheet?.catalogMessage,
            )
        } finally {
            releaseFirst.countDown()
            scope.cancel()
            catalogDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingInteractionCancelsAnInFlightSend() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sendStarted = CompletableDeferred<Unit>()
        val sendCancelled = CompletableDeferred<Unit>()
        val interactions = FakeCharacterInteractions(
            send = {
                sendStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    sendCancelled.complete(Unit)
                }
            },
        )
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = interactions,
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            syncResources = {},
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            viewModel.openInteract(TargetMemberNumber)
            viewModel.sendActivity(group = TestGroup, activityName = TestActivity)
            withTimeout(5_000) { sendStarted.await() }
            assertTrue(viewModel.uiState.value.interactionSheet?.busy == true)

            viewModel.closeInteract()

            withTimeout(5_000) { sendCancelled.await() }
            assertNull(viewModel.uiState.value.interactionSheet)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationBeforeInteractionStartsReleasesTheRegisteredTask() {
        val queued = java.util.ArrayDeque<Runnable>()
        var queueWork = false
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = queueWork
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var sent = false
        val model = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(send = { sent = true; BcCharacterInteractionResult.Sent }),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            syncResources = {}, scope = scope, catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            model.openInteract(TargetMemberNumber)
            queueWork = true
            model.sendActivity(group = TestGroup, activityName = TestActivity)
            assertTrue(model.uiState.value.interactionSheet?.busy == true)
            scope.cancel()
            while (queued.isNotEmpty()) queued.removeFirst().run()
            assertFalse(sent)
            assertTrue(model.uiState.value.interactionSheet?.busy == false)
        } finally {
            scope.cancel()
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    @Test
    fun immediatelyCompletedInteractionClearsBusyState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            syncResources = {},
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            viewModel.openInteract(TargetMemberNumber)
            viewModel.sendActivity(group = TestGroup, activityName = TestActivity)

            assertTrue(viewModel.uiState.value.interactionSheet?.busy == false)
            assertEquals(
                InteractionUserMessage.General(InteractionUserMessageKey.ActionSent),
                viewModel.uiState.value.interactionSheet?.actionMessage,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun actionPickerKeepsTheTargetAndActionsAfterSend() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            syncResources = {},
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            viewModel.openActionPicker()
            assertEquals(RoomDetailsInteractionMode.ActionPicker, viewModel.uiState.value.interactionMode)
            assertNull(viewModel.uiState.value.interactionSheet)
            viewModel.selectActionPickerTarget(TargetMemberNumber)
            viewModel.sendActivity(group = TestGroup, activityName = TestActivity)

            assertEquals(RoomDetailsInteractionMode.ActionPicker, viewModel.uiState.value.interactionMode)
            assertEquals(TargetMemberNumber, viewModel.uiState.value.interactionSheet?.target?.memberNumber)
            assertEquals(TestActivity, viewModel.uiState.value.interactionSheet?.activities?.firstOrNull()?.name)
            assertEquals(
                InteractionUserMessage.General(InteractionUserMessageKey.ActionSent),
                viewModel.uiState.value.interactionSheet?.actionMessage,
            )

            viewModel.closeInteract()
            viewModel.openActionPicker()
            assertEquals(TargetMemberNumber, viewModel.uiState.value.interactionSheet?.target?.memberNumber)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledActionSendCannotCloseAReplacementItemPicker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingResult = CompletableDeferred<Pair<Job, Continuation<BcCharacterInteractionResult>>>()
        val connection = FakeConnectionStatusReader(
            roomSnapshot(
                roomName = "Test Room",
                characters = listOf(
                    SelfCharacter,
                    TargetCharacter.copy(appearance = listOf(BcCharacterItem(TestGroup, "BallGag"))),
                ),
            ),
        )
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = FakeCharacterInteractions(send = {
                val job = checkNotNull(currentCoroutineContext()[Job])
                suspendCoroutine { continuation -> pendingResult.complete(job to continuation) }
            }),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            viewModel.openActionPicker(TargetMemberNumber)
            viewModel.sendActivity(group = TestGroup, activityName = TestActivity)
            val (oldJob, continuation) = withTimeout(5_000) { pendingResult.await() }

            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            viewModel.updateItemPickerQuery("new selection")
            assertTrue(oldJob.isCancelled)
            assertEquals(RoomDetailsInteractionMode.ItemPicker, viewModel.uiState.value.interactionMode)

            continuation.resume(BcCharacterInteractionResult.Sent)
            withTimeout(5_000) { oldJob.join() }
            connection.mutableSnapshot.value = connection.snapshot.value.copy(
                currentRoom = checkNotNull(connection.snapshot.value.currentRoom).copy(description = "Refresh after late send"),
            )

            val sheet = checkNotNull(viewModel.uiState.value.interactionSheet)
            assertEquals(RoomDetailsInteractionMode.ItemPicker, viewModel.uiState.value.interactionMode)
            assertEquals(TargetMemberNumber, sheet.target.memberNumber)
            assertEquals(TestGroup, sheet.selectedGroup?.name)
            assertEquals("new selection", sheet.itemPicker?.query)
            assertTrue(!sheet.busy)
            assertNull(sheet.actionMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changingTheActionTargetCancelsTheOldSendAndKeepsThePickerOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingResult = CompletableDeferred<Pair<Job, Continuation<BcCharacterInteractionResult>>>()
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(send = {
                val job = checkNotNull(currentCoroutineContext()[Job])
                suspendCoroutine { continuation -> pendingResult.complete(job to continuation) }
            }),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            viewModel.openActionPicker(TargetMemberNumber)
            viewModel.sendActivity(group = TestGroup, activityName = TestActivity)
            val (oldJob, continuation) = withTimeout(5_000) { pendingResult.await() }

            viewModel.selectActionPickerTarget(SelfCharacter.memberNumber)
            assertTrue(oldJob.isCancelled)
            continuation.resume(BcCharacterInteractionResult.Sent)
            withTimeout(5_000) { oldJob.join() }

            val sheet = checkNotNull(viewModel.uiState.value.interactionSheet)
            assertEquals(RoomDetailsInteractionMode.ActionPicker, viewModel.uiState.value.interactionMode)
            assertEquals(SelfCharacter.memberNumber, sheet.target.memberNumber)
            assertTrue(!sheet.busy)
            assertNull(sheet.actionMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun itemPickerModifyStillSendsTheSelectedCandidate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sentRequest: BcPermissionRequest? = null
        val candidate = BcInteractionCandidate.ModifyItem(
            targetMemberNumber = TargetMemberNumber,
            group = TestGroup,
            itemName = "BallGag",
            propertyJson = """{"Type":"Tight"}""",
            difficulty = 2,
        )
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(send = { request ->
                sentRequest = request
                BcCharacterInteractionResult.Sent
            }),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            viewModel.selectItemPickerOperation(BcItemManagementOperation.Modify.wireName)
            viewModel.sendItemPickerEntry(
                InteractionItemPickerEntryUiState(
                    operation = BcItemManagementOperation.Modify.wireName,
                    group = TestGroup,
                    itemName = candidate.itemName,
                    label = "Tight",
                    propertyJson = candidate.propertyJson,
                    difficulty = candidate.difficulty,
                    candidate = candidate,
                ),
            )

            assertEquals(candidate, sentRequest?.candidate)
            assertEquals(TargetMemberNumber, sentRequest?.context?.target?.memberNumber)
            assertEquals(
                InteractionUserMessage.General(InteractionUserMessageKey.ItemConfigured),
                viewModel.uiState.value.interactionSheet?.actionMessage,
            )
            assertTrue(viewModel.uiState.value.interactionSheet?.busy == false)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pickerBackClosesTheDockAndOnlyUnwindsUnsubmittedItemOptions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val itemCatalog = TestCatalog.copy(
            groups = TestCatalog.groups.map { it.copy(hasItems = true) },
            items = listOf(BcInteractionItem(TestGroup, "BallGag", "Ball gag")),
        )
        val itemKey = BcAssetKey(TestGroup, "BallGag")
        val itemRules = TestRules.copy(
            assets = mapOf(itemKey to BcAssetRule(TestGroup, "BallGag")),
            extendedItems = mapOf(
                itemKey to BcExtendedItemRule(
                    group = TestGroup,
                    name = "BallGag",
                    archetype = BcExtendedItemArchetype.Typed,
                    recordKey = "typed",
                    typedOptions = listOf("Loose", "Tight").mapIndexed { index, name ->
                        BcExtendedItemOptionRule(name, index, propertyJson = """{"TypeRecord":{"typed":$index}}""")
                    },
                ),
            ),
        )
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = object : BcInteractionCatalogRepository {
                override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot = itemCatalog
                override fun refresh(version: String): BcInteractionCatalogLoadResult = BcInteractionCatalogLoadResult.Ready(itemCatalog)
            },
            ruleCatalogRepository = object : BcRuleCatalogRepository {
                override fun currentSnapshot(version: String): BcRuleCatalog = itemRules
                override fun refresh(version: String): BcRuleCatalogLoadResult = BcRuleCatalogLoadResult.Ready(itemRules)
            },
            currentVersion = MutableStateFlow("R129"),
            syncResources = {},
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            viewModel.openActionPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            assertTrue(viewModel.handlePickerBack())
            assertNull(viewModel.uiState.value.interactionMode)
            assertNull(viewModel.uiState.value.interactionSheet)

            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            viewModel.updateItemPickerQuery("gag")
            val catalogEntry = checkNotNull(viewModel.uiState.value.interactionSheet?.itemPicker).entries.single()
            assertTrue(catalogEntry.canOpenOptions)
            viewModel.sendItemPickerEntry(catalogEntry)
            assertEquals("BallGag", viewModel.uiState.value.interactionSheet?.itemPicker?.selectedItemName)
            viewModel.updateItemPickerQuery("tight")
            assertEquals("Tight", viewModel.uiState.value.interactionSheet?.itemPicker?.entries?.single()?.label)
            assertTrue(viewModel.handlePickerBack())
            assertEquals("gag", viewModel.uiState.value.interactionSheet?.itemPicker?.query)
            assertNull(viewModel.uiState.value.interactionSheet?.itemPicker?.selectedItemName)
            assertEquals("BallGag", viewModel.uiState.value.interactionSheet?.itemPicker?.entries?.single()?.itemName)
            assertTrue(viewModel.handlePickerBack())
            assertNull(viewModel.uiState.value.interactionMode)
            assertNull(viewModel.uiState.value.interactionSheet)

            viewModel.openClothingPicker(TargetMemberNumber)
            assertTrue(viewModel.handlePickerBack())
            assertNull(viewModel.uiState.value.interactionMode)
            assertNull(viewModel.uiState.value.interactionSheet)
            assertTrue(!viewModel.handlePickerBack())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingCatalogResourcesAreSyncedAndRetriedOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refreshCount = AtomicInteger()
        val syncCount = AtomicInteger()
        val catalogRepository = object : BcInteractionCatalogRepository {
            override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? =
                TestCatalog.takeIf { refreshCount.get() > 1 }

            override fun refresh(version: String): BcInteractionCatalogLoadResult =
                if (refreshCount.incrementAndGet() == 1) {
                    BcInteractionCatalogLoadResult.MissingResources(listOf("missing.csv"))
                } else {
                    BcInteractionCatalogLoadResult.Ready(TestCatalog)
                }
        }
        val viewModel = RoomDetailsViewModel(
            connectionStatus = FakeConnectionStatusReader(),
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = catalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            syncResources = { syncCount.incrementAndGet() },
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            viewModel.openInteract(TargetMemberNumber)

            assertEquals(1, syncCount.get())
            assertEquals(2, refreshCount.get())
            assertNull(viewModel.uiState.value.interactionSheet?.catalogMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun roomHeaderAndCharactersComeFromTheSameSessionSnapshot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val roomACharacter = TargetCharacter.copy(name = "Room A Member", displayName = "Room A Member")
        val roomBCharacter = TargetCharacter.copy(name = "Room B Member", displayName = "Room B Member")
        val connection = FakeConnectionStatusReader(
            roomSnapshot(roomName = "Room A", characters = listOf(roomACharacter)),
        )
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )

        try {
            assertEquals("Room A", viewModel.uiState.value.roomTitle)
            assertEquals(listOf("Room A Member"), viewModel.uiState.value.characters.map { it.displayName })

            connection.mutableSnapshot.value = roomSnapshot(
                roomName = "Room B",
                characters = listOf(roomBCharacter),
            )

            assertEquals("Room B", viewModel.uiState.value.roomTitle)
            assertEquals(listOf("Room B Member"), viewModel.uiState.value.characters.map { it.displayName })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun departedTargetIsClearedAndCannotBeSilentlyReusedAfterRejoining() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connection = FakeConnectionStatusReader()
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            viewModel.openActionPicker(TargetMemberNumber)
            connection.mutableSnapshot.value = roomSnapshot("Test Room", listOf(SelfCharacter))
            assertNull(viewModel.uiState.value.interactionSheet)
            assertEquals(
                InteractionUserMessage.General(InteractionUserMessageKey.TargetLeftRoom),
                viewModel.uiState.value.interactionNotice,
            )
            connection.mutableSnapshot.value = roomSnapshot("Test Room", listOf(SelfCharacter, TargetCharacter))
            assertNull(viewModel.uiState.value.interactionSheet)
            viewModel.openActionPicker()
            assertNull(viewModel.uiState.value.interactionSheet)
        } finally { scope.cancel() }
    }

    @Test
    fun changingRoomsClearsInteractionEvenWhenTheSamePersonIsInBothRooms() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connection = FakeConnectionStatusReader()
        val viewModel = RoomDetailsViewModel(
            connectionStatus = connection,
            characterInteractions = FakeCharacterInteractions(),
            interactionCatalogRepository = ReadyInteractionCatalogRepository,
            ruleCatalogRepository = ReadyRuleCatalogRepository,
            currentVersion = MutableStateFlow("R129"),
            scope = scope,
            catalogDispatcher = Dispatchers.Unconfined,
        )
        try {
            viewModel.openItemPicker(TargetMemberNumber)
            viewModel.selectInteractionGroup(TestGroup)
            connection.mutableSnapshot.value = roomSnapshot("Different Room", listOf(SelfCharacter, TargetCharacter))
            assertNull(viewModel.uiState.value.interactionSheet)
            assertEquals(RoomDetailsInteractionMode.ItemPicker, viewModel.uiState.value.interactionMode)
        } finally { scope.cancel() }
    }

    private fun awaitCatalogMessage(viewModel: RoomDetailsViewModel, expected: InteractionUserMessage) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (viewModel.uiState.value.interactionSheet?.catalogMessage != expected) {
            check(System.nanoTime() < deadline) { "Timed out waiting for catalog message: $expected" }
            Thread.sleep(10)
        }
    }

    private fun CountDownLatch.awaitIgnoringInterrupts() {
        while (true) {
            try {
                await()
                return
            } catch (_: InterruptedException) {
                // Keep the stale blocking refresh alive to verify that its result is ignored.
            }
        }
    }

    private class FakeConnectionStatusReader(
        initial: BcSessionSnapshot = roomSnapshot(
            roomName = "Test Room",
            characters = listOf(SelfCharacter, TargetCharacter),
        ),
    ) : ConnectionStatusReader {
        val mutableSnapshot = MutableStateFlow(initial)
        override val snapshot: StateFlow<BcSessionSnapshot> = mutableSnapshot
    }

    private class FakeCharacterInteractions(
        private val send: suspend (BcPermissionRequest) -> BcCharacterInteractionResult = {
            BcCharacterInteractionResult.Sent
        },
    ) : BcCharacterInteractions {
        override suspend fun sendInteraction(
            request: BcPermissionRequest,
            activityClientMsgId: String?,
        ): BcCharacterInteractionResult = send(request)
    }

    private object ReadyInteractionCatalogRepository : BcInteractionCatalogRepository {
        override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot = TestCatalog

        override fun refresh(version: String): BcInteractionCatalogLoadResult =
            BcInteractionCatalogLoadResult.Ready(TestCatalog)
    }

    private object ReadyRuleCatalogRepository : BcRuleCatalogRepository {
        override fun currentSnapshot(version: String): BcRuleCatalog = TestRules

        override fun refresh(version: String): BcRuleCatalogLoadResult =
            BcRuleCatalogLoadResult.Ready(TestRules)
    }

    private companion object {
        const val TargetMemberNumber = 2
        const val TestGroup = "ItemMouth"
        const val TestActivity = "Kiss"

        val SelfCharacter = BcCharacterSnapshot(
            id = "self",
            memberNumber = 1,
            name = "Self",
            displayName = "Self",
            isSelf = true,
        )
        val TargetCharacter = BcCharacterSnapshot(
            id = "target",
            memberNumber = TargetMemberNumber,
            name = "Target",
            displayName = "Target",
            isSelf = false,
        )

        fun roomSnapshot(
            roomName: String,
            characters: List<BcCharacterSnapshot>,
        ): BcSessionSnapshot = BcSessionSnapshot(
            currentRoom = BcRoom(
                name = BcRoomName(roomName),
                description = "",
                memberCount = characters.size,
                memberLimit = 10,
                isLocked = false,
                language = null,
                mapType = BcRoomMapType.ChatRoom,
            ),
            roomCharacters = characters,
        )

        val TestCatalog = BcInteractionCatalogSnapshot(
            version = "R129",
            generatedAtEpochMillis = 0,
            groups = listOf(BcInteractionGroup(TestGroup, "Mouth", hasActivities = true, hasItems = false)),
            activities = listOf(BcInteractionActivity(TestGroup, TestActivity, "Kiss")),
            items = emptyList(),
            sourcePaths = emptyList(),
        )
        val TestRules = BcRuleCatalog(
            version = "R129",
            groups = mapOf(TestGroup to BcGroupRule(name = TestGroup)),
            activities = mapOf(
                TestActivity to BcActivityRule(
                    name = TestActivity,
                    targetGroups = setOf(TestGroup),
                ),
            ),
            assets = emptyMap(),
        )
    }
}
