package dev.vivian.mobilebc.domain.session.connection

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BlockedReason
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionConnectionControllerTest {
    @Test
    fun forceDisconnectBlocksReconnectAndClearsOnlineState() = runTest {
        val state = BcSessionRuntimeState().apply {
            rememberLogin(testAccount.accountName, "secret")
            updateSession {
                it.copy(
                    desiredOnline = true,
                    account = testAccount,
                    state = BcSessionState.Ready,
                    currentRoom = room,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = 100L,
                    relationships = BcRelationshipSnapshot().nextLoaded(
                        memberNumber = testAccount.memberNumber,
                        relations = BcSelfRelations(friends = setOf(1, 2)),
                    ),
                )
            }
        }
        var markDisconnectedCalls = 0
        val emittedSnapshots = mutableListOf<dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot>()
        val snapshotJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.snapshot.drop(1).toList(emittedSnapshots)
        }
        val controller = controller(
            state = state,
            markRoomConversationDisconnected = { markDisconnectedCalls += 1 },
        )

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.ForceDisconnected(BcSessionForceDisconnectReason.DuplicatedLogin)
        )
        runCurrent()
        snapshotJob.cancel()

        assertEquals(false, state.current.desiredOnline)
        assertNull(state.lastPassword)
        assertNull(state.current.lastVerifiedAtMillis)
        assertEquals(emptySet<Int>(), state.current.friendMemberNumbers)
        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertEquals(BcSessionState.Blocked(BlockedReason.DuplicatedLogin), state.current.state)
        assertNull(state.current.currentRoom)
        assertEquals(1, markDisconnectedCalls)
        assertEquals(1, emittedSnapshots.size)
        assertEquals(state.current, emittedSnapshots.single())
    }

    @Test
    fun serverInfoVerifiesAuthenticatedSession() = runTest {
        val state = BcSessionRuntimeState().apply {
            updateSession {
                it.copy(
                    desiredOnline = true,
                    account = testAccount,
                    state = BcSessionState.Ready,
                    connectionHealth = BcConnectionHealth.Offline,
                )
            }
        }
        val controller = controller(state)

        controller.handleGatewayEvent(BcSessionGatewayEvent.ServerInfo(onlinePlayers = 10, serverTime = 1234L))

        assertEquals(BcConnectionHealth.VerifiedOnline, state.current.connectionHealth)
        assertTrue((state.current.lastVerifiedAtMillis ?: 0L) > 0L)
    }

    @Test
    fun repeatedInboundMessagesDoNotRepublishFreshSessionSnapshots() = runTest {
        val verifiedAt = System.currentTimeMillis()
        val state = BcSessionRuntimeState().apply {
            updateSession {
                it.copy(
                    desiredOnline = true,
                    account = testAccount,
                    state = BcSessionState.Ready,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = verifiedAt,
                )
            }
        }
        val emittedSnapshots = mutableListOf<dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot>()
        val snapshotJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.snapshot.drop(1).toList(emittedSnapshots)
        }
        val controller = controller(state)

        repeat(100) {
            controller.handleGatewayEvent(BcSessionGatewayEvent.InboundMessage)
        }
        runCurrent()
        snapshotJob.cancel()

        assertTrue(emittedSnapshots.isEmpty())
        assertEquals(verifiedAt, state.current.lastVerifiedAtMillis)
    }

    @Test
    fun staleVerifiedSessionRequestsSilentReconnect() = runTest {
        val state = BcSessionRuntimeState().apply {
            lastServerConfig = BcServerConfig(version = "R130")
            rememberLogin("account", "secret")
            updateSession {
                it.copy(
                    desiredOnline = true,
                    account = testAccount,
                    state = BcSessionState.Ready,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = 1L,
                )
            }
        }
        val gateway = FakeSessionGateway(probablyConnected = false)
        val controller = controller(
            state = state,
            gateway = gateway,
            verifiedFreshMs = 10L,
            reconnectAfterStaleMs = 20L,
        )

        controller.onNetworkAvailable()

        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertEquals(BcSessionState.Reconnecting(ReconnectReason.SilentDisconnect, attempt = 1), state.current.state)
        controller.cancelReconnect()
    }

    @Test
    fun cancelledScopeCannotPublishAReconnectThatWillNeverRun() = runTest {
        val state = BcSessionRuntimeState().apply {
            lastServerConfig = BcServerConfig(version = "R130")
            rememberLogin("account", "secret")
            updateSession { it.copy(desiredOnline = true, state = BcSessionState.Ready) }
        }
        val cancelledJob = kotlinx.coroutines.Job().apply { cancel() }
        val gateway = FakeSessionGateway()
        val controller = controller(state, gateway,
            controllerScope = kotlinx.coroutines.CoroutineScope(coroutineContext + cancelledJob))

        controller.requestReconnect(ReconnectReason.NetworkLost)
        controller.requestReconnect(ReconnectReason.NetworkAvailable)
        advanceUntilIdle()

        assertEquals(BcSessionState.Ready, state.current.state)
        assertEquals(0, gateway.connectCalls)
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun duplicateReconnectTriggersShareSingleReconnectJob() = runTest {
        val state = BcSessionRuntimeState().apply {
            lastServerConfig = BcServerConfig(version = "R130")
            rememberLogin("account", "secret")
            updateSession {
                it.copy(
                    desiredOnline = true,
                    account = testAccount,
                    state = BcSessionState.Ready,
                    currentRoom = room,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                )
            }
        }
        val gateway = FakeSessionGateway(
            loginResult = BcSessionLoginResult.Success(
                memberNumber = testAccount.memberNumber,
                name = testAccount.displayName,
            )
        )
        var autoJoinCalls = 0
        val controller = controller(
            state = state,
            gateway = gateway,
            autoJoinLastRoomAfterReconnect = { autoJoinCalls += 1 },
        )

        controller.onTransportSuspect(ReconnectReason.NetworkLost)
        controller.onOperationFailure(ReconnectReason.OperationFailed, IllegalStateException("late failure"))
        controller.onNetworkAvailable()
        advanceUntilIdle()

        assertEquals(1, gateway.disconnectCalls)
        assertEquals(1, gateway.connectCalls)
        assertEquals(1, gateway.loginCalls)
        assertEquals(1, autoJoinCalls)
    }

    @Test
    fun restoreSavedSessionReconnectsAndAutoJoinsLastRoom() = runTest {
        val server = BcServerConfig(version = "R130")
        val state = BcSessionRuntimeState()
        val gateway = FakeSessionGateway(
            loginResult = BcSessionLoginResult.Success(
                memberNumber = testAccount.memberNumber,
                name = testAccount.displayName,
            ),
        )
        var autoJoinCalls = 0
        val controller = controller(
            state = state,
            gateway = gateway,
            autoJoinLastRoomAfterReconnect = { autoJoinCalls += 1 },
        )

        controller.restoreSavedSession(server, "alice", "secret", "Saved Room")

        assertEquals(true, state.current.desiredOnline)
        assertEquals(BcSessionState.Reconnecting(ReconnectReason.NetworkAvailable, 1), state.current.state)
        assertEquals(server, state.lastServerConfig)
        assertEquals("alice", state.lastAccountName)
        assertEquals("Saved Room", state.lastJoinedRoomName)

        advanceUntilIdle()

        assertEquals(1, gateway.disconnectCalls)
        assertEquals(1, gateway.connectCalls)
        assertEquals(1, gateway.loginCalls)
        assertEquals(1, autoJoinCalls)
    }

    @Test
    fun connectPublishesTheNormalizedUserSelectedVersion() = runTest {
        val state = BcSessionRuntimeState()
        val gateway = FakeSessionGateway()
        val controller = controller(state = state, gateway = gateway)

        controller.connect(BcServerConfig(version = "r131beta2"))

        assertEquals("R131Beta2", state.current.bcVersion)
        assertEquals("R131Beta2", state.lastServerConfig?.version)
        assertEquals("R131Beta2", gateway.lastConnectedConfig?.version)
    }

    @Test(expected = IllegalArgumentException::class)
    fun connectRejectsBlankVersionInsteadOfUsingTheDefault() = runTest {
        controller(
            state = BcSessionRuntimeState(),
            gateway = FakeSessionGateway(),
        ).connect(BcServerConfig(version = "   "))
    }

    @Test
    fun closeDisconnectsSynchronouslyWithoutClearingTheSavedRoomTarget() = runTest {
        val state = BcSessionRuntimeState().apply { rememberRoomTarget("Saved Room") }
        val gateway = FakeSessionGateway()
        var roomTargetChanges = 0
        val controller = controller(
            state = state,
            gateway = gateway,
            emitRoomTargetChanged = { roomTargetChanges += 1 },
        )

        controller.close()
        controller.close()

        assertEquals(BcSessionState.Stopped, state.current.state)
        assertEquals(false, state.current.desiredOnline)
        assertEquals(1, gateway.disconnectCalls)
        assertEquals(0, roomTargetChanges)
        assertEquals("Saved Room", state.lastJoinedRoomName)
    }

    @Test
    fun stoppedReconnectDoesNotApplyANonCancellableLateLoginResult() = runTest {
        val state = BcSessionRuntimeState()
        val loginResult = CompletableDeferred<BcSessionLoginResult>()
        val gateway = object : ConnectionTestGateway by FakeSessionGateway() {
            override suspend fun login(accountName: String, password: String): BcSessionLoginResult =
                withContext(NonCancellable) { loginResult.await() }
        }
        var recoveredLogins = 0
        var autoJoinCalls = 0
        val controller = controller(
            state = state,
            gateway = gateway,
            onRecoveredLogin = { recoveredLogins += 1 },
            autoJoinLastRoomAfterReconnect = { autoJoinCalls += 1 },
        )
        controller.restoreSavedSession(BcServerConfig(version = "R130"), "alice", "secret", "Saved Room")
        runCurrent()

        controller.close()
        loginResult.complete(BcSessionLoginResult.Success(testAccount.memberNumber, testAccount.displayName))
        runCurrent()

        assertEquals(0, recoveredLogins)
        assertEquals(0, autoJoinCalls)
        assertEquals(BcSessionState.Stopped, state.current.state)
    }

    @Test
    fun lateSuccessfulProbeCannotVerifyAReconnectingSession() = runTest {
        val state = BcSessionRuntimeState().apply {
            lastServerConfig = BcServerConfig(version = "R130")
            rememberLogin("alice", "secret")
            updateSession {
                it.copy(
                    state = BcSessionState.Ready,
                    desiredOnline = true,
                    account = testAccount,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = System.currentTimeMillis(),
                )
            }
        }
        val reply = CompletableDeferred<Unit>()
        var probeCalls = 0
        val gateway = object : ConnectionTestGateway by FakeSessionGateway() {
            override suspend fun probeAuthenticatedSession() {
                probeCalls += 1
                withContext(NonCancellable) { reply.await() }
            }
        }
        val controller = controller(state, gateway)
        try {
            controller.start()
            advanceTimeBy(45_000L)
            runCurrent()
            assertEquals(1, probeCalls)

            controller.onNetworkLost()
            reply.complete(Unit)
            runCurrent()

            assertTrue(state.current.state is BcSessionState.Reconnecting)
            assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
            assertEquals(false, state.current.isVerifiedOnline)
        } finally {
            controller.close()
            reply.complete(Unit)
        }
    }

    @Test
    fun authenticatedEvidenceFromAnOldAccountGenerationCannotVerifyTheReplacementLogin() = runTest {
        val state = BcSessionRuntimeState().apply {
            updateSession {
                it.copy(
                    state = BcSessionState.Ready,
                    desiredOnline = true,
                    account = testAccount,
                    relationships = it.relationships.nextLoaded(testAccount.memberNumber, BcSelfRelations()),
                )
            }
        }
        val oldGeneration = state.current.relationships.accountGeneration
        state.updateSession {
            it.copy(relationships = it.relationships.nextLoaded(testAccount.memberNumber, BcSelfRelations()))
        }

        controller(state).touchVerified(testAccount, oldGeneration)

        assertEquals(BcConnectionHealth.Offline, state.current.connectionHealth)
        assertNull(state.current.lastVerifiedAtMillis)
    }

    private fun TestScope.controller(
        state: BcSessionRuntimeState,
        gateway: ConnectionTestGateway = FakeSessionGateway(),
        controllerScope: kotlinx.coroutines.CoroutineScope = this,
        verifiedFreshMs: Long = 60_000L,
        reconnectAfterStaleMs: Long = 75_000L,
        markRoomConversationDisconnected: () -> Unit = {},
        autoJoinLastRoomAfterReconnect: suspend (BcAccount) -> Unit = {},
        emitRoomTargetChanged: suspend (String?) -> Unit = {},
        onRecoveredLogin: () -> Unit = {},
    ): BcSessionConnectionController =
        BcSessionConnectionController(
            stateHolder = state,
            gateway = gateway,
            scope = controllerScope,
            markRoomConversationDisconnected = markRoomConversationDisconnected,
            handleRecoveredLogin = { result, accountName, _ ->
                onRecoveredLogin()
                BcAccount(result.memberNumber, result.name, accountName)
            },
            handleUnrecoverableLoginFailure = {},
            autoJoinLastRoomAfterReconnect = autoJoinLastRoomAfterReconnect,
            emitRoomTargetChanged = emitRoomTargetChanged,
            verifiedFreshMs = verifiedFreshMs,
            reconnectAfterStaleMs = reconnectAfterStaleMs,
        )

    private interface ConnectionTestGateway : SessionConnectionGateway

    private class FakeSessionGateway(
        private val probablyConnected: Boolean = true,
        private val loginResult: BcSessionLoginResult = BcSessionLoginResult.Failure("not implemented"),
    ) : ConnectionTestGateway {
        override val events: Flow<BcSessionGatewayEvent> = emptyFlow()

        var connectCalls = 0
            private set
        var disconnectCalls = 0
            private set
        var loginCalls = 0
            private set
        var lastConnectedConfig: BcServerConfig? = null
            private set

        override suspend fun connect(config: BcServerConfig) {
            connectCalls += 1
            lastConnectedConfig = config
        }

        override suspend fun login(accountName: String, password: String): BcSessionLoginResult {
            loginCalls += 1
            return loginResult
        }

        override suspend fun probeAuthenticatedSession() = Unit

        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()

        override suspend fun flushAccountUpdates() = Unit

        override suspend fun disconnect() {
            disconnectCalls += 1
        }

        override fun isProbablyConnected(): Boolean = probablyConnected
    }

    private companion object {
        val testAccount = BcAccount(memberNumber = 1001, displayName = "Alice", accountName = "alice")
        val room = dev.vivian.mobilebc.core.model.BcRoom(
            name = dev.vivian.mobilebc.core.model.BcRoomName("Test Room"),
            description = "",
            memberCount = 1,
            memberLimit = 10,
            isLocked = false,
            language = null,
        )
    }
}
