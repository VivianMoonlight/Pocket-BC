package dev.vivian.mobilebc.domain.bcrelationship

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcRelationshipItemReconcilerTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancelledReconciliationDoesNotScheduleARetry() = runTest {
        val cancelled = CancellationException("item update cancelled")
        val gateway = FakeInteractionGateway { throw cancelled }
        val diagnostics = mutableListOf<String>()
        val snapshots = MutableStateFlow(
            snapshot(1, BcSelfRelations(ownership = fullOwnership()), character(BcCharacterItem("ItemNeck", "LeatherCollar"))),
        )
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogDispatcher = StandardTestDispatcher(testScheduler),
            diagnostic = diagnostics::add,
            retryScope = backgroundScope,
        )

        val failure = runCatching { reconciler.reconcile() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        testScheduler.advanceTimeBy(15_001L)
        testScheduler.runCurrent()

        assertEquals(0, gateway.itemCommands.size)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun roomVisitOrConnectionChangeDuringCatalogLoadingRejectsTheOldPlan() = runTest {
        listOf(false, true).forEach { changeRoomVisit ->
            val gateway = FakeInteractionGateway()
            val snapshots = MutableStateFlow(
                snapshot(1, BcSelfRelations(ownership = fullOwnership()), character(BcCharacterItem("ItemNeck", "LeatherCollar"))),
            )
            val reconciler = BcRelationshipItemReconciler(
                gateway,
                snapshots,
                catalogProvider = {
                    snapshots.value = if (changeRoomVisit) snapshots.value.copy(roomPresenceEpoch = 2L)
                    else snapshots.value.copy(connectionHealth = BcConnectionHealth.Offline)
                    null
                },
                catalogDispatcher = StandardTestDispatcher(testScheduler),
            )

            reconciler.reconcile()

            assertTrue(gateway.itemCommands.isEmpty())
        }
    }

    @Test
    fun relationshipChangesDuringCatalogLoadingUseTheLatestPlan() = runTest {
        val gateway = FakeInteractionGateway()
        val snapshots = MutableStateFlow(
            snapshot(1, BcSelfRelations(ownership = fullOwnership()), character(BcCharacterItem("ItemNeck", "LeatherCollar"))),
        )
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = {
                snapshots.value = snapshots.value.copy(relationships = snapshots.value.relationships.copy(self = BcSelfRelations()))
                null
            },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )

        reconciler.reconcile()

        assertTrue(gateway.itemCommands.isEmpty())
    }

    @Test
    fun newRoomVisitDoesNotReuseTheOldPendingSubmission() = runTest {
        val gateway = FakeInteractionGateway()
        val snapshots = MutableStateFlow(
            snapshot(1, BcSelfRelations(ownership = fullOwnership()), character(BcCharacterItem("ItemNeck", "LeatherCollar"))),
        )
        val reconciler = BcRelationshipItemReconciler(gateway, snapshots, clockMillis = { 1_000L })

        reconciler.reconcile()
        snapshots.value = snapshots.value.copy(roomPresenceEpoch = 2L)
        reconciler.reconcile()

        assertEquals(2, gateway.itemCommands.size)
    }

    @Test
    fun fullyOwnedCharacterGetsNativeSlaveCollarButKeepsClubVariant() {
        val relations = BcSelfRelations(ownership = fullOwnership())
        val replacePlan = BcRelationshipItemPlanner.plan(
            character = character(
                BcCharacterItem(group = "ItemNeck", name = "LeatherCollar"),
            ),
            relations = relations,
            catalog = null,
            cleanupOwnership = false,
        )
        val keepPlan = BcRelationshipItemPlanner.plan(
            character = character(
                BcCharacterItem(group = "ItemNeck", name = "ClubSlaveCollar"),
            ),
            relations = relations,
            catalog = null,
            cleanupOwnership = false,
        )

        assertEquals("SlaveCollar", replacePlan.mutations.single().command.name)
        assertTrue(keepPlan.mutations.isEmpty())
    }

    @Test
    fun trialOrMissingOwnerRemovesSlaveCollarAndResynchronizesLinkedNeckGroups() {
        val plan = BcRelationshipItemPlanner.plan(
            character = character(
                BcCharacterItem(group = "ItemNeck", name = "SlaveCollar"),
                BcCharacterItem(group = "ItemNeckAccessories", name = "CollarBell"),
            ),
            relations = BcSelfRelations(
                ownership = BcOwnership(2002, "Owner", stage = 0, startEpochMillis = null, notes = null),
            ),
            catalog = null,
            cleanupOwnership = false,
        )

        val commands = plan.mutations.associateBy { it.command.group }
        assertNull(commands.getValue("ItemNeck").command.name)
        assertEquals("CollarBell", commands.getValue("ItemNeckAccessories").command.name)
        assertNull(commands.getValue("ItemNeckRestraints").command.name)
    }

    @Test
    fun clearOwnershipRemovesOwnerOnlyAssetsAndOwnerLocksWithoutTouchingLoverOnlyAssets() {
        val catalog = catalog(
            BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true),
            BcAssetRule(group = "ItemMisc", name = "FutureOwnerLock", ownerOnly = true),
            BcAssetRule(group = "ItemVulva", name = "FutureLoverToy", loverOnly = true),
        )
        val plan = BcRelationshipItemPlanner.plan(
            character = character(
                BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt"),
                BcCharacterItem(
                    group = "ItemArms",
                    name = "Ribbons",
                    propertyJson = """{"Effect":["BlockArms","Lock"],"LockedBy":"FutureOwnerLock","LockMemberNumber":2002,"Custom":"keep"}""",
                ),
                BcCharacterItem(group = "ItemVulva", name = "FutureLoverToy"),
            ),
            relations = BcSelfRelations(),
            catalog = catalog,
            cleanupOwnership = true,
        )

        val commands = plan.mutations.associateBy { it.command.group }
        assertTrue(plan.ownershipCleanupRequired)
        assertNull(commands.getValue("ItemPelvis").command.name)
        val armsProperty = commands.getValue("ItemArms").command.propertyJson.orEmpty()
        assertFalse(armsProperty.contains("LockedBy"))
        assertFalse(armsProperty.contains("LockMemberNumber"))
        assertFalse(armsProperty.contains("\"Lock\""))
        assertTrue(armsProperty.contains("BlockArms"))
        assertTrue(armsProperty.contains("Custom"))
        assertFalse(commands.containsKey("ItemVulva"))
    }

    @Test
    fun missingLoversOnlyRestoresLoveLeatherSlaveCollar() {
        val plan = BcRelationshipItemPlanner.plan(
            character = character(
                BcCharacterItem(
                    group = "ItemNeck",
                    name = "SlaveCollar",
                    colorJson = "\"#663300\"",
                    propertyJson = """{"TypeRecord":{"noarch":21},"Effect":[],"Block":[]}""",
                ),
            ),
            relations = BcSelfRelations(ownership = fullOwnership()),
            catalog = null,
            cleanupOwnership = false,
        )

        val command = plan.mutations.single().command
        assertEquals("\"Default\"", command.colorJson)
        assertEquals("""{"TypeRecord":{"noarch":0}}""", command.propertyJson)
    }

    @Test
    fun unchangedRoomSnapshotDoesNotResubmitBeforeRetryWindow() = runTest {
        var now = 1_000L
        val gateway = FakeInteractionGateway()
        val snapshots = MutableStateFlow(
            snapshot(
                generation = 1,
                relations = BcSelfRelations(ownership = fullOwnership()),
                character = character(BcCharacterItem(group = "ItemNeck", name = "LeatherCollar")),
            ),
        )
        val reconciler = BcRelationshipItemReconciler(
            gateway = gateway,
            sessionSnapshot = snapshots,
            clockMillis = { now },
        )

        reconciler.reconcile()
        reconciler.reconcile()
        now += 15_000L
        reconciler.reconcile()

        assertEquals(2, gateway.itemCommands.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pendingItemMutationRetriesWithoutAnotherRoomEvent() = runTest {
        val gateway = FakeInteractionGateway()
        val snapshots = MutableStateFlow(
            snapshot(
                generation = 1,
                relations = BcSelfRelations(ownership = fullOwnership()),
                character = character(BcCharacterItem(group = "ItemNeck", name = "LeatherCollar")),
            ),
        )
        val reconciler = BcRelationshipItemReconciler(
            gateway = gateway,
            sessionSnapshot = snapshots,
            catalogDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            clockMillis = { testScheduler.currentTime },
            retryScope = backgroundScope,
        )

        reconciler.reconcile()
        assertEquals(1, gateway.itemCommands.size)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(15_000L)
        testScheduler.runCurrent()

        assertEquals(2, gateway.itemCommands.size)
    }

    @Test
    fun ownershipCleanupWaitsForAUsableSelfRoomSnapshot() = runTest {
        val gateway = FakeInteractionGateway()
        val snapshots = MutableStateFlow(
            snapshot(generation = 1, relations = BcSelfRelations(), character = null),
        )
        val reconciler = BcRelationshipItemReconciler(gateway, snapshots)

        reconciler.reconcile(ownershipCleared = true)
        snapshots.value = snapshot(
            generation = 1,
            relations = BcSelfRelations(),
            character = character(BcCharacterItem(group = "ItemPelvis", name = "LoveChastityBelt")),
        )
        reconciler.reconcile()

        assertEquals(1, gateway.itemCommands.size)
        assertEquals("ItemPelvis", gateway.itemCommands.single().group)
        assertNull(gateway.itemCommands.single().name)
    }

    @Test
    fun delayedCleanupFromAnotherGenerationOrLoginCannotLeavePendingSideEffects() = runTest {
        val gateway = FakeInteractionGateway()
        var catalogLoads = 0
        val rules = catalog(BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true))
        val snapshots = MutableStateFlow(snapshot(
            generation = 2L,
            relations = BcSelfRelations(ownership = fullOwnership()),
            character = character(
                BcCharacterItem(group = "ItemNeck", name = "SlaveCollar"),
                BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt"),
            ),
            loginSessionId = "current",
        ))
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = { catalogLoads += 1; rules },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )

        reconciler.reconcile(
            ownershipCleared = true,
            expectedAccountGeneration = 1L,
            expectedLoginSessionId = "current",
        )
        reconciler.reconcile(
            ownershipCleared = true,
            expectedAccountGeneration = 2L,
            expectedLoginSessionId = "old",
        )
        assertEquals(0, catalogLoads)
        assertTrue(gateway.itemCommands.isEmpty())

        reconciler.reconcile(expectedAccountGeneration = 2L, expectedLoginSessionId = "current")

        assertEquals(1, catalogLoads)
        assertTrue(gateway.itemCommands.isEmpty())
    }

    @Test
    fun loginUuidChangesDuringCatalogLoadingRejectTheOldItemPlan() = runTest {
        listOf(false, true).forEach { changePrivateLogSession ->
            val gateway = FakeInteractionGateway()
            var catalogLoads = 0
            val snapshots = MutableStateFlow(snapshot(
                generation = 1L,
                relations = BcSelfRelations(ownership = fullOwnership()),
                character = character(BcCharacterItem(group = "ItemNeck", name = "LeatherCollar")),
                loginSessionId = "current",
            ))
            val reconciler = BcRelationshipItemReconciler(
                gateway,
                snapshots,
                catalogProvider = {
                    catalogLoads += 1
                    snapshots.value = if (changePrivateLogSession) {
                        snapshots.value.copy(selfRuleLogSessionId = "new")
                    } else {
                        snapshots.value.copy(relationships = snapshots.value.relationships.copy(loginSessionId = "new"))
                    }
                    null
                },
                catalogDispatcher = StandardTestDispatcher(testScheduler),
            )

            reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")

            assertEquals(1, catalogLoads)
            assertTrue(gateway.itemCommands.isEmpty())
        }
    }

    @Test
    fun loginClearHistoryWaitsAcrossRoomPresenceChangesUntilSelfAppearanceArrives() = runTest {
        val gateway = FakeInteractionGateway()
        val rules = catalog(BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true))
        val snapshots = MutableStateFlow(snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = null,
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ).copy(currentRoom = null, roomPresenceEpoch = 0L))
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = { rules },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )

        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        assertTrue(gateway.itemCommands.isEmpty())
        snapshots.value = snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = null,
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ).copy(roomPresenceEpoch = 2L)
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        assertTrue(gateway.itemCommands.isEmpty())
        snapshots.value = snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = character(BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt")),
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ).copy(roomPresenceEpoch = 3L)
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")

        assertEquals(1, gateway.itemCommands.size)
        assertEquals("ItemPelvis", gateway.itemCommands.single().group)
        assertNull(gateway.itemCommands.single().name)
    }

    @Test
    fun handledClearHistoryDoesNotRemoveNewOwnerItemsOrResetCollarMetadataAfterRejoining() = runTest {
        val gateway = FakeInteractionGateway()
        val rules = catalog(BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true))
        val snapshots = MutableStateFlow(snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = character(BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt")),
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ))
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = { rules },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        assertEquals(1, gateway.itemCommands.size)
        snapshots.value = snapshots.value.copy(roomCharacters = listOf(character()))
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        val newCollar = BcCharacterItem(
            group = "ItemNeck",
            name = "SlaveCollar",
            colorJson = "\"#663300\"",
            propertyJson = """{"TypeRecord":{"noarch":0},"Text":"New Owner"}""",
            craftJson = """{"Name":"New collar","Description":"keep"}""",
        )
        snapshots.value = snapshot(
            generation = 1L,
            relations = BcSelfRelations(ownership = fullOwnership().copy(memberNumber = 3003, name = "New Owner")),
            character = character(newCollar, BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt")),
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ).copy(roomPresenceEpoch = 2L)

        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")

        assertEquals(1, gateway.itemCommands.size)
        assertEquals(newCollar, snapshots.value.roomCharacters.single().appearance.first())
    }

    @Test
    fun newerClearHistoryCleansOwnerItemsAgainAfterThePreviousCleanupWasConfirmed() = runTest {
        val gateway = FakeInteractionGateway()
        val rules = catalog(BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true))
        val ownerItem = BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt")
        val snapshots = MutableStateFlow(snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = character(ownerItem),
            loginSessionId = "current",
            ownershipClearRevision = 7L,
        ))
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = { rules },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        snapshots.value = snapshots.value.copy(roomCharacters = listOf(character()))
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        snapshots.value = snapshots.value.copy(roomCharacters = listOf(character(ownerItem)))
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")
        assertEquals(1, gateway.itemCommands.size)

        snapshots.value = snapshots.value.copy(relationships = snapshots.value.relationships.copy(
            revision = 12L,
            ownershipClearRevision = 12L,
        ))
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "current")

        assertEquals(2, gateway.itemCommands.size)
        assertTrue(gateway.itemCommands.all { it.group == "ItemPelvis" && it.name == null })
    }

    @Test
    fun anotherLoginGenerationHandlesItsOwnClearEvenWhenTheRevisionNumberIsReused() = runTest {
        val gateway = FakeInteractionGateway()
        val rules = catalog(BcAssetRule(group = "ItemPelvis", name = "FutureOwnerBelt", ownerOnly = true))
        val ownerItem = BcCharacterItem(group = "ItemPelvis", name = "FutureOwnerBelt")
        val snapshots = MutableStateFlow(snapshot(
            generation = 1L,
            relations = BcSelfRelations(),
            character = character(ownerItem),
            loginSessionId = "old",
            ownershipClearRevision = 7L,
        ))
        val reconciler = BcRelationshipItemReconciler(
            gateway,
            snapshots,
            catalogProvider = { rules },
            catalogDispatcher = StandardTestDispatcher(testScheduler),
        )
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "old")
        snapshots.value = snapshots.value.copy(roomCharacters = listOf(character()))
        reconciler.reconcile(expectedAccountGeneration = 1L, expectedLoginSessionId = "old")
        snapshots.value = snapshot(
            generation = 2L,
            relations = BcSelfRelations(),
            character = character(ownerItem),
            loginSessionId = "new",
            ownershipClearRevision = 7L,
        )

        reconciler.reconcile(expectedAccountGeneration = 2L, expectedLoginSessionId = "new")

        assertEquals(2, gateway.itemCommands.size)
        assertTrue(gateway.itemCommands.all { it.group == "ItemPelvis" && it.name == null })
    }

    private fun fullOwnership() = BcOwnership(
        memberNumber = 2002,
        name = "Owner",
        stage = 1,
        startEpochMillis = null,
        notes = null,
    )

    private fun character(vararg appearance: BcCharacterItem) = BcCharacterSnapshot(
        id = "self",
        memberNumber = SELF_MEMBER_NUMBER,
        name = "Self",
        displayName = "Self",
        isSelf = true,
        appearance = appearance.toList(),
    )

    private fun snapshot(
        generation: Long,
        relations: BcSelfRelations,
        character: BcCharacterSnapshot?,
        loginSessionId: String? = null,
        ownershipClearRevision: Long? = null,
    ) = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true,
        account = BcAccount(
            memberNumber = SELF_MEMBER_NUMBER,
            displayName = "Self",
            accountName = "account",
        ),
        currentRoom = BcRoom(BcRoomName("Room"), "", 1, 10, false, null),
        roomPresenceEpoch = 1L,
        roomCharacters = listOfNotNull(character),
        selfRuleLogSessionId = loginSessionId,
        relationships = BcRelationshipSnapshot(
            accountMemberNumber = SELF_MEMBER_NUMBER,
            accountGeneration = generation,
            loadState = BcRelationshipLoadState.Loaded,
            self = relations,
            loginSessionId = loginSessionId,
            revision = ownershipClearRevision ?: 0L,
            ownershipClearRevision = ownershipClearRevision,
        ),
    )

    private fun catalog(vararg assets: BcAssetRule): BcRuleCatalog = BcRuleCatalog(
        version = "R130",
        groups = emptyMap(),
        activities = emptyMap(),
        assets = assets.associateBy { BcAssetKey(it.group, it.name) },
    )

    private class FakeInteractionGateway(
        private val onUpdate: suspend (BcCharacterItemUpdateCommand) -> Unit = {},
    ) : InteractionGateway {
        val itemCommands = mutableListOf<BcCharacterItemUpdateCommand>()

        override suspend fun queryAllowItem(memberNumber: Int): Boolean = true

        override suspend fun sendActivity(command: BcActivityCommand) = Unit

        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) {
            onUpdate(command)
            itemCommands += command
        }
    }

    private companion object {
        private const val SELF_MEMBER_NUMBER = 1001
    }
}
