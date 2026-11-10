package dev.vivian.mobilebc.core.model

/** Changes selected in the authenticated character's pose and expression menu. */
sealed interface BcSelfStateChange {
    data class Pose(val name: String) : BcSelfStateChange
    data object ResetPose : BcSelfStateChange
    data class Expression(val group: String, val name: String?) : BcSelfStateChange
    data object ResetExpressions : BcSelfStateChange
}

/** A private account Log entry; a null expiry means the rule has no expiry. */
data class BcOwnerRuleLogRecord(
    val name: String,
    val group: String,
    val expiresAtMillis: Long? = null,
)

/** Source state used to build a self update, checked against the live socket roster at emission. */
data class BcSelfStateEmitExpectation(
    val character: BcCharacterSnapshot,
    val ownerPresent: Boolean,
    /** Paired/reset expression echoes may change only these Expression fields while queued. */
    val editedExpressionGroups: Set<String> = emptySet(),
    /** Identifies the login whose private state produced the command, including same-account relogins. */
    val selfRuleLogSessionId: String? = null,
)

enum class BcSelfStateUnavailableReason {
    Offline,
    NotInRoom,
    SelfUnavailable,
    AccountChanged,
    RoomChanged,
    CharacterStateChanged,
    UnsupportedAssetFamily,
    CatalogPreparing,
    InvalidPose,
    PoseRestrictedByItem,
    PoseBlockedByOwner,
    OwnerRulesUnavailable,
    InvalidExpression,
    ExpressionItemMissing,
    ExpressionRestrictedByItem,
    ExpressionPrerequisiteNotMet,
    UnsupportedExpressionPrerequisite,
}

sealed interface BcSelfStateResult {
    data object Sent : BcSelfStateResult
    data class Rejected(val reason: BcSelfStateUnavailableReason) : BcSelfStateResult
    data class Failed(val reason: String) : BcSelfStateResult
}
