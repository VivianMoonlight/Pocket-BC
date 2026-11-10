package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcWardrobeState

sealed interface BcSessionState {
    data object Idle : BcSessionState
    data class Connecting(val attempt: Int) : BcSessionState
    data object Connected : BcSessionState
    data object LoggingIn : BcSessionState
    data class LoginQueued(val position: Int?) : BcSessionState
    data object Ready : BcSessionState
    data class SearchingRooms(val filter: BcRoomSearchFilter) : BcSessionState
    data class JoiningRoom(val roomName: String) : BcSessionState
    data class CreatingRoom(val roomName: String) : BcSessionState
    data class UpdatingRoom(val roomName: String) : BcSessionState
    data class ManagingRoomMember(val roomName: String, val targetMemberNumber: Int) : BcSessionState
    data class Reconnecting(val reason: ReconnectReason, val attempt: Int) : BcSessionState
    data class Blocked(val reason: BlockedReason) : BcSessionState
    data class Error(val error: BcError, val recoverable: Boolean) : BcSessionState
    data object Stopped : BcSessionState
}

sealed interface BcConnectionHealth {
    data object Offline : BcConnectionHealth
    data object ConnectedUnverified : BcConnectionHealth
    data object VerifiedOnline : BcConnectionHealth
    data object Stale : BcConnectionHealth
}

val BcSessionState.isAuthenticated: Boolean
    get() = when (this) {
        BcSessionState.Ready,
        is BcSessionState.SearchingRooms,
        is BcSessionState.JoiningRoom,
        is BcSessionState.CreatingRoom,
        is BcSessionState.UpdatingRoom,
        is BcSessionState.ManagingRoomMember,
        -> true

        BcSessionState.Idle,
        is BcSessionState.Connecting,
        BcSessionState.Connected,
        BcSessionState.LoggingIn,
        is BcSessionState.LoginQueued,
        is BcSessionState.Reconnecting,
        is BcSessionState.Blocked,
        is BcSessionState.Error,
        BcSessionState.Stopped,
        -> false
    }

/**
 * Atomic, read-only picture of the active lightweight session.
 *
 * Connection lifecycle, authentication identity, and room presence used to live in independent
 * StateFlows. Consumers could therefore observe combinations that never represented a real
 * session transition. This snapshot is the single source of truth for that state cluster; one
 * business transition publishes one complete value.
 */
data class BcSessionSnapshot(
    val state: BcSessionState = BcSessionState.Idle,
    val connectionHealth: BcConnectionHealth = BcConnectionHealth.Offline,
    val lastVerifiedAtMillis: Long? = null,
    val desiredOnline: Boolean = false,
    val autoJoinRoomAfterReconnect: Boolean = false,
    /** Normalized version from the user-selected config used by this session connection. */
    val bcVersion: String? = null,
    val account: BcAccount? = null,
    val currentRoom: BcRoom? = null,
    /** Distinguishes room visits, including leaving and rejoining the same named room. */
    val roomPresenceEpoch: Long = 0L,
    /** Opaque visit generation supplied by the gateway for room event fencing. */
    val gatewayRoomPresenceEpoch: Long? = null,
    val roomMembers: List<BcMember> = emptyList(),
    val roomCharacters: List<BcCharacterSnapshot> = emptyList(),
    val relationships: BcRelationshipSnapshot = BcRelationshipSnapshot(),
    /** Null means the account's private rule log is unavailable or has changed since login. */
    val selfRuleLog: List<BcOwnerRuleLogRecord>? = null,
    /** Binds private rule state to one gateway login, including repeated logins as the same account. */
    val selfRuleLogSessionId: String? = null,
    val privateChatState: BcPrivateChatState? = null,
    /** Revision of the latest OwnerRuleWhisperBlock in this private-log login generation. */
    val whisperTargetResetRevision: Long = 0L,
    val serverTimeAnchor: BcServerTimeAnchor? = null,
    val wardrobeState: BcWardrobeState? = null,
) {
    val friendMemberNumbers: Set<Int>
        get() = relationships.self.friends

    val isVerifiedOnline: Boolean
        get() = desiredOnline && connectionHealth == BcConnectionHealth.VerifiedOnline && account != null &&
            state.isAuthenticated
}

data class BcServerTimeAnchor(
    val epochMillis: Long,
    val receivedAtMonotonicNanos: Long,
)

fun BcSessionSnapshot.estimatedServerTimeMillis(nowMonotonicNanos: Long = System.nanoTime()): Long? {
    val anchor = serverTimeAnchor ?: return null
    if (anchor.epochMillis < 0L) return null
    val elapsedNanos = nowMonotonicNanos - anchor.receivedAtMonotonicNanos
    if (elapsedNanos < 0L) return null
    return runCatching { Math.addExact(anchor.epochMillis, elapsedNanos / 1_000_000L) }.getOrNull()
}

enum class ReconnectReason {
    NetworkLost,
    NetworkAvailable,
    SilentDisconnect,
    OperationFailed,
    UserRequested,
}

enum class BlockedReason {
    DuplicatedLogin,
    RateLimited,
    InvalidCredentials,
    ServerMaintenance,
    ManualStop,
}
