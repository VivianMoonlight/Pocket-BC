package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessage
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.SessionGateway
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBcSessionRuntimeRoomTargetTest {
    @Test
    fun failedRecoveryTargetWriteDoesNotStopRoomUpdatesOrTheirPersistence() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val targets = mutableListOf<Pair<String, String?>>()
        val runtime = runtime(gateway, parent) { account, target ->
            targets += account to target
            if (targets.size == 1) throw IOException("The recovery settings are temporarily unavailable.")
        }
        try {
            runCurrent()
            runtime.access.connector.connect(BcServerConfig(version = "R129"))
            runtime.access.auth.login("alice", "secret")
            gateway.mutableEvents.emit(joined("A", gateway.loginToken))
            runCurrent()

            assertEquals("A", runtime.access.connectionStatus.snapshot.value.currentRoom?.name?.value)
            assertEquals(1, gateway.mutableEvents.subscriptionCount.value)

            gateway.mutableEvents.emit(updated("B", gateway.loginToken))
            runCurrent()

            assertEquals("B", runtime.access.connectionStatus.snapshot.value.currentRoom?.name?.value)
            assertEquals(listOf("alice" to "A", "alice" to "B"), targets)
            assertEquals(1, gateway.mutableEvents.subscriptionCount.value)
        } finally {
            runtime.close()
            parent.cancel()
        }
    }

    @Test
    fun explicitLeavePersistsAfterAnAlreadyRunningRenameWrite() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val renameStarted = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        val targets = mutableListOf<String?>()
        val runtime = runtime(gateway, parent) { _, target ->
            if (target == "B") {
                renameStarted.complete(Unit)
                releaseRename.await()
            }
            targets += target
        }
        try {
            runCurrent()
            runtime.access.connector.connect(BcServerConfig(version = "R129"))
            runtime.access.auth.login("alice", "secret")
            gateway.mutableEvents.emit(joined("A", gateway.loginToken))
            runCurrent()
            gateway.mutableEvents.emit(updated("B", gateway.loginToken))
            runCurrent()
            assertTrue(renameStarted.isCompleted)

            val leave = launch { runtime.access.rooms.leaveRoom() }
            runCurrent()
            assertEquals(1, gateway.leaves)
            assertFalse(leave.isCompleted)
            assertEquals(listOf("A"), targets)

            releaseRename.complete(Unit)
            runCurrent()
            leave.join()

            assertEquals(listOf("A", "B", null), targets)
            assertEquals(1, gateway.mutableEvents.subscriptionCount.value)
        } finally {
            releaseRename.complete(Unit)
            runtime.close()
            parent.cancel()
        }
    }

    @Test
    fun leaveQueuedBehindRenameCannotClearTheSameAccountsNewLoginTarget() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val releaseRename = CompletableDeferred<Unit>()
        val renameStarted = CompletableDeferred<Unit>()
        val targets = mutableListOf<String?>()
        val runtime = runtime(gateway, parent) { _, target ->
            if (target == "B") {
                renameStarted.complete(Unit)
                releaseRename.await()
            }
            targets += target
        }
        try {
            runCurrent()
            runtime.access.connector.connect(BcServerConfig(version = "R129"))
            runtime.access.auth.login("alice", "secret")
            gateway.mutableEvents.emit(joined("A", gateway.loginToken))
            runCurrent()
            gateway.mutableEvents.emit(updated("B", gateway.loginToken))
            runCurrent()
            assertTrue(renameStarted.isCompleted)

            val leave = launch { runtime.access.rooms.leaveRoom() }
            runCurrent()
            assertEquals(1, gateway.leaves)
            assertFalse(leave.isCompleted)

            gateway.loginToken = "new-login"
            runtime.access.auth.login("alice", "secret")
            assertEquals("new-login", runtime.access.connectionStatus.snapshot.value.selfRuleLogSessionId)
            releaseRename.complete(Unit)
            runCurrent()
            leave.join()

            gateway.mutableEvents.emit(joined("C", gateway.loginToken))
            runCurrent()

            assertEquals(listOf("A", "B", "C"), targets)
            assertEquals("C", runtime.access.connectionStatus.snapshot.value.currentRoom?.name?.value)
            assertEquals(1, gateway.mutableEvents.subscriptionCount.value)
        } finally {
            releaseRename.complete(Unit)
            runtime.close()
            parent.cancel()
        }
    }

    private fun joined(name: String, token: String) = BcSessionGatewayEvent.RoomJoined(
        room = room(name),
        members = emptyList(),
        accountMemberNumber = 1001,
        selfRuleLogSessionId = token,
        roomPresenceEpoch = 1L,
    )

    private fun updated(name: String, token: String) = BcSessionGatewayEvent.RoomUpdated(
        room = room(name),
        members = emptyList(),
        accountMemberNumber = 1001,
        selfRuleLogSessionId = token,
        roomPresenceEpoch = 1L,
    )

    private fun room(name: String) = BcRoom(
        name = BcRoomName(name),
        description = "",
        memberCount = 1,
        memberLimit = 10,
        isLocked = false,
        language = "EN",
    )

    private fun runtime(
        gateway: Gateway,
        scope: CoroutineScope,
        onRoomTargetChanged: suspend (String, String?) -> Unit,
    ) = createDefaultBcSessionRuntime(
        gateway = gateway,
        scope = scope,
        outboundStore = object : BcOutboundMessageStore {
            override suspend fun enqueue(message: BcOutboundMessage, localMessage: BcMessage) = Unit
        },
        dispatchQueuedOutbound = { QueuedOutboundDispatchResult.NotDispatched },
        timelineStore = object : BcMessageTimelineStore {
            override suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) = Unit
            override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> = flowOf(emptyList())
            override suspend fun advanceReadCursor(ownerMemberNumber: Int, conversationId: BcConversationId, throughMessage: BcMessage) = Unit
            override fun observeMessages(ownerMemberNumber: Int, conversationId: BcConversationId, limit: Int): Flow<List<BcMessage>> = flowOf(emptyList())
        },
        onRoomTargetChanged = onRoomTargetChanged,
    )

    private class Gateway : SessionGateway {
        val mutableEvents = MutableSharedFlow<BcSessionGatewayEvent>()
        override val events: Flow<BcSessionGatewayEvent> = mutableEvents
        var loginToken = "current-login"
        var leaves = 0
        override suspend fun connect(config: BcServerConfig) = Unit
        override suspend fun login(accountName: String, password: String): BcSessionLoginResult =
            BcSessionLoginResult.Success(1001, "Alice", selfRuleLogSessionId = loginToken)
        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = emptyList()
        override suspend fun flushAccountUpdates() = Unit
        override suspend fun disconnect() = Unit
        override fun isProbablyConnected(): Boolean = true
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() { leaves += 1 }
        override suspend fun queryAllowItem(memberNumber: Int): Boolean = true
        override suspend fun sendActivity(command: BcActivityCommand) = Unit
        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) = Unit
        override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
    }
}
