package dev.vivian.mobilebc.domain.session.auth

import dev.vivian.mobilebc.core.model.AuthErrorReason
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.SessionConnectionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.isInvalidCredentials
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.acceptsRelationshipEvent
import dev.vivian.mobilebc.domain.session.core.withGatewayUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class BcSessionAuthController(
    private val stateHolder: BcSessionRuntimeState,
    private val gateway: SessionConnectionGateway,
    private val cancelReconnectJob: () -> Unit,
    private val upsertFriendConversations: (BcRelationshipSnapshot) -> Unit,
    private val upsertLoginConversation: (String) -> Unit,
    private val refreshOnlineFriends: suspend () -> Unit,
    private val emitRoomTargetChanged: suspend (String?) -> Unit,
    private val monotonicNowNanos: () -> Long = System::nanoTime,
    private val onRelationshipsLoaded: (BcRelationshipSnapshot) -> Unit = {},
) : BcSessionAuth {
    private val loginLock = Any()
    private var loginRequestGeneration = 0L
    private val invalidatedSelfRuleLogSessions = mutableSetOf<Pair<Int, String>>()
    private val pendingPrivateChatStates = mutableMapOf<Pair<Int, String>, BcPrivateChatState>()
    private val pendingWardrobeStates = mutableMapOf<Pair<Int, String>, BcWardrobeState>()
    private val pendingWhisperTargetResets = mutableMapOf<Pair<Int, String>, Long>()
    private val pendingRelationshipUpdates = mutableMapOf<Pair<Int, String>, MutableMap<Long, PendingRelationshipUpdate>>()

    fun handleGatewayEvent(event: BcSessionGatewayEvent): Unit = synchronized(loginLock) {
        when (event) {
            is BcSessionGatewayEvent.ServerInfo -> {
                if (stateHolder.current.desiredOnline) {
                    val receivedAtNanos = event.receivedAtMonotonicNanos ?: monotonicNowNanos()
                    stateHolder.updateSession {
                        it.copy(serverTimeAnchor = BcServerTimeAnchor(event.serverTime, receivedAtNanos))
                    }
                }
            }
            is BcSessionGatewayEvent.SelfRuleLogInvalidated -> {
                val current = stateHolder.current
                if (current.desiredOnline && (
                        current.state == BcSessionState.LoggingIn ||
                            current.state is BcSessionState.LoginQueued ||
                            current.state is BcSessionState.Reconnecting
                        )
                ) {
                    // A Hidden update can arrive before the login coroutine publishes its result.
                    invalidatedSelfRuleLogSessions += event.ownerMemberNumber to event.selfRuleLogSessionId
                }
                stateHolder.updateSession {
                    if (it.account?.memberNumber == event.ownerMemberNumber &&
                        it.selfRuleLogSessionId == event.selfRuleLogSessionId
                    ) {
                        it.copy(selfRuleLog = null, privateChatState = it.privateChatState?.copy(ruleLog = null))
                    } else {
                        it
                    }
                }
            }
            is BcSessionGatewayEvent.SelfPrivateChatStateUpdated -> {
                val current = stateHolder.current
                val key = event.ownerMemberNumber to event.selfRuleLogSessionId
                if (current.desiredOnline && (
                        current.state == BcSessionState.LoggingIn ||
                            current.state is BcSessionState.LoginQueued || current.state is BcSessionState.Reconnecting
                        ) && event.state.revision >= 0L
                ) {
                    val previous = pendingPrivateChatStates[key]
                    if (previous == null || event.state.revision > previous.revision) {
                        pendingPrivateChatStates[key] = event.state
                        invalidatedSelfRuleLogSessions.remove(key)
                    }
                    if (event.resetWhisperTarget) {
                        pendingWhisperTargetResets[key] = maxOf(pendingWhisperTargetResets[key] ?: 0L, event.state.revision)
                    }
                }
                stateHolder.updateSession {
                    if (it.desiredOnline && it.account?.memberNumber == event.ownerMemberNumber &&
                        it.selfRuleLogSessionId == event.selfRuleLogSessionId &&
                        event.state.revision >= 0L
                    ) {
                        val updated = if (event.state.revision > (it.privateChatState?.revision ?: -1L)) {
                            it.copy(privateChatState = event.state, selfRuleLog = event.state.ruleLog)
                        } else it
                        if (event.resetWhisperTarget && event.state.revision > updated.whisperTargetResetRevision) {
                            updated.copy(whisperTargetResetRevision = event.state.revision)
                        } else updated
                    } else {
                        it
                    }
                }
            }
            is BcSessionGatewayEvent.SelfWardrobeStateUpdated -> {
                val memberNumber = event.ownerMemberNumber
                val sessionId = event.selfRuleLogSessionId
                if (!event.state.matchesLogin(memberNumber, sessionId)) return@synchronized
                val current = stateHolder.current
                if (current.desiredOnline && (
                        current.state == BcSessionState.LoggingIn || current.state is BcSessionState.LoginQueued ||
                            current.state is BcSessionState.Reconnecting
                        )
                ) {
                    val key = memberNumber to sessionId
                    val previous = pendingWardrobeStates[key]
                    if (previous == null || event.state.revision > previous.revision) {
                        pendingWardrobeStates[key] = event.state
                    }
                }
                stateHolder.updateSession {
                    if (it.desiredOnline && it.account?.memberNumber == memberNumber &&
                        it.selfRuleLogSessionId == sessionId &&
                        event.state.revision > (it.wardrobeState?.revision ?: -1L)
                    ) it.copy(wardrobeState = event.state) else it
                }
            }
            is BcSessionGatewayEvent.RelationshipUpdated -> {
                val memberNumber = event.accountMemberNumber
                val sessionId = event.selfRuleLogSessionId?.takeIf(String::isNotBlank)
                val revision = event.revision
                val update = event.update as? BcRelationshipGatewayUpdate.Ownership
                val current = stateHolder.current
                if (current.desiredOnline && (
                        current.state == BcSessionState.LoggingIn ||
                            current.state is BcSessionState.LoginQueued || current.state is BcSessionState.Reconnecting
                        ) && memberNumber != null && memberNumber > 0 && sessionId != null &&
                    revision != null && revision >= 0L
                ) {
                    pendingRelationshipUpdates.getOrPut(memberNumber to sessionId) { mutableMapOf() }
                        .putIfAbsent(revision, PendingRelationshipUpdate(event, System.currentTimeMillis()))
                }
                // The gateway keeps canonical logs and self ownership synchronized, including
                // native ClearOwnership's complete OwnerRule-group deletion.
                if (update != null &&
                    (update.clearOwnership || update.ownership != null || update.ownerName != null)
                ) {
                    stateHolder.updateSession {
                        if (it.privateChatState == null && it.acceptsRelationshipEvent(event) &&
                            (revision == null || revision > it.relationships.revision)
                        ) it.copy(selfRuleLog = null) else it
                    }
                }
            }
            is BcSessionGatewayEvent.Disconnected,
            is BcSessionGatewayEvent.ForceDisconnected,
            -> {
                invalidatedSelfRuleLogSessions.clear()
                pendingPrivateChatStates.clear()
                pendingWardrobeStates.clear()
                pendingWhisperTargetResets.clear()
                pendingRelationshipUpdates.clear()
                stateHolder.updateSession {
                    it.copy(
                        selfRuleLog = null,
                        selfRuleLogSessionId = null,
                        privateChatState = null,
                        wardrobeState = null,
                        whisperTargetResetRevision = 0L,
                        serverTimeAnchor = null,
                        gatewayRoomPresenceEpoch = null,
                    )
                }
            }
            else -> Unit
        }
    }

    override suspend fun login(accountName: String, password: String) {
        coroutineContext.ensureActive()
        val attempt = synchronized(loginLock) {
            val current = stateHolder.current
            check(current.desiredOnline) { "Connect to BC before logging in." }
            invalidatedSelfRuleLogSessions.clear()
            pendingPrivateChatStates.clear()
            pendingWardrobeStates.clear()
            pendingWhisperTargetResets.clear()
            pendingRelationshipUpdates.clear()
            LoginAttempt(++loginRequestGeneration, current.relationships.accountGeneration).also { attempt ->
                updateLoginSession(attempt, starting = true) {
                    it.copy(
                        state = BcSessionState.LoggingIn,
                        selfRuleLog = null,
                        selfRuleLogSessionId = null,
                        privateChatState = null,
                        wardrobeState = null,
                        whisperTargetResetRevision = 0L,
                        gatewayRoomPresenceEpoch = null,
                    )
                }
            }
        }
        when (val result = gateway.login(accountName, password)) {
            is BcSessionLoginResult.Success -> completeLogin(result, accountName, password, attempt)
            is BcSessionLoginResult.Failure -> {
                coroutineContext.ensureActive()
                if (result.isInvalidCredentials()) {
                    handleUnrecoverableLoginFailure(attempt)
                } else {
                    handleRecoverableLoginFailure(result.message, attempt)
                }
            }
        }
    }

    internal suspend fun handleRecoveredLogin(
        result: BcSessionLoginResult.Success,
        accountName: String,
        password: String,
    ): BcAccount = completeLogin(result, accountName, password, recoveryAttempt())

    internal suspend fun handleRecoveredLoginFailure() {
        handleUnrecoverableLoginFailure(recoveryAttempt())
    }

    private suspend fun completeLogin(
        result: BcSessionLoginResult.Success,
        accountName: String,
        password: String,
        attempt: LoginAttempt,
    ): BcAccount {
        coroutineContext.ensureActive()
        val account = BcAccount(
            memberNumber = result.memberNumber,
            displayName = result.name,
            accountName = accountName,
        )
        val verifiedAtMillis = System.currentTimeMillis()
        synchronized(loginLock) {
            val key = result.selfRuleLogSessionId?.let { result.memberNumber to it }
            val pendingPrivateChat = key?.let(pendingPrivateChatStates::get)
            val privateChat = (pendingPrivateChat?.takeIf {
                it.revision > (result.privateChatState?.revision ?: -1L)
            } ?: result.privateChatState)?.let {
                if (key != null && key in invalidatedSelfRuleLogSessions) it.copy(ruleLog = null) else it
            }
            val loginWardrobe = result.wardrobeState?.takeIf {
                it.matchesLogin(result.memberNumber, result.selfRuleLogSessionId)
            }
            val pendingWardrobe = key?.let(pendingWardrobeStates::get)
            val wardrobe = pendingWardrobe?.takeIf { it.revision > (loginWardrobe?.revision ?: -1L) } ?: loginWardrobe
            val pendingOwnershipChanged = key?.let(pendingRelationshipUpdates::get)?.any { (revision, pending) ->
                val update = pending.event.update as? BcRelationshipGatewayUpdate.Ownership
                revision > result.relationshipRevision && update != null &&
                    (update.clearOwnership || update.ownership != null || update.ownerName != null)
            } == true
            updateLoginSession(attempt) {
                var relationships = it.relationships.nextLoaded(
                    memberNumber = result.memberNumber,
                    relations = result.relationships,
                    loginSessionId = result.selfRuleLogSessionId,
                    capabilitiesByMember = result.relationshipCapabilities,
                    revision = result.relationshipRevision,
                    ownershipClearRevision = result.relationshipOwnershipClearRevision,
                )
                key?.let(pendingRelationshipUpdates::get)?.toSortedMap()?.forEach { (revision, pending) ->
                    if (revision > relationships.revision) {
                        relationships = relationships.withGatewayUpdate(pending.event.update, pending.receivedAtMillis, revision)
                            .copy(revision = revision)
                    }
                }
                it.copy(
                    state = BcSessionState.Ready,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    lastVerifiedAtMillis = verifiedAtMillis,
                    account = account,
                    currentRoom = null,
                    gatewayRoomPresenceEpoch = null,
                    roomMembers = emptyList(),
                    roomCharacters = emptyList(),
                    roomPresenceEpoch = it.roomPresenceEpoch + if (it.currentRoom != null) 1L else 0L,
                    selfRuleLog = if (privateChat != null) privateChat.ruleLog else result.selfRuleLog.takeUnless {
                        pendingOwnershipChanged || result.selfRuleLogSessionId?.let { sessionId ->
                            (result.memberNumber to sessionId) in invalidatedSelfRuleLogSessions
                        } == true
                    },
                    selfRuleLogSessionId = result.selfRuleLogSessionId,
                    privateChatState = privateChat,
                    wardrobeState = wardrobe,
                    whisperTargetResetRevision = key?.let(pendingWhisperTargetResets::get) ?: 0L,
                    relationships = relationships,
                )
            }
            invalidatedSelfRuleLogSessions.clear()
            pendingPrivateChatStates.clear()
            pendingWardrobeStates.clear()
            pendingWhisperTargetResets.clear()
            pendingRelationshipUpdates.clear()
            stateHolder.rememberLogin(accountName, password)
            val loadedRelationships = stateHolder.current.relationships
            upsertFriendConversations(loadedRelationships)
            onRelationshipsLoaded(loadedRelationships)
            if (!attempt.recovering) cancelReconnectJob()
            upsertLoginConversation(account.displayName)
        }
        refreshOnlineFriends()
        return account
    }

    override suspend fun logout() {
        synchronized(loginLock) {
            loginRequestGeneration += 1
            invalidatedSelfRuleLogSessions.clear()
            pendingPrivateChatStates.clear()
            pendingWardrobeStates.clear()
            pendingWhisperTargetResets.clear()
            pendingRelationshipUpdates.clear()
            stateHolder.updateSession {
                it.copy(
                    selfRuleLog = null,
                    selfRuleLogSessionId = null,
                    privateChatState = null,
                    wardrobeState = null,
                    whisperTargetResetRevision = 0L,
                    gatewayRoomPresenceEpoch = null,
                )
            }
        }
        runCatching { gateway.flushAccountUpdates() }
        emitRoomTargetChanged(null)
        stateHolder.updateSession {
            it.copy(
                state = BcSessionState.Stopped,
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
                desiredOnline = false,
                account = null,
                currentRoom = null,
                gatewayRoomPresenceEpoch = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
                relationships = it.relationships.nextUnloaded(),
                selfRuleLog = null,
                selfRuleLogSessionId = null,
                privateChatState = null,
                wardrobeState = null,
                whisperTargetResetRevision = 0L,
                serverTimeAnchor = null,
            )
        }
        runCatching { gateway.disconnect() }
        stateHolder.rememberLogin(null, null)
        cancelReconnectJob()
        stateHolder.rememberRoomTarget(null, null)
    }

    private suspend fun handleRecoverableLoginFailure(message: String, attempt: LoginAttempt) {
        updateLoginSession(attempt) {
            it.copy(
                state = BcSessionState.Error(
                    error = BcError.Network(message),
                    recoverable = true,
                ),
                connectionHealth = BcConnectionHealth.Offline,
                lastVerifiedAtMillis = null,
                desiredOnline = false,
                currentRoom = null,
                gatewayRoomPresenceEpoch = null,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
                selfRuleLog = null,
                selfRuleLogSessionId = null,
                privateChatState = null,
                wardrobeState = null,
                whisperTargetResetRevision = 0L,
                serverTimeAnchor = null,
            )
        }
        runCatching { gateway.disconnect() }
    }

    private suspend fun handleUnrecoverableLoginFailure(attempt: LoginAttempt) {
        synchronized(loginLock) {
            updateLoginSession(attempt) {
                it.copy(
                    state = BcSessionState.Error(
                        error = BcError.Auth(AuthErrorReason.InvalidCredentials),
                        recoverable = false,
                    ),
                    connectionHealth = BcConnectionHealth.Offline,
                    lastVerifiedAtMillis = null,
                    desiredOnline = false,
                    account = null,
                    currentRoom = null,
                    gatewayRoomPresenceEpoch = null,
                    roomMembers = emptyList(),
                    roomCharacters = emptyList(),
                    relationships = it.relationships.nextUnloaded(),
                    selfRuleLog = null,
                    selfRuleLogSessionId = null,
                    privateChatState = null,
                    wardrobeState = null,
                    whisperTargetResetRevision = 0L,
                    serverTimeAnchor = null,
                )
            }
            stateHolder.rememberLogin(null, null)
        }
        runCatching { gateway.disconnect() }
    }

    private fun recoveryAttempt(): LoginAttempt = synchronized(loginLock) {
        LoginAttempt(loginRequestGeneration, stateHolder.current.relationships.accountGeneration, recovering = true)
    }

    private fun BcWardrobeState.matchesLogin(memberNumber: Int, sessionId: String?): Boolean =
        memberNumber > 0 && !sessionId.isNullOrBlank() && revision >= 0L &&
            this.memberNumber == memberNumber && selfRuleLogSessionId == sessionId

    private fun updateLoginSession(
        attempt: LoginAttempt,
        starting: Boolean = false,
        transform: (BcSessionSnapshot) -> BcSessionSnapshot,
    ) = synchronized(loginLock) {
        var applied = false
        stateHolder.updateSession { session ->
            val eligiblePhase = when {
                starting -> true
                attempt.recovering -> session.state is BcSessionState.Reconnecting || session.state is BcSessionState.LoginQueued
                else -> session.state == BcSessionState.LoggingIn || session.state is BcSessionState.LoginQueued
            }
            applied = attempt.requestGeneration == loginRequestGeneration &&
                attempt.accountGeneration == session.relationships.accountGeneration &&
                session.desiredOnline && eligiblePhase
            if (applied) transform(session) else session
        }
        if (!applied) throw CancellationException("BC session changed during login.")
    }

    private data class LoginAttempt(
        val requestGeneration: Long,
        val accountGeneration: Long,
        val recovering: Boolean = false,
    )

    private data class PendingRelationshipUpdate(
        val event: BcSessionGatewayEvent.RelationshipUpdated,
        val receivedAtMillis: Long,
    )
}
