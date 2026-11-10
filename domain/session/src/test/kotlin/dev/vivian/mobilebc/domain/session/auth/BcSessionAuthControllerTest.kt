package dev.vivian.mobilebc.domain.session.auth

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionAuthControllerTest {
    @Test
    fun repeatedLoginAsTheSameAccountRequiresAFreshRoomAndCharacterSnapshot() = runTest {
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = privateState())
        })
        controller.login("alice", "secret")
        state.updateSession { it.copy(
            currentRoom = BcRoom(BcRoomName("Old Room"), "", 1, 10, false, null),
            roomMembers = listOf(BcMember(1001, "Alice", true)),
            roomCharacters = listOf(BcCharacterSnapshot("old-self", 1001, "Alice", "Alice", true, owner = "Old Owner")),
            roomPresenceEpoch = 7L,
        ) }
        controller.login("alice", "secret")
        assertEquals(1001, state.current.account?.memberNumber)
        assertNull(state.current.currentRoom)
        assertTrue(state.current.roomMembers.isEmpty())
        assertTrue(state.current.roomCharacters.isEmpty())
        assertEquals(8L, state.current.roomPresenceEpoch)
    }

    @Test
    fun serverClockKeepsTheWireReceiveTimeWhenTheEventConsumerRunsLater() {
        val state = connectedState()
        val controller = BcSessionAuthController(
            stateHolder = state, gateway = Gateway { _, _ -> BcSessionLoginResult.Success(1001, "Alice") },
            cancelReconnectJob = {}, upsertFriendConversations = {}, upsertLoginConversation = {},
            refreshOnlineFriends = {}, emitRoomTargetChanged = {}, monotonicNowNanos = { 900_000_000L },
        )
        controller.handleGatewayEvent(BcSessionGatewayEvent.ServerInfo(1, 10_000L, 100_000_000L))
        assertEquals(BcServerTimeAnchor(10_000L, 100_000_000L), state.current.serverTimeAnchor)
        assertEquals(10_800L, state.current.estimatedServerTimeMillis(900_000_000L))
    }

    @Test
    fun canonicalLogsStayKnownAcrossAnOwnershipUpdateAndUseTheGatewayClearProjection() = runTest {
        val state = connectedState()
        val initial = privateState("BlockTalk")
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = initial)
        })
        controller.login("alice", "secret")
        controller.handleGatewayEvent(BcSessionGatewayEvent.RelationshipUpdated(
            BcRelationshipGatewayUpdate.Ownership(null, null, "New Owner", null, false),
            accountMemberNumber = 1001, selfRuleLogSessionId = "current", revision = 1L,
        ))
        assertEquals(initial, state.current.privateChatState)
        assertEquals(initial.ruleLog, state.current.selfRuleLog)
        val cleared = privateState(revision = 1L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", cleared))
        controller.handleGatewayEvent(BcSessionGatewayEvent.RelationshipUpdated(
            BcRelationshipGatewayUpdate.Ownership(null, null, null, null, true),
            accountMemberNumber = 1001, selfRuleLogSessionId = "current", revision = 2L,
        ))
        assertEquals(cleared, state.current.privateChatState)
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), state.current.selfRuleLog)
    }

    @Test
    fun whisperTargetResetSurvivesNewerLogUpdatesButCannotCrossLoginGenerations() = runTest {
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = privateState())
        })
        controller.login("alice", "secret")
        val newest = privateState("BlockTalk", revision = 4L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", newest))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState("BlockWhisper", 3L), true))
        assertEquals(newest, state.current.privateChatState)
        assertEquals(3L, state.current.whisperTargetResetRevision)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "old", privateState(revision = 8L), true))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(2002, "current", privateState(revision = 8L), true))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState(revision = 2L), true))
        assertEquals(3L, state.current.whisperTargetResetRevision)
        controller.logout()
        assertNull(state.current.privateChatState)
        assertEquals(0L, state.current.whisperTargetResetRevision)
    }

    @Test
    fun whisperTargetResetBeforeLoginPublicationIsRetainedAlongsideTheNewestLog() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { controller.login("alice", "secret") }
        runCurrent()
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState("BlockWhisper", 1L), true))
        val next = privateState("BlockTalk", revision = 2L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", next))
        reply.complete(BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = privateState()))
        login.join()
        assertEquals(next, state.current.privateChatState)
        assertEquals(1L, state.current.whisperTargetResetRevision)
    }

    @Test
    fun canonicalPrivateStateOverridesLegacyProjectionAndUpdatesAreGenerationAndRevisionFenced() = runTest {
        val initial = privateState()
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = listOf(BcOwnerRuleLogRecord("Legacy", "OwnerRule")),
                selfRuleLogSessionId = "current", privateChatState = initial)
        })
        controller.login("alice", "secret")
        assertEquals(initial, state.current.privateChatState)
        assertEquals(initial.ruleLog, state.current.selfRuleLog)
        val next = privateState("BlockTalk", revision = 2L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(2002, "current", next))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "old", next))
        assertEquals(initial, state.current.privateChatState)

        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", next))
        assertEquals(next, state.current.privateChatState)
        assertEquals(next.ruleLog, state.current.selfRuleLog)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState(revision = 1L)))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState(revision = 2L)))
        assertEquals(next, state.current.privateChatState)

        val unknown = next.copy(ruleLog = null, revision = 3L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", unknown))
        assertEquals(unknown, state.current.privateChatState)
        assertNull(state.current.selfRuleLog)
    }

    @Test
    fun privateRuleUpdatesBeforeLoginPublicationKeepTheNewestRevision() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { controller.login("alice", "secret") }
        runCurrent()
        val next = privateState("BlockEmote", revision = 3L)
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", next))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "current", privateState(revision = 2L)))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(2002, "current", privateState(revision = 8L)))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(1001, "old", privateState(revision = 8L)))
        reply.complete(BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = privateState()))
        login.join()
        assertEquals(next, state.current.privateChatState)
        assertEquals(next.ruleLog, state.current.selfRuleLog)
    }

    @Test
    fun legacyInvalidationClearsCanonicalTrustWithoutLosingTheOriginalLogOrPreferences() = runTest {
        val initial = privateState("BlockTalk")
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = "current", privateChatState = initial)
        })
        controller.login("alice", "secret")
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(1001, "current"))
        assertNull(state.current.selfRuleLog)
        assertEquals(initial.copy(ruleLog = null), state.current.privateChatState)
        controller.handleGatewayEvent(BcSessionGatewayEvent.Disconnected("Lost"))
        assertNull(state.current.privateChatState)
    }

    private fun privateState(name: String? = null, revision: Long = 0L) = BcPrivateChatState(
        rawLogJson = if (name == null) "[]" else "[{\"Name\":\"$name\",\"Group\":\"OwnerRule\",\"Extra\":{\"Keep\":true}}]",
        ruleLog = name?.let { listOf(BcOwnerRuleLogRecord(it, "OwnerRule")) } ?: emptyList(),
        preferences = BcPrivateChatPreferences(oocAutoClose = false, chatSettingsJson = "{\"OOCAutoClose\":false,\"Extra\":17}"),
        revision = revision,
    )

    @Test
    fun loginPublishesPrivateRulesAndAnotherAccountReplacesThem() = runTest {
        val rules = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule", 50_000L))
        val gateway = Gateway { account, _ ->
            if (account == "alice") BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = rules, selfRuleLogSessionId = "alice-login")
            else BcSessionLoginResult.Success(2002, "Bob", selfRuleLog = emptyList(), selfRuleLogSessionId = "bob-login")
        }
        val state = connectedState()
        val controller = controller(state, gateway)

        controller.login("alice", "secret")
        assertEquals(rules, state.current.selfRuleLog)
        assertEquals("alice-login", state.current.selfRuleLogSessionId)

        controller.login("bob", "secret")
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), state.current.selfRuleLog)
        assertEquals("bob-login", state.current.selfRuleLogSessionId)
    }

    @Test
    fun beginningAnotherLoginClearsRulesBeforeItsResponseArrives() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val gateway = Gateway { _, _ -> reply.await() }
        val state = connectedState().apply {
            updateSession { it.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule")), selfRuleLogSessionId = "old") }
        }
        val login = launch { controller(state, gateway).login("bob", "secret") }
        runCurrent()

        assertNull(state.current.selfRuleLog)
        assertNull(state.current.selfRuleLogSessionId)
        reply.complete(BcSessionLoginResult.Success(2002, "Bob"))
        login.join()
        assertNull(state.current.selfRuleLog)
    }

    @Test
    fun ownerRuleInvalidationMatchesTheAccountAndItsLoginGeneration() = runTest {
        val gateway = Gateway { _, _ -> BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "current") }
        val state = connectedState()
        val controller = controller(state, gateway)
        controller.login("alice", "secret")

        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(2002, "current"))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(1001, "old"))
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), state.current.selfRuleLog)

        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(1001, "current"))
        assertNull(state.current.selfRuleLog)
        assertEquals("current", state.current.selfRuleLogSessionId)
    }

    @Test
    fun ruleInvalidationBeforeLoginPublicationCannotBeOverwrittenByTheResponse() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { controller.login("alice", "secret") }
        runCurrent()

        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(1001, "current"))
        reply.complete(BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "current"))
        login.join()

        assertNull(state.current.selfRuleLog)
        assertEquals("current", state.current.selfRuleLogSessionId)
    }

    @Test
    fun invalidationFromAnOlderLoginDoesNotDiscardThePendingNewLog() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { controller.login("alice", "secret") }
        runCurrent()

        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(1001, "old"))
        controller.handleGatewayEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(2002, "new"))
        reply.complete(BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "new"))
        login.join()

        assertEquals(emptyList<BcOwnerRuleLogRecord>(), state.current.selfRuleLog)
        assertEquals("new", state.current.selfRuleLogSessionId)
    }

    @Test
    fun ownershipStateChangesInvalidateRulesButCapabilityResultsDoNot() = runTest {
        val state = connectedState()
        val controller = controller(state, Gateway { _, _ ->
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "current")
        })
        controller.login("alice", "secret")
        controller.handleGatewayEvent(BcSessionGatewayEvent.RelationshipUpdated(
            BcRelationshipGatewayUpdate.Ownership(2002, "CanOfferBeginTrial", null, null, false),
            accountMemberNumber = 1001, selfRuleLogSessionId = "current", revision = 1L,
        ))
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), state.current.selfRuleLog)

        controller.handleGatewayEvent(BcSessionGatewayEvent.RelationshipUpdated(
            BcRelationshipGatewayUpdate.Ownership(null, null, null, null, true),
            accountMemberNumber = 1001, selfRuleLogSessionId = "current", revision = 2L,
        ))
        assertNull(state.current.selfRuleLog)
    }

    @Test
    fun logoutClearsPrivateRulesImmediatelyEvenWhileAccountUpdatesFlush() = runTest {
        val flushed = CompletableDeferred<Unit>()
        val gateway = Gateway { _, _ -> BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "current") }
        gateway.flush = { flushed.await() }
        val state = connectedState()
        val controller = controller(state, gateway)
        controller.login("alice", "secret")
        val logout = launch { controller.logout() }
        runCurrent()

        assertNull(state.current.selfRuleLog)
        assertNull(state.current.selfRuleLogSessionId)
        flushed.complete(Unit)
        logout.join()
        assertNull(state.current.serverTimeAnchor)
    }

    @Test
    fun disconnectAndForcedDisconnectClearRulesAndTheServerClock() = runTest {
        listOf(
            BcSessionGatewayEvent.Disconnected("network"),
            BcSessionGatewayEvent.ForceDisconnected(BcSessionForceDisconnectReason.DuplicatedLogin),
        ).forEach { event ->
            val state = connectedState()
            val controller = controller(state, Gateway { _, _ -> BcSessionLoginResult.Success(1001, "Alice", selfRuleLog = emptyList(), selfRuleLogSessionId = "current") })
            controller.login("alice", "secret")
            controller.handleGatewayEvent(BcSessionGatewayEvent.ServerInfo(1, 1_000L))
            controller.handleGatewayEvent(event)

            assertNull(state.current.selfRuleLog)
            assertNull(state.current.selfRuleLogSessionId)
            assertNull(state.current.serverTimeAnchor)
        }
    }

    @Test
    fun serverClockUsesServerInfoAndMonotonicElapsedTime() {
        val state = connectedState()
        val controller = BcSessionAuthController(
            stateHolder = state,
            gateway = Gateway { _, _ -> BcSessionLoginResult.Failure("unused") },
            cancelReconnectJob = {}, upsertFriendConversations = {}, upsertLoginConversation = {},
            refreshOnlineFriends = {}, emitRoomTargetChanged = {}, monotonicNowNanos = { 100_000_000L },
        )

        assertNull(state.current.estimatedServerTimeMillis(100_000_000L))
        controller.handleGatewayEvent(BcSessionGatewayEvent.ServerInfo(1, 10_000L))
        assertEquals(BcServerTimeAnchor(10_000L, 100_000_000L), state.current.serverTimeAnchor)
        assertEquals(10_200L, state.current.estimatedServerTimeMillis(300_000_000L))
    }

    @Test
    fun lateSuccessCannotReactivateAStoppedSession() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val gateway = Gateway { _, _ -> withContext(NonCancellable) { reply.await() } }
        val state = connectedState()
        val controller = controller(state, gateway)
        val login = launch { controller.login("alice", "old-secret") }
        runCurrent()

        state.updateSession {
            it.copy(
                state = BcSessionState.Stopped,
                desiredOnline = false,
                relationships = it.relationships.nextUnloaded(),
            )
        }
        reply.complete(BcSessionLoginResult.Success(1001, "Alice"))
        runCurrent()

        assertTrue(login.isCancelled)
        assertEquals(BcSessionState.Stopped, state.current.state)
        assertNull(state.current.account)
        assertNull(state.lastAccountName)
        assertNull(state.lastPassword)
    }

    @Test
    fun beginningANewerLoginRejectsAnOlderSuccessBeforeTheNewReply() = runTest {
        val oldReply = CompletableDeferred<BcSessionLoginResult>()
        val newReply = CompletableDeferred<BcSessionLoginResult>()
        val gateway = Gateway { account, _ ->
            withContext(NonCancellable) { if (account == "alice") oldReply.await() else newReply.await() }
        }
        val state = connectedState()
        val controller = controller(state, gateway)
        val oldLogin = launch { controller.login("alice", "old-secret") }
        runCurrent()
        val newLogin = launch { controller.login("bob", "new-secret") }
        runCurrent()

        oldReply.complete(BcSessionLoginResult.Success(1001, "Alice"))
        runCurrent()

        assertTrue(oldLogin.isCancelled)
        assertNull(state.current.account)
        assertNull(state.lastPassword)
        newReply.complete(BcSessionLoginResult.Success(2002, "Bob"))
        runCurrent()
        newLogin.join()

        assertEquals("bob", state.current.account?.accountName)
        assertEquals("new-secret", state.lastPassword)
        assertTrue(state.current.isVerifiedOnline)
    }

    @Test
    fun lateFailureCannotClearANewerAuthenticatedAccountOrDisconnectItsTransport() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val gateway = Gateway { account, _ ->
            if (account == "alice") withContext(NonCancellable) { reply.await() }
            else BcSessionLoginResult.Success(2002, "Bob")
        }
        val state = connectedState()
        val controller = controller(state, gateway)
        val oldLogin = launch { controller.login("alice", "old-secret") }
        runCurrent()
        controller.login("bob", "new-secret")

        reply.complete(BcSessionLoginResult.Failure("invalid password"))
        runCurrent()

        assertTrue(oldLogin.isCancelled)
        assertEquals("bob", state.current.account?.accountName)
        assertEquals("new-secret", state.lastPassword)
        assertTrue(state.current.isVerifiedOnline)
        assertEquals(0, gateway.disconnects)
    }

    @Test
    fun logoutInvalidatesAnOldLoginWhileAccountUpdatesAreStillFlushing() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val flushed = CompletableDeferred<Unit>()
        val gateway = Gateway { _, _ -> withContext(NonCancellable) { reply.await() } }
        gateway.flush = { flushed.await() }
        val state = connectedState()
        val controller = controller(state, gateway)
        val login = launch { controller.login("alice", "secret") }
        runCurrent()
        val logout = launch { controller.logout() }
        runCurrent()

        reply.complete(BcSessionLoginResult.Success(1001, "Alice"))
        runCurrent()

        assertTrue(login.isCancelled)
        assertNull(state.current.account)
        assertNull(state.lastPassword)
        flushed.complete(Unit)
        runCurrent()
        logout.join()
        assertEquals(BcSessionState.Stopped, state.current.state)
        assertEquals(1, gateway.disconnects)
    }

    @Test
    fun cancelledCallerCannotPublishANonCooperativeLoginReply() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val gateway = Gateway { _, _ -> withContext(NonCancellable) { reply.await() } }
        val state = connectedState()
        val controller = controller(state, gateway)
        val login = launch { controller.login("alice", "secret") }
        runCurrent()

        login.cancel()
        reply.complete(BcSessionLoginResult.Success(1001, "Alice"))
        runCurrent()

        assertTrue(login.isCancelled)
        assertNull(state.current.account)
        assertNull(state.lastPassword)
    }

    private fun connectedState() = BcSessionRuntimeState().apply {
        updateSession {
            it.copy(
                state = BcSessionState.Connected,
                connectionHealth = BcConnectionHealth.ConnectedUnverified,
                desiredOnline = true,
            )
        }
    }

    private fun controller(state: BcSessionRuntimeState, gateway: Gateway) = BcSessionAuthController(
        stateHolder = state,
        gateway = gateway,
        cancelReconnectJob = {},
        upsertFriendConversations = {},
        upsertLoginConversation = {},
        refreshOnlineFriends = {},
        emitRoomTargetChanged = {},
    )

    private class Gateway(
        private val reply: suspend (String, String) -> BcSessionLoginResult,
    ) : SessionConnectionGateway {
        override val events: Flow<BcSessionGatewayEvent> = emptyFlow()
        var disconnects = 0
        var flush: suspend () -> Unit = {}
        override suspend fun connect(config: BcServerConfig) = Unit
        override suspend fun login(accountName: String, password: String): BcSessionLoginResult = reply(accountName, password)
        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()
        override suspend fun flushAccountUpdates() = flush()
        override suspend fun disconnect() { disconnects += 1 }
        override fun isProbablyConnected(): Boolean = true
    }
}
