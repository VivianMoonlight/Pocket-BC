package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.domain.bcrelationship.BcRelationshipItemReconciler
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemTimerReconciler
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.character.BcCharacterInteractionsController
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuthController
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.domain.session.chat.BcSessionChatController
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionConnector
import dev.vivian.mobilebc.domain.session.connection.SessionRecoverySettings
import dev.vivian.mobilebc.domain.session.connection.BcSessionConnectionController
import dev.vivian.mobilebc.domain.session.core.SessionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRoomsController
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationshipController
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimelineController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger


fun createDefaultBcSessionRuntime(
    gateway: SessionGateway,
    outboundStore: BcOutboundMessageStore,
    dispatchQueuedOutbound: suspend (clientMsgId: String) -> QueuedOutboundDispatchResult,
    timelineStore: BcMessageTimelineStore,
    onRoomTargetChanged: suspend (accountName: String, roomName: String?) -> Unit = { _, _ -> },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    messageCoordinator: BcSessionMessageCoordinator = BcSessionMessageCoordinator(),
    relationshipItemCatalog: () -> BcRuleCatalog? = { null },
): ManagedSession =
    DefaultBcSessionRuntime(
        gateway = gateway,
        parentScope = scope,
        outboundStore = outboundStore,
        dispatchQueuedOutbound = dispatchQueuedOutbound,
        timelineStore = timelineStore,
        onRoomTargetChanged = onRoomTargetChanged,
        messageCoordinator = messageCoordinator,
        relationshipItemCatalog = relationshipItemCatalog,
    )

internal class DefaultBcSessionRuntime(
    private val gateway: SessionGateway,
    outboundStore: BcOutboundMessageStore,
    dispatchQueuedOutbound: suspend (clientMsgId: String) -> QueuedOutboundDispatchResult,
    timelineStore: BcMessageTimelineStore,
    private val onRoomTargetChanged: suspend (accountName: String, roomName: String?) -> Unit,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val messageCoordinator: BcSessionMessageCoordinator = BcSessionMessageCoordinator(),
    relationshipItemCatalog: () -> BcRuleCatalog? = { null },
) : ManagedSession {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val closed = AtomicBoolean()
    private val stateHolder = BcSessionRuntimeState()
    private val roomTargetPersistenceMutex = Mutex()
    private val relationshipController = BcSessionRelationshipController(
        stateHolder = stateHolder,
        gateway = gateway,
        contractPendingScope = scope,
    )
    private val relationshipItemReconciler = BcRelationshipItemReconciler(
        gateway = gateway,
        sessionSnapshot = stateHolder.snapshot,
        catalogProvider = relationshipItemCatalog,
        retryScope = scope,
    )
    private val itemTimerReconciler = BcItemTimerReconciler(gateway, stateHolder.snapshot,
        diagnostic = { Logger.getLogger(DefaultBcSessionRuntime::class.java.name).warning(it) })

    private val timelineController: BcSessionTimelineController = BcSessionTimelineController(
        stateHolder = stateHolder,
        scope = scope,
        timelineStore = timelineStore,
        messageCoordinator = messageCoordinator,
    )

    private val roomsController: BcSessionRoomsController = BcSessionRoomsController(
        stateHolder = stateHolder,
        gateway = gateway,
        onOperationFailure = ::handleConnectionOperationFailure,
        emitRoomTargetChanged = ::persistRoomTarget,
        upsertRoomConversation = timelineController::upsertRoomConversation,
        upsertRoomState = timelineController::upsertRoomState,
        upsertSameRoomDirectConversations = timelineController::upsertSameRoomDirectConversations,
        onRoomRenamed = timelineController::renameRoomConversation,
        onRoomRemoved = timelineController::markRoomConversationDisconnected,
    )

    private val characterInteractionsController: BcCharacterInteractionsController = BcCharacterInteractionsController(
        gateway = gateway,
        sessionSnapshot = stateHolder.snapshot,
    )

    private val connectionController: BcSessionConnectionController = BcSessionConnectionController(
        stateHolder = stateHolder,
        gateway = gateway,
        scope = scope,
        markRoomConversationDisconnected = timelineController::markRoomConversationDisconnected,
        handleRecoveredLogin = ::handleRecoveredLogin,
        handleUnrecoverableLoginFailure = ::handleUnrecoverableLoginFailure,
        autoJoinLastRoomAfterReconnect = roomsController::autoJoinLastRoomAfterReconnect,
        emitRoomTargetChanged = ::persistRoomTarget,
    )

    private val authController: BcSessionAuthController = BcSessionAuthController(
        stateHolder = stateHolder,
        gateway = gateway,
        cancelReconnectJob = connectionController::cancelReconnect,
        upsertFriendConversations = timelineController::upsertFriendConversations,
        upsertLoginConversation = ::upsertLoginConversation,
        refreshOnlineFriends = ::refreshOnlineFriends,
        emitRoomTargetChanged = ::persistRoomTarget,
        onRelationshipsLoaded = { relationships ->
            scope.launch {
                relationshipItemReconciler.reconcile(
                    expectedAccountGeneration = relationships.accountGeneration,
                    expectedLoginSessionId = relationships.loginSessionId,
                )
            }
        },
    )

    private val onlineFriendRoomPoller = OnlineFriendRoomPoller(
        scope = scope,
        sessionSnapshot = stateHolder.snapshot,
        refresh = ::refreshOnlineFriends,
    )

    private val chatController: BcSessionChatController = BcSessionChatController(
        stateHolder = stateHolder,
        outboundStore = outboundStore,
        dispatchQueuedOutbound = dispatchQueuedOutbound,
        messageCoordinator = messageCoordinator,
        onOperationFailure = ::handleConnectionOperationFailure,
        isVerifiedFresh = connectionController::isVerifiedFresh,
        validateRoomChat = gateway::validateRoomChat,
    )

    override val access: SessionAccess = object : SessionAccess {
        override val auth: BcSessionAuth = authController
        override val connectionStatus: ConnectionStatusReader = connectionController
        override val connector: SessionConnector = connectionController
        override val recoverySettings: SessionRecoverySettings = connectionController
        override val rooms: BcSessionRooms = roomsController
        override val relationships: BcSessionRelationships = relationshipController
        override val characterInteractions: BcCharacterInteractions = characterInteractionsController
        override val chat: BcSessionChat = chatController
        override val timeline: BcSessionTimeline = timelineController
    }

    override val hostLifecycle: SessionHostLifecycle = connectionController

    init {
        sessionJob.invokeOnCompletion { connectionController.close() }
        timelineController.start()
        connectionController.start()
        onlineFriendRoomPoller.start()
        itemTimerReconciler.start(scope)
        scope.launch {
            gateway.events.collect(::handleGatewayEvent)
        }
        scope.launch {
            stateHolder.snapshot
                .map { snapshot ->
                    Triple(
                        snapshot.account?.memberNumber,
                        snapshot.relationships.accountGeneration,
                        snapshot.bcVersion to snapshot.roomCharacters.takeIf { snapshot.isVerifiedOnline }.orEmpty(),
                    )
                }
                .distinctUntilChanged()
                .collect { (owner, generation, observed) ->
                    if (owner != null) observed.second.forEach { character ->
                        timelineController.persistCharacterProfile(owner, generation, character, observed.first)
                    }
                }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onlineFriendRoomPoller.stop()
        connectionController.close()
        sessionJob.cancel()
    }

    private suspend fun handleGatewayEvent(event: BcSessionGatewayEvent) {
        if (closed.get()) return
        authController.handleGatewayEvent(event)
        connectionController.handleGatewayEvent(event)
        val relationshipEventAccepted = relationshipController.handleGatewayEvent(event)
        roomsController.handleGatewayEvent(event)
        when (event) {
            is BcSessionGatewayEvent.RelationshipUpdated -> {
                if (!relationshipEventAccepted) return
                val acceptedRelationships = stateHolder.current.relationships
                scope.launch {
                    relationshipItemReconciler.reconcile(
                        ownershipCleared = event.update.let {
                            it is BcRelationshipGatewayUpdate.Ownership && it.clearOwnership
                        },
                        expectedAccountGeneration = acceptedRelationships.accountGeneration,
                        expectedLoginSessionId = acceptedRelationships.loginSessionId,
                    )
                }
            }
            is BcSessionGatewayEvent.RoomJoined,
            is BcSessionGatewayEvent.RoomUpdated,
            -> scope.launch {
                relationshipItemReconciler.reconcile()
            }
            else -> Unit
        }
    }

    private fun handleConnectionOperationFailure(
        reason: ReconnectReason,
        throwable: Throwable?,
    ) {
        connectionController.onOperationFailure(reason, throwable)
    }

    private suspend fun handleRecoveredLogin(
        result: BcSessionLoginResult.Success,
        accountName: String,
        password: String,
    ): BcAccount = authController.handleRecoveredLogin(
        result = result,
        accountName = accountName,
        password = password,
    )

    private suspend fun handleUnrecoverableLoginFailure(message: String) =
        authController.handleRecoveredLoginFailure()

    private fun upsertLoginConversation(displayName: String) {
        timelineController.upsertConversation(
            messageCoordinator.systemLoginConversation(displayName)
        )
    }

    private suspend fun refreshOnlineFriends() {
        val started = stateHolder.current
        val account = started.account ?: return
        val accountGeneration = started.relationships.accountGeneration
        if (!connectionController.isCurrentAuthenticatedSession(account, accountGeneration)) return
        try {
            val friendConversations = gateway.queryOnlineFriendConversations()
            currentCoroutineContext().ensureActive()
            if (!connectionController.isCurrentAuthenticatedSession(account, accountGeneration)) return
            connectionController.touchVerified(account, accountGeneration)
            if (!connectionController.isCurrentAuthenticatedSession(account, accountGeneration)) return
            timelineController.replaceOnlineFriendConversations(
                ownerMemberNumber = account.memberNumber,
                accountGeneration = accountGeneration,
                conversations = friendConversations,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            currentCoroutineContext().ensureActive()
            if (connectionController.isCurrentAuthenticatedSession(account, accountGeneration)) {
                connectionController.onOperationFailure(ReconnectReason.OperationFailed, throwable)
            }
        }
    }

    private suspend fun persistRoomTarget(roomName: String?) {
        val requested = stateHolder.current
        val accountName = requested.account?.accountName ?: stateHolder.lastAccountName ?: return
        if (accountName.isBlank()) return
        val memberNumber = requested.account?.memberNumber
        val generation = requested.relationships.accountGeneration
        val loginSessionId = requested.selfRuleLogSessionId
        val rememberedTarget = stateHolder.rememberedRoomTarget
        val currentRoomName = requested.currentRoom?.name?.value
        roomTargetPersistenceMutex.withLock {
            val current = stateHolder.current
            val currentTarget = stateHolder.rememberedRoomTarget
            val currentAccountName = current.account?.accountName ?: stateHolder.lastAccountName
            if (currentAccountName != accountName || current.account?.memberNumber != memberNumber ||
                current.relationships.accountGeneration != generation || current.selfRuleLogSessionId != loginSessionId ||
                currentTarget != rememberedTarget || current.currentRoom?.name?.value != currentRoomName
            ) return@withLock
            if (roomName != null) {
                if (!current.desiredOnline || roomName != currentTarget?.roomName) return@withLock
            } else if (currentTarget != null && current.desiredOnline && loginSessionId != null) {
                return@withLock
            }
            try {
                onRoomTargetChanged(accountName, roomName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.getLogger(DefaultBcSessionRuntime::class.java.name).log(
                    Level.WARNING, "The BC room recovery target could not be saved.", failure,
                )
            }
        }
    }

}
