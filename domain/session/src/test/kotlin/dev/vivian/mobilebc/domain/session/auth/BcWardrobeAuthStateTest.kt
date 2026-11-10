package dev.vivian.mobilebc.domain.session.auth

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.core.RoomGateway
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRoomsController
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcWardrobeAuthStateTest {
    @Test
    fun successfulLoginPublishesTheWholePrivateWardrobe() = runTest {
        val wardrobe = wardrobe("login", 4L)
        val state = connectedState()

        controller(state, Gateway { _, _ -> success("login", wardrobe) }).login("alice", "secret")

        assertSame(wardrobe, state.current.wardrobeState)
        assertEquals("login", state.current.selfRuleLogSessionId)
        assertTrue(state.current.isVerifiedOnline)
    }

    @Test
    fun sameMemberNewLoginCanReplaceAnOldHighRevisionWithRevisionZero() = runTest {
        val state = loadedState("old-login", 90L)
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val auth = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { auth.login("alice", "new-secret") }
        runCurrent()

        assertFalse(login.isCompleted)
        assertNull(state.current.wardrobeState)
        assertNull(state.current.selfRuleLogSessionId)
        auth.handleGatewayEvent(update(wardrobe("old-login", 99L)))
        val newWardrobe = wardrobe("new-login", 0L)
        reply.complete(success("new-login", newWardrobe))
        runCurrent()

        assertSame(newWardrobe, state.current.wardrobeState)
        assertEquals("new-login", state.current.selfRuleLogSessionId)
        assertEquals("new-secret", state.lastPassword)
    }

    @Test
    fun loginDoesNotTrustAnUnboundOrMalformedWardrobe() = runTest {
        val invalid = listOf(
            "login" to wardrobe("different-login", 0L),
            "login" to wardrobe("login", 0L, memberNumber = 2002),
            "login" to wardrobe("login", -1L),
            " " to wardrobe(" ", 0L),
            null to wardrobe("login", 0L),
        )
        for ((sessionId, wardrobe) in invalid) {
            val state = connectedState()
            controller(state, Gateway { _, _ -> success(sessionId, wardrobe) }).login("alice", "secret")

            assertNull("Unbound wardrobe must stay unavailable: $sessionId", state.current.wardrobeState)
        }
    }

    @Test
    fun authenticatedUpdatesRequireBothEnvelopeAndEmbeddedAccountAndLogin() = runTest {
        val state = loadedState("login", 2L)
        val initial = state.current.wardrobeState
        val auth = controller(state, Gateway { _, _ -> error("No login expected") })
        val invalid = listOf(
            update(wardrobe("old-login", 20L)),
            update(wardrobe("login", 20L, memberNumber = 2002)),
            update(wardrobe("login", 20L), ownerMemberNumber = 2002),
            update(wardrobe("login", 20L), sessionId = "other-login"),
            update(wardrobe(" ", 20L)),
            update(wardrobe("login", -1L)),
            update(wardrobe("login", 20L, memberNumber = 0)),
        )
        for (event in invalid) {
            auth.handleGatewayEvent(event)
            assertSame(initial, state.current.wardrobeState)
        }

        val next = wardrobe("login", 3L)
        auth.handleGatewayEvent(update(next))
        assertSame(next, state.current.wardrobeState)
    }

    @Test
    fun updatesAreStrictlyMonotonicAndDoNotTurnUnknownDataIntoEmptySlots() = runTest {
        val state = loadedState("login", 5L)
        val initial = state.current.wardrobeState
        val auth = controller(state, Gateway { _, _ -> error("No login expected") })

        auth.handleGatewayEvent(update(wardrobe("login", 4L)))
        auth.handleGatewayEvent(update(wardrobe("login", 5L).copy(rawNamesJson = "[\"conflicting\"]")))
        assertSame(initial, state.current.wardrobeState)

        val unavailable = wardrobe("login", 6L).copy(
            rawWardrobeJson = null,
            rawNamesJson = null,
            wardrobeAvailable = false,
            namesAvailable = false,
            slots = emptyList(),
        )
        auth.handleGatewayEvent(update(unavailable))

        assertSame(unavailable, state.current.wardrobeState)
        assertNull(state.current.wardrobeState?.rawWardrobeJson)
        assertFalse(state.current.wardrobeState!!.wardrobeAvailable)
    }

    @Test
    fun earlyHigherRevisionIsRetainedWhenTheLoginReplyFinallyPublishes() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val auth = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { auth.login("alice", "secret") }
        runCurrent()
        val canonical = wardrobe("login", 8L)

        auth.handleGatewayEvent(update(wardrobe("old-login", 100L)))
        auth.handleGatewayEvent(update(wardrobe("login", 100L, memberNumber = 2002)))
        auth.handleGatewayEvent(update(canonical))
        auth.handleGatewayEvent(update(wardrobe("login", 7L)))
        auth.handleGatewayEvent(update(canonical.copy(rawNamesJson = "[\"equal revision conflict\"]")))
        assertNull(state.current.wardrobeState)
        reply.complete(success("login", wardrobe("login", 1L)))
        runCurrent()
        login.join()

        assertSame(canonical, state.current.wardrobeState)
    }

    @Test
    fun malformedEarlyEnvelopeCannotBecomeTheCanonicalLoginWardrobe() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val auth = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { auth.login("alice", "secret") }
        runCurrent()
        val initial = wardrobe("login", 1L)

        auth.handleGatewayEvent(update(wardrobe("other-login", 100L), sessionId = "login"))
        auth.handleGatewayEvent(update(wardrobe("login", 100L, memberNumber = 2002), ownerMemberNumber = 1001))
        auth.handleGatewayEvent(update(wardrobe("login", -1L)))
        reply.complete(success("login", initial))
        runCurrent()
        login.join()

        assertSame(initial, state.current.wardrobeState)
    }

    @Test
    fun newerLoginResultBeatsAnOlderEarlyUpdate() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val auth = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { auth.login("alice", "secret") }
        runCurrent()
        val canonical = wardrobe("login", 9L)

        auth.handleGatewayEvent(update(wardrobe("login", 8L)))
        reply.complete(success("login", canonical))
        runCurrent()
        login.join()

        assertSame(canonical, state.current.wardrobeState)
    }

    @Test
    fun queuedLoginKeepsAHigherRevisionReceivedBeforePublication() = runTest {
        val reply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val auth = controller(state, Gateway { _, _ -> reply.await() })
        val login = launch { auth.login("alice", "secret") }
        runCurrent()
        state.updateSession { it.copy(state = BcSessionState.LoginQueued(3)) }
        val canonical = wardrobe("login", 3L)

        auth.handleGatewayEvent(update(canonical))
        reply.complete(success("login", wardrobe("login", 0L)))
        runCurrent()
        login.join()

        assertSame(canonical, state.current.wardrobeState)
    }

    @Test
    fun recoveredLoginMergesTheCanonicalWardrobeAndKeepsTheRecoveryRoomTarget() = runTest {
        val state = connectedState().apply {
            rememberRoomTarget("Saved Room", "room-secret")
            updateSession { it.copy(state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 2)) }
        }
        val auth = controller(state, Gateway { _, _ -> error("Already received login result") })
        val canonical = wardrobe("recovered-login", 6L)

        auth.handleGatewayEvent(update(wardrobe("old-login", 90L)))
        auth.handleGatewayEvent(update(canonical))
        val account = auth.handleRecoveredLogin(success("recovered-login", wardrobe("recovered-login", 0L)), "alice", "secret")

        assertSame(canonical, state.current.wardrobeState)
        assertSame(account, state.current.account)
        assertEquals("Saved Room", state.lastJoinedRoomName)
        assertEquals("room-secret", state.lastJoinedRoomPassword)
    }

    @Test
    fun lateSameMemberLoginCannotOverwriteANewerLoginUuid() = runTest {
        val oldReply = CompletableDeferred<BcSessionLoginResult>()
        val state = connectedState()
        val canonical = wardrobe("new-login", 0L)
        val auth = controller(state, Gateway { _, password ->
            if (password == "old-secret") withContext(NonCancellable) { oldReply.await() }
            else success("new-login", canonical)
        })
        val oldLogin = launch { auth.login("alice", "old-secret") }
        runCurrent()

        auth.login("alice", "new-secret")
        auth.handleGatewayEvent(update(wardrobe("old-login", 100L)))
        oldReply.complete(success("old-login", wardrobe("old-login", 99L)))
        runCurrent()

        assertTrue(oldLogin.isCancelled)
        assertSame(canonical, state.current.wardrobeState)
        assertEquals("new-login", state.current.selfRuleLogSessionId)
        assertEquals("new-secret", state.lastPassword)
    }

    @Test
    fun logoutClearsWardrobeBeforeAccountUpdatesFinishFlushing() = runTest {
        val state = loadedState("login", 4L)
        val flushed = CompletableDeferred<Unit>()
        val gateway = Gateway { _, _ -> error("No login expected") }.apply { flush = { flushed.await() } }
        val auth = controller(state, gateway)
        val logout = launch { auth.logout() }
        runCurrent()

        assertFalse(logout.isCompleted)
        assertNull(state.current.wardrobeState)
        assertNull(state.current.selfRuleLogSessionId)
        auth.handleGatewayEvent(update(wardrobe("login", 8L)))
        assertNull(state.current.wardrobeState)
        flushed.complete(Unit)
        runCurrent()
        logout.join()

        assertEquals(BcSessionState.Stopped, state.current.state)
        assertNull(state.current.account)
    }

    @Test
    fun disconnectClearsCachedEarlyWardrobeAndRejectsOldUuidAfterRecovery() = runTest {
        val state = connectedState().apply {
            updateSession { it.copy(state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1)) }
        }
        val auth = controller(state, Gateway { _, _ -> error("No login expected") })
        auth.handleGatewayEvent(update(wardrobe("login", 8L)))
        auth.handleGatewayEvent(BcSessionGatewayEvent.Disconnected("lost"))
        val initial = wardrobe("login", 0L)

        auth.handleRecoveredLogin(success("login", initial), "alice", "secret")
        assertSame(initial, state.current.wardrobeState)
        auth.handleGatewayEvent(BcSessionGatewayEvent.ForceDisconnected(BcSessionForceDisconnectReason.DuplicatedLogin))
        assertNull(state.current.wardrobeState)
        assertNull(state.current.selfRuleLogSessionId)
        auth.handleGatewayEvent(update(wardrobe("login", 9L)))
        assertNull(state.current.wardrobeState)
    }

    @Test
    fun loginFailuresCannotKeepAnOldWardrobeOrAnEarlyUpdate() = runTest {
        for (message in listOf("invalid password", "temporarily unavailable")) {
            val reply = CompletableDeferred<BcSessionLoginResult>()
            val state = loadedState("old-login", 7L)
            val auth = controller(state, Gateway { _, _ -> reply.await() })
            val login = launch { auth.login("alice", "secret") }
            runCurrent()
            auth.handleGatewayEvent(update(wardrobe("new-login", 9L)))
            reply.complete(BcSessionLoginResult.Failure(message))
            runCurrent()
            login.join()

            assertNull(state.current.wardrobeState)
            assertNull(state.current.selfRuleLogSessionId)
            auth.handleGatewayEvent(update(wardrobe("new-login", 10L)))
            assertNull(state.current.wardrobeState)
        }
    }

    @Test
    fun leavingTheRoomKeepsAccountWardrobeAndItsLoginBinding() = runTest {
        val state = loadedState("login", 5L).apply {
            rememberRoomTarget("Saved Room")
            updateSession {
                it.copy(currentRoom = BcRoom(BcRoomName("Saved Room"), "", 1, 10, false, null), gatewayRoomPresenceEpoch = 7L)
            }
        }
        val wardrobe = state.current.wardrobeState
        val gateway = Gateway { _, _ -> error("No login expected") }
        val rooms = BcSessionRoomsController(
            stateHolder = state,
            gateway = gateway,
            onOperationFailure = { _, _ -> error("Leaving the room should succeed") },
            emitRoomTargetChanged = {},
            upsertRoomConversation = {},
            upsertRoomState = {},
            upsertSameRoomDirectConversations = {},
        )

        rooms.leaveRoom()
        rooms.handleGatewayEvent(BcSessionGatewayEvent.RoomPresenceReset(1001, "login", 8L))

        assertNull(state.current.currentRoom)
        assertNull(state.lastJoinedRoomName)
        assertSame(wardrobe, state.current.wardrobeState)
        assertEquals("login", state.current.selfRuleLogSessionId)
        assertTrue(state.current.isVerifiedOnline)
    }

    private fun connectedState() = BcSessionRuntimeState().apply {
        updateSession {
            it.copy(state = BcSessionState.Connected, connectionHealth = BcConnectionHealth.ConnectedUnverified, desiredOnline = true)
        }
    }

    private fun loadedState(sessionId: String, revision: Long) = connectedState().apply {
        updateSession {
            it.copy(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                account = BcAccount(1001, "Alice", "alice"),
                selfRuleLogSessionId = sessionId,
                wardrobeState = wardrobe(sessionId, revision),
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

    private fun success(sessionId: String?, wardrobe: BcWardrobeState) = BcSessionLoginResult.Success(
        memberNumber = 1001,
        name = "Alice",
        selfRuleLogSessionId = sessionId,
        wardrobeState = wardrobe,
    )

    private fun update(
        wardrobe: BcWardrobeState,
        ownerMemberNumber: Int = wardrobe.memberNumber,
        sessionId: String = wardrobe.selfRuleLogSessionId,
    ) = BcSessionGatewayEvent.SelfWardrobeStateUpdated(ownerMemberNumber, sessionId, wardrobe)

    private fun wardrobe(sessionId: String, revision: Long, memberNumber: Int = 1001) = BcWardrobeState(
        memberNumber = memberNumber,
        selfRuleLogSessionId = sessionId,
        revision = revision,
        rawWardrobeJson = """[[{"Group":"Cloth","Name":"Shirt","FutureField":42}]]""",
        rawNamesJson = """["Outfit $revision"]""",
        slots = List(BcWardrobeSlotCount) { index ->
            BcWardrobeSlot(
                index = index,
                name = if (index == 0) "Outfit $revision" else null,
                status = if (index == 0) BcWardrobeSlotStatus.Saved else BcWardrobeSlotStatus.Empty,
                appearance = if (index == 0) listOf(BcWardrobeItem("Cloth", "Shirt")) else null,
            )
        },
        wardrobeAvailable = true,
        namesAvailable = true,
        privateInputs = BcWardrobePrivateInputs(
            gameJson = """{"UnknownGameField":true}""",
            inventoryJson = "[]",
            gameAvailable = true,
            inventoryAvailable = true,
        ),
    )

    private class Gateway(
        private val reply: suspend (String, String) -> BcSessionLoginResult,
    ) : SessionConnectionGateway, RoomGateway {
        override val events: Flow<BcSessionGatewayEvent> = emptyFlow()
        var flush: suspend () -> Unit = {}
        override suspend fun connect(config: BcServerConfig) = Unit
        override suspend fun login(accountName: String, password: String) = reply(accountName, password)
        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()
        override suspend fun flushAccountUpdates() = flush()
        override suspend fun disconnect() = Unit
        override fun isProbablyConnected() = true
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
    }
}
