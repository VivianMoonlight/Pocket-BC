package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomRemovalReason
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcSelfAppearanceUpdateCommand
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.core.model.BcServerConfig
import kotlinx.coroutines.flow.Flow

interface SessionConnectionGateway {
    val events: Flow<BcSessionGatewayEvent>

    suspend fun connect(config: BcServerConfig)
    suspend fun login(accountName: String, password: String): BcSessionLoginResult
    suspend fun probeAuthenticatedSession()
    suspend fun queryOnlineFriendConversations(): List<BcConversation>
    suspend fun flushAccountUpdates()
    suspend fun disconnect()
    fun isProbablyConnected(): Boolean
}

interface RoomGateway {
    suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary>
    suspend fun createRoom(command: BcRoomCreateCommand): BcRoom =
        throw UnsupportedOperationException("Room creation is not supported by this gateway.")
    suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom =
        throw UnsupportedOperationException("Room updates are not supported by this gateway.")
    suspend fun manageRoomMember(command: BcRoomMemberAdminCommand) {
        throw UnsupportedOperationException("Room member administration is not supported by this gateway.")
    }
    suspend fun joinRoom(roomName: String, password: String?)
    suspend fun leaveRoom()
    /** Preflight only; queued messages must be checked again at their actual emission. */
    suspend fun validateRoomChat(kind: BcMessageKind, text: String, targetMemberNumber: Int? = null) = Unit
}

interface InteractionGateway {
    suspend fun queryAllowItem(memberNumber: Int): Boolean
    suspend fun sendActivity(command: BcActivityCommand)
    suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand)
    /** Queued transports must run this check immediately before emitting the item update. */
    suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand, expectedCharacters: List<BcCharacterSnapshot>? = null, validateBeforeEmit: () -> Unit) {
        validateBeforeEmit()
        updateCharacterItem(command)
    }
    suspend fun updatePose(command: BcPoseUpdateCommand) {
        throw UnsupportedOperationException("Pose updates are not supported by this gateway.")
    }
    /** Queued transports must run this check immediately before emitting the packet. */
    suspend fun updatePose(command: BcPoseUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit) {
        validateBeforeEmit()
        updatePose(command)
    }
    suspend fun updateExpression(command: BcExpressionUpdateCommand) {
        throw UnsupportedOperationException("Expression updates are not supported by this gateway.")
    }
    /** Queued transports must run this check immediately before emitting the packet. */
    suspend fun updateExpression(command: BcExpressionUpdateCommand, expectedSelfState: BcSelfStateEmitExpectation, validateBeforeEmit: () -> Unit) {
        validateBeforeEmit()
        updateExpression(command)
    }
    suspend fun saveWardrobe(
        request: BcWardrobeSaveRequest,
        appearance: List<BcWardrobeItem>,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ): BcWardrobeState = throw UnsupportedOperationException("Wardrobe saving is not supported by this gateway.")

    suspend fun renameWardrobe(
        request: BcWardrobeRenameRequest,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ): BcWardrobeState = throw UnsupportedOperationException("Wardrobe naming is not supported by this gateway.")

    suspend fun restoreSelfAppearance(
        command: BcSelfAppearanceUpdateCommand,
        expectedSelfState: BcSelfStateEmitExpectation,
        validateBeforeEmit: () -> Unit,
    ) {
        throw UnsupportedOperationException("Wardrobe restoration is not supported by this gateway.")
    }
    suspend fun publishEchoCompatibility(command: BcEchoCompatibilityCommand) {
        throw UnsupportedOperationException("Echo compatibility messages are not supported by this gateway.")
    }
    suspend fun publishEchoDrawOrder(command: BcEchoDrawOrderCommand) {
        throw UnsupportedOperationException("Echo draw-order messages are not supported by this gateway.")
    }
}

interface RelationshipGateway {
    suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate)

    /** Null means no option was observed; native queries may remain silent. */
    suspend fun queryOwnershipCapability(memberNumber: Int): String? =
        throw UnsupportedOperationException("Ownership capability queries are not supported by this gateway.")

    suspend fun queryLovershipCapability(memberNumber: Int): String? =
        throw UnsupportedOperationException("Lovership capability queries are not supported by this gateway.")

    suspend fun sendRelationshipContractAction(command: BcRelationshipContractCommand) {
        throw UnsupportedOperationException("Relationship contract actions are not supported by this gateway.")
    }

    suspend fun applyRelationshipEconomyEffect(
        effect: BcRelationshipEconomyEffect,
    ): BcRelationshipEconomyState {
        throw UnsupportedOperationException("Relationship economy effects are not supported by this gateway.")
    }
}

data class BcRelationshipListsUpdate(
    val friends: Set<Int>,
    val friendNames: Map<Int, String>,
    val ignored: Set<Int>,
    val whitelist: Set<Int>,
    val blacklist: Set<Int>,
    val submissives: Set<Int>,
)

interface SessionGateway : SessionConnectionGateway, RoomGateway, InteractionGateway, RelationshipGateway

sealed interface BcSessionLoginResult {
    data class Success(
        val memberNumber: Int,
        val name: String,
        val relationships: BcSelfRelations = BcSelfRelations(),
        val selfRuleLog: List<BcOwnerRuleLogRecord>? = null,
        val selfRuleLogSessionId: String? = null,
        val privateChatState: BcPrivateChatState? = null,
        val relationshipRevision: Long = 0L,
        val relationshipCapabilities: Map<Int, BcRelationshipCapabilities> = emptyMap(),
        val relationshipOwnershipClearRevision: Long? = null,
        val wardrobeState: BcWardrobeState? = null,
    ) : BcSessionLoginResult

    data class Failure(
        val message: String,
        val kind: BcSessionLoginFailureKind = classifyLoginFailureMessage(message),
    ) : BcSessionLoginResult
}

enum class BcSessionLoginFailureKind {
    InvalidCredentials,
    Transient,
    Unknown,
}

fun classifyLoginFailureMessage(message: String): BcSessionLoginFailureKind {
    val normalized = message.trim().lowercase()
    if (normalized.isBlank()) return BcSessionLoginFailureKind.Unknown

    val transientMarkers = listOf(
        "timed out",
        "timeout",
        "time out",
        "disconnected",
        "disconnect",
        "socket",
        "connect error",
        "connection",
        "network",
        "unavailable",
        "closed",
        "cancelled",
        "canceled",
        "pending",
    )
    if (transientMarkers.any { it in normalized }) {
        return BcSessionLoginFailureKind.Transient
    }

    val invalidCredentialMarkers = listOf(
        "invalid credential",
        "invalid login",
        "invalid account",
        "invalid username",
        "invalid password",
        "wrong password",
        "incorrect password",
        "bad password",
        "password is wrong",
        "account name or password",
        "username or password",
        "login failed",
        "authentication failed",
        "auth failed",
        "not found",
        "does not exist",
    )
    return if (invalidCredentialMarkers.any { it in normalized }) {
        BcSessionLoginFailureKind.InvalidCredentials
    } else {
        BcSessionLoginFailureKind.Unknown
    }
}

fun BcSessionLoginResult.Failure.isInvalidCredentials(): Boolean =
    kind == BcSessionLoginFailureKind.InvalidCredentials

sealed interface BcSessionGatewayEvent {
    data object Connected : BcSessionGatewayEvent
    data class Disconnected(val reason: String?) : BcSessionGatewayEvent
    data class Reconnecting(val attempt: Int) : BcSessionGatewayEvent
    data class ForceDisconnected(val reason: BcSessionForceDisconnectReason) : BcSessionGatewayEvent
    data class ServerInfo(
        val onlinePlayers: Int,
        val serverTime: Long,
        val receivedAtMonotonicNanos: Long? = null,
    ) : BcSessionGatewayEvent
    data class LoginQueued(val position: Int) : BcSessionGatewayEvent
    data class RelationshipUpdated(
        val update: BcRelationshipGatewayUpdate,
        val accountMemberNumber: Int? = null,
        val selfRuleLogSessionId: String? = null,
        val revision: Long? = null,
    ) : BcSessionGatewayEvent
    data class RoomJoined(
        val room: BcRoom,
        val members: List<BcMember>,
        val characters: List<BcCharacterSnapshot> = emptyList(),
        val accountMemberNumber: Int? = null,
        val selfRuleLogSessionId: String? = null,
        val roomPresenceEpoch: Long? = null,
    ) : BcSessionGatewayEvent

    data class RoomUpdated(
        val room: BcRoom,
        val members: List<BcMember>,
        val characters: List<BcCharacterSnapshot> = emptyList(),
        val accountMemberNumber: Int? = null,
        val selfRuleLogSessionId: String? = null,
        val roomPresenceEpoch: Long? = null,
        /** Rebinds the retained room after a rejected join; ordinary property updates leave this null. */
        val previousRoomPresenceEpoch: Long? = null,
        val previousRoomName: BcRoomName? = null,
    ) : BcSessionGatewayEvent
    data class RoomPresenceReset(
        val accountMemberNumber: Int? = null,
        val selfRuleLogSessionId: String? = null,
        val roomPresenceEpoch: Long? = null,
    ) : BcSessionGatewayEvent
    data class RoomRemoved(
        val roomName: BcRoomName,
        val reason: BcRoomRemovalReason,
        val accountMemberNumber: Int,
        val selfRuleLogSessionId: String,
        val previousRoomPresenceEpoch: Long,
        val roomPresenceEpoch: Long,
    ) : BcSessionGatewayEvent
    data object RoomLeft : BcSessionGatewayEvent
    data object InboundMessage : BcSessionGatewayEvent
    data class SelfRuleLogInvalidated(
        val ownerMemberNumber: Int,
        val selfRuleLogSessionId: String,
    ) : BcSessionGatewayEvent
    data class SelfPrivateChatStateUpdated(
        val ownerMemberNumber: Int,
        val selfRuleLogSessionId: String,
        val state: BcPrivateChatState,
        val resetWhisperTarget: Boolean = false,
    ) : BcSessionGatewayEvent
    data class SelfWardrobeStateUpdated(
        val ownerMemberNumber: Int,
        val selfRuleLogSessionId: String,
        val state: BcWardrobeState,
    ) : BcSessionGatewayEvent
}

sealed interface BcRelationshipGatewayUpdate {
    data class Ownership(
        val memberNumber: Int?,
        val result: String?,
        val ownerName: String?,
        val ownership: BcOwnership?,
        val clearOwnership: Boolean,
    ) : BcRelationshipGatewayUpdate

    data class Lovership(
        val memberNumber: Int?,
        val result: String?,
        val loverships: List<BcLovership>?,
    ) : BcRelationshipGatewayUpdate
}

enum class BcSessionForceDisconnectReason {
    DuplicatedLogin,
    RateLimited,
    Unknown,
}
