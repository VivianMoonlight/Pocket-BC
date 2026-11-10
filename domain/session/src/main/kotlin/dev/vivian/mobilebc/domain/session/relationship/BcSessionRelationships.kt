package dev.vivian.mobilebc.domain.session.relationship

import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind

interface BcSessionRelationships {
    suspend fun queryRelationshipCapabilities(
        memberNumber: Int,
    ): BcRelationshipCapabilityQueryResult

    suspend fun submitRelationshipContractAction(
        memberNumber: Int,
        kind: BcRelationshipContractKind,
        action: BcRelationshipContractAction,
        destructiveActionConfirmed: Boolean = false,
    ): BcRelationshipContractActionResult

    suspend fun updateRelationshipList(
        memberNumber: Int,
        kind: BcRelationshipListKind,
        enabled: Boolean,
    ): BcRelationshipMutationResult

    fun recordFriendRequest(senderMemberNumber: Int, receivedAtMillis: Long)
    suspend fun acceptFriendRequest(senderMemberNumber: Int): BcRelationshipMutationResult
    fun dismissFriendRequest(senderMemberNumber: Int)
}

sealed interface BcRelationshipCapabilityQueryResult {
    data class Loaded(
        val capabilities: dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities,
    ) : BcRelationshipCapabilityQueryResult

    data class Partial(
        val capabilities: dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities,
    ) : BcRelationshipCapabilityQueryResult

    /** Silence cannot distinguish unavailable options from a delayed response. */
    data object Unanswered : BcRelationshipCapabilityQueryResult

    data class Rejected(
        val reason: BcRelationshipMutationRejection,
    ) : BcRelationshipCapabilityQueryResult

    data class Failed(val message: String) : BcRelationshipCapabilityQueryResult
}

sealed interface BcRelationshipContractActionResult {
    data object Submitted : BcRelationshipContractActionResult
    data class Rejected(
        val reason: BcRelationshipMutationRejection,
    ) : BcRelationshipContractActionResult
    data class Failed(val message: String) : BcRelationshipContractActionResult
}

sealed interface BcRelationshipMutationResult {
    data object Submitted : BcRelationshipMutationResult
    data object Unchanged : BcRelationshipMutationResult
    data class Rejected(val reason: BcRelationshipMutationRejection) : BcRelationshipMutationResult
    data class Failed(val message: String) : BcRelationshipMutationResult
}

enum class BcRelationshipMutationRejection {
    NotVerifiedOnline,
    RelationshipsNotLoaded,
    InvalidMemberNumber,
    CannotTargetSelf,
    AccountChanged,
    FriendRequestNotFound,
    CapabilitiesMissingOrStale,
    ActionNotAllowed,
    DestructiveConfirmationRequired,
    OperationPending,
    EconomyUnavailable,
    InsufficientMoney,
}
