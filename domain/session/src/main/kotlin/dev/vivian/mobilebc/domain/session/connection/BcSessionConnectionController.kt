package dev.vivian.mobilebc.domain.session.connection

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionForceDisconnectReason
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.isInvalidCredentials
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.isAuthenticated
import dev.vivian.mobilebc.domain.session.core.BlockedReason
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.runtime.SessionHostLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

class BcSessionConnectionController(
    private val stateHolder: BcSessionRuntimeState,
    private val gateway: SessionConnectionGateway,
    private val scope: CoroutineScope,
    private val markRoomConversationDisconnected: () -> Unit,
    private val handleRecoveredLogin: suspend (BcSessionLoginResult.Success, String, String) -> BcAccount,
    private val handleUnrecoverableLoginFailure: suspend (String) -> Unit,
    private val autoJoinLastRoomAfterReconnect: suspend (BcAccount) -> Unit,
    private val emitRoomTargetChanged: suspend (String?) -> Unit,
    private val supervisorIntervalMs: Long = 15_000L,
    private val authenticatedProbeIntervalMs: Long = 45_000L,
    private val verifiedFreshMs: Long = 60_000L,
    private val reconnectAfterStaleMs: Long = 75_000L,
) : ConnectionStatusReader, SessionConnector, SessionRecoverySettings, SessionHostLifecycle {
    private val reconnectLock = Any()

    private var supervisorJob: Job? = null
    private var authenticatedProbeJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectGeneration: Long = 0L
    private val closed = AtomicBoolean()

    override val snapshot = stateHolder.snapshot

    fun start() {
        if (supervisorJob?.isActive != true) {
            supervisorJob = scope.launch {
                while (true) {
                    delay(supervisorIntervalMs)
                    reevaluateConnectionHealth(System.currentTimeMillis())
                }
            }
        }

        if (authenticatedProbeJob?.isActive != true) {
            authenticatedProbeJob = scope.launch {
                while (true) {
                    delay(authenticatedProbeIntervalMs)
                    probeAuthenticatedSession()
                }
            }
        }
    }

    override suspend fun connect(server: BcServerConfig) {
        check(!closed.get()) { "BC session is closed." }
        val selectedServer = server.withNormalizedVersion()
        cancelReconnect()
        stateHolder.lastServerConfig = selectedServer
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = BcSessionState.Connecting(attempt = 1),
                connectionHealth = BcConnectionHealth.Offline,
                desiredOnline = true,
                bcVersion = selectedServer.version,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            )
        }
        gateway.connect(selectedServer)
    }

    override fun restoreSavedSession(
        server: BcServerConfig,
        accountName: String,
        password: String,
        lastRoomName: String?,
    ) {
        check(!closed.get()) { "BC session is closed." }
        if (accountName.isBlank() || password.isBlank()) return

        val selectedServer = server.withNormalizedVersion()
        stateHolder.lastServerConfig = selectedServer
        stateHolder.rememberLogin(accountName, password)
        stateHolder.rememberRoomTarget(lastRoomName?.takeIf { it.isNotBlank() }, null)
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = BcSessionState.Connecting(attempt = 1),
                connectionHealth = BcConnectionHealth.Offline,
                desiredOnline = true,
                bcVersion = selectedServer.version,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            )
        }

        requestReconnectInternal(ReconnectReason.NetworkAvailable)
    }

    fun requestReconnect(reason: ReconnectReason) {
        requestReconnectInternal(reason)
    }

    override fun onTransportSuspect(reason: ReconnectReason) {
        markTransportSuspect(reason, message = null)
    }

    override fun onOperationFailure(reason: ReconnectReason, throwable: Throwable?) {
        markTransportSuspect(reason, throwable?.message)
    }

    override fun onNetworkAvailable() {
        val current = stateHolder.current
        if (!current.desiredOnline || !hasReconnectCredentials()) return

        reevaluateConnectionHealth(System.currentTimeMillis())

        if (stateHolder.current.connectionHealth == BcConnectionHealth.VerifiedOnline && isVerifiedFresh()) return
        if (isReconnectActive()) return

        val health = stateHolder.current.connectionHealth
        val shouldReconnect = health == BcConnectionHealth.Stale ||
            health == BcConnectionHealth.Offline ||
            !gateway.isProbablyConnected()

        if (shouldReconnect) {
            requestReconnect(ReconnectReason.NetworkAvailable)
        }
    }

    override fun onNetworkLost() {
        val current = stateHolder.current
        if (!current.desiredOnline ||
            current.state is BcSessionState.Stopped
        ) return

        onTransportSuspect(ReconnectReason.NetworkLost)
    }

    override fun setAutoJoinRoomAfterReconnect(enabled: Boolean) {
        stateHolder.updateSession { it.copy(autoJoinRoomAfterReconnect = enabled) }
    }

    override fun stop() {
        stopSession(clearRoomTarget = true)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        supervisorJob?.cancel()
        authenticatedProbeJob?.cancel()
        stopSession(clearRoomTarget = false)
    }

    private fun stopSession(clearRoomTarget: Boolean) {
        cancelReconnect()
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = BcSessionState.Stopped,
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
                desiredOnline = false,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
                relationships = it.relationships.nextUnloaded(),
            )
        }
        // Transport teardown must still run when the runtime or its parent scope is cancelled.
        scope.launch(context = NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            runCatching { gateway.disconnect() }
            if (clearRoomTarget) {
                emitRoomTargetChanged(null)
                stateHolder.rememberRoomTarget(null, null)
            }
        }
    }

    fun handleGatewayEvent(event: BcSessionGatewayEvent) {
        if (closed.get()) return
        when (event) {
            BcSessionGatewayEvent.Connected -> handleConnected()
            is BcSessionGatewayEvent.Disconnected -> handleDisconnected(event.reason)
            is BcSessionGatewayEvent.Reconnecting -> handleGatewayReconnecting(event.attempt)
            is BcSessionGatewayEvent.ForceDisconnected -> handleForceDisconnected(event.reason)
            is BcSessionGatewayEvent.ServerInfo -> handleServerInfo()
            is BcSessionGatewayEvent.LoginQueued -> handleLoginQueued(event.position)
            BcSessionGatewayEvent.InboundMessage -> handleInboundMessage()
            else -> Unit
        }
    }

    fun touchVerified(expectedAccount: BcAccount? = null, expectedAccountGeneration: Long? = null) {
        val nowMillis = System.currentTimeMillis()
        stateHolder.updateSession {
            if (it.account == null || !it.desiredOnline || !it.state.isAuthenticated ||
                (expectedAccount != null && it.account != expectedAccount) ||
                (expectedAccountGeneration != null && it.relationships.accountGeneration != expectedAccountGeneration)
            ) {
                it
            } else if (
                it.connectionHealth == BcConnectionHealth.VerifiedOnline &&
                it.lastVerifiedAtMillis?.let { verifiedAt ->
                    nowMillis >= verifiedAt && nowMillis - verifiedAt < supervisorIntervalMs
                } == true
            ) {
                // Inbound messages are connection-health evidence, but publishing a full session
                // snapshot for every chat packet needlessly invalidates unrelated UI state.
                it
            } else {
                it.copy(
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = nowMillis,
                )
            }
        }
    }

    internal fun isCurrentAuthenticatedSession(account: BcAccount, accountGeneration: Long): Boolean {
        val current = stateHolder.current
        return !closed.get() && current.desiredOnline && current.account == account &&
            current.relationships.accountGeneration == accountGeneration &&
            current.state.isAuthenticated
    }

    fun cancelReconnect() {
        val jobToCancel = synchronized(reconnectLock) {
            reconnectGeneration += 1
            reconnectJob.also { reconnectJob = null }
        }
        jobToCancel?.cancel()
    }

    fun isVerifiedFresh(): Boolean =
        stateHolder.current.lastVerifiedAtMillis?.let { System.currentTimeMillis() - it <= verifiedFreshMs } == true

    private fun requestReconnectInternal(reason: ReconnectReason) {
        val jobToStart = synchronized(reconnectLock) {
            val snapshot = stateHolder.current
            if (!snapshot.desiredOnline) return

            if (!snapshot.state.canAttemptReconnect()) return

            val server = stateHolder.lastServerConfig
            val login = stateHolder.rememberedLogin
            val accountName = login?.accountName
            val password = login?.password
            if (server == null || accountName.isNullOrBlank() || password == null) {
                markDisconnectedWithoutReconnect("Reconnect credentials are unavailable.")
                return
            }

            if (reconnectJob != null) return

            reconnectGeneration += 1
            val generation = reconnectGeneration

            scope.launch(start = CoroutineStart.LAZY) {
                runReconnectLoop(
                    generation = generation,
                    reason = reason,
                    server = server,
                    accountName = accountName,
                    password = password,
                )
            }.also { job ->
                reconnectJob = job
                job.invokeOnCompletion {
                    synchronized(reconnectLock) {
                        if (reconnectJob === job) reconnectJob = null
                    }
                }
            }
        }

        synchronized(reconnectLock) {
            if (reconnectJob !== jobToStart || jobToStart.isCancelled) return
            setReconnecting(reason = reason, attempt = 1)
        }
        jobToStart.start()
    }

    private suspend fun runReconnectLoop(
        generation: Long,
        reason: ReconnectReason,
        server: BcServerConfig,
        accountName: String,
        password: String,
    ) {
        try {
            gateway.disconnect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A failed teardown does not prevent a new transport attempt.
        }
        if (!isCurrentReconnectGeneration(generation) || !stateHolder.current.desiredOnline) return

        var attempt = 1
        while (isCurrentReconnectGeneration(generation) && stateHolder.current.desiredOnline) {
            setReconnecting(reason, attempt)

            val delayMs = reconnectDelayForAttempt(attempt)
            if (delayMs > 0) delay(delayMs)
            if (!isCurrentReconnectGeneration(generation) || !stateHolder.current.desiredOnline) break

            val result = try {
                gateway.connect(server)
                gateway.login(accountName, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }

            coroutineContext.ensureActive()
            if (!isCurrentReconnectGeneration(generation) || !stateHolder.current.desiredOnline) return

            when (result) {
                is BcSessionLoginResult.Success -> {
                    val account = handleRecoveredLogin(result, accountName, password)
                    coroutineContext.ensureActive()
                    if (!isCurrentReconnectGeneration(generation) || !stateHolder.current.desiredOnline) return
                    touchVerified()
                    autoJoinLastRoomAfterReconnect(account)
                    return
                }

                is BcSessionLoginResult.Failure -> {
                    if (result.isInvalidCredentials()) {
                        handleUnrecoverableLoginFailure(result.message)
                        return
                    }
                    markRecoverableReconnectLoginFailure(result.message)
                }

                null -> Unit
            }

            attempt += 1
        }
    }

    private fun handleConnected() {
        stateHolder.updateSession { current ->
            current.copy(
                state = if (current.state.canAcceptConnectedEvent()) BcSessionState.Connected else current.state,
                connectionHealth = if (current.state.canAcceptConnectedHealth()) {
                    BcConnectionHealth.ConnectedUnverified
                } else {
                    current.connectionHealth
                },
            )
        }
    }

    private fun handleGatewayReconnecting(attempt: Int) {
        if (stateHolder.current.desiredOnline && !isReconnectActive()) {
            setReconnecting(ReconnectReason.NetworkLost, attempt)
        }
    }

    private fun handleServerInfo() {
        val current = stateHolder.current
        if (current.state.isAuthenticated) {
            touchVerified()
        } else if (current.state !is BcSessionState.Reconnecting &&
            current.connectionHealth == BcConnectionHealth.Offline
        ) {
            stateHolder.updateSession { it.copy(connectionHealth = BcConnectionHealth.ConnectedUnverified) }
        }
    }

    private fun handleLoginQueued(position: Int) {
        stateHolder.updateSession {
            it.copy(
                state = BcSessionState.LoginQueued(position),
                connectionHealth = if (it.connectionHealth == BcConnectionHealth.VerifiedOnline) {
                    it.connectionHealth
                } else {
                    BcConnectionHealth.ConnectedUnverified
                },
            )
        }
    }

    private fun handleInboundMessage() {
        val current = stateHolder.current
        if (current.account != null &&
            current.state.isAuthenticated
        ) {
            touchVerified()
        }
    }

    private fun handleDisconnected(reason: String?) {
        val current = stateHolder.current
        if (current.state is BcSessionState.Stopped) return

        markRoomConversationDisconnected()

        if (current.state is BcSessionState.Blocked) {
            stateHolder.updateSession {
                it.withoutPrivateAccountState().copy(
                    connectionHealth = BcConnectionHealth.Offline,
                    currentRoom = null,
                    roomMembers = emptyList(),
                    roomCharacters = emptyList(),
                )
            }
            return
        }

        if (!current.desiredOnline) {
            stateHolder.updateSession {
                it.withoutPrivateAccountState().copy(
                    connectionHealth = BcConnectionHealth.Offline,
                    currentRoom = null,
                    roomMembers = emptyList(),
                    roomCharacters = emptyList(),
                )
            }
            return
        }

        markTransportSuspect(ReconnectReason.NetworkLost, reason ?: "Disconnected from BC.")
    }

    private fun handleForceDisconnected(reason: BcSessionForceDisconnectReason) {
        cancelReconnect()
        stateHolder.forgetLoginPassword()
        markRoomConversationDisconnected()
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = when (reason) {
                    BcSessionForceDisconnectReason.DuplicatedLogin -> BcSessionState.Blocked(BlockedReason.DuplicatedLogin)
                    BcSessionForceDisconnectReason.RateLimited -> BcSessionState.Blocked(BlockedReason.RateLimited)
                    BcSessionForceDisconnectReason.Unknown -> BcSessionState.Blocked(BlockedReason.ServerMaintenance)
                },
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
                desiredOnline = false,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
                relationships = it.relationships.nextUnloaded(),
            )
        }
    }

    private fun setReconnecting(reason: ReconnectReason, attempt: Int) {
        markRoomConversationDisconnected()
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = BcSessionState.Reconnecting(reason, attempt),
                connectionHealth = BcConnectionHealth.Offline,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            )
        }
    }

    private fun markTransportSuspect(reason: ReconnectReason, message: String?) {
        val current = stateHolder.current
        if (current.state is BcSessionState.Stopped ||
            current.state is BcSessionState.Blocked
        ) return

        if (isReconnectActive()) return

        if (!current.desiredOnline) {
            markDisconnectedWithoutReconnect(message ?: "BC connection is unavailable.")
            return
        }

        if (!hasReconnectCredentials()) {
            markDisconnectedWithoutReconnect(message ?: "Reconnect credentials are unavailable.")
            return
        }

        requestReconnect(reason)
    }

    private fun reevaluateConnectionHealth(nowMillis: Long) {
        val current = stateHolder.current
        if (!current.desiredOnline ||
            isReconnectActive() ||
            current.state is BcSessionState.Reconnecting
        ) {
            return
        }

        val lastVerified = current.lastVerifiedAtMillis ?: return
        val ageMillis = nowMillis - lastVerified
        when {
            ageMillis >= reconnectAfterStaleMs -> {
                markStale()
                requestReconnect(ReconnectReason.SilentDisconnect)
            }

            ageMillis >= verifiedFreshMs -> markStale()
        }
    }

    private suspend fun probeAuthenticatedSession() {
        if (!shouldProbeAuthenticatedSession()) return
        val started = stateHolder.current
        val account = started.account ?: return
        val accountGeneration = started.relationships.accountGeneration

        runCatching {
            gateway.probeAuthenticatedSession()
        }.onSuccess {
            touchVerified(account, accountGeneration)
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            if (isCurrentAuthenticatedSession(account, accountGeneration)) {
                onOperationFailure(ReconnectReason.OperationFailed, throwable)
            }
        }
    }

    private fun shouldProbeAuthenticatedSession(): Boolean {
        val current = stateHolder.current
        if (!current.desiredOnline || current.account == null) return false
        if (isReconnectActive()) return false
        if (!current.state.isAuthenticated) return false

        return current.connectionHealth == BcConnectionHealth.VerifiedOnline ||
            current.connectionHealth == BcConnectionHealth.ConnectedUnverified
    }

    private fun markStale() {
        val current = stateHolder.current
        if (current.state is BcSessionState.Stopped ||
            current.state is BcSessionState.Blocked ||
            current.state is BcSessionState.Reconnecting
        ) {
            return
        }

        markRoomConversationDisconnected()
        stateHolder.updateSession {
            val fallbackState = if (
                it.state is BcSessionState.JoiningRoom ||
                it.state is BcSessionState.CreatingRoom ||
                it.state is BcSessionState.UpdatingRoom ||
                it.state is BcSessionState.ManagingRoomMember ||
                it.state is BcSessionState.SearchingRooms
            ) {
                if (it.account != null) BcSessionState.Ready else it.state
            } else {
                it.state
            }
            it.copy(
                state = fallbackState,
                connectionHealth = BcConnectionHealth.Stale,
                currentRoom = null,
                gatewayRoomPresenceEpoch = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            )
        }
    }

    private fun markDisconnectedWithoutReconnect(message: String) {
        markRoomConversationDisconnected()
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = BcSessionState.Error(BcError.Network(message), recoverable = false),
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
                currentRoom = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            )
        }
    }

    private fun markRecoverableReconnectLoginFailure(message: String) {
        stateHolder.updateSession {
            it.withoutPrivateAccountState().copy(
                state = if (it.state is BcSessionState.Reconnecting) {
                    it.state
                } else {
                    BcSessionState.Error(BcError.Network(message), recoverable = true)
                },
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
            )
        }
    }

    private fun hasReconnectCredentials(): Boolean {
        val login = stateHolder.rememberedLogin
        return stateHolder.lastServerConfig != null && !login?.accountName.isNullOrBlank() && login?.password != null
    }

    private fun BcSessionSnapshot.withoutPrivateAccountState(): BcSessionSnapshot = copy(
        selfRuleLog = null,
        selfRuleLogSessionId = null,
        privateChatState = null,
        wardrobeState = null,
        whisperTargetResetRevision = 0L,
        serverTimeAnchor = null,
        gatewayRoomPresenceEpoch = null,
    )

    private fun isReconnectActive(): Boolean = synchronized(reconnectLock) {
        reconnectJob != null
    }

    private fun isCurrentReconnectGeneration(generation: Long): Boolean = synchronized(reconnectLock) {
        reconnectGeneration == generation
    }

    private fun BcServerConfig.withNormalizedVersion(): BcServerConfig {
        require(version.isNotBlank()) { "BC version is required to connect." }
        return copy(version = version.normalizedBcVersion())
    }

    private fun BcSessionState.canAcceptConnectedEvent(): Boolean = this is BcSessionState.Connecting ||
        this == BcSessionState.Idle

    private fun BcSessionState.canAcceptConnectedHealth(): Boolean = this is BcSessionState.Connecting ||
        this is BcSessionState.Reconnecting ||
        this == BcSessionState.Idle ||
        this == BcSessionState.Connected

    private fun BcSessionState.canAttemptReconnect(): Boolean = when (this) {
        BcSessionState.Idle,
        BcSessionState.Stopped,
        is BcSessionState.Blocked,
        -> false

        is BcSessionState.Error -> recoverable

        BcSessionState.Connected,
        is BcSessionState.Connecting,
        BcSessionState.LoggingIn,
        is BcSessionState.LoginQueued,
        BcSessionState.Ready,
        is BcSessionState.SearchingRooms,
        is BcSessionState.JoiningRoom,
        is BcSessionState.CreatingRoom,
        is BcSessionState.UpdatingRoom,
        is BcSessionState.ManagingRoomMember,
        is BcSessionState.Reconnecting,
        -> true
    }

    private fun reconnectDelayForAttempt(attempt: Int): Long {
        val delays = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
        return delays.getOrElse(attempt - 1) { 60_000L }
    }
}
