package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterPoseState
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.toArrayPoseMapping
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyOverlay
import dev.vivian.mobilebc.domain.bcitemproperty.containsBcToken
import dev.vivian.mobilebc.domain.bcitemproperty.sameBcToken
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule

enum class BcCharacterItemSource {
    Actor,
    Target,
}

data class BcWornItemState(
    val item: BcCharacterItem,
    val assetRule: BcAssetRule?,
    val source: BcCharacterItemSource,
    val property: BcItemPropertyOverlay = BcItemPropertyOverlay.from(item.propertyJson),
) {
    val group: String get() = item.group
    val name: String get() = item.name
    val lockedBy: String? get() = property.lockedBy
    val isLocked: Boolean get() = lockedBy != null

    val effects: Set<String> = assetRule?.effects.orEmpty() + property.effects
    val blocks: Set<String> = assetRule?.blocks.orEmpty() + property.blocks
    val expose: Set<String> = assetRule?.expose.orEmpty() + property.expose
    val allowActivity: Set<String> = assetRule?.allowActivity.orEmpty() + property.allowActivity
    val allowActivityOn: Set<String> = assetRule?.allowActivityOn.orEmpty() + property.allowActivityOn
    val allowActivePose: Set<String> = assetRule?.allowActivePose.orEmpty() + property.allowActivePose
    val setPose: Set<String> = assetRule?.setPose.orEmpty() + property.setPose
    val attributes: Set<String> get() = property.attributes

    fun supportsNeeds(
        needs: Set<String>,
        activityName: String,
        targetGroup: String,
    ): Boolean = needs.all { supportsNeed(it, activityName, targetGroup) }

    fun supportsNeed(
        need: String,
        activityName: String,
        targetGroup: String,
    ): Boolean {
        // Mirrors CharacterItemsForActivity(C, Activity): the item must expose AllowActivity.
        // Matching the asset name alone is too permissive. BC's ActivityGenerateItemActivitiesFromNeed
        // does not restrict this lookup by AllowActivityOn; activity target-group validity is checked
        // on the Activity rule itself.
        return allowActivity.any {
            it.sameBcToken(need) || it.sameBcToken(activityName) || it.sameBcToken(SHOCK_ITEM)
        }
    }

    fun canReceiveShock(): Boolean =
        effects.any { it.sameBcToken("ReceiveShock") || it.containsBcToken("ReceiveShock") }

    fun canTriggerShock(activityName: String, targetGroup: String): Boolean =
        effects.any { it.sameBcToken("TriggerShock") || it.containsBcToken("TriggerShock") } ||
            allowActivity.any {
                it.sameBcToken("TriggerShock") || it.sameBcToken(SHOCK_ITEM) || it.sameBcToken(activityName)
            } && (allowActivityOn.isEmpty() || allowActivityOn.any { it.sameBcToken(targetGroup) })

    fun blocksGroup(group: String): Boolean = blocks.any { it.sameBcToken(group) }

    fun exposesGroup(group: String): Boolean = expose.any {
        it.sameBcToken(group) || it.sameBcToken("Naked") || it.sameBcToken("Access$group")
    }

    fun hasExactEffect(token: String): Boolean = effects.any { it.sameBcToken(token) }

    fun hasEffectLike(vararg tokens: String): Boolean =
        effects.any { effect -> tokens.any { effect.containsBcToken(it) } }

    fun hasAttributeLike(vararg tokens: String): Boolean =
        attributes.any { attribute -> tokens.any { attribute.containsBcToken(it) } }

    private companion object {
        const val SHOCK_ITEM = "ShockItem"
    }
}

data class BcCharacterRuleState(
    val character: BcCharacterSnapshot,
    val wornItems: List<BcWornItemState>,
    val allowedPoseMapping: Map<String, Set<String>> = resolveAllowedPoseMapping(wornItems),
    val poseState: BcCharacterPoseState = resolvePoseState(character, wornItems, allowedPoseMapping),
) {
    val itemByGroup: Map<String, BcWornItemState> = wornItems.associateBy { it.group.lowercase() }
    val effects: Set<String> = wornItems.flatMap { it.effects }.toSet()
    val blocks: Set<String> = wornItems.flatMap { it.blocks }.toSet()
    val exposedGroups: Set<String> = wornItems.flatMap { it.expose }.toSet()
    val activePose: Set<String> = poseState.toPoseList().toSet()

    fun item(group: String): BcWornItemState? = itemByGroup[group.lowercase()]

    fun ignoringGroup(group: String): BcCharacterRuleState {
        val filtered = wornItems.filterNot { it.group.sameBcToken(group) }
        val filteredAllowedPoseMapping = resolveAllowedPoseMapping(filtered)
        return copy(
            wornItems = filtered,
            allowedPoseMapping = filteredAllowedPoseMapping,
            poseState = resolvePoseState(character, filtered, filteredAllowedPoseMapping),
        )
    }

    fun canInteract(): Boolean = wornItems.none { it.hasExactEffect("Block") }

    fun canUseHands(): Boolean = canUseArms() &&
        !hasEffectLike("BlockHands", "BindHands", "EncloseHands", "MergedFingers") &&
        !blocksGroup("ItemHands")

    fun canUseArms(): Boolean = !hasEffectLike("BlockArms", "RestrictArms", "BindArms", "EncloseArms") &&
        !blocksGroup("ItemArms")

    fun canUseFeet(): Boolean = !hasEffectLike("BlockFeet", "BindFeet", "Freeze", "Immobilize") &&
        !blocksGroup("ItemFeet")

    fun canUseMouthOrTongue(): Boolean = !isMouthBlocked()

    fun isGagged(): Boolean = hasEffectLike("Gag", "BlockMouth", "MouthBlocked")

    fun isMouthBlocked(): Boolean = hasEffectLike("BlockMouth", "Gag", "MouthBlocked") ||
        blocksGroup("ItemMouth")

    fun isMouthOpen(): Boolean {
        val mouthItem = wornItems.firstOrNull { it.group.sameBcToken("ItemMouth") } ?: return true
        return mouthItem.hasEffectLike("MouthOpen", "OpenMouth")
    }

    fun canMoveHead(): Boolean = !hasEffectLike("FixedHead", "BlockHead") &&
        isGroupAccessibleForActivity("ItemHead")

    /**
     * Mirrors InventoryGroupIsBlockedForCharacter(C, group, Activity=true) conservatively:
     * regular item Block entries close a group, but activity-specific AllowActivityOn entries can
     * keep the group available for activities.
     */
    fun isGroupAccessibleForActivity(group: String): Boolean =
        hasActivityAccessAllowance(group) || !isGroupBlockedForItemChange(group)

    fun isGroupBlockedForItemChange(
        group: String,
        ignoreItemsInGroup: String? = null,
    ): Boolean = wornItems.any { item ->
        if (ignoreItemsInGroup != null && item.group.sameBcToken(ignoreItemsInGroup)) {
            false
        } else {
            item.blocksGroup(group)
        }
    }

    /**
     * Mirrors the item-use part of InventoryGroupIsAvailable/InventoryGroupIsBlockedForCharacter.
     * Regular Block entries close the group. If a non-player target is enclosed, only the group
     * containing the enclosing item remains available for item changes.
     */
    fun isGroupAvailableForItemChange(
        group: String,
        ignoreItemsInGroup: String? = null,
    ): Boolean {
        if (isGroupBlockedForItemChange(group, ignoreItemsInGroup)) return false
        if (character.isSelf || !isEnclosed()) return true

        val current = wornItems.firstOrNull { it.group.sameBcToken(group) }
        return current?.hasExactEffect("Enclose") == true || current?.hasExactEffect("OneWayEnclose") == true
    }

    fun isZoneNakedOrExposed(group: String): Boolean {
        if (isGroupBlockedForItemChange(group)) return false
        val current = wornItems.firstOrNull { it.group.sameBcToken(group) }
        return current == null || current.exposesGroup(group) || current.hasEffectLike("Naked")
    }

    fun isGroupEmpty(group: String): Boolean = wornItems.none { it.group.sameBcToken(group) }

    fun canTalk(): Boolean = !isMouthBlocked()

    fun isCollared(): Boolean = hasAnyItemInGroups("ItemNeck", "ItemNeckAccessories") ||
        hasItemNameOrEffectLike("Collar", "Collared")

    fun isLeashed(): Boolean = hasAnyItemInGroups("ItemNeckRestraints") ||
        hasItemNameOrEffectLike("Leash", "Leashed", "Tether")

    /** Mirrors Character.IsSuspended: PoseMapping.BodyAddon == Suspension or Effect includes Suspended. */
    fun isSuspended(): Boolean = poseState.bodyAddon?.sameBcToken("Suspension") == true || hasExactEffect("Suspended")

    /** Mirrors InventoryPrerequisiteMessage("NotLifted"): Effect includes exact Lifted. */
    fun isLifted(): Boolean = hasExactEffect("Lifted")

    /** Mirrors Character.IsMounted / InventoryPrerequisiteMessage("NotMounted"): Effect includes exact Mounted. */
    fun isMounted(): Boolean = hasExactEffect("Mounted")

    /** Mirrors InventoryPrerequisiteMessage("NotChained"): Effect includes exact IsChained. */
    fun isChained(): Boolean = hasExactEffect("IsChained")

    /** Mirrors Character.IsEnclose for item availability checks. */
    fun isEnclosed(): Boolean = hasExactEffect("Enclose") || (character.isSelf && hasExactEffect("OneWayEnclose"))

    /** Mirrors InventoryPrerequisiteMessage("OnBed"): Effect includes exact OnBed. */
    fun isOnBed(): Boolean = hasExactEffect("OnBed")

    fun isVulvaChaste(): Boolean = hasExactEffect("Chaste")

    fun isButtChaste(): Boolean = hasExactEffect("ButtChaste")

    fun isBreastChaste(): Boolean = hasExactEffect("BreastChaste")

    fun isPlugged(): Boolean = hasExactEffect("IsPlugged")

    fun isVulvaFull(): Boolean = hasExactEffect("FillVulva")

    fun isChaste(): Boolean = isVulvaChaste() || hasExactEffect("BreastChaste")

    fun hasForcedErection(): Boolean = hasExactEffect("ForcedErection")

    fun hasCuffedArms(): Boolean = hasItemNameOrEffectLike("ArmCuff", "ArmsCuff", "CuffedArms", "BindArms", "Cuffs") ||
        !isGroupEmpty("ItemArms")

    fun hasCuffedFeet(): Boolean = hasItemNameOrEffectLike("AnkleCuff", "FeetCuff", "CuffedFeet", "BindFeet", "Cuffs") ||
        !isGroupEmpty("ItemFeet")

    fun hasCuffedLegs(): Boolean = hasItemNameOrEffectLike("LegCuff", "CuffedLegs", "BindLegs", "Cuffs") ||
        !isGroupEmpty("ItemLegs")

    fun hasAnyItemInGroups(vararg groups: String): Boolean = wornItems.any { item ->
        groups.any { item.group.sameBcToken(it) }
    }

    fun hasItemInGroupWithName(group: String, vararg names: String): Boolean = wornItems.any { item ->
        item.group.sameBcToken(group) && names.any { item.name.sameBcToken(it) }
    }

    fun hasItemNameOrEffectLike(vararg tokens: String): Boolean = wornItems.any { item ->
        tokens.any { token -> item.name.containsBcToken(token) } || item.hasEffectLike(*tokens)
    }

    fun hasAttributeLike(vararg tokens: String): Boolean = wornItems.any { item ->
        item.hasAttributeLike(*tokens)
    }

    fun hasEffectLike(vararg tokens: String): Boolean = wornItems.any { item ->
        item.hasEffectLike(*tokens)
    }

    fun hasExactEffect(token: String): Boolean = effects.any { it.sameBcToken(token) }

    /** Mirrors PoseAvailable(C, category, pose): missing category means unrestricted. */
    fun poseAvailable(category: String, pose: String): Boolean =
        allowedPoseMapping[category]?.any { it.sameBcToken(pose) } ?: true

    /** Mirrors PoseAvailable for a flat pose name by first resolving the BC pose category. */
    fun poseAvailable(pose: String): Boolean {
        val category = BcCharacterPoseState.categoryOf(pose) ?: return true
        return poseAvailable(category, pose)
    }

    fun isKneeling(): Boolean = poseState.bodyLower in POSE_ALL_KNEELING

    fun isStanding(): Boolean = poseState.bodyLower in POSE_ALL_STANDING

    fun activePoseAllowedBy(allowedPose: Set<String>): Boolean {
        if (allowedPose.isEmpty()) return true
        val allowedByCategory = allowedPose.toList().toArrayPoseMapping()
        return poseState.toPoseList().all { active ->
            val category = BcCharacterPoseState.categoryOf(active) ?: return@all true
            val categoryAllowed = allowedByCategory[category] ?: return@all true
            categoryAllowed.any { it.sameBcToken(active) }
        }
    }

    fun allowsActivity(activityName: String): List<BcWornItemState> = wornItems.filter { item ->
        item.allowActivity.any { it.sameBcToken(activityName) }
    }

    fun allowsActivityOn(group: String, activityName: String): Boolean = wornItems.any { item ->
        item.allowActivity.any { it.sameBcToken(activityName) } &&
            (item.allowActivityOn.isEmpty() || item.allowActivityOn.any { it.sameBcToken(group) })
    }

    private fun blocksGroup(group: String): Boolean = isGroupBlockedForItemChange(group)

    private fun hasActivityAccessAllowance(group: String): Boolean = wornItems.any { item ->
        item.allowActivityOn.any { it.sameBcToken(group) }
    }
}

private val POSE_ALL_KNEELING = setOf("Kneel", "KneelingSpread")
private val POSE_ALL_STANDING = setOf("BaseLower", "LegsOpen", "LegsClosed", "Spread")

private fun resolveAllowedPoseMapping(wornItems: List<BcWornItemState>): Map<String, Set<String>> {
    val allowedByCategory = linkedMapOf<String, Set<String>>()

    wornItems.forEach { item ->
        val allowMapping = item.allowActivePose.toList().toArrayPoseMapping()
        if (allowMapping.isNotEmpty()) {
            allowMapping.forEach { (category, poses) ->
                val poseSet = poses.toSet()
                allowedByCategory[category] = allowedByCategory[category]?.intersect(poseSet) ?: poseSet
            }
            if ((allowMapping.containsKey("BodyUpper") || allowMapping.containsKey("BodyLower")) && !allowMapping.containsKey("BodyFull")) {
                allowedByCategory["BodyFull"] = emptySet()
            }
        }
    }

    return allowedByCategory
}

private fun resolvePoseState(
    character: BcCharacterSnapshot,
    wornItems: List<BcWornItemState>,
    allowedPoseMapping: Map<String, Set<String>> = resolveAllowedPoseMapping(wornItems),
): BcCharacterPoseState {
    val setPoseByCategory = linkedMapOf<String, LinkedHashSet<String>>()

    wornItems.forEach { item ->
        item.setPose.toList().toArrayPoseMapping().forEach { (category, poses) ->
            val current = setPoseByCategory.getOrPut(category) { linkedSetOf() }
            current.addAll(poses)
        }
    }

    val activeByCategory = character.activePose.toArrayPoseMapping()
        .mapValues { (_, poses) -> poses.toMutableList() }
        .toMutableMap()

    setPoseByCategory.forEach { (category, poses) ->
        activeByCategory.getOrPut(category) { mutableListOf() }.addAll(poses)
    }

    val poseMapping = linkedMapOf<String, String>()
    val categories = (activeByCategory.keys + allowedPoseMapping.keys + setOf("BodyUpper", "BodyLower", "BodyFull", "BodyAddon", "BodyHands"))
    categories.forEach { category ->
        val allowed = allowedPoseMapping[category]
        val candidate = activeByCategory[category]?.firstOrNull { pose -> allowed == null || allowed.any { it.sameBcToken(pose) } }
        if (candidate != null) poseMapping[category] = candidate
    }

    if (poseMapping.containsKey("BodyFull")) {
        poseMapping.remove("BodyUpper")
        poseMapping.remove("BodyLower")
    } else {
        poseMapping["BodyUpper"] = poseMapping["BodyUpper"] ?: character.poseState.bodyUpper ?: "BaseUpper"
        poseMapping["BodyLower"] = poseMapping["BodyLower"] ?: character.poseState.bodyLower ?: "BaseLower"
    }

    if (poseMapping.containsKey("BodyHands")) {
        if (poseMapping["BodyUpper"] == null || poseMapping["BodyUpper"] == "BaseUpper") {
            poseMapping.remove("BodyUpper")
        } else {
            poseMapping.remove("BodyHands")
        }
    }

    return BcCharacterPoseState.fromMapping(poseMapping, fallback = character.poseState)
}
