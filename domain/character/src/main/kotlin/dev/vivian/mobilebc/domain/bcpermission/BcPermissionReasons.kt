package dev.vivian.mobilebc.domain.bcpermission

data class BcPermissionReason(
    val code: BcPermissionReasonCode,
    val message: String,
    val severity: BcPermissionSeverity = BcPermissionSeverity.Blocking,
    val arguments: Map<String, String> = emptyMap(),
)

enum class BcPermissionSeverity {
    Blocking,
    Warning,
    Informational,
}

enum class BcPermissionReasonCode {
    ActorNotInRoom,
    TargetNotInRoom,
    BlankGroup,
    BlankActivityName,
    BlankItemName,
    UnknownGroup,
    UnknownActivity,
    UnknownAsset,
    ActivityNotAllowedOnGroup,
    RoomBlocksActivities,
    TargetArousalInactive,
    TargetArousalZoneDisabled,
    ActivityPreferenceBlocked,
    UnsupportedActivityPrerequisite,
    UnsupportedAssetPrerequisite,
    MissingRequiredActorItem,
    MissingRequiredTargetItem,
    MissingShockTriggerItem,
    MissingShockReceiverItem,
    SelectedActivityItemUnavailable,
    ActorCannotUseHands,
    ActorCannotUseArms,
    ActorCannotUseFeet,
    ActorCannotUseMouth,
    TargetCannotUseTongue,
    TargetMouthBlocked,
    TargetMouthNotBlocked,
    TargetMouthNotOpen,
    ActorNotGagged,
    TargetNotKneeling,
    TargetZoneBlocked,
    TargetZoneNotNaked,
    TargetZoneOccupied,
    ActorZoneOccupied,
    ActorZoneBlocked,
    ActorZoneNotNaked,
    AssetDisabled,
    ItemExclusivePermissionDenied,
    GroupDoesNotAllowNone,
    ServerAllowItemRequired,
    ServerAllowItemDenied,
    LocalAllowItemDenied,
    TargetBlacklistedActor,
    TargetItemNotWorn,
    TargetItemPermissionBlocked,
    TargetItemPermissionLimited,
    TargetItemLocked,
    ItemUnlockUnavailable,
    IncorrectLockCredential,
    LockInputUnavailable,
    LockConfigurationUnavailable,
    InvalidLockConfiguration,
    ItemLockUnavailable,
    ItemDoesNotAllowLock,
    ItemAlreadyLocked,
    LockConfigurationUnsupported,
    LockTimerUnavailable,
    LockTimerExpired,
    LockTimerPermissionDenied,
    InvalidLockTimerChange,
    AfcHeartLockPermissionDenied,
    InvalidItemText,
    ActorPrerequisiteNotMet,
    TargetPrerequisiteNotMet,
    TargetPoseNotAllowed,
    UnsupportedOptionPrerequisite,
}

internal object BcPermissionReasons {
    fun actorNotInRoom(memberNumber: Int) = BcPermissionReason(
        code = BcPermissionReasonCode.ActorNotInRoom,
        message = "Actor #$memberNumber is not in the current room.",
        arguments = mapOf("memberNumber" to memberNumber.toString()),
    )

    fun targetNotInRoom(memberNumber: Int) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetNotInRoom,
        message = "Target #$memberNumber is not in the current room.",
        arguments = mapOf("memberNumber" to memberNumber.toString()),
    )

    fun blankGroup() = BcPermissionReason(
        code = BcPermissionReasonCode.BlankGroup,
        message = "The interaction target group is blank.",
    )

    fun blankActivityName() = BcPermissionReason(
        code = BcPermissionReasonCode.BlankActivityName,
        message = "The activity name is blank.",
    )

    fun blankItemName() = BcPermissionReason(
        code = BcPermissionReasonCode.BlankItemName,
        message = "The item name is blank.",
    )

    fun unknownGroup(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.UnknownGroup,
        message = "BC rule catalog does not contain group '$group'.",
        arguments = mapOf("group" to group),
    )

    fun unknownActivity(activityName: String) = BcPermissionReason(
        code = BcPermissionReasonCode.UnknownActivity,
        message = "BC rule catalog does not contain activity '$activityName'.",
        arguments = mapOf("activityName" to activityName),
    )

    fun unknownAsset(group: String, name: String) = BcPermissionReason(
        code = BcPermissionReasonCode.UnknownAsset,
        message = "BC rule catalog does not contain item '$group/$name'.",
        arguments = mapOf("group" to group, "name" to name),
    )

    fun activityNotAllowedOnGroup(activityName: String, group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActivityNotAllowedOnGroup,
        message = "Activity '$activityName' is not allowed on group '$group'.",
        arguments = mapOf("activityName" to activityName, "group" to group),
    )

    fun roomBlocksActivities() = BcPermissionReason(
        code = BcPermissionReasonCode.RoomBlocksActivities,
        message = "This room blocks the Arousal category, so activities are disabled.",
    )

    fun targetArousalInactive() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetArousalInactive,
        message = "The target has arousal activities disabled.",
    )

    fun targetArousalZoneDisabled(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetArousalZoneDisabled,
        message = "The target has disabled activities on '$group'.",
        arguments = mapOf("group" to group),
    )

    fun activityPreferenceBlocked(activityName: String, subject: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActivityPreferenceBlocked,
        message = "$subject has blocked activity '$activityName' in arousal preferences.",
        arguments = mapOf("activityName" to activityName, "subject" to subject),
    )

    fun unsupportedActivityPrerequisite(activityName: String, prerequisites: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.UnsupportedActivityPrerequisite,
        message = "Activity '$activityName' has prerequisites not implemented by the local evaluator: ${prerequisites.joinToString()}.",
        arguments = mapOf(
            "activityName" to activityName,
            "prerequisites" to prerequisites.joinToString(","),
        ),
    )

    fun unsupportedAssetPrerequisite(group: String, name: String, prerequisites: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.UnsupportedAssetPrerequisite,
        message = "Item '$group/$name' has asset prerequisites not implemented by the local evaluator: ${prerequisites.joinToString()}.",
        arguments = mapOf(
            "group" to group,
            "name" to name,
            "prerequisites" to prerequisites.joinToString(","),
        ),
    )

    fun missingRequiredActorItem(activityName: String, needs: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.MissingRequiredActorItem,
        message = "Activity '$activityName' requires an actor item that allows: ${needs.joinToString()}.",
        arguments = mapOf("activityName" to activityName, "needs" to needs.joinToString(",")),
    )

    fun missingRequiredTargetItem(activityName: String, needs: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.MissingRequiredTargetItem,
        message = "Activity '$activityName' requires a target item that allows: ${needs.joinToString()}.",
        arguments = mapOf("activityName" to activityName, "needs" to needs.joinToString(",")),
    )

    fun missingShockTriggerItem(activityName: String) = BcPermissionReason(
        code = BcPermissionReasonCode.MissingShockTriggerItem,
        message = "Activity '$activityName' requires a worn actor item that can trigger shocks.",
        arguments = mapOf("activityName" to activityName),
    )

    fun missingShockReceiverItem(activityName: String) = BcPermissionReason(
        code = BcPermissionReasonCode.MissingShockReceiverItem,
        message = "Activity '$activityName' requires a worn target item that can receive shocks.",
        arguments = mapOf("activityName" to activityName),
    )

    fun selectedActivityItemUnavailable(activityName: String, group: String?, name: String?) = BcPermissionReason(
        code = BcPermissionReasonCode.SelectedActivityItemUnavailable,
        message = "Selected item '${group.orEmpty()}/${name.orEmpty()}' is not available for activity '$activityName'.",
        arguments = mapOf(
            "activityName" to activityName,
            "group" to group.orEmpty(),
            "name" to name.orEmpty(),
        ),
    )

    fun actorCannotUseHands() = BcPermissionReason(
        code = BcPermissionReasonCode.ActorCannotUseHands,
        message = "The actor cannot use hands for this activity.",
    )

    fun actorCannotUseArms() = BcPermissionReason(
        code = BcPermissionReasonCode.ActorCannotUseArms,
        message = "The actor cannot use arms for this activity.",
    )

    fun actorCannotUseFeet() = BcPermissionReason(
        code = BcPermissionReasonCode.ActorCannotUseFeet,
        message = "The actor cannot use feet for this activity.",
    )

    fun actorCannotUseMouth() = BcPermissionReason(
        code = BcPermissionReasonCode.ActorCannotUseMouth,
        message = "The actor cannot use mouth or tongue for this activity.",
    )

    fun targetCannotUseTongue() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetCannotUseTongue,
        message = "The target cannot use tongue for this activity.",
    )

    fun targetMouthBlocked() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetMouthBlocked,
        message = "The target mouth is blocked.",
    )

    fun targetMouthNotBlocked() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetMouthNotBlocked,
        message = "The target mouth is not blocked.",
    )

    fun targetMouthNotOpen() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetMouthNotOpen,
        message = "The target mouth is not open.",
    )

    fun actorNotGagged() = BcPermissionReason(
        code = BcPermissionReasonCode.ActorNotGagged,
        message = "The actor is not gagged.",
    )

    fun targetNotKneeling() = BcPermissionReason(
        code = BcPermissionReasonCode.TargetNotKneeling,
        message = "The target is not kneeling.",
    )

    fun targetZoneBlocked(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetZoneBlocked,
        message = "The target group '$group' is blocked or inaccessible.",
        arguments = mapOf("group" to group),
    )

    fun targetZoneNotNaked(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetZoneNotNaked,
        message = "The target group '$group' is not naked or exposed enough for this activity.",
        arguments = mapOf("group" to group),
    )

    fun targetZoneOccupied(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetZoneOccupied,
        message = "The target group '$group' must be empty for this activity.",
        arguments = mapOf("group" to group),
    )

    fun actorZoneOccupied(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActorZoneOccupied,
        message = "The actor group '$group' must be empty for this activity.",
        arguments = mapOf("group" to group),
    )

    fun actorZoneBlocked(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActorZoneBlocked,
        message = "The actor group '$group' is blocked or inaccessible.",
        arguments = mapOf("group" to group),
    )

    fun actorZoneNotNaked(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActorZoneNotNaked,
        message = "The actor group '$group' is not naked or exposed enough for this activity.",
        arguments = mapOf("group" to group),
    )

    fun assetDisabled(group: String, name: String) = BcPermissionReason(
        code = BcPermissionReasonCode.AssetDisabled,
        message = "Item '$group/$name' is disabled by the BC rule catalog.",
        arguments = mapOf("group" to group, "name" to name),
    )

    fun itemExclusivePermissionDenied(group: String, name: String, required: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ItemExclusivePermissionDenied,
        message = "Item '$group/$name' requires $required permission.",
        arguments = mapOf("group" to group, "name" to name, "required" to required),
    )

    fun groupDoesNotAllowNone(group: String) = BcPermissionReason(
        code = BcPermissionReasonCode.GroupDoesNotAllowNone,
        message = "Group '$group' does not allow removing the item without a replacement.",
        arguments = mapOf("group" to group),
    )

    fun serverAllowItemRequired() = BcPermissionReason(
        code = BcPermissionReasonCode.ServerAllowItemRequired,
        message = "A server AllowItem check is required before this item update can be sent.",
    )

    fun serverAllowItemDenied() = BcPermissionReason(
        code = BcPermissionReasonCode.ServerAllowItemDenied,
        message = "The server AllowItem check denied this item update.",
    )

    fun localAllowItemDenied(required: String) = BcPermissionReason(
        code = BcPermissionReasonCode.LocalAllowItemDenied,
        message = "The target item interaction setting requires $required permission.",
        arguments = mapOf("required" to required),
    )

    fun targetBlacklistedActor(targetMemberNumber: Int, actorMemberNumber: Int) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetBlacklistedActor,
        message = "Target #$targetMemberNumber has blacklisted actor #$actorMemberNumber.",
        arguments = mapOf(
            "targetMemberNumber" to targetMemberNumber.toString(),
            "actorMemberNumber" to actorMemberNumber.toString(),
        ),
    )

    fun targetItemNotWorn(group: String, name: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetItemNotWorn,
        message = "Target is not currently wearing item '$group/$name'.",
        arguments = mapOf("group" to group, "name" to name),
    )

    fun targetItemPermissionBlocked(group: String, name: String, type: String? = null) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetItemPermissionBlocked,
        message = buildString {
            append("Target has blocked item '$group/$name'")
            if (!type.isNullOrBlank()) append(" option '$type'")
            append(".")
        },
        arguments = buildMap {
            put("group", group)
            put("name", name)
            type?.let { put("type", it) }
        },
    )

    fun targetItemPermissionLimited(group: String, name: String, type: String? = null) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetItemPermissionLimited,
        message = buildString {
            append("Target has limited item '$group/$name'")
            if (!type.isNullOrBlank()) append(" option '$type'")
            append(" and actor is not owner, lover, or whitelisted.")
        },
        arguments = buildMap {
            put("group", group)
            put("name", name)
            type?.let { put("type", it) }
        },
    )

    fun targetItemLocked(group: String, name: String, lockName: String?) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetItemLocked,
        message = "Target item '$group/$name' is locked${lockName?.let { " by $it" } ?: ""} and cannot be modified locally.",
        arguments = buildMap {
            put("group", group)
            put("name", name)
            lockName?.let { put("lockName", it) }
        },
    )

    fun actorPrerequisiteNotMet(prerequisite: String) = BcPermissionReason(
        code = BcPermissionReasonCode.ActorPrerequisiteNotMet,
        message = "The actor does not satisfy prerequisite '$prerequisite'.",
        arguments = mapOf("prerequisite" to prerequisite),
    )

    fun targetPrerequisiteNotMet(prerequisite: String) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetPrerequisiteNotMet,
        message = "The target does not satisfy prerequisite '$prerequisite'.",
        arguments = mapOf("prerequisite" to prerequisite),
    )

    fun targetPoseNotAllowed(group: String, name: String, activePose: List<String>, allowedPose: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.TargetPoseNotAllowed,
        message = "Item '$group/$name' cannot be used with target pose ${activePose.joinToString().ifBlank { "<none>" }}; allowed poses are ${allowedPose.joinToString()}.",
        arguments = mapOf(
            "group" to group,
            "name" to name,
            "activePose" to activePose.joinToString(","),
            "allowedPose" to allowedPose.joinToString(","),
        ),
    )

    fun unsupportedOptionPrerequisite(group: String, name: String, prerequisites: Set<String>) = BcPermissionReason(
        code = BcPermissionReasonCode.UnsupportedOptionPrerequisite,
        message = "Item option '$group/$name' has prerequisites not implemented by the local evaluator: ${prerequisites.joinToString()}.",
        arguments = mapOf(
            "group" to group,
            "name" to name,
            "prerequisites" to prerequisites.joinToString(","),
        ),
    )
}
