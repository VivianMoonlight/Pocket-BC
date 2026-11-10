package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessage
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.SessionGateway
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBcSessionRuntimeLifecycleTest {
    @Test fun sessionExpiresTheWearersTimerWithoutAnyItemPanelOrUserAction() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val runtime = runtime(gateway, parent)
        try {
            runtime.access.connector.connect(BcServerConfig(version = "R133"))
            val login = launch { runtime.access.auth.login("alice", "secret") }
            runCurrent(); login.join()
            gateway.mutableEvents.emit(BcSessionGatewayEvent.ServerInfo(1, 2000, System.nanoTime()))
            val self = dev.vivian.mobilebc.core.model.BcCharacterSnapshot("1001", 1001, "Alice", "Alice", true,
                appearance = listOf(dev.vivian.mobilebc.core.model.BcCharacterItem("ItemArms", "LeatherCuffs", propertyJson = """{"LockedBy":"TimerPadlock","RemoveTimer":1000,"Effect":["Lock","Block"]}""")))
            gateway.mutableEvents.emit(BcSessionGatewayEvent.RoomJoined(
                dev.vivian.mobilebc.core.model.BcRoom(dev.vivian.mobilebc.core.model.BcRoomName("Room"), "", 1, 10, false, null), emptyList(), listOf(self)))
            runCurrent()
            assertEquals(1, gateway.itemCommands.size)
            assertEquals("TimerRelease", gateway.itemCommands.single().publishAction?.content)
            assertTrue(!gateway.itemCommands.single().propertyJson!!.contains("LockedBy"))
        } finally { runtime.close(); parent.cancel(); runCurrent() }
    }

    @Test
    fun cancelledFriendQueryCannotPublishOrReconnectTheSameLogin() = runTest {
        listOf(false, true).forEach { fails ->
            val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val gateway = Gateway()
            var reply: Continuation<List<BcConversation>>? = null
            gateway.onlineFriendQuery = { suspendCoroutine { reply = it } }
            val runtime = runtime(gateway, parent)
            try {
                runtime.access.connector.connect(BcServerConfig(version = "R130"))
                val login = launch { runtime.access.auth.login("alice", "secret") }
                runCurrent()
                val before = runtime.access.connectionStatus.snapshot.value
                assertTrue(before.isVerifiedOnline)
                login.cancel()
                val result = if (fails) Result.failure(IllegalStateException("late query error")) else Result.success(
                    listOf(BcConversation(BcConversationId("message:2002"), ConversationType.Direct, "Cancelled presence", "")),
                )
                requireNotNull(reply).resumeWith(result)
                runCurrent()
                login.join()

                assertEquals(before, runtime.access.connectionStatus.snapshot.value)
                assertTrue(runtime.access.timeline.conversations.value.rows.isEmpty())
                assertEquals(1, gateway.connects)
            } finally {
                runtime.close()
                parent.cancel()
                runCurrent()
            }
        }
    }

    @Test
    fun closeCancelsSubscriptionsAndDisconnectsWithoutCancellingTheParentOrClearingRecoveryTarget() = runTest {
        val parentJob = Job()
        val parent = CoroutineScope(parentJob + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        var targetClears = 0
        val runtime = runtime(gateway, parent) { _, _ -> targetClears += 1 }
        runtime.hostLifecycle.restoreSavedSession(BcServerConfig(version = "R130"), "alice", "secret", "Saved Room")
        runCurrent()
        assertEquals(1, gateway.mutableEvents.subscriptionCount.value)
        val disconnectsBeforeClose = gateway.disconnects

        runtime.close()
        runtime.close()
        runCurrent()

        assertEquals(0, gateway.mutableEvents.subscriptionCount.value)
        assertEquals(disconnectsBeforeClose + 1, gateway.disconnects)
        assertEquals(0, targetClears)
        assertEquals(BcSessionState.Stopped, runtime.access.connectionStatus.snapshot.value.state)
        assertTrue(parentJob.isActive)
        parent.cancel()
    }

    @Test
    fun parentCancellationAlsoClosesTransportAndSubscriptions() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        runtime(gateway, parent)
        runCurrent()
        assertEquals(1, gateway.mutableEvents.subscriptionCount.value)

        parent.cancel()
        runCurrent()

        assertEquals(0, gateway.mutableEvents.subscriptionCount.value)
        assertEquals(1, gateway.disconnects)
    }

    @Test
    fun lateFriendQueryCannotVerifyOrReplacePresenceWhileReconnecting() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val reply = CompletableDeferred<List<BcConversation>>()
        gateway.onlineFriendQuery = { withContext(NonCancellable) { reply.await() } }
        val runtime = runtime(gateway, parent)
        try {
            runtime.access.connector.connect(BcServerConfig(version = "R130"))
            val login = launch { runtime.access.auth.login("alice", "secret") }
            runCurrent()
            gateway.loginResult = { awaitCancellation() }

            runtime.hostLifecycle.onNetworkLost()
            reply.complete(listOf(BcConversation(BcConversationId("message:2002"), ConversationType.Direct, "Old presence", "")))
            runCurrent()
            login.join()

            assertTrue(runtime.access.connectionStatus.snapshot.value.state is BcSessionState.Reconnecting)
            assertEquals(false, runtime.access.connectionStatus.snapshot.value.isVerifiedOnline)
            assertTrue(runtime.access.timeline.conversations.value.rows.none { it.id == BcConversationId("message:2002") })
        } finally {
            runtime.close()
            parent.cancel()
            reply.complete(emptyList())
        }
    }

    @Test
    fun lateFriendQueryFailureDoesNotReconnectANewerAuthenticatedLogin() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val gateway = Gateway()
        val reply = CompletableDeferred<List<BcConversation>>()
        var queries = 0
        gateway.onlineFriendQuery = {
            queries += 1
            if (queries == 1) withContext(NonCancellable) { reply.await() } else emptyList()
        }
        val runtime = runtime(gateway, parent)
        try {
            runtime.access.connector.connect(BcServerConfig(version = "R130"))
            val previousLogin = launch { runtime.access.auth.login("alice", "secret") }
            runCurrent()
            runtime.access.auth.login("alice", "secret")

            reply.completeExceptionally(IllegalStateException("late failure from previous query"))
            runCurrent()
            previousLogin.join()

            assertEquals(BcSessionState.Ready, runtime.access.connectionStatus.snapshot.value.state)
            assertTrue(runtime.access.connectionStatus.snapshot.value.isVerifiedOnline)
            assertEquals(1, gateway.connects)
        } finally {
            runtime.close()
            parent.cancel()
            reply.complete(emptyList())
        }
    }

    private fun runtime(
        gateway: Gateway,
        scope: CoroutineScope,
        onRoomTargetChanged: suspend (String, String?) -> Unit = { _, _ -> },
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
        val itemCommands = mutableListOf<BcCharacterItemUpdateCommand>()
        val mutableEvents = MutableSharedFlow<BcSessionGatewayEvent>()
        override val events: Flow<BcSessionGatewayEvent> = mutableEvents
        var disconnects = 0
        var connects = 0
        var onlineFriendQuery: suspend () -> List<BcConversation> = { emptyList() }
        var loginResult: suspend () -> BcSessionLoginResult = { BcSessionLoginResult.Success(1001, "Alice") }
        override suspend fun connect(config: BcServerConfig) { connects += 1 }
        override suspend fun login(accountName: String, password: String): BcSessionLoginResult = loginResult()
        override suspend fun probeAuthenticatedSession() = Unit
        override suspend fun queryOnlineFriendConversations(): List<BcConversation> = onlineFriendQuery()
        override suspend fun flushAccountUpdates() = Unit
        override suspend fun disconnect() { disconnects += 1 }
        override fun isProbablyConnected(): Boolean = true
        override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> = emptyList()
        override suspend fun joinRoom(roomName: String, password: String?) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun queryAllowItem(memberNumber: Int): Boolean = true
        override suspend fun sendActivity(command: BcActivityCommand) = Unit
        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) { itemCommands += command }
        override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
    }
}
