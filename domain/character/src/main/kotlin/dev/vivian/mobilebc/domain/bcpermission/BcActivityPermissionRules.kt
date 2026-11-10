package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterRuleState
import dev.vivian.mobilebc.domain.bccharacterstate.hasBodyPart
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule

internal fun activityAvailabilityBlockingReasons(
    context: BcPermissionContext,
    activity: BcActivityRule,
    group: BcGroupRule,
    actorState: BcCharacterRuleState,
    targetState: BcCharacterRuleState,
): List<BcPermissionReason> = buildList {
    if (context.room.blockCategories.any { it.sameActivityToken("Arousal") }) {
        add(BcPermissionReasons.roomBlocksActivities())
    }
    if (context.actor.memberNumber != context.target.memberNumber &&
        (actorState.isEnclosed() || targetState.isEnclosed())
    ) {
        add(BcPermissionReasons.actorPrerequisiteNotMet("CharacterNotEnclosed"))
    }

    val targetArousal = context.target.arousalPreferences()
    if (targetArousal.active?.sameActivityToken("Inactive") == true) {
        add(BcPermissionReasons.targetArousalInactive())
    }
    if (group.arousalZoneId?.let(targetArousal::zoneFactor) == 0) {
        add(BcPermissionReasons.targetArousalZoneDisabled(group.name))
    }

    activity.activityId?.let { activityId ->
        if (context.actor.arousalPreferences().activityFactor(activityId, receiving = false) == 0) {
            add(BcPermissionReasons.activityPreferenceBlocked(activity.name, "The actor"))
        }
        if (targetArousal.activityFactor(activityId, receiving = true) == 0) {
            add(BcPermissionReasons.activityPreferenceBlocked(activity.name, "The target"))
        }
    }
}

internal fun activityZoneNakedBlockingReason(
    state: BcCharacterRuleState,
    group: String,
    actor: Boolean,
): BcPermissionReason? {
    val available = when {
        group.sameActivityToken("ItemButt") ->
            state.isGroupAccessibleForActivity("ItemButt") && !state.isPlugged() && !state.isButtChaste()
        group.sameActivityToken("ItemVulva") || group.sameActivityToken("ItemVulvaPiercings") ->
            state.isGroupAccessibleForActivity("ItemPelvis") &&
                state.isGroupAccessibleForActivity("ItemVulva") &&
                !state.isVulvaChaste()
        group.sameActivityToken("ItemBreast") || group.sameActivityToken("ItemNipples") ->
            state.isGroupAccessibleForActivity("ItemBreast") && !state.isBreastChaste()
        group.sameActivityToken("ItemBoots") -> !state.hasAnyItemInGroups("ItemBoots", "Socks", "Shoes")
        group.sameActivityToken("ItemHands") -> !state.hasAnyItemInGroups("ItemHands", "Gloves")
        else -> true
    }
    if (available) return null
    return if (actor) BcPermissionReasons.actorZoneNotNaked(group) else BcPermissionReasons.targetZoneNotNaked(group)
}

internal fun echoPrerequisiteBlockingReason(
    prerequisite: String,
    context: BcPermissionContext,
    actorState: BcCharacterRuleState,
    targetState: BcCharacterRuleState,
    activityGroup: String,
): BcPermissionReason? = when (prerequisite) {
    "Luzi_ActedZoneNaked" -> echoActedZoneNakedBlockingReason(targetState, activityGroup)
    "Luzi_CanWalk" -> if (actorState.canUseFeet()) null else BcPermissionReasons.actorCannotUseFeet()
    "Luzi_Female" -> actorRequirement(context.actor.hasBodyPart("penis"), prerequisite)
    "Luzi_HasBreast" -> actorRequirement(!context.actor.hasBodyPart("breasts"), prerequisite)
    "Luzi_HasCatTail" -> actorRequirement(!actorState.hasItemInGroupWithName("TailStraps", *CAT_TAIL_ITEMS), prerequisite)
    "Luzi_HasKennel" -> targetRequirement(!targetState.hasItemInGroupWithName("ItemDevices", "Kennel"), prerequisite)
    "Luzi_HasPawMittens" -> actorRequirement(!actorState.hasItemInGroupWithName("ItemHands", "PawMittens", "ElbowLengthMittens"), prerequisite)
    "Luzi_HasPenis" -> actorRequirement(!context.actor.hasBodyPart("penis"), prerequisite)
    "Luzi_HasPetSuit" -> actorRequirement(!actorState.hasEchoPetSuit(), prerequisite)
    "Luzi_HasSword" -> actorRequirement(!actorState.hasEchoSword(), prerequisite)
    "Luzi_HasTail" -> actorRequirement(!actorState.hasAnyItemInGroups("TailStraps"), prerequisite)
    "Luzi_HasTentacles" -> actorRequirement(!actorState.hasEchoTentacles(), prerequisite)
    "Luzi_HasWings" -> actorRequirement(!actorState.hasAnyItemInGroups("Wings"), prerequisite)
    "Luzi_Has鱼鱼尾" -> actorRequirement(!actorState.hasItemInGroupWithName("动物身体_Luzi", "鱼鱼尾"), prerequisite)
    "Luzi_IsStanding" -> actorRequirement(!actorState.isStanding(), prerequisite)
    "Luzi_IsKneeling" -> actorRequirement(!actorState.isKneeling(), prerequisite)
    "Luzi_IsAllFours" -> actorRequirement(!actorState.poseState.containsPose("AllFours"), prerequisite)
    "Luzi_KneelOrAllFours" -> actorRequirement(!actorState.isKneeling() && !actorState.poseState.containsPose("AllFours"), prerequisite)
    "Luzi_PrivateExposed" -> echoPrivateExposedBlockingReason(actorState, prerequisite)
    "Luzi_TargetHasBreast" -> targetRequirement(!context.target.hasBodyPart("breasts"), prerequisite)
    "Luzi_TargetHasCatTail" -> targetRequirement(!targetState.hasItemInGroupWithName("TailStraps", *CAT_TAIL_ITEMS), prerequisite)
    "Luzi_TargetHasItemVulva" -> targetRequirement(targetState.isGroupEmpty("ItemVulva"), prerequisite)
    "Luzi_TargetHasPawMittens" -> targetRequirement(!targetState.hasItemInGroupWithName("ItemHands", "PawMittens", "ElbowLengthMittens"), prerequisite)
    "Luzi_TargetHasTentacles" -> targetRequirement(!targetState.hasEchoTentacles(), prerequisite)
    "Luzi_TargetKneelOrAllFours" -> targetRequirement(!targetState.isKneeling() && !targetState.poseState.containsPose("AllFours"), prerequisite)
    "Luzi_TargetLeashedOrCanBeLeashed" -> targetRequirement(!targetState.isLeashed(), prerequisite)
    else -> BcPermissionReasons.unsupportedActivityPrerequisite("Echo", setOf(prerequisite))
}

private fun actorRequirement(blocked: Boolean, prerequisite: String): BcPermissionReason? =
    if (blocked) BcPermissionReasons.actorPrerequisiteNotMet(prerequisite) else null

private fun targetRequirement(blocked: Boolean, prerequisite: String): BcPermissionReason? =
    if (blocked) BcPermissionReasons.targetPrerequisiteNotMet(prerequisite) else null

private fun echoActedZoneNakedBlockingReason(
    state: BcCharacterRuleState,
    group: String,
): BcPermissionReason? {
    val available = when {
        group.sameActivityToken("ItemBreast") || group.sameActivityToken("ItemNipples") ->
            state.isGroupAccessibleForActivity("ItemBreast") && !state.isBreastChaste()
        group.sameActivityToken("ItemButt") ->
            state.isGroupAccessibleForActivity("ItemButt") && !state.isButtChaste()
        group.sameActivityToken("ItemVulva") || group.sameActivityToken("ItemVulvaPiercings") ->
            state.isGroupAccessibleForActivity("ItemVulva") && !state.isVulvaChaste()
        group.sameActivityToken("ItemHands") -> !state.hasExactEffect("MergedFingers")
        else -> true
    }
    return if (available) null else BcPermissionReasons.targetZoneNotNaked(group)
}

private fun echoPrivateExposedBlockingReason(
    state: BcCharacterRuleState,
    prerequisite: String,
): BcPermissionReason? = actorRequirement(
    blocked = !state.isGroupAccessibleForActivity("ItemVulva") || state.isVulvaChaste(),
    prerequisite = prerequisite,
)

private fun BcCharacterRuleState.hasEchoTentacles(): Boolean =
    hasItemInGroupWithName("TailStraps", "Tentacles") ||
        hasItemInGroupWithName("ItemButt", "Tentacles")

private fun BcCharacterRuleState.hasEchoPetSuit(): Boolean =
    hasItemInGroupWithName(
        "ItemArms",
        "ShinyPetSuit",
        "BitchSuit",
        "StrictLeatherPetCrawler",
        "乳胶宠物拘束服",
    ) || (
        hasItemInGroupWithName("ItemArms", "宠物服上", "PawPaddedPetsuitArms", "StrappedPetsuitArms") &&
            hasItemInGroupWithName("ItemLegs", "宠物服下", "PawPaddedPetsuitLegs", "StrappedPetsuitLegs")
        )

private fun BcCharacterRuleState.hasEchoSword(): Boolean {
    val item = wornItems.firstOrNull { it.group.sameActivityToken("ItemHandheld") } ?: return false
    if (item.name in setOf("Sword", "分层剑")) return true
    val typeRecord = BcItemPropertyJson.readTypeRecord(item.item.propertyJson)
    return when (item.name) {
        "武器组合" -> typeRecord["t"] == 1 && typeRecord["s"] == 1
        "刀" -> typeRecord["A"] == 1
        else -> false
    }
}

private data class BcArousalPreferences(
    val active: String?,
    val activity: String?,
    val zone: String?,
) {
    fun activityFactor(activityId: Int, receiving: Boolean): Int? {
        val value = (activity?.getOrNull(activityId)?.code ?: return null) - 100
        val factor = if (receiving) value % 10 else value / 10
        return factor.takeIf { it in 0..4 } ?: 2
    }

    fun zoneFactor(zoneId: Int): Int? {
        val value = (zone?.getOrNull(zoneId)?.code ?: return null) - 100
        return (value % 10).coerceIn(0, 4)
    }
}

private fun BcCharacterSnapshot.arousalPreferences(): BcArousalPreferences = BcArousalPreferences(
    active = arousalSettingsJson.readBcStringProperty("Active"),
    activity = arousalSettingsJson.readBcStringProperty("Activity"),
    zone = arousalSettingsJson.readBcStringProperty("Zone"),
)

private fun String.sameActivityToken(other: String): Boolean = equals(other, ignoreCase = true)

private val CAT_TAIL_ITEMS = arrayOf(
    "TailStrap",
    "KittenTailStrap2",
    "KittenTailStrap1",
    "穿戴式浅色猫尾镜像",
    "小型穿戴式软猫尾镜像",
)
