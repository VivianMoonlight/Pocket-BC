package dev.vivian.mobilebc.domain.session.auth

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.RelationshipGateway
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationshipController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionRelationshipLoginIsolationTest {
    @Test
    fun clearBeforeLoginPublicationReplacesStaleOwnershipAndKeepsCanonicalPrivateLogs() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val loadedSnapshots = mutableListOf<BcRelationshipSnapshot>()
        val fixture = Fixture(onRelationshipsLoaded = { loadedSnapshots += it }) { _, _ -> reply.await() }
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()

        val clearedPrivateState = privateState(revision = 1L)
        fixture.auth.handleGatewayEvent(
            BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", clearedPrivateState),
        )
        assertFalse(fixture.deliver(ownershipEvent(clear = true, revision = 7L)))
        assertTrue(loadedSnapshots.isEmpty())
        reply.complete(success(
            relationships = BcSelfRelations(legacyOwnerName = "Old Owner", ownership = owner(2002, "Old Owner")),
            privateChatState = privateState("BlockTalk"),
        ))
        login.join()

        val snapshot = fixture.state.current
        assertNull(snapshot.relationships.self.ownership)
        assertNull(snapshot.relationships.self.legacyOwnerName)
        assertEquals(7L, snapshot.relationships.revision)
        assertEquals(7L, snapshot.relationships.ownershipClearRevision)
        assertEquals("current", snapshot.relationships.loginSessionId)
        assertEquals(clearedPrivateState, snapshot.privateChatState)
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), snapshot.selfRuleLog)
        assertEquals(listOf(snapshot.relationships), loadedSnapshots)
    }

    @Test
    fun updatesBeforePublicationMergeByRevisionWithoutReplacingUnrelatedLoginFields() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val fixture = Fixture { _, _ -> reply.await() }
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()
        val lover = lover(4004, "Dana")

        fixture.deliver(lovershipEvent(
            target = 2002,
            result = "CanBeginDating",
            loverships = listOf(lover(1001, "Alice"), lover),
            revision = 3L,
        ))
        fixture.deliver(ownershipEvent(
            target = 2002,
            result = "CanOfferStartTrial",
            owner = owner(3003, "New Owner"),
            revision = 1L,
        ))
        fixture.deliver(ownershipEvent(
            target = 2002,
            result = "CanOfferEndTrial",
            revision = 2L,
        ))
        reply.complete(success(
            relationships = BcSelfRelations(
                friends = setOf(5005),
                legacyOwnerName = "Old Owner",
                ownership = owner(6006, "Old Owner"),
                money = 500,
            ),
        ))
        login.join()

        val relationships = fixture.state.current.relationships
        assertEquals(1001, relationships.accountMemberNumber)
        assertEquals(owner(3003, "New Owner"), relationships.self.ownership)
        assertEquals("New Owner", relationships.self.legacyOwnerName)
        assertEquals(listOf(lover), relationships.self.loverships)
        assertEquals(setOf(5005), relationships.self.friends)
        assertEquals(500, relationships.self.money)
        assertEquals("CanOfferEndTrial", relationships.capabilitiesByMember[2002]?.ownershipResult)
        assertEquals("CanBeginDating", relationships.capabilitiesByMember[2002]?.lovershipResult)
        assertEquals(setOf(2002), relationships.capabilitiesByMember.keys)
        assertEquals(3L, relationships.revision)
    }

    @Test
    fun loginResultRevisionSupersedesOlderBufferedUpdatesWhileNewerUpdatesStillApply() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val fixture = Fixture { _, _ -> reply.await() }
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()
        fixture.deliver(ownershipEvent(clear = true, revision = 2L))
        fixture.deliver(ownershipEvent(owner = owner(7007, "Stale Owner"), revision = 4L))
        fixture.deliver(lovershipEvent(loverships = emptyList(), revision = 5L))
        fixture.deliver(ownershipEvent(target = 2002, result = "CanOfferEndTrial", revision = 8L))
        val currentOwner = owner(3003, "Current Owner")
        val currentLover = lover(4004, "Dana")
        val currentCapabilities = BcRelationshipCapabilities(
            ownershipResult = "CanOfferStartTrial",
            lovershipResult = "CanBeginDating",
            queriedAtMillis = 900L,
        )
        reply.complete(success(
            relationships = BcSelfRelations(
                legacyOwnerName = currentOwner.name,
                ownership = currentOwner,
                loverships = listOf(currentLover),
            ),
            revision = 5L,
            capabilities = mapOf(2002 to currentCapabilities),
            ownershipClearRevision = 3L,
        ))
        login.join()

        val relationships = fixture.state.current.relationships
        assertEquals(currentOwner, relationships.self.ownership)
        assertEquals(listOf(currentLover), relationships.self.loverships)
        assertEquals("CanOfferEndTrial", relationships.capabilitiesByMember[2002]?.ownershipResult)
        assertEquals("CanBeginDating", relationships.capabilitiesByMember[2002]?.lovershipResult)
        assertEquals(8L, relationships.revision)
        assertEquals(3L, relationships.ownershipClearRevision)
    }

    @Test
    fun bufferedEventsRequireTheExactAccountLoginAndACompleteValidEnvelope() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val fixture = Fixture { _, _ -> reply.await() }
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()
        val clear = ownershipEvent(clear = true, revision = 10L)
        listOf(
            clear.copy(accountMemberNumber = 2002),
            clear.copy(selfRuleLogSessionId = "old"),
            clear.copy(accountMemberNumber = null),
            clear.copy(selfRuleLogSessionId = null),
            clear.copy(selfRuleLogSessionId = ""),
            clear.copy(selfRuleLogSessionId = "   "),
            clear.copy(revision = null),
            clear.copy(revision = -1L),
            BcSessionGatewayEvent.RelationshipUpdated(clear.update),
        ).forEach { assertFalse(fixture.deliver(it)) }
        val initial = BcSelfRelations(legacyOwnerName = "Owner", ownership = owner(3003, "Owner"))
        reply.complete(success(relationships = initial))
        login.join()

        assertEquals(initial, fixture.state.current.relationships.self)
        assertEquals(0L, fixture.state.current.relationships.revision)
        assertEquals(privateState("BlockTalk"), fixture.state.current.privateChatState)
    }

    @Test
    fun sameAccountReloginCannotReuseEarlierLoginEventsBeforeOrAfterPublication() = runTest {
        val secondReply = CompletableDeferred<BcSessionLoginResult>()
        var calls = 0
        val fixture = Fixture { _, _ ->
            if (++calls == 1) success(sessionId = "old", relationships = BcSelfRelations(
                legacyOwnerName = "Old Owner", ownership = owner(2002, "Old Owner"),
            ), revision = 2L, ownershipClearRevision = 1L) else secondReply.await()
        }
        fixture.auth.login("alice", "secret")
        val firstGeneration = fixture.state.current.relationships.accountGeneration
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()
        assertNull(fixture.state.current.privateChatState)
        assertFalse(fixture.deliver(ownershipEvent(clear = true, sessionId = "old", revision = 99L)))
        fixture.deliver(ownershipEvent(owner = owner(3003, "New Owner"), sessionId = "new", revision = 1L))
        secondReply.complete(success(sessionId = "new"))
        login.join()

        assertFalse(fixture.deliver(ownershipEvent(clear = true, sessionId = "old", revision = 100L)))
        assertFalse(fixture.deliver(lovershipEvent(
            target = 2002, result = "CanBeginDating", sessionId = "old", revision = 101L,
        )))
        fixture.auth.handleGatewayEvent(
            BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "old", privateState(revision = 100L)),
        )

        val snapshot = fixture.state.current
        assertEquals(firstGeneration + 1L, snapshot.relationships.accountGeneration)
        assertEquals(owner(3003, "New Owner"), snapshot.relationships.self.ownership)
        assertEquals("new", snapshot.relationships.loginSessionId)
        assertEquals("new", snapshot.selfRuleLogSessionId)
        assertEquals(1L, snapshot.relationships.revision)
        assertNull(snapshot.relationships.ownershipClearRevision)
        assertTrue(snapshot.relationships.capabilitiesByMember.isEmpty())
        assertEquals(privateState("BlockTalk"), snapshot.privateChatState)
    }

    @Test
    fun liveEventsIgnoreOtherAccountsSessionsDuplicatesAndOlderRevisions() = runTest {
        val initial = BcSelfRelations(legacyOwnerName = "Owner", ownership = owner(3003, "Owner"))
        val fixture = Fixture { _, _ -> success(relationships = initial, revision = 4L) }
        fixture.auth.login("alice", "secret")
        val clear = ownershipEvent(clear = true, revision = 5L)
        assertFalse(fixture.deliver(clear.copy(accountMemberNumber = 2002)))
        assertFalse(fixture.deliver(clear.copy(selfRuleLogSessionId = "old")))
        assertFalse(fixture.deliver(clear.copy(revision = 4L)))
        assertFalse(fixture.deliver(clear.copy(revision = 3L)))
        assertEquals(initial, fixture.state.current.relationships.self)

        assertTrue(fixture.deliver(ownershipEvent(
            target = 2002, result = "CanOfferStartTrial", owner = owner(4004, "New Owner"), revision = 5L,
        )))
        assertFalse(fixture.deliver(clear))
        assertFalse(fixture.deliver(clear.copy(revision = 4L)))
        val snapshot = fixture.state.current
        assertEquals(1001, snapshot.account?.memberNumber)
        assertEquals(1001, snapshot.relationships.accountMemberNumber)
        assertEquals(owner(4004, "New Owner"), snapshot.relationships.self.ownership)
        assertEquals("CanOfferStartTrial", snapshot.relationships.capabilitiesByMember[2002]?.ownershipResult)
        assertEquals(5L, snapshot.relationships.revision)
        assertEquals(privateState("BlockTalk"), snapshot.privateChatState)
    }

    @Test
    fun staleRelationshipEventsCannotInvalidateLegacyPrivateRules() = runTest {
        val rules = listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule"))
        val fixture = Fixture { _, _ -> BcSessionLoginResult.Success(
            1001, "Alice", selfRuleLog = rules, selfRuleLogSessionId = "current",
        ) }
        fixture.auth.login("alice", "secret")
        val clear = ownershipEvent(clear = true, revision = 1L)
        assertFalse(fixture.deliver(clear.copy(accountMemberNumber = 2002)))
        assertFalse(fixture.deliver(clear.copy(selfRuleLogSessionId = "old")))
        assertFalse(fixture.deliver(clear.copy(revision = 0L)))
        assertEquals(rules, fixture.state.current.selfRuleLog)
        assertNull(fixture.state.current.privateChatState)

        assertTrue(fixture.deliver(clear))
        assertNull(fixture.state.current.selfRuleLog)
        assertEquals("current", fixture.state.current.selfRuleLogSessionId)
        assertEquals(1L, fixture.state.current.relationships.ownershipClearRevision)
    }

    @Test
    fun clearBeforePublicationAlsoInvalidatesTheLegacyPrivateRuleProjection() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val fixture = Fixture { _, _ -> reply.await() }
        val login = launch { fixture.auth.login("alice", "secret") }
        runCurrent()
        fixture.deliver(ownershipEvent(clear = true, revision = 1L))
        reply.complete(BcSessionLoginResult.Success(
            memberNumber = 1001,
            name = "Alice",
            relationships = BcSelfRelations(legacyOwnerName = "Old Owner", ownership = owner(2002, "Old Owner")),
            selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule")),
            selfRuleLogSessionId = "current",
        ))
        login.join()

        assertNull(fixture.state.current.relationships.self.ownership)
        assertNull(fixture.state.current.selfRuleLog)
        assertNull(fixture.state.current.privateChatState)
        assertEquals("current", fixture.state.current.selfRuleLogSessionId)
        assertEquals(1L, fixture.state.current.relationships.revision)
        assertEquals(1L, fixture.state.current.relationships.ownershipClearRevision)
    }

    @Test
    fun obsoleteLoginCompletionCannotDiscardNewerPendingRelationshipUpdates() = runTest {
        val oldReply = CompletableDeferred<BcSessionLoginResult>()
        val newReply = CompletableDeferred<BcSessionLoginResult>()
        val loadedSnapshots = mutableListOf<BcRelationshipSnapshot>()
        val fixture = Fixture(onRelationshipsLoaded = { loadedSnapshots += it }) { account, _ ->
            withContext(NonCancellable) { if (account == "old") oldReply.await() else newReply.await() }
        }
        val oldLogin = launch { fixture.auth.login("old", "old-secret") }
        runCurrent()
        fixture.deliver(ownershipEvent(clear = true, sessionId = "old", revision = 8L))
        val newLogin = launch { fixture.auth.login("new", "new-secret") }
        runCurrent()
        fixture.deliver(ownershipEvent(owner = owner(3003, "New Owner"), sessionId = "new", revision = 1L))

        oldReply.complete(success(sessionId = "old"))
        runCurrent()
        assertTrue(oldLogin.isCancelled)
        assertTrue(loadedSnapshots.isEmpty())
        newReply.complete(success(sessionId = "new"))
        newLogin.join()

        val snapshot = fixture.state.current
        assertEquals("new", snapshot.account?.accountName)
        assertEquals("new-secret", fixture.state.lastPassword)
        assertEquals("new", snapshot.relationships.loginSessionId)
        assertEquals(owner(3003, "New Owner"), snapshot.relationships.self.ownership)
        assertEquals(1L, snapshot.relationships.revision)
        assertEquals(privateState("BlockTalk"), snapshot.privateChatState)
        assertEquals(listOf(snapshot.relationships), loadedSnapshots)
        assertNull(loadedSnapshots.single().ownershipClearRevision)
    }

    private class Fixture(
        onRelationshipsLoaded: (BcRelationshipSnapshot) -> Unit = {},
        reply: suspend (String, String) -> BcSessionLoginResult,
    ) {
        val state = BcSessionRuntimeState().apply {
            updateSession { it.copy(
                state = BcSessionState.Connected,
                connectionHealth = BcConnectionHealth.ConnectedUnverified,
                desiredOnline = true,
            ) }
        }
        val auth = BcSessionAuthController(
            stateHolder = state,
            gateway = Gateway(reply),
            cancelReconnectJob = {},
            upsertFriendConversations = {},
            upsertLoginConversation = {},
            refreshOnlineFriends = {},
            emitRoomTargetChanged = {},
            onRelationshipsLoaded = onRelationshipsLoaded,
        )
        val relationships = BcSessionRelationshipController(
            stateHolder = state,
            gateway = object : RelationshipGateway {
                override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
            },
            nowMillis = { 1_000L },
        )

        fun deliver(event: BcSessionGatewayEvent.RelationshipUpdated): Boolean {
            auth.handleGatewayEvent(event)
            return relationships.handleGatewayEvent(event)
        }
    }

    private class Gateway(
        private val reply: suspend (String, String) -> BcSessionLoginResult,
    ) : SessionConnectionGateway {
        override val events: Flow<BcSessionGatewayEvent> = emptyFlow()
        override suspend fun connect(config: BcServerConfig) = Unit
        override suspend fun login(accountName: String, password: String) = reply(accountName, password)
        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()
        override suspend fun flushAccountUpdates() = Unit
        override suspend fun disconnect() = Unit
        override fun isProbablyConnected() = true
    }

    private fun success(
        relationships: BcSelfRelations = BcSelfRelations(),
        sessionId: String = "current",
        revision: Long = 0L,
        capabilities: Map<Int, BcRelationshipCapabilities> = emptyMap(),
        ownershipClearRevision: Long? = null,
        privateChatState: BcPrivateChatState = privateState("BlockTalk"),
    ) = BcSessionLoginResult.Success(
        memberNumber = 1001,
        name = "Alice",
        relationships = relationships,
        selfRuleLogSessionId = sessionId,
        privateChatState = privateChatState,
        relationshipRevision = revision,
        relationshipCapabilities = capabilities,
        relationshipOwnershipClearRevision = ownershipClearRevision,
    )

    private fun ownershipEvent(
        target: Int? = null,
        result: String? = null,
        owner: BcOwnership? = null,
        clear: Boolean = false,
        sessionId: String = "current",
        revision: Long,
    ) = BcSessionGatewayEvent.RelationshipUpdated(
        update = BcRelationshipGatewayUpdate.Ownership(target, result, owner?.name, owner, clear),
        accountMemberNumber = 1001,
        selfRuleLogSessionId = sessionId,
        revision = revision,
    )

    private fun lovershipEvent(
        target: Int? = null,
        result: String? = null,
        loverships: List<BcLovership>? = null,
        sessionId: String = "current",
        revision: Long,
    ) = BcSessionGatewayEvent.RelationshipUpdated(
        update = BcRelationshipGatewayUpdate.Lovership(target, result, loverships),
        accountMemberNumber = 1001,
        selfRuleLogSessionId = sessionId,
        revision = revision,
    )

    private fun owner(memberNumber: Int, name: String) = BcOwnership(memberNumber, name, 1, 100L, null)

    private fun lover(memberNumber: Int, name: String) = BcLovership(memberNumber, name, 0, 100L)

    private fun privateState(name: String? = null, revision: Long = 0L) = BcPrivateChatState(
        rawLogJson = if (name == null) "[]" else "[{\"Name\":\"$name\",\"Group\":\"OwnerRule\"}]",
        ruleLog = name?.let { listOf(BcOwnerRuleLogRecord(it, "OwnerRule")) } ?: emptyList(),
        preferences = BcPrivateChatPreferences(oocAutoClose = false),
        revision = revision,
    )
}
