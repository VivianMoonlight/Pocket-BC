package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.AccountQueryRequest
import bot.inker.bc.razor.protocol.account.AccountOwnershipResponse
import bot.inker.bc.razor.protocol.account.BeepRequest
import bot.inker.bc.razor.protocol.auth.LoginRequest
import bot.inker.bc.razor.protocol.chat.ChatMessageType
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import bot.inker.bc.razor.protocol.room.ChatRoomSearchRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatMaxLength
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcSelfAppearanceUpdateCommand
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingResult
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingRejectionReason
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.core.model.BcAuthenticatedPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomChatRejectedException
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomRemovalReason
import dev.vivian.mobilebc.core.model.BcRoomCommandValidation
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.core.model.validateBcRoomCreate
import dev.vivian.mobilebc.core.model.validateBcRoomUpdate
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.BcInboundReceiveContext
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.core.protocol.createRazorWireGson
import dev.vivian.mobilebc.core.protocol.BcWardrobeCodec
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.SessionGateway
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.sync.OutboxCommandDispatcher
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import dev.vivian.mobilebc.domain.sync.OutboxDispatchRejectedException
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcspeech.BcRoomChatPreparation
import dev.vivian.mobilebc.domain.bcspeech.prepareBcRoomChat
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeContext
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlanner
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEmissionRejectedException
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class RazorGatewayImpl(
    private val ruleCatalogProvider: () -> BcRuleCatalog? = { null },
    private val textLanguageProvider: () -> String = { "EN" },
    private val roomBackgroundNamesProvider: () -> Set<String>? = { null },
) : SessionGateway, OutboxCommandDispatcher, AutoCloseable {
    private val closed = AtomicBoolean()
    // Callbacks and session reset share this fence; the transport releases its lifecycle lock
    // before callback effects acquire the room-state lock.
    private val inboundLock = Any()
    val packetLog = PacketLogStore()
    private val inboundOccurrence = AtomicLong()
    @Volatile
    var currentBcVersion: String? = null
        private set
    private val gson: Gson = createRazorWireGson()
    private val eventQueue = Channel<BcSessionGatewayEvent>(Channel.UNLIMITED)
    /** Ordered events retained until the single session runtime consumer handles them. */
    override val events: Flow<BcSessionGatewayEvent> = eventQueue.receiveAsFlow()
    private val inboundMessageQueue = RazorInboundMessageQueue()
    private val inboundMessageSignalGate = RazorInboundMessageSignalGate()
    private val roomManagementRequests = RazorRoomManagementRequests()
    private var pendingRoomMutation: PendingRoomMutation? = null
    private val loginMutex = Mutex()
    private val connectionMutex = Mutex()
    @Volatile
    private var selectedServerConfig: BcServerConfig? = null
    @Volatile
    private var lastLoginConnectionGeneration: Long? = null
    val inboundFrames: Flow<CanonicalFrame> = inboundMessageQueue.frames
    private val transport = RazorSocketTransport(
        packetLog = packetLog,
        onConnected = { emitEvent(BcSessionGatewayEvent.Connected) },
        onDisconnected = ::handleDisconnected,
        onIncoming = ::handleIncomingEvent,
        callbackLock = inboundLock,
    )
    private val outbound = RazorOutboundPipeline(
        gson = gson,
        captureEmitGuard = transport::captureEmitGuard,
        captureAccountEmitGuard = ::captureAccountUpdateEmitGuard,
        emitNow = transport::emitNow,
    )
    private val roomStateStore = RazorRoomStateStore(
        gson = gson,
        emit = ::emitEvent,
        queueAccountAppearance = outbound::queueAccountAppearance,
        diagnostic = { message ->
            packetLog.record(
                direction = PacketLogDirection.Inbound,
                eventName = "RoomStateDiagnostic",
                payloadJson = gson.toJson(mapOf("Message" to message)),
            )
        },
    )
    private val requests = RazorRequestCoordinator(
        onAuthenticated = { memberNumber ->
            roomManagementRequests.reset(roomMutationDisconnected())
            roomStateStore.clearRoom()
            roomStateStore.selfMemberNumber = memberNumber
            roomStateStore.renewSelfRuleLog()
            emitEvent(BcSessionGatewayEvent.RoomLeft)
        },
        onRelationshipEconomyLoaded = { economy ->
            relationshipEconomy = economy
        },
    )
    private var relationshipEconomy: BcRelationshipEconomyState? = null
    @Volatile
    private var serverTimeAnchor: BcServerTimeAnchor? = null
    private val wardrobePlanner = BcWardrobePlanner()

    private fun captureAccountUpdateEmitGuard(): (() -> Unit) -> Unit {
        val account = requests.authenticatedPrivateChatState
        return { emitPacket ->
            val emitted = account?.let { queuedAccount ->
                requests.withAuthenticatedPrivateChatState(queuedAccount.selfRuleLogSessionId) { current ->
                    if (current.memberNumber != queuedAccount.memberNumber) {
                        throw OutboxDispatchDeferredException("The account update belongs to a previous login.")
                    }
                    emitPacket()
                    true
                }
            }
            if (emitted != true) {
                throw OutboxDispatchDeferredException("The account update belongs to a previous login.")
            }
        }
    }

    override suspend fun connect(config: BcServerConfig) {
        connectSocket(config)
    }

    private suspend fun connectSocket(
        config: BcServerConfig,
        queuedFailure: Throwable = IllegalStateException("Socket sender queue was cleared."),
        expectedGeneration: Long? = null,
    ): Long = connectionMutex.withLock {
        val origin = synchronized(inboundLock) {
            check(!closed.get()) { "BC gateway is closed." }
            if (expectedGeneration != null && transport.captureGeneration() != expectedGeneration) {
                throw CancellationException("The BC connection changed before the new login started.")
            }
            closeSocket(emitDisconnected = false, queuedFailure = queuedFailure)
            selectedServerConfig = config
            currentBcVersion = config.version
            transport.captureGeneration()
        }
        transport.connect(config, expectedGeneration = origin)
    }

    override suspend fun login(accountName: String, password: String): BcSessionLoginResult {
        if (!loginMutex.tryLock()) return BcSessionLoginResult.Failure("A login request is already pending.")
        try {
            val connection = synchronized(inboundLock) {
                requireConnected()
                val generation = transport.captureGeneration()
                val reconnect = lastLoginConnectionGeneration == generation || requests.authenticatedPrivateChatState != null
                Triple(generation, reconnect, selectedServerConfig)
            }
            val generation = if (connection.second) {
                val config = checkNotNull(connection.third) { "The BC server configuration is unavailable for a new login." }
                connectSocket(config, OutboxDispatchDeferredException("The active login changed before this message was sent."),
                    expectedGeneration = connection.first)
            } else connection.first
            return requests.awaitLogin {
                lastLoginConnectionGeneration = generation
                roomManagementRequests.reset(roomMutationDisconnected())
                outbound.clear(OutboxDispatchDeferredException("The active login changed before this message was sent."))
                outbound.sendJson("AccountLogin", LoginRequest(accountName, password)) { emitPacket ->
                    transport.emitIfCurrent(generation, emitPacket)
                }
            }
        } finally {
            loginMutex.unlock()
        }
    }

    override suspend fun probeAuthenticatedSession() {
        queryOnlineFriendConversations()
    }

    override suspend fun queryOnlineFriendConversations(): List<BcConversation> {
        requireConnected()
        return requests.awaitOnlineFriends {
            outbound.sendJson("AccountQuery", AccountQueryRequest("OnlineFriends"))
        }
    }

    override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> {
        requireConnected()
        return requests.awaitRoomSearch {
            outbound.sendJson(
                "ChatRoomSearch",
                ChatRoomSearchRequest(
                    query = filter.query.uppercase().trim(),
                    space = filter.space,
                    fullRooms = filter.includeFullRooms,
                    language = filter.language.orEmpty(),
                    showLocked = filter.includeLockedRooms,
                    searchDescs = filter.searchDescription,
                    mapTypes = filter.roomType.mapType?.let { listOf(it.wireValue) }.orEmpty(),
                ),
            )
        }
    }

    override suspend fun queryAllowItem(memberNumber: Int): Boolean {
        requireConnected()
        return requests.awaitAllowItem(memberNumber) {
            outbound.sendJson("ChatRoomAllowItem", mapOf("MemberNumber" to memberNumber))
        }
    }

    override suspend fun joinRoom(roomName: String, password: String?) {
        requireConnected()
        ensureRoomTransitionAvailable()
        val payload = buildMap {
            put("Name", roomName)
            if (!password.isNullOrBlank()) put("Password", password)
        }
        var transitionEpoch: Long? = null
        try {
            requests.awaitRoomJoin(onRejected = {
                transitionEpoch?.let(roomStateStore::rejectRoomJoin)
            }) {
                outbound.sendJson("ChatRoomJoin", payload) { emitPacket ->
                    synchronized(inboundLock) {
                        transitionEpoch = roomStateStore.emitRoomJoin(emitPacket)
                    }
                }
            }
        } catch (rejected: BcRoomJoinRejectedException) {
            transitionEpoch?.let(roomStateStore::rejectRoomJoin)
            throw rejected
        }
    }

    override suspend fun leaveRoom() {
        requireConnected()
        ensureRoomTransitionAvailable()
        outbound.flushAccountUpdates()
        outbound.sendJson("ChatRoomLeave", "", roomStateStore::emitRoomTransition)
    }

    override suspend fun createRoom(command: BcRoomCreateCommand): BcRoom {
        rejectRoomMutationIf(!transport.isConnected(), BcRoomMutationRejectionReason.ConnectionUnavailable)
        ensureRoomTransitionAvailable()
        val scope = captureRoomManagementScope()
        val normalized = when (val validation = validateBcRoomCreate(command, roomBackgroundNamesProvider().orEmpty())) {
            is BcRoomCommandValidation.Valid -> validation.command
            is BcRoomCommandValidation.Invalid -> throw BcRoomMutationRejectedException(validation.rejection)
        }
        roomStateStore.withRoomCreation(scope.accountMemberNumber, scope.roomPresenceEpoch) {}
        return awaitRoomMutation(scope, RazorRoomManagementRequests.Kind.Create, normalized.settings.name) { binding ->
            var accountAtEmission: BcAuthenticatedPrivateChatState? = null
            outbound.sendPreparedJson<JsonObject>("ChatRoomCreate", emitGuard = { emitPacket ->
                roomStateStore.withRoomCreation(scope.accountMemberNumber, scope.roomPresenceEpoch) {
                    withRoomManagementAccount(scope) { account ->
                        accountAtEmission = account
                        emitPacket()
                    }
                }
            }) {
                val account = checkNotNull(accountAtEmission)
                val payload = normalized.toRoomCreatePayload(scope.accountMemberNumber, creationBanList(account))
                binding.ticket.markEmitting()
                binding.emitted = true
                binding.emittedEpoch = roomStateStore.markRoomCreationPending()
                payload
            }
        }
    }

    override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom {
        rejectRoomMutationIf(!transport.isConnected(), BcRoomMutationRejectionReason.ConnectionUnavailable)
        ensureRoomTransitionAvailable()
        val scope = captureRoomManagementScope()
        val normalized = when (val validation = validateBcRoomUpdate(command, roomBackgroundNamesProvider().orEmpty())) {
            is BcRoomCommandValidation.Valid -> validation.command
            is BcRoomCommandValidation.Invalid -> throw BcRoomMutationRejectedException(validation.rejection)
        }
        roomStateStore.withRoomUpdate(normalized, scope.accountMemberNumber) {}
        val expectedName = if (dev.vivian.mobilebc.core.model.BcRoomBasicField.Name in normalized.editedFields) {
            normalized.settings.name
        } else normalized.base.roomName.value
        return awaitRoomMutation(scope, RazorRoomManagementRequests.Kind.Update, expectedName) { binding ->
            var latestSettings: JsonObject? = null
            outbound.sendPreparedJson<JsonObject>("ChatRoomAdmin", emitGuard = { emitPacket ->
                roomStateStore.withRoomUpdate(normalized, scope.accountMemberNumber) { settings ->
                    withRoomManagementAccount(scope) {
                        latestSettings = settings
                        emitPacket()
                    }
                }
            }) {
                val settings = checkNotNull(latestSettings)
                binding.expectedEditedFields = normalized.editedRoomFields()
                binding.ticket.markEmitting()
                binding.emitted = true
                JsonObject().apply {
                    addProperty("MemberNumber", 0)
                    addProperty("Action", "Update")
                    add("Room", settings)
                }
            }
        }
    }

    override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand) {
        rejectRoomMemberAdministrationIf(!transport.isConnected(), BcRoomMemberAdminRejectionReason.ConnectionUnavailable)
        ensureRoomMemberManagementAvailable()
        val account = requests.authenticatedPrivateChatState
            ?: throw BcRoomMemberAdminRejectedException(BcRoomMemberAdminRejectionReason.AccountUnavailable)
        val generation = transport.captureGeneration()
        roomStateStore.withRoomMemberAdministration(command, account.memberNumber) {}
        outbound.sendJson("ChatRoomAdmin", command.toRoomMemberAdminPayload()) { emitPacket ->
            roomStateStore.withRoomMemberAdministration(command, account.memberNumber) {
                ensureRoomMemberManagementAvailable()
                val emitted = requests.withAuthenticatedPrivateChatState(account.selfRuleLogSessionId) { current ->
                    rejectRoomMemberAdministrationIf(current.memberNumber != account.memberNumber,
                        BcRoomMemberAdminRejectionReason.AccountUnavailable)
                    rejectRoomMemberAdministrationIf(transport.captureGeneration() != generation || !transport.isConnected(),
                        BcRoomMemberAdminRejectionReason.ConnectionUnavailable)
                    transport.emitIfCurrent(generation, emitPacket)
                    true
                }
                rejectRoomMemberAdministrationIf(emitted != true, BcRoomMemberAdminRejectionReason.AccountUnavailable)
            }
        }
    }

    private fun ensureRoomMemberManagementAvailable() {
        try {
            roomManagementRequests.ensureRoomTransitionAvailable()
        } catch (busy: RazorRoomManagementRequests.BusyException) {
            throw BcRoomMemberAdminRejectedException(BcRoomMemberAdminRejectionReason.Busy)
        } catch (pending: RazorRoomManagementRequests.ChannelQuarantinedException) {
            throw BcRoomMemberAdminRejectedException(BcRoomMemberAdminRejectionReason.PendingResponse)
        }
    }

    private fun captureRoomManagementScope(): RazorRoomManagementRequests.Scope {
        val account = requests.authenticatedPrivateChatState ?: throw BcRoomMutationRejectedException(
            BcRoomMutationRejection(BcRoomMutationRejectionReason.AccountUnavailable),
        )
        val room = roomStateStore.captureReceiveContext()
        rejectRoomMutationIf(account.memberNumber <= 0 || room.ownerMemberNumber != account.memberNumber,
            BcRoomMutationRejectionReason.AccountUnavailable)
        return RazorRoomManagementRequests.Scope(transport.captureGeneration(), account.memberNumber,
            account.selfRuleLogSessionId, room.roomPresenceEpoch, room.roomName)
    }

    private fun <T> withRoomManagementAccount(scope: RazorRoomManagementRequests.Scope, block: (BcAuthenticatedPrivateChatState) -> T): T {
        rejectRoomMutationIf(transport.captureGeneration() != scope.connectionGeneration, BcRoomMutationRejectionReason.ConnectionUnavailable)
        val result = requests.withAuthenticatedPrivateChatState(scope.loginSessionId) { account ->
            rejectRoomMutationIf(account.memberNumber != scope.accountMemberNumber, BcRoomMutationRejectionReason.AccountUnavailable)
            RoomManagementValue(block(account))
        } ?: throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.AccountUnavailable))
        return result.value
    }

    private suspend fun awaitRoomMutation(
        scope: RazorRoomManagementRequests.Scope,
        kind: RazorRoomManagementRequests.Kind,
        expectedRoomName: String,
        send: suspend (PendingRoomMutation) -> Unit,
    ): BcRoom {
        var binding: PendingRoomMutation? = null
        try {
            val result = roomManagementRequests.await(scope, kind, expectedRoomName) { ticket ->
                val owned = PendingRoomMutation(ticket)
                binding = owned
                synchronized(roomStateStore) { pendingRoomMutation = owned }
                send(owned)
            }
            return when (result) {
                is RazorRoomManagementRequests.Result.Authoritative -> checkNotNull(binding?.confirmedRoom)
                is RazorRoomManagementRequests.Result.Rejected -> {
                    if (kind == RazorRoomManagementRequests.Kind.Create) {
                        binding?.emittedEpoch?.let(roomStateStore::rejectRoomJoin)
                    }
                    throw BcRoomMutationRejectedException(
                        BcRoomMutationRejection(BcRoomMutationRejectionReason.ServerRejected, serverCode = result.token),
                    )
                }
                is RazorRoomManagementRequests.Result.InvalidResponse -> throw BcRoomMutationOutcomeUnknownException(
                    BcRoomMutationUnknownReason.InvalidResponse, result.payloadJson,
                )
                is RazorRoomManagementRequests.Result.TimedOut -> throw BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.TimedOut)
            }
        } catch (busy: RazorRoomManagementRequests.BusyException) {
            throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.Busy))
        } catch (pending: RazorRoomManagementRequests.ChannelQuarantinedException) {
            throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.PendingResponse))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: BcRoomMutationRejectedException) {
            throw rejected
        } catch (unknown: BcRoomMutationOutcomeUnknownException) {
            throw unknown
        } catch (failure: Throwable) {
            if (binding?.emitted == true || requests.authenticatedSelfRuleLogSessionId != scope.loginSessionId ||
                transport.captureGeneration() != scope.connectionGeneration
            ) {
                throw roomMutationDisconnected().apply { initCause(failure) }
            }
            throw failure
        } finally {
            synchronized(roomStateStore) {
                if (pendingRoomMutation === binding) pendingRoomMutation = null
            }
        }
    }

    private fun ensureRoomTransitionAvailable() {
        try {
            roomManagementRequests.ensureRoomTransitionAvailable()
        } catch (busy: RazorRoomManagementRequests.BusyException) {
            throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.Busy))
        } catch (pending: RazorRoomManagementRequests.ChannelQuarantinedException) {
            throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.PendingResponse))
        }
    }

    private fun creationBanList(account: BcAuthenticatedPrivateChatState): List<Int> {
        val onlineSettings = account.state.preferences.onlineSettingsJson?.let { raw ->
            runCatching { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        }
        fun enabled(field: String, fallback: Boolean): Boolean = onlineSettings?.get(field)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback
        val relations = requests.authenticatedRelationships?.takeIf { it.loginSessionId == account.selfRuleLogSessionId }?.self
            ?: throw BcRoomMutationRejectedException(BcRoomMutationRejection(BcRoomMutationRejectionReason.AccountUnavailable))
        return buildList {
            if (enabled("AutoBanBlackList", false)) addAll(relations.blacklist)
            if (enabled("AutoBanGhostList", true)) addAll(relations.ignored)
        }.distinct()
    }

    override suspend fun send(command: BcOutboundCommand) {
        sendPrepared(command)
    }

    override suspend fun sendPrepared(command: BcOutboundCommand): BcOutboundDispatchReceipt {
        require((command.replyToId == null) == (command.replyRoomName == null)) { "Replies require a room scope." }
        val replyToId = command.replyToId
        if (replyToId != null) {
            require(replyToId.isNotBlank()) { "Reply message ID is missing." }
            require(!command.replyRoomName.isNullOrBlank()) { "Reply room scope is missing." }
            require(
                (command.transport == BcMessageTransport.RoomChat &&
                    (command.kind == BcMessageKind.Chat || command.kind == BcMessageKind.Emote)) ||
                    (command.transport == BcMessageTransport.RoomWhisper && command.kind == BcMessageKind.Whisper),
            ) { "This message kind does not support replies." }
        }
        if ((command.transport == BcMessageTransport.RoomChat &&
                (command.kind == BcMessageKind.Chat || command.kind == BcMessageKind.Emote)) ||
            command.transport == BcMessageTransport.RoomWhisper
        ) {
            require(command.text.length <= BcRoomChatMaxLength) { "Room chat is limited to $BcRoomChatMaxLength characters." }
        }
        if (command.transport == BcMessageTransport.AccountBeep) {
            sendBeep(
                target = requireNotNull(command.targetMemberNumber) { "Account beep target is missing." },
                text = command.text,
                clientMsgId = command.clientMsgId,
            )
            return BcOutboundDispatchReceipt()
        }
        val type = when (command.transport) {
            BcMessageTransport.RoomWhisper -> {
                require(command.kind == BcMessageKind.Whisper) { "Queued room whisper requires the Whisper message kind." }
                requireNotNull(command.targetMemberNumber) { "Room whisper target is missing." }
                ChatMessageType.WHISPER
            }
            BcMessageTransport.RoomChat -> {
                require(command.targetMemberNumber == null) { "Queued room chat cannot specify a target." }
                when (command.kind) {
                    BcMessageKind.Chat -> ChatMessageType.CHAT
                    BcMessageKind.Emote -> ChatMessageType.EMOTE
                    BcMessageKind.Action -> {
                        requireConnected()
                        outbound.sendJson("ChatRoomChat", buildCustomActionMessage(command.text, normalizedMsgId(command.clientMsgId)),
                            roomStateStore.captureRoomMessageGuard(command))
                        return BcOutboundDispatchReceipt()
                    }
                    else -> error("Unsupported queued room message kind: ${command.kind.name}")
                }
            }
            BcMessageTransport.AccountBeep,
            BcMessageTransport.LocalSystem,
            BcMessageTransport.ProtocolEvent,
            -> error("Unsupported outbox transport: ${command.transport.name}")
        }
        requireConnected()
        val roomGuard = roomStateStore.captureRoomMessageGuard(command)
        val token = requests.authenticatedSelfRuleLogSessionId
            ?: throw OutboxDispatchRejectedException(BcRoomChatRejection(BcRoomChatRejectionReason.AccountStateUnavailable))
        var activePrivate: BcAuthenticatedPrivateChatState? = null
        var privateUpdate: BcPrivateChatState? = null
        val payload = outbound.sendPreparedJson("ChatRoomChat", emitGuard = { emitPacket ->
            roomGuard {
                val emitted = requests.withAuthenticatedPrivateChatState(token) { account ->
                    if (account.memberNumber != roomStateStore.selfMemberNumber) {
                        throw OutboxDispatchDeferredException("The active account changed before this message was sent.")
                    }
                    activePrivate = account
                    emitPacket()
                    privateUpdate?.let { updated -> publishPrivateChatState(account, updated) }
                    true
                }
                if (emitted != true) throw OutboxDispatchDeferredException("The active login changed before this message was sent.")
            }
        }) {
            val account = checkNotNull(activePrivate)
            val prepared = when (val result = prepareCurrentRoomChat(command.text, command.kind, account.state)) {
                is BcRoomChatPreparation.Prepared -> result
                is BcRoomChatPreparation.Rejected -> throw OutboxDispatchRejectedException(result.rejection)
            }
            privateUpdate = prepared.logMutation?.let { mutation ->
                applyPrivateLogMutation(account.state, mutation)
                    ?: throw OutboxDispatchRejectedException(BcRoomChatRejection(BcRoomChatRejectionReason.OwnerRulesUnavailable))
            }
            buildRoomMessage(prepared.text, type, command.targetMemberNumber, normalizedMsgId(command.clientMsgId), command.replyToId)
        }
        return BcOutboundDispatchReceipt(payload.content, gson.toJson(payload.dictionary))
    }

    override suspend fun validateRoomChat(kind: BcMessageKind, text: String, targetMemberNumber: Int?) {
        if (kind !in setOf(BcMessageKind.Chat, BcMessageKind.Emote, BcMessageKind.Whisper)) return
        require(text.length <= BcRoomChatMaxLength) { "Room chat is limited to $BcRoomChatMaxLength characters." }
        requireConnected()
        val token = requests.authenticatedSelfRuleLogSessionId
            ?: throw BcRoomChatRejectedException(BcRoomChatRejection(BcRoomChatRejectionReason.AccountStateUnavailable))
        val roomGuard = roomStateStore.captureRoomActionGuard(targetMemberNumber)
        roomGuard {
            val validated = requests.withAuthenticatedPrivateChatState(token) { account ->
                if (account.memberNumber != roomStateStore.selfMemberNumber) {
                    throw OutboxDispatchDeferredException("The active account changed before this message was prepared.")
                }
                when (val result = prepareCurrentRoomChat(text, kind, account.state)) {
                    is BcRoomChatPreparation.Prepared -> Unit
                    is BcRoomChatPreparation.Rejected -> throw BcRoomChatRejectedException(result.rejection)
                }
                true
            }
            if (validated != true) throw OutboxDispatchDeferredException("The active login changed before this message was prepared.")
        }
    }

    private fun prepareCurrentRoomChat(
        text: String,
        kind: BcMessageKind,
        privateState: BcPrivateChatState,
    ): BcRoomChatPreparation {
        val characters = roomStateStore.captureCharacterSnapshots()
        return prepareBcRoomChat(text, kind, characters.firstOrNull { it.isSelf }, characters, privateState,
            estimatedServerTimeMillis())
    }

    private fun publishPrivateChatState(
        expected: BcAuthenticatedPrivateChatState,
        updated: BcPrivateChatState,
        resetWhisperTarget: Boolean = false,
        persistLog: Boolean = true,
    ) {
        val committed = requests.compareAndSetPrivateChatState(expected, updated) ?: return
        if (committed.state.ruleLog == null) roomStateStore.invalidateSelfRuleLog(roomStateStore.captureReceiveContext())
        else roomStateStore.renewSelfRuleLog()
        committed.state.rawLogJson?.takeIf { persistLog }?.let { raw ->
            outbound.queueAccountUpdates(mapOf("Log" to JsonParser.parseString(raw)))
        }
        emitEvent(BcSessionGatewayEvent.SelfPrivateChatStateUpdated(committed.memberNumber,
            committed.selfRuleLogSessionId, committed.state, resetWhisperTarget))
    }

    private fun estimatedServerTimeMillis(): Long? {
        val anchor = serverTimeAnchor ?: return null
        val elapsedNanos = System.nanoTime() - anchor.receivedAtMonotonicNanos
        if (anchor.epochMillis < 0 || elapsedNanos < 0) return null
        return runCatching { Math.addExact(anchor.epochMillis, elapsedNanos / 1_000_000L) }.getOrNull()
    }

    override suspend fun sendActivity(command: BcActivityCommand) {
        requireConnected()
        val context = roomStateStore.captureReceiveContext()
        val sourceMemberNumber = context.ownerMemberNumber
            ?: throw IllegalStateException("Cannot send a BC activity before login completes.")
        outbound.sendJson(
            "ChatRoomChat", buildActivityChatPayload(command, sourceMemberNumber),
            roomStateStore.captureRoomActionGuard(command.targetMemberNumber, context),
        )
    }

    override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) {
        sendCharacterItemUpdate(command, null) {}
    }

    override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand, expectedCharacters: List<BcCharacterSnapshot>?, validateBeforeEmit: () -> Unit) {
        sendCharacterItemUpdate(command, expectedCharacters, validateBeforeEmit)
    }

    private suspend fun sendCharacterItemUpdate(command: BcCharacterItemUpdateCommand, expectedCharacters: List<BcCharacterSnapshot>?, validateBeforeEmit: () -> Unit) {
        requireConnected()
        val context = roomStateStore.captureReceiveContext()
        val sourceMemberNumber = context.ownerMemberNumber
            ?: throw IllegalStateException("Cannot update a BC item before login completes.")
        val emitGuard = roomStateStore.captureRoomActionGuard(command.targetMemberNumber, context)
        val itemGuard = command.expireTimerSource?.let { source ->
            check(source.group == command.group) { "The expired item group does not match the update." }
            roomStateStore.captureExpiredItemUpdateGuard(command.targetMemberNumber, context, source)
        } ?: roomStateStore.captureItemUpdateGuard(command.targetMemberNumber, context, expectedCharacters)
        var emittedItem: JsonObject? = null
        var emittedCommand = command
        val guardedItemEmission: (() -> Unit) -> Unit = { emitPacket ->
            itemGuard {
                command.expireTimerSource?.let { source ->
                    val expiry = source.propertyJson?.let { JsonParser.parseString(it).asJsonObject.get("RemoveTimer") }
                    val now = estimatedServerTimeMillis() ?: error("Reconnect to obtain the time before settling an expired item.")
                    check(expiry?.isJsonPrimitive == true && expiry.asJsonPrimitive.isNumber && expiry.asDouble.isFinite() &&
                        expiry.asDouble <= now.toDouble()) { "The item timer has not expired." }
                }
                val expectedPrivate = command.permissionPrivateState
                if (expectedPrivate == null) {
                    validateBeforeEmit()
                    emitPacket()
                    roomStateStore.applyEmittedItemUpdate(checkNotNull(emittedItem))
                } else {
                    check(requests.withAuthenticatedWardrobeState(expectedPrivate.wardrobe.selfRuleLogSessionId) { wardrobe, privateChat ->
                        check(wardrobe == expectedPrivate.wardrobe && privateChat.ruleLog == expectedPrivate.ruleLog) {
                            "Your keys or owner rules changed. Select the operation again."
                        }
                        validateBeforeEmit()
                        emitPacket()
                        roomStateStore.applyEmittedItemUpdate(checkNotNull(emittedItem))
                        true
                    } == true) { "Your login changed. Select the operation again." }
                }
            }
        }
        if (command.afcHeartLockChange != null) {
            outbound.sendPreparedItemWithCompanion(guardedItemEmission) {
                buildAfcHeartLockUpdate(command, sourceMemberNumber, estimatedServerTimeMillis() ?: System.currentTimeMillis()).also { emittedItem = it.item }
            }
        } else outbound.sendPreparedJson("ChatRoomCharacterItemUpdate", emitGuard = guardedItemEmission) {
            val timerSeconds = command.initializeLockTimerSeconds
            val outgoing = if (timerSeconds != null) {
                check(timerSeconds > 0) { "The lock timer must be positive." }
                val now = estimatedServerTimeMillis() ?: error("Reconnect to obtain the time before applying a timer lock.")
                val expiry = Math.addExact(now, Math.multiplyExact(timerSeconds.toLong(), 1000L))
                command.copy(propertyJson = dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson.overlayRawProperties(
                    command.propertyJson, "{\"RemoveTimer\":$expiry}",
                ))
            } else if (command.adjustLockTimer != null) {
                val now = estimatedServerTimeMillis() ?: error("Reconnect to obtain the current lock time.")
                prepareTimerAdjustment(command, now, textLanguageProvider())
            } else command
            emittedCommand = outgoing
            buildCharacterItemUpdatePayload(outgoing).also { emittedItem = it }
        }
        emittedCommand.publishAction?.let {
            outbound.sendJson("ChatRoomChat", buildItemActionChatPayload(emittedCommand, sourceMemberNumber), emitGuard)
        }
    }

    override suspend fun updatePose(command: BcPoseUpdateCommand) {
        sendPoseUpdate(command, expectedSelfState = null) {}
    }

    override suspend fun updatePose(command: BcPoseUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit) {
        sendPoseUpdate(command, expectedSelfState, validateBeforeEmit)
    }

    private suspend fun sendPoseUpdate(command: BcPoseUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation?, validateBeforeEmit: () -> Unit) {
        requireConnected()
        check(roomStateStore.selfMemberNumber != null) {
            "Cannot update pose before login completes."
        }
        validateSelfUpdateLogin(expectedSelfState)
        val roomGuard = expectedSelfState?.let { roomStateStore.captureProtectedSelfUpdateGuard(it, requireOwnerPoseRules = true) }
            ?: roomStateStore.captureRoomActionGuard()
        val payload = buildPoseUpdatePayload(command)
        outbound.sendJson("ChatRoomCharacterPoseUpdate", payload) { emitPacket ->
            roomGuard {
                withProtectedSelfRules(expectedSelfState, requireOwnerPoseRules = true) {
                    validateBeforeEmit()
                    emitPacket()
                    roomStateStore.applyEmittedPoseUpdate(payload.getAsJsonArray("Pose").map { it.asString })
                }
            }
        }
    }

    override suspend fun updateExpression(command: BcExpressionUpdateCommand) {
        sendExpressionUpdate(command, expectedSelfState = null) {}
    }

    override suspend fun saveWardrobe(
        request: BcWardrobeSaveRequest,
        appearance: List<BcWardrobeItem>,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ): BcWardrobeState = sendWardrobeAccountUpdate(
        field = "Wardrobe",
        expectedRevision = request.expectedRevision,
        expectedSelfState = expectedSelfState,
        validateBeforeEmit = validateBeforeEmit,
    ) { wardrobe, context, rules ->
        rejectWardrobeIf(wardrobePlanner.saveAppearance(context.self, rules) != appearance,
            BcWardrobeUnavailableReason.CharacterStateChanged)
        BcWardrobeCodec.prepareSave(wardrobe, request, appearance)
    }

    override suspend fun renameWardrobe(
        request: BcWardrobeRenameRequest,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ): BcWardrobeState = sendWardrobeAccountUpdate(
        field = "WardrobeCharacterNames",
        expectedRevision = request.expectedRevision,
        expectedSelfState = expectedSelfState,
        validateBeforeEmit = validateBeforeEmit,
    ) { wardrobe, context, _ -> BcWardrobeCodec.prepareRename(wardrobe, request, context.self.name) }

    private suspend fun sendWardrobeAccountUpdate(
        field: String,
        expectedRevision: Long,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
        prepare: (BcWardrobeState, BcWardrobeContext, BcRuleCatalog) -> BcWardrobeEncodingResult,
    ): BcWardrobeState {
        val rules = captureWardrobeRules()
        val token = wardrobeLoginToken(expectedSelfState)
        val roomGuard = roomStateStore.captureProtectedSelfUpdateGuard(expectedSelfState, requireOwnerPoseRules = false)
        var active: BcWardrobeState? = null
        var context: BcWardrobeContext? = null
        var next: BcWardrobeState? = null
        var committed: BcWardrobeState? = null
        outbound.sendPreparedJson<JsonObject>("AccountUpdate", emitGuard = { emitPacket ->
            roomGuard {
                val emitted = requests.withAuthenticatedWardrobeState(token) { wardrobe, privateChat ->
                    rejectWardrobeIf(wardrobe.memberNumber != expectedSelfState.character.memberNumber,
                        BcWardrobeUnavailableReason.AccountChanged)
                    rejectWardrobeIf(wardrobe.revision != expectedRevision || wardrobe.revision == Long.MAX_VALUE,
                        BcWardrobeUnavailableReason.RevisionChanged)
                    validateWardrobeRulesVersion(rules)
                    val liveContext = currentWardrobeContext(wardrobe, privateChat)
                    wardrobePlanner.unavailableReason(liveContext, rules)?.let(::rejectWardrobe)
                    validateBeforeEmit()
                    active = wardrobe
                    context = liveContext
                    emitPacket()
                    committed = publishWardrobeState(wardrobe, checkNotNull(next))
                    true
                }
                if (emitted != true) rejectWardrobe(BcWardrobeUnavailableReason.AccountChanged)
            }
        }) {
            val wardrobe = checkNotNull(active)
            val encoded = when (val result = prepare(wardrobe, checkNotNull(context), rules)) {
                is BcWardrobeEncodingResult.Encoded -> result.valueJson
                is BcWardrobeEncodingResult.Rejected -> rejectWardrobe(result.reason.toWardrobeUnavailableReason())
            }
            next = BcWardrobeCodec.decodeState(
                memberNumber = wardrobe.memberNumber,
                selfRuleLogSessionId = wardrobe.selfRuleLogSessionId,
                revision = wardrobe.revision,
                rawWardrobeJson = if (field == "Wardrobe") encoded else wardrobe.rawWardrobeJson,
                rawNamesJson = if (field == "WardrobeCharacterNames") encoded else wardrobe.rawNamesJson,
                fallbackName = checkNotNull(context).self.name,
                privateInputs = wardrobe.privateInputs,
            )
            JsonObject().apply { add(field, JsonParser.parseString(encoded)) }
        }
        return checkNotNull(committed) { "The wardrobe update did not reach the socket emission boundary." }
    }

    override suspend fun restoreSelfAppearance(
        command: BcSelfAppearanceUpdateCommand,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ) {
        val rules = captureWardrobeRules()
        val token = wardrobeLoginToken(expectedSelfState)
        val roomGuard = roomStateStore.captureProtectedSelfUpdateGuard(expectedSelfState, requireOwnerPoseRules = false)
        var appearance: List<bot.inker.bc.razor.protocol.common.ItemBundle>? = null
        outbound.sendPreparedJson<JsonObject>("ChatRoomCharacterUpdate", emitGuard = { emitPacket ->
            roomGuard {
                val emitted = requests.withAuthenticatedWardrobeState(token) { wardrobe, privateChat ->
                    rejectWardrobeIf(wardrobe.memberNumber != expectedSelfState.character.memberNumber,
                        BcWardrobeUnavailableReason.AccountChanged)
                    rejectWardrobeIf(wardrobe.revision != command.wardrobeRevision, BcWardrobeUnavailableReason.RevisionChanged)
                    validateWardrobeRulesVersion(rules)
                    val liveContext = currentWardrobeContext(wardrobe, privateChat)
                    val plan = wardrobePlanner.plan(liveContext, command.wardrobeSlotIndex, rules)
                    plan.unavailableReason?.let(::rejectWardrobe)
                    rejectWardrobeIf(plan.appearance != command.appearance || command.activePose != liveContext.self.activePose,
                        BcWardrobeUnavailableReason.CharacterStateChanged)
                    validateBeforeEmit()
                    appearance = command.appearance.toAppearanceItemBundles()
                    emitPacket()
                    outbound.queueAccountUpdates(mapOf(
                        "AssetFamily" to liveContext.self.assetFamily,
                        "Appearance" to checkNotNull(appearance),
                    ))
                    true
                }
                if (emitted != true) rejectWardrobe(BcWardrobeUnavailableReason.AccountChanged)
            }
        }) { buildSelfAppearanceUpdatePayload(checkNotNull(appearance), command.activePose, gson) }
    }

    private fun captureWardrobeRules(): BcRuleCatalog {
        rejectWardrobeIf(!transport.isConnected(), BcWardrobeUnavailableReason.Offline)
        val rules = ruleCatalogProvider() ?: rejectWardrobe(BcWardrobeUnavailableReason.CatalogPreparing)
        validateWardrobeRulesVersion(rules)
        return rules
    }

    private fun validateWardrobeRulesVersion(rules: BcRuleCatalog) {
        rejectWardrobeIf(rules.groups.isEmpty() || rules.assets.isEmpty() ||
            currentBcVersion?.normalizedBcVersionOrNull() != rules.version, BcWardrobeUnavailableReason.CatalogPreparing)
    }

    private fun wardrobeLoginToken(expected: BcSelfStateEmitExpectation): String {
        val token = expected.selfRuleLogSessionId
        rejectWardrobeIf(token == null || token != requests.authenticatedSelfRuleLogSessionId,
            BcWardrobeUnavailableReason.AccountChanged)
        return checkNotNull(token)
    }

    private fun currentWardrobeContext(wardrobe: BcWardrobeState, privateChat: BcPrivateChatState): BcWardrobeContext {
        val room = roomStateStore.captureRoomProjection() ?: rejectWardrobe(BcWardrobeUnavailableReason.NotInRoom)
        val characters = roomStateStore.captureCharacterSnapshots()
        val self = characters.firstOrNull { it.isSelf } ?: rejectWardrobe(BcWardrobeUnavailableReason.SelfUnavailable)
        return BcWardrobeContext(self, room.name, room.space, room.game, characters.map { it.memberNumber }.toSet(),
            room.blockCategories, wardrobe, privateChat.ruleLog, wardrobe.selfRuleLogSessionId, estimatedServerTimeMillis())
    }

    private fun publishWardrobeState(expected: BcWardrobeState, updated: BcWardrobeState): BcWardrobeState {
        val committed = requests.compareAndSetWardrobeState(expected, updated)
            ?: rejectWardrobe(BcWardrobeUnavailableReason.RevisionChanged)
        emitEvent(BcSessionGatewayEvent.SelfWardrobeStateUpdated(committed.memberNumber, committed.selfRuleLogSessionId, committed))
        return committed
    }

    private fun BcWardrobeEncodingRejectionReason.toWardrobeUnavailableReason(): BcWardrobeUnavailableReason = when (this) {
        BcWardrobeEncodingRejectionReason.InvalidSlot -> BcWardrobeUnavailableReason.InvalidSlot
        BcWardrobeEncodingRejectionReason.InvalidName -> BcWardrobeUnavailableReason.InvalidName
        BcWardrobeEncodingRejectionReason.NamesUnavailable -> BcWardrobeUnavailableReason.NamesUnavailable
        BcWardrobeEncodingRejectionReason.RevisionChanged -> BcWardrobeUnavailableReason.RevisionChanged
        else -> BcWardrobeUnavailableReason.UnsupportedSlot
    }

    private fun rejectWardrobeIf(condition: Boolean, reason: BcWardrobeUnavailableReason) {
        if (condition) rejectWardrobe(reason)
    }

    private fun rejectWardrobe(reason: BcWardrobeUnavailableReason): Nothing = throw BcWardrobeEmissionRejectedException(reason)

    override suspend fun updateExpression(command: BcExpressionUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit) {
        sendExpressionUpdate(command, expectedSelfState, validateBeforeEmit)
    }

    private suspend fun sendExpressionUpdate(command: BcExpressionUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation?, validateBeforeEmit: () -> Unit) {
        requireConnected()
        check(roomStateStore.selfMemberNumber != null) {
            "Cannot update expression before login completes."
        }
        require(command.group.isNotBlank()) { "Expression group is required." }
        validateSelfUpdateLogin(expectedSelfState)
        val roomGuard = expectedSelfState?.let { roomStateStore.captureProtectedSelfUpdateGuard(it, requireOwnerPoseRules = false) }
            ?: roomStateStore.captureRoomActionGuard()
        val payload = buildExpressionUpdatePayload(command)
        outbound.sendJson("ChatRoomCharacterExpressionUpdate", payload) { emitPacket ->
            roomGuard {
                withProtectedSelfRules(expectedSelfState, requireOwnerPoseRules = false) {
                    validateBeforeEmit()
                    emitPacket()
                    roomStateStore.applyEmittedExpressionUpdate(payload.get("Group").asString, payload.get("Name")?.asString)
                }
            }
        }
    }

    private fun validateSelfUpdateLogin(expected: BcSelfStateEmitExpectation?) {
        if (expected == null) return
        val logSessionId = expected.selfRuleLogSessionId
        check(logSessionId != null && logSessionId == requests.authenticatedSelfRuleLogSessionId) {
            "The active login changed before this update was sent."
        }
    }

    private fun withProtectedSelfRules(
        expected: BcSelfStateEmitExpectation?,
        requireOwnerPoseRules: Boolean,
        emitPacket: () -> Unit,
    ) {
        if (expected == null) {
            emitPacket()
            return
        }
        validateSelfUpdateLogin(expected)
        val token = requireNotNull(expected.selfRuleLogSessionId)
        val emitted = requests.withAuthenticatedPrivateChatState(token) { account ->
            check(account.memberNumber == expected.character.memberNumber) { "The active character changed before this update was sent." }
            if (requireOwnerPoseRules) validateLiveOwnerPoseRules(account.state)
            emitPacket()
            true
        }
        if (emitted == null) {
            validateSelfUpdateLogin(expected)
            check(requests.authenticatedPrivateChatState == null) { "The active login changed before this update was sent." }
            if (requireOwnerPoseRules) validateLiveOwnerPoseRules(null)
            emitPacket()
        }
    }

    private fun validateLiveOwnerPoseRules(privateState: BcPrivateChatState?) {
        val characters = roomStateStore.captureCharacterSnapshots()
        val self = characters.firstOrNull { it.isSelf } ?: error("Your character is not available in this room.")
        val ownership = self.ownership ?: return
        if ((ownership.stage ?: 0) < 1 || characters.none { it.memberNumber == ownership.memberNumber }) return
        val logs = privateState?.ruleLog ?: error("Your owner rules are unavailable. Reconnect before changing your pose.")
        val matching = logs.filter { it.group == "OwnerRule" && it.name == "BlockChangePose" }
        check(matching.none { it.expiresAtMillis == null }) { "Your owner has blocked pose changes." }
        if (matching.isEmpty()) return
        val now = estimatedServerTimeMillis() ?: error("Your owner rules are unavailable. Reconnect before changing your pose.")
        check(matching.none { requireNotNull(it.expiresAtMillis) >= now }) { "Your owner has blocked pose changes." }
    }

    override suspend fun publishEchoCompatibility(command: BcEchoCompatibilityCommand) {
        requireConnected()
        check(roomStateStore.selfMemberNumber != null) {
            "Cannot publish Echo compatibility before login completes."
        }
        require(command.modName.isNotBlank()) { "Echo compatibility mod name is required." }
        require(command.version.isNotBlank()) { "Echo compatibility version is required." }
        outbound.sendJson("ChatRoomChat", buildEchoCompatibilityPayload(command), roomStateStore.captureRoomActionGuard(command.targetMemberNumber))
    }

    override suspend fun publishEchoDrawOrder(command: BcEchoDrawOrderCommand) {
        requireConnected()
        check(roomStateStore.selfMemberNumber != null) {
            "Cannot publish Echo draw order before login completes."
        }
        require(command.otherMemberNumber > 0) { "Echo paired member number must be positive." }
        require(command.associatedAssetGroup.isNotBlank()) { "Echo associated asset group is required." }
        require(command.associatedAssetName.isNotBlank()) { "Echo associated asset name is required." }
        outbound.sendJson("ChatRoomChat", buildEchoDrawOrderPayload(command), roomStateStore.captureRoomActionGuard(command.otherMemberNumber))
    }

    private suspend fun sendBeep(target: Int, text: String, clientMsgId: String?) {
        requireConnected()
        outbound.sendJson(
            "AccountBeep",
            BeepRequest(
                memberNumber = target,
                message = text.ifBlank { null },
                msgId = clientMsgId?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    override suspend fun flushAccountUpdates() {
        outbound.flushAccountUpdates()
    }

    override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) {
        val batch = synchronized(inboundLock) {
            requireConnected()
            val account = requests.authenticatedPrivateChatState ?: error("Cannot update relationship lists before login completes.")
            requests.withAuthenticatedPrivateChatState(account.selfRuleLogSessionId) {
                checkNotNull(requests.updateRelationshipLists(account.selfRuleLogSessionId, update)) {
                    "The active relationship session changed before this update was queued."
                }
                outbound.queueRelationshipLists(update)
                outbound.drainAccountUpdatePacket()
            }
        }
        outbound.flushAccountUpdates(batch)
    }

    override suspend fun queryOwnershipCapability(memberNumber: Int): String? {
        requireConnected()
        require(memberNumber > 0) { "Ownership capability member number must be positive." }
        return requests.awaitOwnershipCapability(memberNumber) {
            outbound.sendJson("AccountOwnership", mapOf("MemberNumber" to memberNumber))
        }
    }

    override suspend fun queryLovershipCapability(memberNumber: Int): String? {
        requireConnected()
        require(memberNumber > 0) { "Lovership capability member number must be positive." }
        return requests.awaitLovershipCapability(memberNumber) {
            outbound.sendJson("AccountLovership", mapOf("MemberNumber" to memberNumber))
        }
    }

    override suspend fun sendRelationshipContractAction(command: BcRelationshipContractCommand) {
        requireConnected()
        require(command.memberNumber > 0) { "Relationship contract member number must be positive." }
        val payload = command.toRazorPayload()
        outbound.sendJson(payload.eventName, payload.value)
    }

    override suspend fun applyRelationshipEconomyEffect(
        effect: BcRelationshipEconomyEffect,
    ): BcRelationshipEconomyState {
        val (generation, pendingUpdate, economyUpdate) = synchronized(inboundLock) {
            requireConnected()
            val current = relationshipEconomy
                ?: throw IllegalStateException("Relationship economy state is unavailable before login completes.")
            val next = outbound.queueRelationshipEconomyEffect(effect, current)
            Triple(transport.captureGeneration(), outbound.drainAccountUpdatePacket(), current to next)
        }
        val (current, next) = economyUpdate
        outbound.flushAccountUpdates(pendingUpdate)
        synchronized(inboundLock) {
            check(transport.captureGeneration() == generation) {
                "BC connection was replaced before the relationship economy update completed."
            }
            requireConnected()
            check(relationshipEconomy === current) {
                "Relationship economy state changed before the update completed."
            }
            relationshipEconomy = next
        }
        return next
    }

    override suspend fun disconnect() {
        val (generation, pendingUpdate) = synchronized(inboundLock) {
            transport.captureGeneration() to outbound.drainAccountUpdatePacket()
        }
        try {
            outbound.flushAccountUpdates(pendingUpdate)
        } finally {
            closeSocket(emitDisconnected = true, expectedGeneration = generation)
        }
    }

    override fun isProbablyConnected(): Boolean =
        transport.isConnected()

    fun isCurrentRoomContext(context: BcInboundReceiveContext): Boolean =
        roomStateStore.isCurrentRoomContext(context)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeSocket(emitDisconnected = false, terminal = true)
        outbound.close()
        inboundMessageQueue.close()
        eventQueue.close()
        while (eventQueue.tryReceive().isSuccess) Unit
    }

    fun drainPendingInboundFrames(): List<CanonicalFrame> = inboundMessageQueue.drainPending()

    private fun handleIncomingEvent(eventName: String, rawPayload: String) {
        if (closed.get()) return
        val receiveAccount = requests.authenticatedPrivateChatState
        val frame = toCanonicalFrame(eventName, rawPayload)
        if (eventName == "ChatRoomMessage" || eventName == "ChatRoomChat") {
            invalidateSelfRuleLogForOwnerRule(frame)
        }
        if (inboundMessageQueue.offer(frame)) {
            if (inboundMessageSignalGate.shouldSignal(frame.receivedAtMillis)) {
                emitEvent(BcSessionGatewayEvent.InboundMessage)
            }
            return
        }
        val wireEvent = runCatching { RazorWireParser.parse(eventName, rawPayload) }.getOrNull() ?: return
        if (wireEvent is RazorWireEvent.AccountOwnership || wireEvent is RazorWireEvent.AccountLovership) {
            val update = when (wireEvent) {
                is RazorWireEvent.AccountOwnership -> {
                    handleSelfOwnership(wireEvent.value, receiveAccount)
                    wireEvent.value.toGatewayUpdate()
                }
                is RazorWireEvent.AccountLovership -> wireEvent.value.toGatewayUpdate()
                else -> error("Unexpected relationship response")
            }
            emitRelationshipUpdate(receiveAccount, update, frame.receivedAtMillis)
            requests.handle(wireEvent)
            return
        }
        if (wireEvent is RazorWireEvent.ChatRoomSearchResponse) {
            val receiveContext = frame.receiveContext
            var removed = false
            val token = (wireEvent.value as? bot.inker.bc.razor.protocol.room.RoomJoinResult.Error)?.message
            val reason = when (token) {
                "RoomKicked" -> BcRoomRemovalReason.Kicked
                "RoomBanned" -> BcRoomRemovalReason.Banned
                else -> null
            }
            if (reason != null && receiveAccount != null && receiveContext != null) {
                synchronized(roomStateStore) {
                    requests.withAuthenticatedPrivateChatState(receiveAccount.selfRuleLogSessionId) { current ->
                        if (current.memberNumber == receiveAccount.memberNumber) {
                            removed = roomStateStore.removeCurrentRoom(receiveContext, reason, current.selfRuleLogSessionId)
                            if (removed) roomManagementRequests.abortActive(roomMutationDisconnected())
                        }
                    }
                }
            }
            if (!removed) requests.handle(wireEvent)
            return
        }
        val roomHandled = synchronized(roomStateStore) {
            val handled = if (receiveAccount == null) wireEvent.isRoomStateEvent() else {
                requests.withAuthenticatedPrivateChatState(receiveAccount.selfRuleLogSessionId) {
                    roomStateStore.handle(wireEvent).also { handled ->
                        if (handled && wireEvent is RazorWireEvent.CharacterSync) {
                            invalidateWardrobeInputsForCharacterSync(wireEvent, receiveAccount)
                        }
                    }
                } ?: wireEvent.isRoomStateEvent()
            }
            if (handled) reconcileRoomMutation(wireEvent, receiveAccount)
            handled
        }
        if (roomHandled || requests.handle(wireEvent)) return
        when (wireEvent) {
            is RazorWireEvent.ServerInfo -> {
                val anchor = BcServerTimeAnchor(wireEvent.value.time, System.nanoTime())
                serverTimeAnchor = anchor
                emitEvent(BcSessionGatewayEvent.ServerInfo(
                    onlinePlayers = wireEvent.value.onlinePlayers,
                    serverTime = wireEvent.value.time,
                    receivedAtMonotonicNanos = anchor.receivedAtMonotonicNanos,
                ))
            }
            is RazorWireEvent.LoginQueue -> emitEvent(BcSessionGatewayEvent.LoginQueued(wireEvent.position ?: 0))
            is RazorWireEvent.ForceDisconnect -> emitEvent(
                BcSessionGatewayEvent.ForceDisconnected(wireEvent.value.toGatewayReason())
            )
            is RazorWireEvent.AccountOwnership,
            is RazorWireEvent.AccountLovership -> Unit
            is RazorWireEvent.ChatRoomCreateResponse -> handleRoomManagementResponse(receiveAccount,
                RazorRoomManagementRequests.Kind.Create, wireEvent.value)
            is RazorWireEvent.ChatRoomUpdateResponse -> handleRoomManagementResponse(receiveAccount,
                RazorRoomManagementRequests.Kind.Update, wireEvent.value)
            is RazorWireEvent.LoginResponse,
            is RazorWireEvent.AccountQueryResult,
            is RazorWireEvent.ChatRoomSearchResult,
            is RazorWireEvent.ChatRoomSearchResponse,
            is RazorWireEvent.AllowItem,
            -> Unit // Request responses or unsupported account-recovery responses.
            is RazorWireEvent.ChatRoomSync,
            is RazorWireEvent.MemberJoin,
            is RazorWireEvent.MemberLeave,
            is RazorWireEvent.RoomProperties,
            is RazorWireEvent.InvalidRoomSettings,
            is RazorWireEvent.CharacterSync,
            is RazorWireEvent.ExpressionSync,
            is RazorWireEvent.PoseSync,
            is RazorWireEvent.ArousalSync,
            is RazorWireEvent.ItemSync,
            is RazorWireEvent.MapDataSync,
            is RazorWireEvent.ReorderPlayers,
            -> Unit // Handled by roomStateStore above.
            is RazorWireEvent.AccountBeep,
            is RazorWireEvent.ChatRoomMessage,
            -> Unit // Queued and health-signalled before wire-event dispatch.
            is RazorWireEvent.Unknown -> Unit
        }
    }

    private fun invalidateSelfRuleLogForOwnerRule(frame: CanonicalFrame) {
        val context = frame.receiveContext ?: return
        val accountMemberNumber = context.ownerMemberNumber ?: return
        val logSessionId = requests.authenticatedSelfRuleLogSessionId ?: return
        val payload = runCatching { JsonParser.parseString(frame.raw) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val type = payload.get("Type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val content = payload.get("Content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return
        if (type != "Hidden" || !content.startsWith("OwnerRule")) return
        val target = payload.get("Target")
        if (target != null && !target.isJsonNull && target.asMemberNumberOrNull() != accountMemberNumber) return
        val sender = payload.get("Sender")?.asMemberNumberOrNull() ?: return
        roomStateStore.withCurrentRoomCharacters(context) { characters ->
            if (characters.none { it.memberNumber == sender }) return@withCurrentRoomCharacters
            val self = characters.firstOrNull { it.memberNumber == accountMemberNumber }
            val ownerMemberNumber = self?.ownership?.memberNumber
            if (ownerMemberNumber != null && sender != ownerMemberNumber) return@withCurrentRoomCharacters
            val handled = requests.withAuthenticatedPrivateChatState(logSessionId) { account ->
                if (account.memberNumber != accountMemberNumber) return@withAuthenticatedPrivateChatState true
                when (val update = planOwnerRule(account.state, content, sender, ownerMemberNumber,
                        self?.ownership?.stage, estimatedServerTimeMillis())) {
                    BcOwnerRuleUpdate.Ignore -> Unit
                    BcOwnerRuleUpdate.Invalidate -> publishPrivateChatState(account,
                        account.state.copy(ruleLog = null), persistLog = false)
                    is BcOwnerRuleUpdate.Apply -> publishPrivateChatState(account, update.state, update.resetWhisperTarget)
                }
                if (sender == ownerMemberNumber && (self?.ownership?.stage ?: 0) >= 1) {
                    invalidateWardrobeInputsForOwnerRule(account, content)
                }
                true
            }
            if (handled == null && requests.authenticatedPrivateChatState == null &&
                requests.authenticatedSelfRuleLogSessionId == logSessionId && roomStateStore.invalidateSelfRuleLog(context)
            ) {
                emitEvent(BcSessionGatewayEvent.SelfRuleLogInvalidated(accountMemberNumber, logSessionId))
            }
        }
    }

    private fun invalidateWardrobeInputsForOwnerRule(account: BcAuthenticatedPrivateChatState, content: String) {
        val inventoryChanged = content == "OwnerRuleKeyConfiscate" || content == "OwnerRuleRemoteConfiscate"
        val timerCell = content in setOf("OwnerRuleTimerCell5", "OwnerRuleTimerCell15", "OwnerRuleTimerCell30", "OwnerRuleTimerCell60")
        val gameChanged = timerCell || content in setOf(
            "OwnerRuleGGTS5", "OwnerRuleGGTS15", "OwnerRuleGGTS30", "OwnerRuleGGTS60",
            "OwnerRuleGGTS90", "OwnerRuleGGTS120", "OwnerRuleGGTS180",
        )
        if (!inventoryChanged && !gameChanged) return
        requests.withAuthenticatedWardrobeState(account.selfRuleLogSessionId) { wardrobe, _ ->
            if (wardrobe.memberNumber != account.memberNumber) return@withAuthenticatedWardrobeState
            val inputs = wardrobe.privateInputs
            publishWardrobeState(wardrobe, wardrobe.copy(privateInputs = inputs.copy(
                inventoryAvailable = inputs.inventoryAvailable && !inventoryChanged,
                inventoryItems = inputs.inventoryItems.takeUnless { inventoryChanged },
                inventoryIds = inputs.inventoryIds.takeUnless { inventoryChanged },
                gameAvailable = inputs.gameAvailable && !gameChanged,
            )))
        }
        if (timerCell) publishPrivateChatState(account, account.state.copy(ruleLog = null), persistLog = false)
    }

    private fun invalidateWardrobeInputsForCharacterSync(
        event: RazorWireEvent.CharacterSync,
        account: BcAuthenticatedPrivateChatState,
    ) {
        val fields = event.value.character.takeIf { it.isJsonObject }?.asJsonObject ?: return
        if (fields.get("MemberNumber")?.asMemberNumberOrNull() != account.memberNumber ||
            roomStateStore.selfMemberNumber != account.memberNumber || roomStateStore.captureRoomProjection() == null
        ) return
        requests.withAuthenticatedWardrobeState(account.selfRuleLogSessionId) { wardrobe, _ ->
            if (wardrobe.memberNumber != account.memberNumber) return@withAuthenticatedWardrobeState
            val inputs = wardrobe.privateInputs
            val inventoryChanged = fields.has("InventoryData") && fields.get("InventoryData").let { incoming ->
                !incoming.isJsonPrimitive || !incoming.asJsonPrimitive.isString || incoming.asString != inputs.inventoryData
            }
            val gameChanged = fields.has("Game") && wardrobeGameEvidenceChanged(fields.get("Game"), inputs.gameJson)
            if (!inventoryChanged && !gameChanged) return@withAuthenticatedWardrobeState
            publishWardrobeState(wardrobe, wardrobe.copy(privateInputs = inputs.copy(
                inventoryAvailable = inputs.inventoryAvailable && !inventoryChanged,
                inventoryItems = inputs.inventoryItems.takeUnless { inventoryChanged },
                inventoryIds = inputs.inventoryIds.takeUnless { inventoryChanged },
                gameAvailable = inputs.gameAvailable && !gameChanged,
            )))
        }
    }

    private fun wardrobeGameEvidenceChanged(incoming: com.google.gson.JsonElement, privateJson: String?): Boolean {
        if (incoming.isJsonNull) return false
        if (!incoming.isJsonObject) return true
        val baseline = privateJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
            ?.takeIf { it.isJsonObject }?.asJsonObject
        for ((game, field) in listOf("GGTS" to "Level", "LARP" to "Status", "MagicBattle" to "Status")) {
            val evidence = incoming.asJsonObject.get(game) ?: continue
            if (evidence.isJsonNull) continue
            if (!evidence.isJsonObject) return true
            val value = evidence.asJsonObject.get(field) ?: continue
            val previous = baseline?.get(game)?.takeIf { it.isJsonObject }?.asJsonObject?.get(field)
            if (field == "Level") {
                fun level(raw: com.google.gson.JsonElement?): Double? {
                    if (raw == null || raw.isJsonNull) return 0.0
                    if (!raw.isJsonPrimitive || !raw.asJsonPrimitive.isNumber) return null
                    return raw.asDouble.takeIf { it.isFinite() }?.let { if (it in 1.0..10.0) it else 0.0 }
                }
                val currentLevel = level(value) ?: return true
                if (currentLevel != level(previous)) return true
            } else {
                fun status(raw: com.google.gson.JsonElement?): String? {
                    if (raw == null || raw.isJsonNull) return ""
                    return raw.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?.takeIf { it == "" || it == "Running" }
                }
                val currentStatus = status(value) ?: return true
                if (currentStatus != status(previous)) return true
            }
        }
        return false
    }

    private fun handleSelfOwnership(response: AccountOwnershipResponse, receiveAccount: BcAuthenticatedPrivateChatState?) = synchronized(roomStateStore) {
        val memberNumber = roomStateStore.selfMemberNumber ?: return@synchronized
        if (response.clearOwnership != true && response.owner == null && response.ownership == null) return@synchronized
        val token = receiveAccount?.selfRuleLogSessionId ?: return@synchronized
        requests.withAuthenticatedPrivateChatState(token) { account ->
            if (account.memberNumber != memberNumber) return@withAuthenticatedPrivateChatState
            if (response.clearOwnership == true) {
                val updated = applyPrivateLogMutation(account.state, BcPrivateLogMutation.DeleteGroup("OwnerRule"))
                if (updated == null) publishPrivateChatState(account, account.state.copy(ruleLog = null), persistLog = false)
                else publishPrivateChatState(account, updated)
            }
            if (response.clearOwnership == true || response.owner != null) {
                requests.withAuthenticatedWardrobeState(token) { wardrobe, _ ->
                    val owner = if (response.clearOwnership == true) null else response.owner
                    if (wardrobe.privateInputs.owner != owner) {
                        publishWardrobeState(wardrobe, wardrobe.copy(privateInputs = wardrobe.privateInputs.copy(owner = owner)))
                    }
                }
            }
            roomStateStore.applySelfOwnership(response)
        }
    }

    private fun handleDisconnected(reason: String?) {
        val message = reason ?: "Disconnected from BC."
        roomManagementRequests.reset(roomMutationDisconnected())
        roomStateStore.clearRoom()
        outbound.clear()
        requests.disconnect(message)
        relationshipEconomy = null
        serverTimeAnchor = null
        inboundMessageSignalGate.reset()
        emitEvent(BcSessionGatewayEvent.Disconnected(reason))
    }

    private fun closeSocket(
        emitDisconnected: Boolean,
        terminal: Boolean = false,
        expectedGeneration: Long? = null,
        queuedFailure: Throwable = IllegalStateException("Socket sender queue was cleared."),
    ) = synchronized(inboundLock) {
        if (!transport.close(terminal, expectedGeneration)) return@synchronized
        roomManagementRequests.reset(roomMutationDisconnected())
        roomStateStore.resetSession()
        outbound.clear(queuedFailure)
        requests.disconnect("Disconnected from BC.")
        relationshipEconomy = null
        serverTimeAnchor = null
        inboundMessageSignalGate.reset()
        if (emitDisconnected) {
            emitEvent(BcSessionGatewayEvent.Disconnected("disconnect"))
        }
    }

    private fun normalizedMsgId(clientMsgId: String?): String =
        clientMsgId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()

    private fun toCanonicalFrame(eventName: String, rawJson: String): CanonicalFrame =
        CanonicalFrame(
            namespace = "/",
            transportEventName = eventName,
            args = listOf(rawJson),
            receivedAtMillis = System.currentTimeMillis(),
            socketId = null,
            ackId = null,
            raw = rawJson,
            occurrenceId = inboundOccurrence.incrementAndGet(),
            receiveContext = roomStateStore.captureReceiveContext(),
        )

    private fun requireConnected() {
        check(transport.isConnected()) {
            "Socket.IO transport is not connected."
        }
    }

    private fun emitEvent(event: BcSessionGatewayEvent) {
        if (closed.get()) return
        val account = requests.authenticatedPrivateChatState
        val context = roomStateStore.captureReceiveContext()
        val fenced = when (event) {
            is BcSessionGatewayEvent.RoomJoined -> account?.let {
                event.copy(accountMemberNumber = it.memberNumber, selfRuleLogSessionId = it.selfRuleLogSessionId,
                    roomPresenceEpoch = context.roomPresenceEpoch)
            }
            is BcSessionGatewayEvent.RoomUpdated -> account?.let {
                event.copy(accountMemberNumber = it.memberNumber, selfRuleLogSessionId = it.selfRuleLogSessionId,
                    roomPresenceEpoch = context.roomPresenceEpoch)
            }
            BcSessionGatewayEvent.RoomLeft -> account?.let {
                BcSessionGatewayEvent.RoomPresenceReset(it.memberNumber, it.selfRuleLogSessionId, context.roomPresenceEpoch)
            }
            else -> event
        }
        fenced?.let(eventQueue::trySend)
    }

    private fun emitRelationshipUpdate(account: BcAuthenticatedPrivateChatState?, update: BcRelationshipGatewayUpdate, receivedAtMillis: Long) {
        if (account == null) return
        val snapshot = requests.applyRelationshipUpdate(account.selfRuleLogSessionId, update, receivedAtMillis) ?: return
        emitEvent(BcSessionGatewayEvent.RelationshipUpdated(update, account.memberNumber,
            account.selfRuleLogSessionId, snapshot.revision))
    }

    private fun handleRoomManagementResponse(
        account: BcAuthenticatedPrivateChatState?,
        kind: RazorRoomManagementRequests.Kind,
        response: bot.inker.bc.razor.protocol.room.RoomManagementResponse,
    ) {
        if (account == null) return
        val context = roomStateStore.captureReceiveContext()
        val scope = RazorRoomManagementRequests.Scope(transport.captureGeneration(), account.memberNumber,
            account.selfRuleLogSessionId, context.roomPresenceEpoch, context.roomName)
        roomManagementRequests.handleResponse(scope, kind, response)
    }

    private fun reconcileRoomMutation(event: RazorWireEvent, receiveAccount: BcAuthenticatedPrivateChatState?) {
        val binding = pendingRoomMutation ?: return
        if (!binding.emitted) return
        val ticket = binding.ticket
        if (roomManagementRequests.pendingTicket !== ticket) return
        val context = roomStateStore.captureReceiveContext()
        val account = receiveAccount ?: return
        if (account.memberNumber != ticket.scope.accountMemberNumber ||
            account.selfRuleLogSessionId != ticket.scope.loginSessionId ||
            transport.captureGeneration() != ticket.scope.connectionGeneration
        ) return
        if (event is RazorWireEvent.InvalidRoomSettings) {
            roomManagementRequests.abortActive(BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.InvalidResponse,
                event.rawPayloadJson))
            return
        }
        val room = roomStateStore.captureRoomProjection()
        if (ticket.kind == RazorRoomManagementRequests.Kind.Create) {
            if (event !is RazorWireEvent.ChatRoomSync) return
            val expectedEpoch = binding.emittedEpoch?.plus(1L)
            val matches = room != null && room.name.value == ticket.expectedRoomName &&
                context.roomPresenceEpoch == expectedEpoch &&
                account.memberNumber in context.roomMembers &&
                room.adminSettings?.adminMemberNumbers?.contains(account.memberNumber) == true
            if (matches) {
                binding.confirmedRoom = room
                roomManagementRequests.confirm(ticket.scope, room!!.name.value, editedFieldsMatched = true)
            } else roomManagementRequests.abortActive(roomMutationDisconnected())
        } else {
            if (room == null || context.roomPresenceEpoch != ticket.scope.roomPresenceEpoch ||
                (context.roomName != ticket.scope.roomName && context.roomName != ticket.expectedRoomName)
            ) {
                roomManagementRequests.abortActive(roomMutationDisconnected())
                return
            }
            if (event !is RazorWireEvent.RoomProperties) return
            if (room.adminSettings == null) {
                roomManagementRequests.abortActive(BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.InvalidResponse,
                    event.rawPayloadJson))
                return
            }
            val raw = roomStateStore.captureRawSettings()
            val matches = binding.expectedEditedFields?.all { (field, expected) -> raw?.get(field) == expected } == true
            if (matches && room.name.value == ticket.expectedRoomName) {
                binding.confirmedRoom = room
                roomManagementRequests.confirm(ticket.scope, room.name.value, editedFieldsMatched = true)
            }
        }
    }
}

private class RoomManagementValue<T>(val value: T)

private class PendingRoomMutation(val ticket: RazorRoomManagementRequests.RequestTicket) {
    var emitted = false
    var emittedEpoch: Long? = null
    var expectedEditedFields: Map<String, com.google.gson.JsonElement>? = null
    var confirmedRoom: BcRoom? = null
}

private fun roomMutationDisconnected() = BcRoomMutationOutcomeUnknownException(BcRoomMutationUnknownReason.Disconnected)

private fun RazorWireEvent.isRoomStateEvent(): Boolean = when (this) {
    is RazorWireEvent.ChatRoomSync, is RazorWireEvent.MemberJoin, is RazorWireEvent.MemberLeave,
    is RazorWireEvent.RoomProperties, is RazorWireEvent.InvalidRoomSettings, is RazorWireEvent.CharacterSync,
    is RazorWireEvent.ExpressionSync, is RazorWireEvent.PoseSync, is RazorWireEvent.ArousalSync,
    is RazorWireEvent.ItemSync, is RazorWireEvent.MapDataSync, is RazorWireEvent.ReorderPlayers -> true
    else -> false
}

private fun com.google.gson.JsonElement.asMemberNumberOrNull(): Int? =
    runCatching {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return@runCatching null
        val value = asBigDecimal
        value.intValueExact().takeIf { it >= 0 }
    }.getOrNull()
