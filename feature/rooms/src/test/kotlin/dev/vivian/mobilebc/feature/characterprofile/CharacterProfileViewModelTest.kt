package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipCapabilityQueryResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipContractActionResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipMutationResult
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.timeline.BcContactProfileSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import dev.vivian.mobilebc.feature.rooms.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterProfileViewModelTest {
    @Test
    fun cancelledQueuedRelationshipRequestReleasesGateForNextRequest() {
        val tasks = java.util.ArrayDeque<Runnable>()
        var lastJob: Job? = null
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                lastJob = context[Job]
                tasks.addLast(block)
            }
        }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val status = FakeConnectionStatus(session(character(20)).copy(
            desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline,
        ))
        var calls = 0
        val model = CharacterProfileViewModel(20, FakeTimeline(null), status,
            object : FakeRelationships() {
                override suspend fun updateRelationshipList(
                    memberNumber: Int, kind: BcRelationshipListKind, enabled: Boolean,
                ): BcRelationshipMutationResult {
                    calls += 1
                    return BcRelationshipMutationResult.Submitted
                }
            }, scope)
        try {
            drain()
            model.updateRelationshipList(BcRelationshipListKind.Friend, true)
            requireNotNull(lastJob).cancel()
            drain()

            assertEquals(0, calls)
            assertFalse(model.uiState.value.relationship!!.operationInFlight)
            assertNull(model.uiState.value.relationshipMessageRes)
            model.updateRelationshipList(BcRelationshipListKind.Ignore, true)
            drain()
            assertEquals(1, calls)
            assertFalse(model.uiState.value.relationship!!.operationInFlight)
            assertEquals(R.string.character_list_update_submitted, model.uiState.value.relationshipMessageRes)
        } finally {
            model.close()
            scope.cancel()
            drain()
        }
    }

    @Test
    fun previousLoginRequestCannotBlockOrFinishTheCurrentLoginRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatus(session(character(20)).copy(
            desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline,
        ))
        val oldResult = CompletableDeferred<BcRelationshipMutationResult>()
        val newResult = CompletableDeferred<BcRelationshipMutationResult>()
        var calls = 0
        val model = CharacterProfileViewModel(20, FakeTimeline(null), status,
            object : FakeRelationships() {
                override suspend fun updateRelationshipList(
                    memberNumber: Int, kind: BcRelationshipListKind, enabled: Boolean,
                ): BcRelationshipMutationResult = if (++calls == 1) oldResult.await() else newResult.await()
            }, scope)
        try {
            model.updateRelationshipList(BcRelationshipListKind.Friend, true)
            assertTrue(model.uiState.value.relationship!!.operationInFlight)
            status.snapshot.value = status.snapshot.value.copy(
                relationships = status.snapshot.value.relationships.nextLoaded(10, BcSelfRelations()),
            )
            yield()
            assertFalse(model.uiState.value.relationship!!.operationInFlight)
            assertNull(model.uiState.value.relationshipMessageRes)
            model.updateRelationshipList(BcRelationshipListKind.Ignore, true)
            model.updateRelationshipList(BcRelationshipListKind.Ignore, true)
            assertEquals(2, calls)
            assertTrue(model.uiState.value.relationship!!.operationInFlight)
            oldResult.complete(BcRelationshipMutationResult.Submitted)
            yield()
            assertTrue(model.uiState.value.relationship!!.operationInFlight)
            assertNull(model.uiState.value.relationshipMessageRes)
            newResult.complete(BcRelationshipMutationResult.Submitted)
            yield()
            assertFalse(model.uiState.value.relationship!!.operationInFlight)
            assertEquals(R.string.character_list_update_submitted, model.uiState.value.relationshipMessageRes)
            status.snapshot.value = status.snapshot.value.copy(
                connectionHealth = BcConnectionHealth.Offline,
                relationships = status.snapshot.value.relationships.nextUnloaded(),
            )
            yield()
            assertNull(model.uiState.value.relationshipMessageRes)
        } finally { model.close(); scope.cancel() }
    }

    @Test
    fun historicalProfileWaitsForMatchingAccountAndLoginGeneration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val status = FakeConnectionStatus(session(character(20)).copy(currentRoom = null, roomCharacters = emptyList()))
        val stored = BcContactProfile(memberNumber = 20, name = "Stored target", description = "Old description", firstSeenAtMillis = 1, lastSeenAtMillis = 2)
        val timeline = FakeTimeline(stored)
        val model = CharacterProfileViewModel(20, timeline, status, FakeRelationships(), scope)
        try {
            yield()
            assertEquals("Stored target", model.uiState.value.displayName)
            status.snapshot.value = status.snapshot.value.copy(account = BcAccount(30, "Other", "other"))
            yield()
            assertEquals("#20", model.uiState.value.displayName)
            assertNull(model.uiState.value.description)
            assertNull(model.uiState.value.lastSeenAtMillis)
            timeline.profile.value = BcContactProfileSnapshot(30, 1, stored.copy(name = "Other account history"))
            yield()
            assertEquals("Other account history", model.uiState.value.displayName)
            status.snapshot.value = status.snapshot.value.copy(relationships = status.snapshot.value.relationships.nextUnloaded())
            yield()
            assertEquals("#20", model.uiState.value.displayName)
            timeline.profile.value = BcContactProfileSnapshot(30, 2, stored.copy(name = "Current history"))
            yield()
            assertEquals("Current history", model.uiState.value.displayName)
        } finally { model.close(); scope.cancel() }
    }

    @Test
    fun repeatedRelationshipClicksAreNotQueuedAndOfflineChangesAreRejectedLocally() = runBlocking {
        val status = FakeConnectionStatus(session(character(20)).copy(
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<BcRelationshipMutationResult>()
        var calls = 0
        try {
            val viewModel = CharacterProfileViewModel(20, FakeTimeline(null), status,
                object : FakeRelationships() {
                    override suspend fun updateRelationshipList(
                        memberNumber: Int,
                        kind: BcRelationshipListKind,
                        enabled: Boolean,
                    ): BcRelationshipMutationResult {
                        calls++
                        return result.await()
                    }
                }, scope)
            viewModel.updateRelationshipList(BcRelationshipListKind.Friend, true)
            viewModel.updateRelationshipList(BcRelationshipListKind.Friend, true)
            assertTrue(viewModel.uiState.value.relationship!!.operationInFlight)
            result.complete(BcRelationshipMutationResult.Submitted)
            yield()
            assertEquals(1, calls)
            assertFalse(viewModel.uiState.value.relationship!!.operationInFlight)
            assertEquals(R.string.character_list_update_submitted, viewModel.uiState.value.relationshipMessageRes)

            status.snapshot.value = status.snapshot.value.copy(connectionHealth = BcConnectionHealth.Offline)
            viewModel.updateRelationshipList(BcRelationshipListKind.Ignore, true)
            yield()
            assertEquals(1, calls)
            assertNull(viewModel.uiState.value.relationship)
            assertNull(viewModel.uiState.value.relationshipMessageRes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun relationshipCompletionFromPreviousAccountDoesNotPublishFeedback() = runBlocking {
        val status = FakeConnectionStatus(session(character(20)).copy(
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<BcRelationshipMutationResult>()
        try {
            val viewModel = CharacterProfileViewModel(20, FakeTimeline(null), status,
                object : FakeRelationships() {
                    override suspend fun updateRelationshipList(
                        memberNumber: Int,
                        kind: BcRelationshipListKind,
                        enabled: Boolean,
                    ): BcRelationshipMutationResult = result.await()
                }, scope)
            viewModel.updateRelationshipList(BcRelationshipListKind.Friend, true)
            status.snapshot.value = status.snapshot.value.copy(
                account = BcAccount(30, "Other", "other"),
                relationships = status.snapshot.value.relationships.nextLoaded(30, BcSelfRelations()),
            )
            result.complete(BcRelationshipMutationResult.Submitted)
            yield()

            assertNull(viewModel.uiState.value.relationshipMessageRes)
            assertFalse(viewModel.uiState.value.relationship!!.operationInFlight)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun leavingRoomKeepsOwnIdentity() = runBlocking {
        val status = FakeConnectionStatus(session(character(10, isSelf = true)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = profile(10, status, scope)
            yield()
            assertTrue(viewModel.uiState.value.isInCurrentRoom)
            assertTrue(viewModel.uiState.value.isSelf)

            status.snapshot.value = status.snapshot.value.copy(currentRoom = null, roomCharacters = emptyList())
            yield()

            assertFalse(viewModel.uiState.value.isInCurrentRoom)
            assertTrue(viewModel.uiState.value.isSelf)
            assertNull(viewModel.uiState.value.relationship)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun liveEmptyLovershipDoesNotRestoreHistoricalLovers() = runBlocking {
        val status = FakeConnectionStatus(session(character(20)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stored = BcContactProfile(
            memberNumber = 20,
            name = "Stored",
            loverNames = listOf("Former lover"),
            firstSeenAtMillis = 1,
            lastSeenAtMillis = 1,
        )
        try {
            val viewModel = profile(20, status, scope, stored)
            yield()
            assertTrue(viewModel.uiState.value.lovers.map { it.name }.isEmpty())

            status.snapshot.value = status.snapshot.value.copy(currentRoom = null, roomCharacters = emptyList())
            yield()
            assertEquals(stored.loverNames, viewModel.uiState.value.lovers.map { it.name })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearedLiveProfileFieldsDoNotRestoreHistoricalValues() = runBlocking {
        val status = FakeConnectionStatus(session(character(20)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stored = BcContactProfile(
            memberNumber = 20,
            name = "Stored",
            nickname = "Former nickname",
            title = "Former title",
            description = "Former description",
            ownerName = "Former owner",
            statusText = "Away",
            firstSeenAtMillis = 1,
            lastSeenAtMillis = 1,
        )
        try {
            val viewModel = profile(20, status, scope, stored)
            yield()

            assertNull(viewModel.uiState.value.nickname)
            assertNull(viewModel.uiState.value.title)
            assertNull(viewModel.uiState.value.description)
            assertNull(viewModel.uiState.value.owner)
            assertNull(viewModel.uiState.value.statusText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun presenceAndReverseRelationshipUseTheSameSessionPublication() = runBlocking {
        val first = character(20).copy(displayName = "Before", whiteList = listOf(10))
        val status = FakeConnectionStatus(session(first))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = profile(20, status, scope)
            val publications = mutableListOf<CharacterProfileUiState>()
            scope.launch { viewModel.uiState.collect { publications += it } }
            yield()

            status.snapshot.value = status.snapshot.value.copy(
                roomCharacters = listOf(first.copy(displayName = "After", whiteList = emptyList())),
            )
            yield()

            assertEquals("After", viewModel.uiState.value.displayName)
            assertFalse(viewModel.uiState.value.relationship!!.theyWhitelistMe!!)
            publications.filter { it.relationship != null }.forEach { state ->
                assertEquals(state.displayName == "Before", state.relationship!!.theyWhitelistMe)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingRelationshipQueryDoesNotPublishAnOperationError() = runBlocking {
        val status = FakeConnectionStatus(session(character(20)).copy(
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = CharacterProfileViewModel(
                memberNumber = 20,
                timeline = FakeTimeline(null),
                connectionStatus = status,
                relationships = object : FakeRelationships() {
                    override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult {
                        throw CancellationException("Screen closed")
                    }
                },
                scope = scope,
            )
            yield()
            viewModel.queryRelationshipCapabilities()
            yield()

            assertNull(viewModel.uiState.value.relationshipMessageRes)
            assertFalse(viewModel.uiState.value.relationship!!.operationInFlight)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun capabilitiesAreQueriedOnlyWhenTargetIsInCurrentRoom() = runBlocking {
        val inRoom = session(character(20)).copy(
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
        )
        val status = FakeConnectionStatus(inRoom.copy(currentRoom = null, roomCharacters = emptyList()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var queries = 0
        try {
            val viewModel = CharacterProfileViewModel(
                memberNumber = 20,
                timeline = FakeTimeline(null),
                connectionStatus = status,
                relationships = object : FakeRelationships() {
                    override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult {
                        queries++
                        return BcRelationshipCapabilityQueryResult.Loaded(
                            dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities(queriedAtMillis = 1),
                        )
                    }
                },
                scope = scope,
            )
            yield()
            viewModel.queryRelationshipCapabilities()
            assertEquals(0, queries)
            status.snapshot.value = inRoom
            yield()
            assertEquals(1, queries)
            status.snapshot.value = inRoom.copy(roomCharacters = emptyList())
            yield()
            viewModel.queryRelationshipCapabilities()
            assertEquals(1, queries)
            assertNull(viewModel.uiState.value.relationship)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closingProfileCancelsItsQueryAndKeepsParentScopeAlive() = runBlocking {
        val status = FakeConnectionStatus(
            session(character(20)).copy(
                desiredOnline = true,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        try {
            val viewModel = CharacterProfileViewModel(
                memberNumber = 20,
                timeline = FakeTimeline(null),
                connectionStatus = status,
                relationships = object : FakeRelationships() {
                    override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult {
                        started.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                            error("Query should have been cancelled")
                        } finally {
                            cancelled = true
                        }
                    }
                },
                scope = scope,
            )
            started.await()

            viewModel.close()

            assertTrue(cancelled)
            assertTrue(scope.coroutineContext[Job]!!.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun incompleteQueriesStaySilentAndAllowRetry() = runBlocking {
        for (result in listOf(
            BcRelationshipCapabilityQueryResult.Unanswered,
            BcRelationshipCapabilityQueryResult.Partial(BcRelationshipCapabilities(
                lovershipResult = "CanBeginDating", queriedAtMillis = 1,
            )),
        )) {
            val status = FakeConnectionStatus(session(character(20)).copy(
                desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline,
            ))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var queries = 0
            try {
                val viewModel = CharacterProfileViewModel(
                    memberNumber = 20, timeline = FakeTimeline(null), connectionStatus = status,
                    relationships = object : FakeRelationships() {
                        override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult {
                            queries++
                            return result
                        }
                    },
                    scope = scope,
                )
                yield()
                assertNull(viewModel.uiState.value.relationshipMessageRes)
                assertFalse(viewModel.uiState.value.relationship!!.operationInFlight)
                viewModel.queryRelationshipCapabilities()
                yield()
                assertEquals(2, queries)
            } finally {
                scope.cancel()
            }
        }
    }


    @Test
    fun cachedBioAndOutfitRenderOutsideRoomWithLegacyNameFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stored = BcContactProfile(20, "Cached", ownerName = "Legacy owner", loverNames = listOf("Legacy lover"),
            firstSeenAtMillis = 1, lastSeenAtMillis = 200, snapshotUpdatedAtMillis = 200,
            portrait = dev.vivian.mobilebc.core.model.BcProfilePortrait("R133", "Female3DCG", listOf("Kneel"),
                listOf(dev.vivian.mobilebc.core.model.BcCharacterItem("Cloth", "Shirt")), 200))
        try {
            val status = FakeConnectionStatus(session(character(20)).copy(currentRoom = null, roomCharacters = emptyList()))
            val model = profile(20, status, scope, stored)
            yield()
            assertEquals("Legacy owner", model.uiState.value.owner!!.name)
            assertNull(model.uiState.value.owner!!.memberNumber)
            assertEquals("Legacy lover", model.uiState.value.lovers.single().name)
            assertEquals("Shirt", model.uiState.value.portrait!!.character.appearance.single().name)
            assertEquals(200L, model.uiState.value.snapshotUpdatedAtMillis)
            assertNull(model.uiState.value.relationship)
            status.snapshot.value = status.snapshot.value.copy(account = BcAccount(99, "Other", "other"),
                relationships = status.snapshot.value.relationships.nextLoaded(99, BcSelfRelations()))
            yield()
            assertNull(model.uiState.value.portrait)
        } finally { scope.cancel() }
    }

    @Test
    fun liveRelationsKeepIdsAndActualOperationFailuresRemainVisible() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val target = character(20).copy(
            ownership = dev.vivian.mobilebc.core.model.BcCharacterOwnership("Owner", 30, stage = 1, start = 100L),
            lovership = listOf(dev.vivian.mobilebc.core.model.BcCharacterLovership("Lover", 40),
                dev.vivian.mobilebc.core.model.BcCharacterLovership("NPC-Eve", null)))
        try {
            val status = FakeConnectionStatus(session(target))
            val model = CharacterProfileViewModel(20, FakeTimeline(null), status, object : FakeRelationships() {
                override suspend fun updateRelationshipList(memberNumber: Int, kind: BcRelationshipListKind, enabled: Boolean) =
                    BcRelationshipMutationResult.Failed("failure")
            }, scope)
            assertEquals(30, model.uiState.value.owner!!.memberNumber)
            assertEquals(1, model.uiState.value.owner!!.stage)
            assertEquals(100L, model.uiState.value.owner!!.startEpochMillis)
            assertEquals(listOf(40, null), model.uiState.value.lovers.map { it.memberNumber })
            assertEquals(listOf("Lover", "Eve"), model.uiState.value.lovers.map { it.name })
            model.updateRelationshipList(BcRelationshipListKind.Friend, true)
            yield()
            assertEquals(R.string.character_operation_failed, model.uiState.value.relationshipMessageRes)
        } finally { scope.cancel() }
    }

    @Test
    fun leavingRoomCancelsQueryAndBlocksEveryOperationEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<BcRelationshipCapabilityQueryResult>()
        var mutations = 0
        var contracts = 0
        var queries = 0
        try {
            val status = FakeConnectionStatus(session(character(20)))
            val model = CharacterProfileViewModel(20, FakeTimeline(null), status, object : FakeRelationships() {
                override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult {
                    queries++
                    return pending.await()
                }
                override suspend fun updateRelationshipList(memberNumber: Int, kind: BcRelationshipListKind, enabled: Boolean): BcRelationshipMutationResult {
                    mutations++
                    return BcRelationshipMutationResult.Submitted
                }
                override suspend fun submitRelationshipContractAction(memberNumber: Int, kind: BcRelationshipContractKind,
                    action: BcRelationshipContractAction, destructiveActionConfirmed: Boolean): BcRelationshipContractActionResult {
                    contracts++
                    return BcRelationshipContractActionResult.Submitted
                }
            }, scope)
            status.snapshot.value = status.snapshot.value.copy(currentRoom = null, roomCharacters = emptyList(), roomPresenceEpoch = 2)
            model.updateRelationshipList(BcRelationshipListKind.Friend, true)
            model.queryRelationshipCapabilities()
            model.submitRelationshipAction(CharacterRelationshipContractAction("test", 0, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Propose))
            pending.complete(BcRelationshipCapabilityQueryResult.Failed("late error"))
            yield()
            assertEquals(1, queries)
            assertEquals(0, mutations)
            assertEquals(0, contracts)
            assertNull(model.uiState.value.relationship)
            assertNull(model.uiState.value.relationshipMessageRes)
        } finally { scope.cancel() }
    }

    @Test
    fun usernameUsesPublicNameRatherThanInternalAccountNameOrNickname() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val target = character(20).copy(name = "PublicName", accountName = "Online-internal", nickname = "Nick")
            val status = FakeConnectionStatus(session(target))
            val stored = BcContactProfile(20, "Cached nickname", characterName = "CachedName", firstSeenAtMillis = 1, lastSeenAtMillis = 2)
            val model = profile(20, status, scope, stored)
            assertEquals("PublicName", model.uiState.value.characterName)
            status.snapshot.value = status.snapshot.value.copy(currentRoom = null, roomCharacters = emptyList())
            yield()
            assertEquals("CachedName", model.uiState.value.characterName)
        } finally { scope.cancel() }
    }

    private fun profile(
        memberNumber: Int,
        status: FakeConnectionStatus,
        scope: CoroutineScope,
        stored: BcContactProfile? = null,
    ) = CharacterProfileViewModel(
        memberNumber = memberNumber,
        timeline = FakeTimeline(stored),
        connectionStatus = status,
        relationships = FakeRelationships(),
        scope = scope,
    )

    private fun character(memberNumber: Int, isSelf: Boolean = false) = BcCharacterSnapshot(
        id = memberNumber.toString(),
        memberNumber = memberNumber,
        name = "Target",
        displayName = "Target",
        isSelf = isSelf,
    )

    private fun session(character: BcCharacterSnapshot): BcSessionSnapshot = BcSessionSnapshot(
        state = BcSessionState.Ready,
        desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline,
        account = BcAccount(10, "Self", "self"),
        currentRoom = BcRoom(
            name = BcRoomName("Room"),
            description = "",
            memberCount = 2,
            memberLimit = 10,
            isLocked = false,
            language = null,
        ),
        roomCharacters = listOf(character),
    ).let { it.copy(relationships = it.relationships.nextLoaded(10, BcSelfRelations())) }

    private class FakeConnectionStatus(initial: BcSessionSnapshot) : ConnectionStatusReader {
        override val snapshot = MutableStateFlow(initial)
    }

    private class FakeTimeline(stored: BcContactProfile?) : BcSessionTimeline {
        override val conversations: StateFlow<BcConversationSnapshot> = MutableStateFlow(BcConversationSnapshot())
        override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
        override fun observeMessages(conversationId: BcConversationId, limit: Int): Flow<BcMessageSnapshot> =
            flowOf(BcMessageSnapshot())
        val profile = MutableStateFlow(BcContactProfileSnapshot(10, 1, stored))
        override fun observeCharacterProfile(memberNumber: Int): Flow<BcContactProfileSnapshot> = profile
        override fun selectConversation(conversationId: BcConversationId, owner: Any) = Unit
        override fun clearSelectedConversation(owner: Any) = Unit
        override fun isConversationVisible(conversationId: BcConversationId): Boolean = false
        override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) = Unit
    }

    private open class FakeRelationships : BcSessionRelationships {
        override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult =
            BcRelationshipCapabilityQueryResult.Loaded(BcRelationshipCapabilities())
        override suspend fun submitRelationshipContractAction(
            memberNumber: Int,
            kind: BcRelationshipContractKind,
            action: BcRelationshipContractAction,
            destructiveActionConfirmed: Boolean,
        ): BcRelationshipContractActionResult = BcRelationshipContractActionResult.Submitted
        override suspend fun updateRelationshipList(
            memberNumber: Int,
            kind: BcRelationshipListKind,
            enabled: Boolean,
        ): BcRelationshipMutationResult = BcRelationshipMutationResult.Submitted
        override fun recordFriendRequest(senderMemberNumber: Int, receivedAtMillis: Long) = Unit
        override suspend fun acceptFriendRequest(senderMemberNumber: Int): BcRelationshipMutationResult =
            BcRelationshipMutationResult.Submitted
        override fun dismissFriendRequest(senderMemberNumber: Int) = Unit
    }
}
