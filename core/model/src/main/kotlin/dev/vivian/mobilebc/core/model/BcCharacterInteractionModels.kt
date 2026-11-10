package dev.vivian.mobilebc.core.model

/**
 * A structured BC activity request.
 *
 * This is intentionally protocol-shaped, but still payload-free: the socket layer is responsible
 * for translating it into the real ChatRoomChat Type=Activity packet and dictionary entries.
 */
data class BcActivityCommand(
    val targetMemberNumber: Int,
    val targetGroup: String,
    val activityName: String,
    val activityAssetGroup: String? = null,
    val activityAssetName: String? = null,
    /** Echo-compatible fallback template injected into the Activity dictionary for unknown clients. */
    val fallbackText: String? = null,
    val clientMsgId: String? = null,
)

/** A server-authoritative Activity message decoded from the room chat stream. */
data class BcInboundActivityCommand(
    val sourceMemberNumber: Int,
    val targetMemberNumber: Int,
    val targetGroup: String,
    val activityName: String,
    val clientMsgId: String? = null,
)

/**
 * A structured BC item update request for one character appearance group.
 *
 * A null [name] means removing the item from [group]. JSON fields are kept as raw strings at this
 * layer so M3A does not force a JSON library dependency into :core:model; M3B should parse and emit
 * them as real JSON values in the socket payload.
 */
data class BcCharacterItemUpdateCommand(
    val targetMemberNumber: Int,
    val group: String,
    val name: String? = null,
    val colorJson: String? = null,
    val difficulty: Int? = null,
    val propertyJson: String? = null,
    val craftJson: String? = null,
    val publishAction: BcItemActionMessage? = null,
    val permissionPrivateState: BcItemPermissionPrivateState? = null,
    val initializeLockTimerSeconds: Int? = null,
    val afcHeartLockChange: BcAfcHeartLockChange? = null,
    /** Automatic wearer-side expiry checks this item, rather than unrelated appearance groups. */
    val expireTimerSource: BcCharacterItem? = null,
    val adjustLockTimer: BcItemTimerAdjustment? = null,
)

/** Applied by the transport using its clock immediately before emission. */
data class BcItemTimerAdjustment(val seconds: Int, val maximumSeconds: Long)

/** Companion protocol required by Liko-AFC, in addition to the native item update. */
sealed interface BcAfcHeartLockChange {
    data class Apply(val ownerName: String, val assetFamily: String) : BcAfcHeartLockChange
    data class Remove(val lockId: String?) : BcAfcHeartLockChange
}

data class BcItemPermissionPrivateState(
    val wardrobe: BcWardrobeState,
    val ruleLog: List<BcOwnerRuleLogRecord>,
)

/** Updates the authenticated character's active pose list. */
data class BcPoseUpdateCommand(
    val poses: List<String>,
)

/** Updates one facial-expression group on the authenticated character. */
data class BcExpressionUpdateCommand(
    val group: String,
    val name: String?,
    /** Full current appearance with this expression applied, as sent by BC's expression menu. */
    val appearance: List<BcCharacterItem>? = null,
)

/** Publishes the limited Echo compatibility capability implemented by the mobile client. */
data class BcEchoCompatibilityCommand(
    val modName: String,
    val version: String,
    val beta: Boolean = false,
    val targetMemberNumber: Int? = null,
)

enum class BcEchoDrawRelation {
    PreviousCharacter,
    NextCharacter,
}

enum class BcEchoLeashRole(val wireValue: String) {
    Lead("lead"),
    Follow("follow"),
}

/** Structured form of Echo's Luzi_XCharacterDrawState Hidden message. */
data class BcEchoDrawOrderCommand(
    val otherMemberNumber: Int,
    val relation: BcEchoDrawRelation,
    val associatedAssetGroup: String,
    val associatedAssetName: String,
    val leash: BcEchoLeashRole,
)

data class BcItemActionMessage(
    val content: String,
    val previousAssetName: String? = null,
    val nextAssetName: String? = null,
    val previousCraftName: String? = null,
    val nextCraftName: String? = null,
    val nextAssetGroup: String? = null,
    val lockName: String? = null,
    val destinationCharacterName: String? = null,
    val timerTime: String? = null,
)

sealed interface BcCharacterInteractionResult {
    data object Sent : BcCharacterInteractionResult
    data class Rejected(val reason: String) : BcCharacterInteractionResult
    data class Failed(val reason: String) : BcCharacterInteractionResult
}

sealed interface BcInboundActivityResult {
    data object Ignored : BcInboundActivityResult
    data object Applied : BcInboundActivityResult
    data class Rejected(val reason: String) : BcInboundActivityResult
    data class Failed(val reason: String) : BcInboundActivityResult
}
