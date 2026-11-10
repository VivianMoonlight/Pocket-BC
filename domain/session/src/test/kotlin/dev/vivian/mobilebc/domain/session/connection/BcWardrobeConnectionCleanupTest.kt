package dev.vivian.mobilebc.domain.session.connection

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BlockedReason
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcWardrobeConnectionCleanupTest {
    @Test
    fun stopClearsPrivateStateBeforeBlockedTransportDisconnectCompletes() = runTest {
        val state = loadedState()
        val disconnectRelease = CompletableDeferred<Unit>()
        val gateway = CleanupGateway(disconnectRelease = disconnectRelease)
        val controller = controller(state, gateway)

        try {
            controller.stop()

            assertTrue(gateway.disconnectEntered.isCompleted)
            assertFalse(disconnectRelease.isCompleted)
            assertEquals(BcSessionState.Stopped, state.current.state)
            assertFalse(state.current.desiredOnline)
            assertPrivateStateCleared(state.current)
            assertRoomCleared(state.current)
        } finally {
            disconnectRelease.complete(Unit)
            runCurrent()
        }

        assertNull(state.lastJoinedRoomName)
        assertNull(state.lastJoinedRoomPassword)
    }

    @Test
    fun closeClearsPrivateStateImmediatelyAndKeepsRecoveryTargetDuringBlockedDisconnect() = runTest {
        val state = loadedState()
        val disconnectRelease = CompletableDeferred<Unit>()
        val gateway = CleanupGateway(disconnectRelease = disconnectRelease)
        val controller = controller(state, gateway)

        try {
            controller.close()
            controller.close()

            assertTrue(gateway.disconnectEntered.isCompleted)
            assertEquals(1, gateway.disconnectCalls)
            assertFalse(disconnectRelease.isCompleted)
            assertEquals(BcSessionState.Stopped, state.current.state)
            assertPrivateStateCleared(state.current)
            assertRoomCleared(state.current)
            assertRecoveryTarget(state)
        } finally {
            disconnectRelease.complete(Unit)
            runCurrent()
        }

        assertRecoveryTarget(state)
    }

    @Test
    fun allForceDisconnectReasonsClearPrivateStateWithoutWaitingForAuth() = runTest {
        val cases = listOf(
            BcSessionForceDisconnectReason.DuplicatedLogin to BlockedReason.DuplicatedLogin,
            BcSessionForceDisconnectReason.RateLimited to BlockedReason.RateLimited,
            BcSessionForceDisconnectReason.Unknown to BlockedReason.ServerMaintenance,
        )
        for ((reason, blockedReason) in cases) {
            val state = loadedState()
            val gateway = CleanupGateway()

            controller(state, gateway).handleGatewayEvent(BcSessionGatewayEvent.ForceDisconnected(reason))

            assertEquals(BcSessionState.Blocked(blockedReason), state.current.state)
            assertFalse(state.current.desiredOnline)
            assertNull(state.lastPassword)
            assertPrivateStateCleared(state.current)
            assertRoomCleared(state.current)
            assertEquals(0, gateway.loginCalls)
        }
    }

    @Test
    fun disconnectedBlockedBranchClearsPrivateStateWithoutChangingBlockReason() = runTest {
        val state = loadedState().apply {
            updateSession { it.copy(state = BcSessionState.Blocked(BlockedReason.RateLimited)) }
        }
        val gateway = CleanupGateway()

        controller(state, gateway).handleGatewayEvent(BcSessionGatewayEvent.Disconnected("transport closed"))

        assertEquals(BcSessionState.Blocked(BlockedReason.RateLimited), state.current.state)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertPrivateStateCleared(state.current)
        assertRoomCleared(state.current)
        runCurrent()
        assertEquals(0, gateway.connectCalls)
    }

    @Test
    fun disconnectedNotDesiredBranchClearsPrivateStateWithoutStartingRecovery() = runTest {
        val state = loadedState().apply { updateSession { it.copy(desiredOnline = false) } }
        val gateway = CleanupGateway()

        controller(state, gateway).handleGatewayEvent(BcSessionGatewayEvent.Disconnected("offline"))

        assertFalse(state.current.desiredOnline)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertPrivateStateCleared(state.current)
        assertRoomCleared(state.current)
        runCurrent()
        assertEquals(0, gateway.connectCalls)
    }

    @Test
    fun disconnectedWithoutCredentialsClearsPrivateStateAndCannotReconnect() = runTest {
        val state = loadedState().apply { forgetLoginPassword() }
        val gateway = CleanupGateway()

        controller(state, gateway).handleGatewayEvent(BcSessionGatewayEvent.Disconnected("lost"))

        assertTrue(state.current.state is BcSessionState.Error)
        assertFalse((state.current.state as BcSessionState.Error).recoverable)
        assertPrivateStateCleared(state.current)
        assertRoomCleared(state.current)
        runCurrent()
        assertEquals(0, gateway.connectCalls)
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun networkLostClearsPrivateStateSynchronouslyAndKeepsTheRecoveryTarget() = runTest {
        val state = loadedState()
        val gateway = CleanupGateway()
        val controller = controller(state, gateway)

        try {
            controller.onNetworkLost()

            assertEquals(BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1), state.current.state)
            assertPrivateStateCleared(state.current)
            assertRoomCleared(state.current)
            assertRecoveryTarget(state)
            runCurrent()
            assertTrue(gateway.loginEntered.isCompleted)
            assertEquals(1, gateway.loginCalls)
            assertEquals("alice" to "secret", gateway.loginCredentials)
            assertRecoveryTarget(state)
        } finally {
            controller.cancelReconnect()
        }
    }

    @Test
    fun gatewayReconnectingEventAlsoClearsPrivateStateBeforeRecoveredLogin() = runTest {
        val state = loadedState()

        controller(state).handleGatewayEvent(BcSessionGatewayEvent.Reconnecting(attempt = 3))

        assertEquals(BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 3), state.current.state)
        assertPrivateStateCleared(state.current)
        assertRoomCleared(state.current)
        assertRecoveryTarget(state)
    }

    @Test
    fun explicitConnectClearsPreviousLoginDataBeforeConnectReturns() = runTest {
        val state = loadedState()
        val connectRelease = CompletableDeferred<Unit>()
        val gateway = CleanupGateway(connectRelease = connectRelease)
        val controller = controller(state, gateway)
        val newServer = BcServerConfig(version = "R130")
        val connectJob = backgroundScope.launch { controller.connect(newServer) }

        try {
            runCurrent()

            assertTrue(gateway.connectEntered.isCompleted)
            assertFalse(connectJob.isCompleted)
            assertEquals(BcSessionState.Connecting(1), state.current.state)
            assertPrivateStateCleared(state.current)
            assertEquals(newServer, state.lastServerConfig)
        } finally {
            connectRelease.complete(Unit)
            runCurrent()
        }

        assertTrue(connectJob.isCompleted)
    }

    @Test
    fun restoreSavedSessionClearsPreviousLoginDataAndWaitsForTheNewLogin() = runTest {
        val state = loadedState()
        val gateway = CleanupGateway()
        val controller = controller(state, gateway)

        try {
            controller.restoreSavedSession(BcServerConfig(version = "R130"), "bob", "new-secret", "New Room")

            assertEquals(BcSessionState.Reconnecting(ReconnectReason.NetworkAvailable, 1), state.current.state)
            assertPrivateStateCleared(state.current)
            assertEquals("New Room", state.lastJoinedRoomName)
            assertNull(state.lastJoinedRoomPassword)
            runCurrent()
            assertTrue(gateway.loginEntered.isCompleted)
            assertEquals("bob" to "new-secret", gateway.loginCredentials)
            assertPrivateStateCleared(state.current)
        } finally {
            controller.cancelReconnect()
        }
    }

    @Test
    fun staleRoomHealthKeepsAccountPrivateDataUntilTheReconnectThreshold() = runTest {
        val state = loadedState().apply {
            updateSession { it.copy(lastVerifiedAtMillis = System.currentTimeMillis() - 10_000L) }
        }
        val before = state.current
        val gateway = CleanupGateway()
        val controller = controller(
            state,
            gateway,
            verifiedFreshMs = 1_000L,
            reconnectAfterStaleMs = Long.MAX_VALUE,
        )

        try {
            controller.start()
            advanceTimeBy(15_000L)
            runCurrent()

            assertEquals(BcConnectionHealth.Stale, state.current.connectionHealth)
            assertEquals(BcSessionState.Ready, state.current.state)
            assertRoomCleared(state.current)
            assertSame(before.account, state.current.account)
            assertSame(before.wardrobeState, state.current.wardrobeState)
            assertSame(before.privateChatState, state.current.privateChatState)
            assertEquals(before.selfRuleLog, state.current.selfRuleLog)
            assertEquals(before.selfRuleLogSessionId, state.current.selfRuleLogSessionId)
            assertEquals(before.serverTimeAnchor, state.current.serverTimeAnchor)
            assertEquals(before.whisperTargetResetRevision, state.current.whisperTargetResetRevision)
            assertRecoveryTarget(state)
            assertEquals(0, gateway.disconnectCalls)
        } finally {
            controller.close()
        }
    }

    private fun TestScope.controller(
        state: BcSessionRuntimeState,
        gateway: CleanupGateway = CleanupGateway(),
        verifiedFreshMs: Long = 60_000L,
        reconnectAfterStaleMs: Long = 75_000L,
    ) = BcSessionConnectionController(
        stateHolder = state,
        gateway = gateway,
        scope = backgroundScope,
        markRoomConversationDisconnected = {},
        handleRecoveredLogin = { _, _, _ -> error("Recovery login must remain pending in cleanup tests.") },
        handleUnrecoverableLoginFailure = { error("Recovery login must remain pending in cleanup tests.") },
        autoJoinLastRoomAfterReconnect = { error("Recovery login must remain pending in cleanup tests.") },
        emitRoomTargetChanged = {},
        verifiedFreshMs = verifiedFreshMs,
        reconnectAfterStaleMs = reconnectAfterStaleMs,
    )

    private fun assertPrivateStateCleared(snapshot: BcSessionSnapshot) {
        assertNull("wardrobeState", snapshot.wardrobeState)
        assertNull("selfRuleLog", snapshot.selfRuleLog)
        assertNull("selfRuleLogSessionId", snapshot.selfRuleLogSessionId)
        assertNull("privateChatState", snapshot.privateChatState)
        assertNull("serverTimeAnchor", snapshot.serverTimeAnchor)
        assertEquals("whisperTargetResetRevision", 0L, snapshot.whisperTargetResetRevision)
        assertNull("gatewayRoomPresenceEpoch", snapshot.gatewayRoomPresenceEpoch)
    }

    private fun assertRoomCleared(snapshot: BcSessionSnapshot) {
        assertNull(snapshot.currentRoom)
        assertTrue(snapshot.roomMembers.isEmpty())
        assertTrue(snapshot.roomCharacters.isEmpty())
        assertNull(snapshot.gatewayRoomPresenceEpoch)
    }

    private fun assertRecoveryTarget(state: BcSessionRuntimeState) {
        assertEquals("Saved Room", state.lastJoinedRoomName)
        assertEquals("room-secret", state.lastJoinedRoomPassword)
    }

    private fun loadedState() = BcSessionRuntimeState().apply {
        lastServerConfig = BcServerConfig(version = "R129")
        rememberLogin("alice", "secret")
        rememberRoomTarget("Saved Room", "room-secret")
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                lastVerifiedAtMillis = System.currentTimeMillis(),
                desiredOnline = true,
                account = account,
                currentRoom = room,
                gatewayRoomPresenceEpoch = 47L,
                roomMembers = listOf(BcMember(account.memberNumber, "Alice", isSelf = true)),
                roomCharacters = listOf(BcCharacterSnapshot("self", account.memberNumber, "Alice", "Alice", true)),
                relationships = BcRelationshipSnapshot().nextLoaded(account.memberNumber, BcSelfRelations()),
                selfRuleLog = log,
                selfRuleLogSessionId = "previous-login",
                privateChatState = privateChat,
                serverTimeAnchor = BcServerTimeAnchor(1_000_000L, 2_000_000L),
                whisperTargetResetRevision = 8L,
                wardrobeState = wardrobe,
            )
        }
    }

    private class CleanupGateway(
        private val disconnectRelease: CompletableDeferred<Unit>? = null,
        private val connectRelease: CompletableDeferred<Unit>? = null,
    ) : SessionConnectionGateway {
        override val events: Flow<BcSessionGatewayEvent> = emptyFlow()
        val disconnectEntered = CompletableDeferred<Unit>()
        val connectEntered = CompletableDeferred<Unit>()
        val loginEntered = CompletableDeferred<Unit>()
        private val loginReply = CompletableDeferred<BcSessionLoginResult>()
        var disconnectCalls = 0
            private set
        var connectCalls = 0
            private set
        var loginCalls = 0
            private set
        var loginCredentials: Pair<String, String>? = null
            private set

        override suspend fun disconnect() {
            disconnectCalls += 1
            disconnectEntered.complete(Unit)
            disconnectRelease?.await()
        }

        override suspend fun connect(config: BcServerConfig) {
            connectCalls += 1
            connectEntered.complete(Unit)
            connectRelease?.await()
        }

        override suspend fun login(accountName: String, password: String): BcSessionLoginResult {
            loginCalls += 1
            loginCredentials = accountName to password
            loginEntered.complete(Unit)
            return loginReply.await()
        }

        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()
        override suspend fun flushAccountUpdates() = Unit
        override fun isProbablyConnected() = true
    }

    private companion object {
        val account = BcAccount(1001, "Alice", "alice")
        val room = BcRoom(BcRoomName("Saved Room"), "", 1, 10, false, null)
        val log = listOf(BcOwnerRuleLogRecord("BlockChange", "OwnerRule", 2_000_000L))
        val privateChat = BcPrivateChatState(
            rawLogJson = """[{"Name":"BlockChange","Group":"OwnerRule","Value":2000000,"Extra":"preserved"}]""",
            ruleLog = log,
            preferences = BcPrivateChatPreferences(chatSettingsJson = """{"FutureSetting":true}"""),
            revision = 8L,
        )
        val wardrobe = BcWardrobeState(
            memberNumber = account.memberNumber,
            selfRuleLogSessionId = "previous-login",
            revision = 12L,
            rawWardrobeJson = """[[{"Group":"Cloth","Name":"Shirt"}]]""",
            rawNamesJson = """["Saved outfit"]""",
            slots = List(BcWardrobeSlotCount) { index ->
                BcWardrobeSlot(
                    index = index,
                    name = if (index == 0) "Saved outfit" else null,
                    status = if (index == 0) BcWardrobeSlotStatus.Saved else BcWardrobeSlotStatus.Empty,
                    appearance = if (index == 0) listOf(BcWardrobeItem("Cloth", "Shirt")) else null,
                )
            },
            wardrobeAvailable = true,
            namesAvailable = true,
            privateInputs = BcWardrobePrivateInputs(
                gameJson = """{"FutureGameData":true}""",
                inventoryJson = "[]",
                gameAvailable = true,
                inventoryAvailable = true,
            ),
        )
    }
}
