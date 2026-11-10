package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterItemSource
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterRuleState
import dev.vivian.mobilebc.domain.bccharacterstate.lockInputConditions
import dev.vivian.mobilebc.domain.bccharacterstate.BcWornItemState
import dev.vivian.mobilebc.domain.bccharacterstate.DefaultBcCharacterStateResolver
import dev.vivian.mobilebc.domain.bccharacterstate.canUseLimitedTargetItem
import dev.vivian.mobilebc.domain.bccharacterstate.hasBodyPart
import dev.vivian.mobilebc.domain.bccharacterstate.hasOwnerRuleFlag
import dev.vivian.mobilebc.domain.bccharacterstate.isFamilyOf
import dev.vivian.mobilebc.domain.bccharacterstate.isLoverOf
import dev.vivian.mobilebc.domain.bccharacterstate.isOwnerOf
import dev.vivian.mobilebc.domain.bccharacterstate.isSiblingOf
import dev.vivian.mobilebc.domain.bccharacterstate.isWhitelistedBy
import dev.vivian.mobilebc.domain.bccharacterstate.reputationValue
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerState
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange
import dev.vivian.mobilebc.domain.bcitemproperty.BcTimerInputChoice
import dev.vivian.mobilebc.domain.bcitemproperty.bcTimerDurations
import dev.vivian.mobilebc.core.model.BcItemTimerAdjustment
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode
import dev.vivian.mobilebc.domain.bcitemproperty.highSecurityKeyholderText
import dev.vivian.mobilebc.domain.bcitemproperty.readBcKeyholderNumbers
import dev.vivian.mobilebc.domain.bcitemproperty.keyholderText
import dev.vivian.mobilebc.domain.bcitemproperty.validBcNewLockPassword
import dev.vivian.mobilebc.domain.bcitemproperty.allowsLock
import dev.vivian.mobilebc.domain.bcitemproperty.nativeLockBaseline
import dev.vivian.mobilebc.domain.bcitemproperty.BcAfcHeartLock
import dev.vivian.mobilebc.domain.bcitemproperty.validText
import dev.vivian.mobilebc.domain.bcitemproperty.textValues
import dev.vivian.mobilebc.core.model.BcAfcHeartLockChange
import dev.vivian.mobilebc.domain.bcitemproperty.readBcBooleanProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcIntProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcPropertyTokens
import dev.vivian.mobilebc.domain.bcitemproperty.containsBcTokenOrFalse as containsTokenOrFalse
import dev.vivian.mobilebc.domain.bcitemproperty.sameBcToken as sameToken
import dev.vivian.mobilebc.domain.bcitemproperty.containsBcToken as containsToken
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcActivityParticipant
import dev.vivian.mobilebc.domain.bcrules.BcActivityStateRequirement
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog

/**
 * Conservative local evaluator for BC character interactions.
 *
 * Resolves candidates against character and catalog state without sending packets or mutating
 * the room. Unknown prerequisites remain denied so a BC update cannot silently allow a restricted
 * command.
 */
class DefaultBcInteractionPermissionEngine(
    private val characterStateResolver: DefaultBcCharacterStateResolver = DefaultBcCharacterStateResolver(),
) {
    fun localAllowItemBlockingReasons(context: BcPermissionContext): List<BcPermissionReason> =
        listOfNotNull(localAllowItemBlockingReason(context))

    fun evaluate(request: BcPermissionRequest): BcPermissionDecision =
        when (val evaluation = evaluateCandidate(request.context, request.candidate)) {
            is CandidateEvaluation.Allowed -> BcPermissionDecision.Allowed(evaluation.executables.first())
            is CandidateEvaluation.Denied -> BcPermissionDecision.Denied(
                candidate = evaluation.candidate,
                reasons = evaluation.reasons,
            )
            is CandidateEvaluation.NeedsServerCheck -> BcPermissionDecision.NeedsServerCheck(
                candidate = evaluation.candidate,
                reasons = evaluation.reasons,
            )
        }

    fun filter(request: BcPermissionFilterRequest): BcPermissionFilterResult {
        if (request.candidates.isEmpty()) return BcPermissionFilterResult.Empty

        return filterPage(
            BcPermissionFilterPageRequest(
                context = request.context,
                candidates = request.candidates,
                offset = 0,
                limit = Int.MAX_VALUE,
            ),
        ).result
    }

    fun filterPage(request: BcPermissionFilterPageRequest): BcPermissionFilterPageResult {
        if (request.candidates.isEmpty() || request.limit == 0 || request.offset >= request.candidates.size) {
            return BcPermissionFilterPageResult.empty(
                offset = request.offset,
                hasMoreCandidates = request.offset < request.candidates.size,
            )
        }

        val allowed = mutableListOf<BcExecutableInteraction>()
        val denied = mutableListOf<BcDeniedInteraction>()
        val needsServerCheck = mutableListOf<BcPendingServerCheckInteraction>()
        var scanned = 0
        var index = request.offset

        fun emittedCount(): Int =
            (if (request.includeAllowed) allowed.size else 0) +
                (if (request.includeDenied) denied.size else 0) +
                (if (request.includeNeedsServerCheck) needsServerCheck.size else 0)

        while (index < request.candidates.size && scanned < request.maxScannedCandidates) {
            if (emittedCount() >= request.limit) break
            val candidate = request.candidates[index]
            when (val evaluation = evaluateCandidate(request.context, candidate)) {
                is CandidateEvaluation.Allowed -> if (request.includeAllowed) {
                    allowed += evaluation.executables
                }
                is CandidateEvaluation.Denied -> if (request.includeDenied) {
                    denied += BcDeniedInteraction(
                        candidate = evaluation.candidate,
                        reasons = evaluation.reasons,
                    )
                }
                is CandidateEvaluation.NeedsServerCheck -> if (request.includeNeedsServerCheck) {
                    needsServerCheck += BcPendingServerCheckInteraction(
                        candidate = evaluation.candidate,
                        reasons = evaluation.reasons,
                    )
                }
            }
            index++
            scanned++
        }

        val result = BcPermissionFilterResult(
            allowed = allowed,
            denied = denied,
            needsServerCheck = needsServerCheck,
        )
        val hasMore = index < request.candidates.size
        return BcPermissionFilterPageResult(
            result = result,
            offset = request.offset,
            nextOffset = index.takeIf { hasMore },
            scannedCandidateCount = scanned,
            emittedInteractionCount = result.interactionCount(),
            hasMoreCandidates = hasMore,
        )
    }

    private fun evaluateCandidate(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate,
    ): CandidateEvaluation {
        val commonReasons = commonBlockingReasons(context, candidate)
        if (commonReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, commonReasons)
        }

        return when (candidate) {
            is BcInteractionCandidate.Activity -> evaluateActivity(context, candidate)
            is BcInteractionCandidate.WearItem -> evaluateWearItem(context, candidate)
            is BcInteractionCandidate.RemoveItem -> evaluateRemoveItem(context, candidate)
            is BcInteractionCandidate.ModifyItem -> evaluateModifyItem(context, candidate)
            is BcInteractionCandidate.UnlockItem -> evaluateUnlockItem(context, candidate)
            is BcInteractionCandidate.LockItem -> evaluateLockItem(context, candidate)
            is BcInteractionCandidate.ConfigureLock -> evaluateConfigureLock(context, candidate)
            is BcInteractionCandidate.ChangeLockTimer -> evaluateChangeLockTimer(context, candidate)
        }
    }

    private fun commonBlockingReasons(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate,
    ): List<BcPermissionReason> = buildList {
        if (!context.room.contains(context.actor.memberNumber)) {
            add(BcPermissionReasons.actorNotInRoom(context.actor.memberNumber))
        }
        if (!context.room.contains(candidate.targetMemberNumber)) {
            add(BcPermissionReasons.targetNotInRoom(candidate.targetMemberNumber))
        }
        if (candidate.group.isBlank()) {
            add(BcPermissionReasons.blankGroup())
        }
        // BC's item permission ladder does not treat blacklist as a universal hard block.
        // ServerAccountAllowItem allows Everyone before consulting blacklist, then checks
        // blacklist only for stricter levels. Keep blacklist handling in item-specific
        // localAllowItemBlockingReason instead of the shared room/group validation.
    }

    private fun evaluateActivity(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.Activity,
    ): CandidateEvaluation {
        if (candidate.activityName.isBlank()) {
            return denied(candidate, BcPermissionReasons.blankActivityName())
        }

        val selectedGroup = context.rules.group(candidate.group)
            ?: return denied(candidate, BcPermissionReasons.unknownGroup(candidate.group))
        val activityGroup = context.rules.activityGroup(selectedGroup)
        val activity = context.rules.activity(candidate.activityName)
            ?: return denied(candidate, BcPermissionReasons.unknownActivity(candidate.activityName))

        val unsupportedPrerequisites = activity.prerequisites.filterNot(::isSupportedActivityPrerequisite).toSet()
        if (unsupportedPrerequisites.isNotEmpty()) {
            return denied(
                candidate,
                BcPermissionReasons.unsupportedActivityPrerequisite(
                    activity.source.label ?: "BC",
                    unsupportedPrerequisites,
                ),
            )
        }

        val isSelfTarget = candidate.targetMemberNumber == context.actor.memberNumber
        val allowedGroups = if (isSelfTarget) activity.selfTargetGroups else activity.targetGroups
        if (!context.rules.activityGroupMatches(activityGroup.name, allowedGroups)) {
            return denied(candidate, BcPermissionReasons.activityNotAllowedOnGroup(activity.name, selectedGroup.name))
        }

        val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        val targetState = resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target)

        val availabilityReasons = activityAvailabilityBlockingReasons(
            context = context,
            activity = activity,
            group = activityGroup,
            actorState = actorState,
            targetState = targetState,
        )
        if (availabilityReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, availabilityReasons)
        }

        val stateReasons = activityStateBlockingReasons(
            context = context,
            candidate = candidate,
            activityGroup = activityGroup.name,
            prerequisites = activity.prerequisites,
            actorState = actorState,
            targetState = targetState,
        )
        if (stateReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, stateReasons)
        }
        val requirementReasons = activityStateRequirementBlockingReasons(
            activity = activity,
            actorState = actorState,
            targetState = targetState,
        )
        if (requirementReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, requirementReasons)
        }

        val actorNeeds = activity.prerequisites
            .mapNotNull { it.removePrefixOrNull(ACTOR_NEEDS_PREFIX) }
            .toSet()
        val targetNeeds = activity.prerequisites
            .mapNotNull { it.removePrefixOrNull(TARGET_NEEDS_PREFIX) }
            .toSet()
        val requiresShock = activity.prerequisites.any { it.equals(SHOCK_ITEM, ignoreCase = true) } ||
            candidate.activityName.equals(SHOCK_ITEM, ignoreCase = true)

        if (requiresShock) {
            return evaluateShockActivity(
                candidate = candidate,
                activity = activity,
                actorState = actorState,
                targetState = targetState,
            )
        }

        if (actorNeeds.isNotEmpty()) {
            val actorItems = actorState.wornItems.filter {
                it.supportsNeeds(
                    needs = actorNeeds,
                    activityName = candidate.activityName,
                    targetGroup = candidate.group,
                )
            }
            if (actorItems.isEmpty()) {
                return denied(candidate, BcPermissionReasons.missingRequiredActorItem(candidate.activityName, actorNeeds))
            }
            val missingTargetNeeds = targetNeeds.filterNot { need ->
                targetState.wornItems.any {
                    it.supportsNeed(
                        need = need,
                        activityName = candidate.activityName,
                        targetGroup = candidate.group,
                    )
                }
            }.toSet()
            if (missingTargetNeeds.isNotEmpty()) {
                return denied(candidate, BcPermissionReasons.missingRequiredTargetItem(candidate.activityName, missingTargetNeeds))
            }
            return selectActivityExecutables(
                candidate = candidate,
                executables = actorItems.map { item -> activityExecutable(candidate, activity, item, isSelfTarget) },
            )
        }

        if (targetNeeds.isNotEmpty()) {
            val targetItems = targetState.wornItems.filter {
                it.supportsNeeds(
                    needs = targetNeeds,
                    activityName = candidate.activityName,
                    targetGroup = candidate.group,
                )
            }
            if (targetItems.isEmpty()) {
                return denied(candidate, BcPermissionReasons.missingRequiredTargetItem(candidate.activityName, targetNeeds))
            }
            return selectActivityExecutables(
                candidate = candidate,
                executables = targetItems.map { item -> activityExecutable(candidate, activity, item, isSelfTarget) },
            )
        }

        return selectActivityExecutables(
            candidate = candidate,
            executables = listOf(
                activityExecutable(
                    candidate = candidate,
                    activity = activity,
                    usedItem = null,
                    isSelfTarget = isSelfTarget,
                )
            ),
        )
    }

    private fun evaluateShockActivity(
        candidate: BcInteractionCandidate.Activity,
        activity: BcActivityRule,
        actorState: BcCharacterRuleState,
        targetState: BcCharacterRuleState,
    ): CandidateEvaluation {
        // BC's ActivityAllowedForGroup only enables ShockItem when the currently focused target
        // item receives shocks. A shockable item on a different group must not make this group's
        // ShockItem executable.
        val receivers = targetState.wornItems.filter {
            it.group.sameToken(candidate.group) && it.canReceiveShock()
        }
        if (receivers.isEmpty()) {
            return denied(candidate, BcPermissionReasons.missingShockReceiverItem(candidate.activityName))
        }

        val triggers = actorState.wornItems.filter { it.canTriggerShock(candidate.activityName, candidate.group) }
        if (triggers.isEmpty()) {
            return denied(candidate, BcPermissionReasons.missingShockTriggerItem(candidate.activityName))
        }

        return selectActivityExecutables(
            candidate = candidate,
            executables = triggers.map { item ->
                activityExecutable(
                    candidate = candidate,
                    activity = activity,
                    usedItem = item,
                    isSelfTarget = candidate.targetMemberNumber == actorState.character.memberNumber,
                )
            },
        )
    }

    private fun activityStateBlockingReasons(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.Activity,
        activityGroup: String,
        prerequisites: Set<String>,
        actorState: BcCharacterRuleState,
        targetState: BcCharacterRuleState,
    ): List<BcPermissionReason> {
        return buildList {
            prerequisites.forEach { prerequisite ->
                when (prerequisite) {
                    "UseHands" -> if (!actorState.canUseHands()) add(BcPermissionReasons.actorCannotUseHands())
                    "UseArms" -> if (!actorState.canUseArms()) add(BcPermissionReasons.actorCannotUseArms())
                    "UseFeet" -> if (!actorState.canUseFeet()) add(BcPermissionReasons.actorCannotUseFeet())
                    "UseMouth" -> if (actorState.isMouthBlocked() || !actorState.canTalk()) add(BcPermissionReasons.actorCannotUseMouth())
                    "UseTongue" -> if (actorState.isMouthBlocked()) add(BcPermissionReasons.actorCannotUseMouth())
                    "CantUseArms" -> if (actorState.canUseArms()) add(BcPermissionReasons.actorCannotUseArms())
                    "CantUseFeet" -> if (actorState.canUseFeet()) add(BcPermissionReasons.actorCannotUseFeet())
                    "TargetCanUseTongue" -> if (!targetState.canUseMouthOrTongue()) add(BcPermissionReasons.targetCannotUseTongue())
                    "TargetMouthOpen" -> if (activityGroup.sameToken("ItemMouth") && !targetState.isMouthOpen()) add(BcPermissionReasons.targetMouthNotOpen())
                    "TargetMouthBlocked" -> if (!targetState.isMouthBlocked()) add(BcPermissionReasons.targetMouthNotBlocked())
                    "IsGagged" -> if (!actorState.isGagged()) add(BcPermissionReasons.actorNotGagged())
                    "TargetKneeling" -> if (!targetState.isKneeling()) add(BcPermissionReasons.targetNotKneeling())
                    "MoveHead" -> if (activityGroup.sameToken("ItemHead") && !targetState.canMoveHead()) add(BcPermissionReasons.targetZoneBlocked("ItemHead"))
                    "ZoneAccessible" -> if (!targetState.isGroupAccessibleForActivity(activityGroup)) add(BcPermissionReasons.targetZoneBlocked(activityGroup))
                    "TargetZoneAccessible" -> if (!actorState.isGroupAccessibleForActivity(activityGroup)) add(BcPermissionReasons.actorZoneBlocked(activityGroup))
                    "ZoneNaked" -> activityZoneNakedBlockingReason(targetState, activityGroup, actor = false)?.let(::add)
                    "TargetZoneNaked" -> activityZoneNakedBlockingReason(actorState, activityGroup, actor = true)?.let(::add)
                    "VulvaEmpty" -> if (activityGroup.sameToken("ItemVulva") && (!context.target.hasBodyPart("vulva") || targetState.isVulvaFull())) add(BcPermissionReasons.targetZoneOccupied("ItemVulva"))
                    "AssEmpty" -> if (activityGroup.sameToken("ItemButt") && targetState.isPlugged()) add(BcPermissionReasons.targetZoneOccupied("ItemButt"))
                    "CanUsePenis" -> if (!actorCanUsePenis(actorState)) add(BcPermissionReasons.actorPrerequisiteNotMet(prerequisite))
                    "Brothers" -> if (!context.actor.hasBodyPart("penis") || !context.target.hasBodyPart("penis") || !context.actor.isSiblingOf(context.target)) add(BcPermissionReasons.actorPrerequisiteNotMet(prerequisite))
                    "Sisters" -> if (context.actor.hasBodyPart("penis") || context.target.hasBodyPart("penis") || !context.actor.isSiblingOf(context.target)) add(BcPermissionReasons.actorPrerequisiteNotMet(prerequisite))
                    "SiblingsWithDifferentGender" -> if (context.actor.hasBodyPart("penis") == context.target.hasBodyPart("penis") || !context.actor.isSiblingOf(context.target)) add(BcPermissionReasons.actorPrerequisiteNotMet(prerequisite))
                    SHOCK_ITEM -> Unit
                    else -> {
                        when {
                            prerequisite.startsWith(ACTOR_NEEDS_PREFIX) || prerequisite.startsWith(TARGET_NEEDS_PREFIX) -> Unit
                            prerequisite.startsWith("Luzi_") -> echoPrerequisiteBlockingReason(
                                prerequisite = prerequisite,
                                context = context,
                                actorState = actorState,
                                targetState = targetState,
                                activityGroup = activityGroup,
                            )?.let(::add)
                            else -> targetPrerequisiteBlockingReason(context, targetState, prerequisite)?.let(::add)
                        }
                    }
                }
            }
        }
    }

    private fun activityStateRequirementBlockingReasons(
        activity: BcActivityRule,
        actorState: BcCharacterRuleState,
        targetState: BcCharacterRuleState,
    ): List<BcPermissionReason> = buildList {
        activity.stateRequirements.forEach { requirement ->
            val state = when (requirement.participant) {
                BcActivityParticipant.Actor -> actorState
                BcActivityParticipant.Target -> targetState
            }
            val satisfied = when (requirement) {
                is BcActivityStateRequirement.GroupEmpty -> state.isGroupEmpty(requirement.group)
                is BcActivityStateRequirement.GroupContainsAny -> state.hasItemInGroupWithName(
                    requirement.group,
                    *requirement.names.toTypedArray(),
                )
                is BcActivityStateRequirement.GroupEmptyOrContainsAny ->
                    state.isGroupEmpty(requirement.group) || state.hasItemInGroupWithName(
                        requirement.group,
                        *requirement.names.toTypedArray(),
                    )
            }
            if (!satisfied) {
                add(requirement.toPermissionReason(activity.name))
            }
        }
    }

    private fun BcActivityStateRequirement.toPermissionReason(activityName: String): BcPermissionReason =
        when (this) {
            is BcActivityStateRequirement.GroupEmpty -> when (participant) {
                BcActivityParticipant.Actor -> BcPermissionReasons.actorZoneOccupied(group)
                BcActivityParticipant.Target -> BcPermissionReasons.targetZoneOccupied(group)
            }
            is BcActivityStateRequirement.GroupEmptyOrContainsAny -> when (participant) {
                BcActivityParticipant.Actor -> BcPermissionReasons.actorZoneOccupied(group)
                BcActivityParticipant.Target -> BcPermissionReasons.targetZoneOccupied(group)
            }
            is BcActivityStateRequirement.GroupContainsAny -> {
                val requirement = "$group/${names.joinToString("|")}"
                when (participant) {
                    BcActivityParticipant.Actor -> BcPermissionReasons.actorPrerequisiteNotMet(
                        "$activityName requires actor item $requirement",
                    )
                    BcActivityParticipant.Target -> BcPermissionReasons.targetPrerequisiteNotMet(
                        "$activityName requires target item $requirement",
                    )
                }
            }
        }

    private fun actorCanUsePenis(actorState: BcCharacterRuleState): Boolean {
        // Mirrors ActivityPrerequisite CanUsePenis:
        // no penis means the prerequisite is irrelevant; otherwise the actor must be able to access
        // the penis/vulva zone and must not be chaste.
        if (!actorState.character.hasBodyPart("penis")) return true
        return !actorState.isVulvaChaste() &&
            actorState.isGroupAccessibleForActivity("ItemVulva")
    }

    private fun evaluateWearItem(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.WearItem,
    ): CandidateEvaluation {
        if (candidate.itemName.isBlank()) {
            return denied(candidate, BcPermissionReasons.blankItemName())
        }
        val group = context.rules.group(candidate.group)
            ?: return denied(candidate, BcPermissionReasons.unknownGroup(candidate.group))
        val asset = context.rules.asset(candidate.group, candidate.itemName)
            ?: return denied(candidate, BcPermissionReasons.unknownAsset(candidate.group, candidate.itemName))
        val currentItem = context.target.appearance.firstOrNull { it.group.equals(candidate.group, ignoreCase = true) }
        val removalReasons = if (currentItem != null) {
            val currentAsset = context.rules.asset(currentItem.group, currentItem.name)
            if (currentAsset == null && !context.target.isSelf) {
                return denied(candidate, BcPermissionReasons.unknownAsset(currentItem.group, currentItem.name))
            }
            if (currentAsset != null) {
                removeItemLocalBlockingReasons(context, currentItem, currentAsset)
            } else emptyList()
        } else emptyList()
        val localReasons = (removalReasons + itemLocalBlockingReasons(
            context = context,
            asset = asset,
            updatePropertyJson = candidate.propertyJson,
            currentItem = currentItem,
        )).distinct()
        if (localReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, localReasons)
        }

        return itemServerCheckOrAllowed(
            context = context,
            candidate = candidate,
            command = BcCharacterItemUpdateCommand(
                targetMemberNumber = candidate.targetMemberNumber,
                group = candidate.group,
                name = candidate.itemName,
                difficulty = candidate.difficulty,
                propertyJson = candidate.propertyJson,
                publishAction = if (group.kind == BcAppearanceGroupKind.Item) {
                    wearOrReplaceAction(
                        previousAssetName = currentItem?.name,
                        nextAssetName = candidate.itemName,
                        previousCraftName = currentItem?.craftJson.readBcStringProperty("Name"),
                    )
                } else {
                    null
                },
            ),
        )
    }

    private fun evaluateRemoveItem(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.RemoveItem,
    ): CandidateEvaluation {
        val group = context.rules.group(candidate.group)
            ?: return denied(candidate, BcPermissionReasons.unknownGroup(candidate.group))
        if (!group.allowNone) {
            return denied(candidate, BcPermissionReasons.groupDoesNotAllowNone(candidate.group))
        }

        val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        itemActorBlockingReason(context, actorState)?.let { return denied(candidate, it) }

        val targetState = resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target)
        if (!targetState.isGroupAvailableForItemChange(candidate.group, ignoreItemsInGroup = candidate.group)) {
            return denied(candidate, BcPermissionReasons.targetZoneBlocked(candidate.group))
        }
        val currentItem = context.target.appearance.firstOrNull { it.group.equals(candidate.group, ignoreCase = true) }
        if (currentItem == null) {
            return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, "<none>"))
        }
        val currentAsset = context.rules.asset(currentItem.group, currentItem.name)
        // Echo Clothing Expansion assets are not part of the cached native catalog. Native BC
        // accepts self-originated removals before asset-specific validation, so preserve that safe
        // escape hatch without allowing unknown custom assets to be removed from other users.
        if (currentAsset == null && !context.target.isSelf) {
            return denied(candidate, BcPermissionReasons.unknownAsset(currentItem.group, currentItem.name))
        }
        val removeReasons = if (currentAsset == null) {
            listOfNotNull(currentItem.lockedItemBlockingReason(context))
        } else {
            removeItemLocalBlockingReasons(
                context = context,
                currentItem = currentItem,
                currentAsset = currentAsset,
            )
        }
        if (removeReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, removeReasons)
        }

        return itemServerCheckOrAllowed(
            context = context,
            candidate = candidate,
            command = BcCharacterItemUpdateCommand(
                targetMemberNumber = candidate.targetMemberNumber,
                group = candidate.group,
                name = null,
                publishAction = if (group.kind == BcAppearanceGroupKind.Item) {
                    removeAction(previousAssetName = currentItem.name, craftName = currentItem.craftJson.readBcStringProperty("Name"))
                } else {
                    null
                },
            ),
        )
    }

    private fun evaluateLockItem(context: BcPermissionContext, candidate: BcInteractionCandidate.LockItem): CandidateEvaluation {
        val item = context.target.appearance.firstOrNull { it.group == candidate.group && it.name == candidate.itemName }
            ?: return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, candidate.itemName))
        val asset = context.rules.asset(item.group, item.name)
            ?: return denied(candidate, BcPermissionReasons.unknownAsset(item.group, item.name))
        fun reject(code: BcPermissionReasonCode, message: String) = denied(candidate, BcPermissionReason(code, message))
        if (item.lockName() != null || "Lock" in item.propertyJson.readBcPropertyTokens("Effect") || "Lock" in asset.effects) {
            return reject(BcPermissionReasonCode.ItemAlreadyLocked, "This item is already locked.")
        }
        if (!asset.allowsLock(item.propertyJson, context.rules.extendedItem(item.group, item.name))) {
            return reject(BcPermissionReasonCode.ItemDoesNotAllowLock, "This item does not allow a lock in its current mode.")
        }
        val isAfc = candidate.lockName == BcAfcHeartLock.Name
        val lock = if (isAfc) BcAfcHeartLock.asset else context.rules.asset("ItemMisc", candidate.lockName)
            ?: return denied(candidate, BcPermissionReasons.unknownAsset("ItemMisc", candidate.lockName))
        if (!lock.isLock || !lock.enable) return reject(BcPermissionReasonCode.ItemLockUnavailable, "This lock is unavailable.")
        if (isAfc && (!BcAfcHeartLock.canApply(context.actor, context.target) || context.rules.asset("ItemMisc", BcAfcHeartLock.NativeLock)?.enable != true)) {
            return reject(BcPermissionReasonCode.AfcHeartLockPermissionDenied, "The wearer must authorize you as an AFC lover or owner to use a heart lock.")
        }
        val nativeLock = if (isAfc) BcAfcHeartLock.NativeLock else lock.name
        val baseline = nativeLockBaseline(nativeLock, context.rules.extendedItem(lock.group, nativeLock))
            ?: return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "This lock requires configuration that is not available here yet.")
        if (!context.inventoryAvailable(lock) || context.itemPrivateState == null) {
            return reject(BcPermissionReasonCode.ItemLockUnavailable, "You do not have this lock available.")
        }
        if (!resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor).canInteract()) {
            return denied(candidate, BcPermissionReasons.actorPrerequisiteNotMet("CanInteract"))
        }
        val targetState = resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target)
        if (!targetState.isGroupAvailableForItemChange(item.group, ignoreItemsInGroup = item.group)) {
            return denied(candidate, BcPermissionReasons.targetZoneBlocked(item.group))
        }
        val reasons = itemPermissionBlockingReasons(context, lock, null).toMutableList()
        if (isAfc) reasons += itemPermissionBlockingReasons(context, checkNotNull(context.rules.asset("ItemMisc", nativeLock)), null)
        if (lock.ownerOnly && !context.actor.isOwnerOf(context.target)) reasons += BcPermissionReasons.itemExclusivePermissionDenied(lock.group, lock.name, "owner")
        if (lock.loverOnly && !context.actor.isLoverOf(context.target)) reasons += BcPermissionReasons.itemExclusivePermissionDenied(lock.group, lock.name, "lover")
        if (lock.familyOnly && !context.actor.isFamilyOf(context.target)) reasons += BcPermissionReasons.itemExclusivePermissionDenied(lock.group, lock.name, "family")
        if (reasons.isNotEmpty()) return CandidateEvaluation.Denied(candidate, reasons)
        val timer = lock.removeTimerSeconds.takeIf { it > 0 }
        if ("Timer" in lock.name && timer == null) return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "The lock timer is not defined.")
        if (timer != null && !context.serverTimeAvailable) return reject(BcPermissionReasonCode.LockTimerUnavailable, "Reconnect to obtain the time before applying a timer lock.")
        return itemServerCheckOrAllowed(context, candidate, BcCharacterItemUpdateCommand(
            targetMemberNumber = candidate.targetMemberNumber,
            group = item.group, name = item.name, colorJson = item.colorJson, difficulty = item.difficulty,
            propertyJson = BcItemPropertyJson.withLock(item.propertyJson, nativeLock, context.actor.memberNumber, context.actor.name, baseline).let {
                if (isAfc) BcItemPropertyJson.overlayRawProperties(it,
                    """{"Name":"${BcAfcHeartLock.Name}","LockPickSeed":"${BcAfcHeartLock.PickSeed}","ExclusiveUnlock":true}""") else it
            },
            craftJson = item.craftJson,
            publishAction = BcItemActionMessage("ActionAddLock", previousAssetName = item.name, nextAssetName = lock.name,
                previousCraftName = item.craftJson.readBcStringProperty("Name"), nextAssetGroup = lock.group),
            permissionPrivateState = context.itemPrivateState,
            initializeLockTimerSeconds = timer,
            afcHeartLockChange = if (isAfc) BcAfcHeartLockChange.Apply(context.actor.nickname?.takeIf { it.isNotBlank() } ?: context.actor.name, context.target.assetFamily) else null,
        ))
    }

    private fun BcPermissionContext.inventoryAvailable(asset: BcAssetRule): Boolean {
        if (asset.value == 0.0 || asset.availableLocations.any { "ChatRoom".startsWith(it) }) return true
        if (asset.buyGroup != null && rules.assets.values.any { it.buyGroup == asset.buyGroup && it.value == 0.0 }) return true
        val inventory = itemPrivateState?.wardrobe?.privateInputs ?: return false
        if (!inventory.inventoryAvailable) return false
        if (inventory.inventoryItems.orEmpty().any { it.group == asset.group && it.name == asset.name }) return true
        return asset.inventoryId?.let { id -> id in inventory.inventoryIds.orEmpty() &&
            rules.assets.values.firstOrNull { it.inventoryId == id }?.let { it.group == asset.group && it.name == asset.name } == true } == true
    }

    private fun evaluateUnlockItem(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.UnlockItem,
    ): CandidateEvaluation {
        val item = context.target.appearance.firstOrNull { it.group == candidate.group && it.name == candidate.itemName }
            ?: return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, candidate.itemName))
        val asset = context.rules.asset(item.group, item.name)
            ?: return denied(candidate, BcPermissionReasons.unknownAsset(item.group, item.name))
        if (candidate.credential == null && !resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor).canInteract()) {
            return denied(candidate, BcPermissionReasons.actorPrerequisiteNotMet("CanInteract"))
        }
        if (item.lockName() != candidate.lockName || candidate.lockName.isBlank() || "Lock" in asset.effects) {
            return denied(candidate, BcPermissionReasons.targetItemLocked(item.group, item.name, item.lockName()))
        }
        if (candidate.credential != null) {
            val kind = BcLockInputKind.forLock(candidate.lockName)
            val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
            val blind = actorState.lockInputConditions().blind
            if (kind == null || blind && kind != BcLockInputKind.Combination && candidate.lockName != "SafewordPadlock") {
                return denied(candidate, BcPermissionReason(BcPermissionReasonCode.LockInputUnavailable,
                    "This lock input is unavailable with the current restraints or vision."))
            }
            val lock = context.rules.asset("ItemMisc", candidate.lockName)
                ?: return denied(candidate, BcPermissionReasons.unknownAsset("ItemMisc", candidate.lockName))
            if (asset.ownerOnly && !context.actor.isOwnerOf(context.target) ||
                asset.loverOnly && !context.actor.isLoverOf(context.target) || asset.familyOnly && !context.actor.isFamilyOf(context.target)) {
                return denied(candidate, BcPermissionReason(BcPermissionReasonCode.ItemUnlockUnavailable, "The item's relationship permissions prevent unlocking."))
            }
            val reasons = itemPermissionBlockingReasons(context, asset, item.propertyJson) + itemPermissionBlockingReasons(context, lock, null)
            if (reasons.isNotEmpty()) return CandidateEvaluation.Denied(candidate, reasons)
            if (!kind.matches(candidate.credential, item.propertyJson)) {
                return denied(candidate, BcPermissionReason(BcPermissionReasonCode.IncorrectLockCredential, "The lock code is incorrect."))
            }
        } else if (!context.canUnlockWithKeys(item, asset, candidate.lockName)) {
            return denied(candidate, BcPermissionReason(BcPermissionReasonCode.ItemUnlockUnavailable,
                "Your available keys or current permissions do not allow unlocking this item."))
        }
        val targetState = resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target)
        if (!targetState.isGroupAvailableForItemChange(item.group, ignoreItemsInGroup = item.group)) {
            return denied(candidate, BcPermissionReasons.targetZoneBlocked(item.group))
        }
        val afc = BcAfcHeartLock.identity(context.target, item)
        val removeItem = candidate.credential != null && BcLockInputKind.forLock(candidate.lockName) == BcLockInputKind.Password &&
            item.propertyJson.readBcBooleanProperty("RemoveOnUnlock") == true
        return itemServerCheckOrAllowed(context, candidate, BcCharacterItemUpdateCommand(
            targetMemberNumber = candidate.targetMemberNumber,
            group = item.group,
            name = item.name.takeUnless { removeItem },
            colorJson = item.colorJson.takeUnless { removeItem },
            difficulty = item.difficulty.takeUnless { removeItem },
            propertyJson = if (removeItem) null else if (afc != null) BcAfcHeartLock.unlockedProperty(item.propertyJson) else BcItemPropertyJson.withoutLock(item.propertyJson),
            craftJson = item.craftJson.takeUnless { removeItem },
            publishAction = BcItemActionMessage("ActionUnlock", previousAssetName = item.name,
                previousCraftName = item.craftJson.readBcStringProperty("Name")),
            permissionPrivateState = context.itemPrivateState,
            afcHeartLockChange = afc?.let { BcAfcHeartLockChange.Remove(it.lockId) },
        ))
    }

    private fun BcPermissionContext.canUnlockWithKeys(item: BcCharacterItem, asset: BcAssetRule, lockName: String): Boolean {
        if (BcAfcHeartLock.identity(target, item) != null) return actor.memberNumber != target.memberNumber && BcAfcHeartLock.canUnlock(actor, target, item)
        if (lockName == "ExclusivePadlock") return actor.memberNumber != target.memberNumber
        val privateState = itemPrivateState ?: return false
        if (keysDeposited() == true) return false
        if (asset.ownerOnly) return asset.enable && actor.isOwnerOf(target)
        if (asset.loverOnly) return asset.enable && actor.isLoverOf(target)
        if (asset.familyOnly) return asset.enable && actor.isFamilyOf(target)
        if ((item.propertyJson.readBcBooleanProperty("SelfUnlock") ?: asset.selfUnlock) == false && actor.memberNumber == target.memberNumber) return false
        fun hasKey(name: String) = rules.asset("ItemMisc", name)?.let { inventoryAvailable(it) } == true
        if (asset.enable && actor.isOwnerOf(target) && hasKey("OwnerPadlockKey")) return true
        val lock = rules.asset("ItemMisc", lockName) ?: return false
        if (lock.familyOnly && privateState.ruleLog.any { it.group == "OwnerRule" && it.name == "BlockFamilyKey" } &&
            (actor.ownership?.stage ?: 0) >= 1) return false
        if (asset.enable && actor.isLoverOf(target) && !lockName.startsWith("Owner") && hasKey("LoversPadlockKey")) return true
        if (lock.exclusiveUnlock) {
            val members = BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(item.propertyJson, "MemberNumberListKeys"))
            if (members != null) return actor.memberNumber.toDouble() in readBcKeyholderNumbers(members)
            if (item.propertyJson.readBcIntProperty("LockMemberNumber") == actor.memberNumber) return true
        }
        if (lock.ownerOnly && !actor.isOwnerOf(target) || lock.loverOnly && !actor.isLoverOf(target) ||
            lock.familyOnly && !actor.isFamilyOf(target)) return false
        return rules.assets.values.firstOrNull { "Unlock$lockName" in it.effects }?.let { inventoryAvailable(it) } == true
    }

    private fun evaluateChangeLockTimer(context: BcPermissionContext, candidate: BcInteractionCandidate.ChangeLockTimer): CandidateEvaluation {
        fun reject(code: BcPermissionReasonCode, message: String) = denied(candidate, BcPermissionReason(code, message))
        val item = context.target.appearance.firstOrNull { it.group == candidate.group && it.name == candidate.itemName }
            ?: return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, candidate.itemName))
        val state = context.lockTimerState(item)?.takeIf { it.lockName == candidate.lockName }
            ?: return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "This item does not have a supported timer lock.")
        val lock = context.rules.asset("ItemMisc", state.lockName) ?: return denied(candidate, BcPermissionReasons.unknownAsset("ItemMisc", state.lockName))
        val asset = context.rules.asset(item.group, item.name) ?: return denied(candidate, BcPermissionReasons.unknownAsset(item.group, item.name))
        if (!lock.isLock || lock.removeTimerSeconds <= 0 || !asset.allowsLock(item.propertyJson, context.rules.extendedItem(item.group, item.name))) {
            return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "The current item mode or lock does not support a timer.")
        }
        val now = context.serverTimeMillis ?: return reject(BcPermissionReasonCode.LockTimerUnavailable, "Reconnect to obtain the current lock time.")
        if (state.deadlineMillis < now) return reject(BcPermissionReasonCode.LockTimerExpired, "The lock timer has expired. Refresh the item.")
        val actor = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        if (!actor.canInteract()) return denied(candidate, BcPermissionReasons.actorPrerequisiteNotMet("CanInteract"))
        if (actor.lockInputConditions().blind) return reject(BcPermissionReasonCode.LockInputUnavailable, "You cannot inspect this timer lock while blind.")
        if (state.lockName == "TimerPasswordPadlock" && !resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target).isGroupAvailableForItemChange(item.group, item.group)) {
            return denied(candidate, BcPermissionReasons.targetZoneBlocked(item.group))
        }
        val reasons = itemPermissionBlockingReasons(context, asset, item.propertyJson) + itemPermissionBlockingReasons(context, lock, null)
        if (reasons.isNotEmpty()) return CandidateEvaluation.Denied(candidate, reasons)
        val access = context.lockTimerAccess(item, state)
        var property: String? = BcItemPropertyJson.withLockEffect(BcItemPropertyJson.withDefaults(item.propertyJson,
            context.rules.extendedItem("ItemMisc", state.lockName)?.baselinePropertyJson))
        var adjustment: BcItemTimerAdjustment? = null
        var restart: Int? = null
        var action: BcItemActionMessage? = null
        when (val change = candidate.change) {
            is BcLockTimerChange.Settings -> {
                if (!access.editSettings) return reject(BcPermissionReasonCode.LockTimerPermissionDenied, "You cannot change this lock's timer settings.")
                if (state.basic && (change.showTimer != null || change.enableRandomInput != null)) return reject(BcPermissionReasonCode.InvalidLockTimerChange, "This timer only supports the removal setting.")
                val fields = buildList {
                    add("\"RemoveItem\":${change.removeItem}")
                    change.showTimer?.let { add("\"ShowTimer\":$it") }
                    change.enableRandomInput?.let { add("\"EnableRandomInput\":$it") }
                }
                property = BcItemPropertyJson.overlayRawProperties(property, fields.joinToString(",", "{", "}"))
            }
            is BcLockTimerChange.Adjust -> {
                if (!access.adjust) return reject(BcPermissionReasonCode.LockTimerPermissionDenied, "You cannot adjust this lock's timer directly.")
                if (bcTimerDurations.none { it.seconds == change.seconds }) return reject(BcPermissionReasonCode.InvalidLockTimerChange, "Choose a supported timer duration.")
                adjustment = BcItemTimerAdjustment(change.seconds, context.lockTimerMaximumSeconds(lock.maxTimerSeconds))
            }
            BcLockTimerChange.Restart -> {
                if (!access.restart) return reject(BcPermissionReasonCode.LockTimerPermissionDenied, "This lock does not support restarting its timer.")
                restart = lock.removeTimerSeconds
                action = BcItemActionMessage("TimerRestart")
            }
            is BcLockTimerChange.LimitedInput -> {
                if (!access.limitedInput) return reject(BcPermissionReasonCode.LockTimerPermissionDenied, "Random timer input is unavailable or you already participated.")
                val step = state.limitedStepSeconds(lock.removeTimerSeconds) ?: return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "This timer duration is unsupported.")
                val seconds = when (change.choice) {
                    BcTimerInputChoice.Decrease -> -step
                    BcTimerInputChoice.Increase -> step
                    BcTimerInputChoice.Random -> step * 2 * if (change.randomPositive) 1 else -1
                }
                val members = BcItemPropertyJson.readExactPropertyValue(property, "MemberNumberList")?.trim() ?: "[]"
                if (!members.startsWith('[') || !members.endsWith(']')) return reject(BcPermissionReasonCode.InvalidLockTimerChange, "The timer participant list is invalid.")
                val body = members.substring(1, members.lastIndex).trim()
                property = BcItemPropertyJson.overlayRawProperties(property, "{\"MemberNumberList\":[${body + if (body.isEmpty()) "" else ","}${context.actor.memberNumber}]}")
                adjustment = BcItemTimerAdjustment(seconds, context.lockTimerMaximumSeconds(lock.maxTimerSeconds))
            }
        }
        if (adjustment?.maximumSeconds?.let { it <= 0 } == true) return reject(BcPermissionReasonCode.LockConfigurationUnsupported, "The lock's maximum timer is unavailable.")
        return itemServerCheckOrAllowed(context, candidate, BcCharacterItemUpdateCommand(context.target.memberNumber, item.group,
            item.name, item.colorJson, item.difficulty, property, item.craftJson, publishAction = action,
            permissionPrivateState = context.itemPrivateState, initializeLockTimerSeconds = restart, adjustLockTimer = adjustment))
    }

    private fun evaluateConfigureLock(context: BcPermissionContext, candidate: BcInteractionCandidate.ConfigureLock): CandidateEvaluation {
        val item = context.target.appearance.firstOrNull { it.group == candidate.group && it.name == candidate.itemName }
            ?: return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, candidate.itemName))
        val asset = context.rules.asset(item.group, item.name) ?: return denied(candidate, BcPermissionReasons.unknownAsset(item.group, item.name))
        val lock = context.rules.asset("ItemMisc", candidate.lockName) ?: return denied(candidate, BcPermissionReasons.unknownAsset("ItemMisc", candidate.lockName))
        if (item.lockName() != candidate.lockName || "Lock" in asset.effects) return denied(candidate, BcPermissionReasons.targetItemLocked(item.group, item.name, item.lockName()))
        if (!asset.allowsLock(item.propertyJson, context.rules.extendedItem(item.group, item.name))) return denied(candidate,
            BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "The current item mode does not support this lock."))
        val inputKind = BcLockInputKind.forLock(candidate.lockName)
        val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        val keyholders = candidate.configuration is BcLockConfiguration.UpdateKeyholders
        if (BcAfcHeartLock.identity(context.target, item) != null || inputKind == null && !keyholders ||
            actorState.lockInputConditions().blind && inputKind != BcLockInputKind.Combination && candidate.lockName != "SafewordPadlock") {
            return denied(candidate, BcPermissionReason(BcPermissionReasonCode.LockInputUnavailable, "This lock input is unavailable with the current vision."))
        }
        if (!resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target).isGroupAvailableForItemChange(item.group, item.group)) {
            return denied(candidate, BcPermissionReasons.targetZoneBlocked(item.group))
        }
        val reasons = itemPermissionBlockingReasons(context, asset, item.propertyJson) + itemPermissionBlockingReasons(context, lock, null)
        if (reasons.isNotEmpty()) return CandidateEvaluation.Denied(candidate, reasons)
        if (asset.ownerOnly && !context.actor.isOwnerOf(context.target) || asset.loverOnly && !context.actor.isLoverOf(context.target) ||
            asset.familyOnly && !context.actor.isFamilyOf(context.target)) {
            return denied(candidate, BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "The item's relationship permissions prevent lock changes."))
        }
        val mode = BcLockConfigurationMode.forLock(item.propertyJson, context.actor.memberNumber)
        val property: String
        val action: String
        when (val configuration = candidate.configuration) {
            is BcLockConfiguration.UpdateKeyholders -> {
                if (mode != BcLockConfigurationMode.UpdateKeyholders ||
                    context.actor.memberNumber.toDouble() !in readBcKeyholderNumbers(highSecurityKeyholderText(item.propertyJson))) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "Only a current keyholder can edit this lock's keyholders."))
                if (configuration.memberNumbers.length > 250) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.InvalidLockConfiguration, "The keyholder list exceeds 250 characters."))
                val deposited = context.keysDeposited() ?: return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "Your key deposit state is unavailable."))
                property = BcItemPropertyJson.withLockEffect(BcItemPropertyJson.overlayStrings(item.propertyJson,
                    mapOf("MemberNumberListKeys" to configuration.keyholderText(context.actor, !deposited))))
                action = "HighSecurityUpdate"
            }
            is BcLockConfiguration.InitializePassword -> {
                if (mode != BcLockConfigurationMode.InitializePassword) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "Only an unconfirmed password lock can be initialized by its creator."))
                if (!validBcNewLockPassword(configuration.password.value) || configuration.hint.length > 140 ||
                    candidate.lockName == "TimerPasswordPadlock" && configuration.removeOnUnlock != null) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.InvalidLockConfiguration, "Invalid password or lock hint."))
                val strings = buildMap {
                    if (configuration.password.value.isNotEmpty()) put("Password", configuration.password.value.uppercase())
                    if (configuration.hint.isNotEmpty()) put("Hint", configuration.hint)
                }
                val flags = if (configuration.removeOnUnlock == null) """{"LockSet":true}""" else
                    """{"LockSet":true,"RemoveOnUnlock":${configuration.removeOnUnlock}}"""
                property = checkNotNull(BcItemPropertyJson.overlayRawProperties(BcItemPropertyJson.overlayStrings(item.propertyJson, strings), flags))
                action = "PasswordChangeSuccess"
            }
            is BcLockConfiguration.ChangeCombination -> {
                if (mode != BcLockConfigurationMode.ChangeCombination) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.LockConfigurationUnavailable, "This lock does not support changing a combination."))
                if (!BcLockInputKind.Combination.matches(configuration.current, item.propertyJson)) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.IncorrectLockCredential, "The lock code is incorrect."))
                if (!BcLockInputKind.Combination.acceptsInput(configuration.next.value)) return denied(candidate,
                    BcPermissionReason(BcPermissionReasonCode.InvalidLockConfiguration, "The new combination must contain four digits."))
                property = BcItemPropertyJson.overlayStrings(item.propertyJson, mapOf("CombinationNumber" to configuration.next.value))
                action = "CombinationChangeSuccess"
            }
        }
        return itemServerCheckOrAllowed(context, candidate, BcCharacterItemUpdateCommand(
            candidate.targetMemberNumber, item.group, item.name, item.colorJson, item.difficulty, property, item.craftJson,
            publishAction = BcItemActionMessage(action), permissionPrivateState = context.itemPrivateState))
    }

    private fun evaluateModifyItem(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate.ModifyItem,
    ): CandidateEvaluation {
        if (candidate.itemName.isBlank()) {
            return denied(candidate, BcPermissionReasons.blankItemName())
        }
        val group = context.rules.group(candidate.group)
            ?: return denied(candidate, BcPermissionReasons.unknownGroup(candidate.group))
        val asset = context.rules.asset(candidate.group, candidate.itemName)
            ?: return denied(candidate, BcPermissionReasons.unknownAsset(candidate.group, candidate.itemName))
        val currentItem = context.target.appearance.firstOrNull {
            it.group.equals(candidate.group, ignoreCase = true) &&
                it.name.equals(candidate.itemName, ignoreCase = true)
        }
        if (currentItem == null) {
            return denied(candidate, BcPermissionReasons.targetItemNotWorn(candidate.group, candidate.itemName))
        }

        val localReasons = itemLocalBlockingReasons(
            context = context,
            asset = asset,
            updatePropertyJson = candidate.propertyJson,
            currentItem = currentItem,
        )
        if (localReasons.isNotEmpty()) {
            return CandidateEvaluation.Denied(candidate, localReasons)
        }

        return itemServerCheckOrAllowed(
            context = context,
            candidate = candidate,
            command = BcCharacterItemUpdateCommand(
                targetMemberNumber = candidate.targetMemberNumber,
                group = candidate.group,
                name = candidate.itemName,
                colorJson = currentItem.colorJson,
                difficulty = candidate.difficulty ?: currentItem.difficulty,
                propertyJson = candidate.propertyJson,
                craftJson = currentItem.craftJson,
                publishAction = if (group.kind == BcAppearanceGroupKind.Item) {
                    modifyAction(itemName = candidate.itemName, craftName = currentItem.craftJson.readBcStringProperty("Name"))
                } else {
                    null
                },
            ),
        )
    }

    private fun itemLocalBlockingReasons(
        context: BcPermissionContext,
        asset: BcAssetRule,
        updatePropertyJson: String? = null,
        currentItem: BcCharacterItem? = null,
    ): List<BcPermissionReason> = buildList {
        val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        val targetState = resolveCharacterState(context.target, context.rules, BcPermissionItemSource.Target)

        itemActorBlockingReason(context, actorState)?.let(::add)
        if (!targetState.isGroupAvailableForItemChange(asset.group, ignoreItemsInGroup = asset.group)) {
            add(BcPermissionReasons.targetZoneBlocked(asset.group))
        }
        currentItem?.lockedItemBlockingReason(context, context.rules.asset(currentItem.group, currentItem.name))?.let(::add)
        addAll(itemPermissionBlockingReasons(context, asset, updatePropertyJson))
        addAll(itemSharedSettingBlockingReasons(context, asset, updatePropertyJson))
        if (!asset.enable) {
            add(BcPermissionReasons.assetDisabled(asset.group, asset.name))
        }
        if (asset.allowActivePose.isNotEmpty() && !targetState.ignoringGroup(asset.group).activePoseAllowedBy(asset.allowActivePose)) {
            add(BcPermissionReasons.targetPoseNotAllowed(asset.group, asset.name, targetState.activePose.toList(), asset.allowActivePose))
        }

        val prerequisiteState = targetState.ignoringGroup(asset.group)
        addAll(
            itemPrerequisiteBlockingReasons(
                context = context,
                targetState = prerequisiteState,
                prerequisites = asset.prerequisites,
                unsupportedReason = { unsupported ->
                    BcPermissionReasons.unsupportedAssetPrerequisite(asset.group, asset.name, unsupported)
                },
            ),
        )
        val optionPrerequisites = updatePropertyJson.readBcPropertyTokens("Prerequisite")
        context.rules.extendedItem(asset.group, asset.name)?.takeIf { it.textFields.isNotEmpty() }?.let { text ->
            if (!text.validText(text.textValues(updatePropertyJson))) {
                add(BcPermissionReason(BcPermissionReasonCode.InvalidItemText, "Text exceeds the item limit or contains unsupported characters."))
            }
        }
        addAll(
            itemPrerequisiteBlockingReasons(
                context = context,
                targetState = prerequisiteState,
                prerequisites = optionPrerequisites,
                unsupportedReason = { unsupported ->
                    BcPermissionReasons.unsupportedOptionPrerequisite(asset.group, asset.name, unsupported)
                },
            ),
        )
        if (asset.ownerOnly && !context.actor.isOwnerOf(context.target)) {
            add(BcPermissionReasons.itemExclusivePermissionDenied(asset.group, asset.name, "owner"))
        }
        if (asset.loverOnly && !context.actor.isLoverOf(context.target)) {
            add(BcPermissionReasons.itemExclusivePermissionDenied(asset.group, asset.name, "lover"))
        }
        if (asset.familyOnly && !context.actor.isFamilyOf(context.target)) {
            add(BcPermissionReasons.itemExclusivePermissionDenied(asset.group, asset.name, "family"))
        }
    }


    private fun itemPrerequisiteBlockingReasons(
        context: BcPermissionContext,
        targetState: BcCharacterRuleState,
        prerequisites: Set<String>,
        unsupportedReason: (Set<String>) -> BcPermissionReason,
    ): List<BcPermissionReason> {
        if (prerequisites.isEmpty()) return emptyList()
        val unsupported = prerequisites.filterNot(::isSupportedItemPrerequisite).toSet()
        if (unsupported.isNotEmpty()) return listOf(unsupportedReason(unsupported))
        val actorState = resolveCharacterState(context.actor, context.rules, BcPermissionItemSource.Actor)
        return buildList {
            prerequisites.forEach { prerequisite ->
                when (prerequisite) {
                    "UseHands" -> if (!actorState.canUseHands()) add(BcPermissionReasons.actorCannotUseHands())
                    "UseArms" -> if (!actorState.canUseArms()) add(BcPermissionReasons.actorCannotUseArms())
                    "UseFeet" -> if (!actorState.canUseFeet()) add(BcPermissionReasons.actorCannotUseFeet())
                    "UseMouth", "UseTongue" -> if (!actorState.canUseMouthOrTongue()) add(BcPermissionReasons.actorCannotUseMouth())
                    "CanInteract" -> if (!actorState.canInteract()) add(BcPermissionReasons.actorPrerequisiteNotMet(prerequisite))
                    else -> targetPrerequisiteBlockingReason(context, targetState, prerequisite)?.let(::add)
                }
            }
        }
    }

    private fun targetPrerequisiteBlockingReason(
        context: BcPermissionContext,
        targetState: BcCharacterRuleState,
        prerequisite: String,
    ): BcPermissionReason? {
        fun blockedGroup(group: String): BcPermissionReason? =
            if (targetState.isGroupAccessibleForActivity(group)) null else BcPermissionReasons.targetZoneBlocked(group)
        fun occupiedGroup(group: String): BcPermissionReason? =
            if (targetState.isGroupEmpty(group)) null else BcPermissionReasons.targetZoneOccupied(group)
        fun missing(): BcPermissionReason = BcPermissionReasons.targetPrerequisiteNotMet(prerequisite)
        fun poseAllowed(pose: String): BcPermissionReason? = if (targetState.poseAvailable(pose)) null else missing()

        return when (prerequisite) {
            "Collared" -> if (targetState.isCollared()) null else missing()
            "Leashed" -> if (targetState.isLeashed()) null else missing()
            "NotSuspended" -> if (!targetState.isSuspended()) null else missing()
            "NotLifted" -> if (!targetState.isLifted()) null else missing()
            "NotMounted" -> if (!targetState.isMounted()) null else missing()
            "NotChained" -> if (!targetState.isChained()) null else missing()
            "NotKneeling" -> if (!targetState.isKneeling()) null else missing()
            "CanTalk" -> if (targetState.canTalk()) null else BcPermissionReasons.targetCannotUseTongue()
            "CannotTalk" -> if (!targetState.canTalk()) null else missing()
            "HasVagina", "HasVulva" -> if (context.target.hasBodyPart("vulva")) null else missing()
            "HasPenis" -> if (context.target.hasBodyPart("penis")) null else missing()
            "HasBreasts" -> if (context.target.hasBodyPart("breasts")) null else missing()
            "HasFlatChest" -> if (context.target.hasBodyPart("flatChest")) null else missing()
            "CanHaveErection" -> if (context.target.hasBodyPart("penis") && !targetState.isVulvaChaste()) null else missing()
            "CanBeLimp" -> if (context.target.hasBodyPart("penis") && !targetState.hasForcedErection()) null else missing()
            "NoErection" -> if (!targetState.hasForcedErection()) null else missing()
            "OnBed" -> if (targetState.isOnBed()) null else missing()
            "CanBeCeilingTethered" -> if (targetState.hasAnyItemInGroups("ItemArms", "ItemTorso", "ItemTorso2", "ItemPelvis")) null else missing()
            "DisplayFrame" -> if (!targetState.hasAnyItemInGroups("ItemArms", "ItemLegs", "ItemFeet", "ItemBoots", "Cloth", "ClothLower", "Shoes")) null else missing()
            "GasMask" -> if (!targetState.hasItemInGroupWithName("ItemArms", "Pillory") && !targetState.hasItemInGroupWithName("ItemDevices", "TheDisplayFrame")) null else missing()
            "CannotHaveWand" -> if (!targetState.hasItemInGroupWithName("ItemVulva", "WandBelt", "HempRopeBelt")) null else missing()
            "CannotBeSuited" -> if (!targetState.hasItemInGroupWithName("ItemArms", "FullLatexSuit")) null else missing()
            "CanAttachMittens" -> if (targetState.hasAttributeLike("CanAttachMittens")) null else missing()
            "NeedsChestHarness" -> if (targetState.hasAttributeLike("IsChestHarness") || targetState.hasItemNameOrEffectLike("ChestHarness")) null else missing()
            "NeedsNippleRings" -> if (targetState.hasItemNameOrEffectLike("NippleRing", "NippleRings") || targetState.hasItemInGroupWithName("ItemNipplesPiercings", "RoundPiercing")) null else missing()
            "NeedsHipHarness" -> if (targetState.hasAttributeLike("IsHipHarness") || targetState.hasItemNameOrEffectLike("HipHarness", "Harness")) null else missing()
            "RemotesAllowed" -> if (context.target.isSelf && context.target.hasOwnerRuleFlag("BlockRemoteSelf")) missing() else null

            "AccessMouth" -> blockedGroup("ItemMouth")
            "AccessVulva" -> blockedGroup("ItemVulva")
            "AccessFullPenis" -> blockedGroup("ItemPenis")
            "AccessBreast", "AccessBreastSuitZip" -> blockedGroup("ItemBreast")
            "AccessButt" -> blockedGroup("ItemButt")
            "AccessTorso" -> blockedGroup("ItemTorso")
            "AccessCrotch" -> blockedGroup("ItemPelvis") ?: blockedGroup("ItemVulva") ?: blockedGroup("ItemButt")
            "CanCoverVulva" -> if (!targetState.hasEffectLike("VulvaShaft")) null else missing()
            "VulvaEmpty" -> occupiedGroup("ItemVulva")
            "AssEmpty", "ButtEmpty" -> occupiedGroup("ItemButt")
            "ClitEmpty" -> occupiedGroup("ItemVulvaPiercings")
            "EyesEmpty" -> occupiedGroup("ItemHead")
            "HoodEmpty", "NotMasked" -> occupiedGroup("ItemHood")
            "NoItemFeet" -> occupiedGroup("ItemFeet")
            "NoItemArms" -> occupiedGroup("ItemArms")
            "NoItemLegs" -> occupiedGroup("ItemLegs")
            "NoItemHands" -> occupiedGroup("ItemHands")
            "NoClothLower" -> occupiedGroup("ClothLower")
            "NoOuterClothes" -> if (targetState.hasAnyItemInGroups("Cloth", "ClothLower", "ClothOuter", "ClothAccessory")) missing() else null
            "NakedFeet" -> if (targetState.hasAnyItemInGroups("ItemBoots", "Socks", "Shoes")) missing() else null
            "NakedHands" -> if (targetState.hasAnyItemInGroups("ItemHands", "Gloves")) missing() else null
            "NoChastityCage" -> if (targetState.hasItemNameOrEffectLike("Chastity", "Cage") || targetState.isVulvaChaste()) missing() else null
            "NotChaste" -> if (targetState.isVulvaChaste()) missing() else null
            "NoMaidTray" -> if (targetState.hasItemInGroupWithName("ItemMisc", "WoodenMaidTray", "WoodenMaidTrayFull") || targetState.hasItemNameOrEffectLike("MaidTray", "Tray")) missing() else null
            "GagUnique", "GagCorset" -> if (targetState.hasAnyItemInGroups("ItemMouth", "ItemMouth2", "ItemMouth3")) missing() else null
            "GagFlat" -> null
            "BlockedMouth" -> if (targetState.isMouthBlocked()) null else missing()
            "NotProtrudingFromMouth" -> if (!targetState.hasItemNameOrEffectLike("Protruding", "ProtrudingMouth", "StickOutMouth")) null else missing()
            "CuffedArms" -> if (targetState.hasCuffedArms()) null else missing()
            "CuffedFeet" -> if (targetState.hasCuffedFeet()) null else missing()
            "CuffedLegs" -> if (targetState.hasCuffedLegs()) null else missing()
            "CuffedArmsOrEmpty" -> if (targetState.hasCuffedArms() || targetState.isGroupEmpty("ItemArms")) null else missing()
            "CuffedFeetOrEmpty" -> if (targetState.hasCuffedFeet() || targetState.isGroupEmpty("ItemFeet")) null else missing()
            "CuffedLegsOrEmpty" -> if (targetState.hasCuffedLegs() || targetState.isGroupEmpty("ItemLegs")) null else missing()

            // Pose prerequisites. We do not have BC's full PoseAvailable graph in native state yet,
            // so these default to allow unless the current snapshot has an obvious incompatible
            // effect or pose.
            "CanBaseUpper" -> poseAllowed("BaseUpper")
            "CanBackBoxTie" -> poseAllowed("BackBoxTie")
            "CanBackCuffs" -> poseAllowed("BackCuffs")
            "CanBackElbowTouch" -> poseAllowed("BackElbowTouch")
            "CanOverTheHead" -> poseAllowed("OverTheHead")
            "CanYoked" -> poseAllowed("Yoked")
            "CanTapedHands" -> poseAllowed("TapedHands")
            "CanBaseLower" -> poseAllowed("BaseLower")
            "CanKneel" -> poseAllowed("Kneel")
            "CanKneelingSpread" -> poseAllowed("KneelingSpread")
            "CanLegsClosed" -> poseAllowed("LegsClosed")
            "CanLegsOpen" -> poseAllowed("LegsOpen")
            "CanSpread" -> poseAllowed("Spread")
            "CanHogtied" -> poseAllowed("Hogtied")
            "CanAllFours" -> poseAllowed("AllFours")
            "CanSuspension" -> poseAllowed("Suspension")

            else -> BcPermissionReasons.unsupportedActivityPrerequisite("BC", setOf(prerequisite))
        }
    }

    private fun itemActorBlockingReason(
        context: BcPermissionContext,
        actorState: BcCharacterRuleState,
    ): BcPermissionReason? {
        if (context.actor.memberNumber == context.target.memberNumber) return null
        return if (actorState.canInteract()) null else BcPermissionReasons.actorPrerequisiteNotMet("CanInteract")
    }

    private fun wearOrReplaceAction(
        previousAssetName: String?,
        nextAssetName: String,
        previousCraftName: String? = null,
    ): BcItemActionMessage = if (previousAssetName.isNullOrBlank()) {
        BcItemActionMessage(
            content = "ActionUse",
            nextAssetName = nextAssetName,
        )
    } else {
        BcItemActionMessage(
            content = "ActionSwap",
            previousAssetName = previousAssetName,
            nextAssetName = nextAssetName,
            previousCraftName = previousCraftName,
        )
    }

    private fun removeAction(previousAssetName: String, craftName: String?): BcItemActionMessage =
        BcItemActionMessage(
            content = "ActionRemove",
            previousAssetName = previousAssetName,
            previousCraftName = craftName,
        )

    private fun modifyAction(itemName: String, craftName: String?): BcItemActionMessage =
        BcItemActionMessage(
            content = "ActionUse",
            nextAssetName = itemName,
            nextCraftName = craftName,
        )

    private fun itemServerCheckOrAllowed(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate,
        command: BcCharacterItemUpdateCommand,
    ): CandidateEvaluation {
        if (context.serverAllowItem != true) {
            localAllowItemBlockingReason(context)?.let { return denied(candidate, it) }
        }
        return when (context.serverAllowItem) {
            null -> CandidateEvaluation.NeedsServerCheck(
                candidate = candidate,
                reasons = listOf(BcPermissionReasons.serverAllowItemRequired()),
            )
            false -> denied(candidate, BcPermissionReasons.serverAllowItemDenied())
            true -> CandidateEvaluation.Allowed(
                executables = listOf(BcExecutableInteraction.ItemUpdate(command = command)),
            )
        }
    }

    private fun itemSharedSettingBlockingReasons(
        context: BcPermissionContext,
        asset: BcAssetRule,
        updatePropertyJson: String?,
    ): List<BcPermissionReason> = buildList {
        val group = context.rules.group(asset.group)
        val settings = context.target.onlineSharedSettingsJson
        val allowFullWardrobeAccess = settings.readBcBooleanProperty("AllowFullWardrobeAccess")
        if (
            context.actor.memberNumber != context.target.memberNumber &&
            allowFullWardrobeAccess != true &&
            group?.kind == BcAppearanceGroupKind.Appearance
        ) {
            add(BcPermissionReasons.targetPrerequisiteNotMet("AllowFullWardrobeAccess"))
        }

        val blockBodyCosplay = settings.readBcBooleanProperty("BlockBodyCosplay") == true
        val bodyCosplay = updatePropertyJson.readBcBooleanProperty("BodyCosplay") == true ||
            updatePropertyJson.containsTokenOrFalse("BodyCosplay") ||
            asset.effects.any { it.containsToken("BodyCosplay") }
        if (blockBodyCosplay && bodyCosplay) {
            add(BcPermissionReasons.targetPrerequisiteNotMet("BlockBodyCosplay"))
        }
    }

    private fun itemPermissionBlockingReasons(
        context: BcPermissionContext,
        asset: BcAssetRule,
        updatePropertyJson: String?,
    ): List<BcPermissionReason> = buildList {
        val typeTokens = itemPermissionTypeTokens(context, asset, updatePropertyJson)
        val typeCandidates = setOf<String?>(null) + typeTokens

        typeCandidates.forEach { type ->
            if (context.target.blockItemsJson.hasItemPermissionEntry(asset.group, asset.name, type)) {
                add(BcPermissionReasons.targetItemPermissionBlocked(asset.group, asset.name, type))
            }
        }

        val limited = typeCandidates.firstOrNull { type ->
            context.target.limitedItemsJson.hasItemPermissionEntry(asset.group, asset.name, type)
        }
        if (limited != null || context.target.limitedItemsJson.hasItemPermissionEntry(asset.group, asset.name, null)) {
            if (!context.actor.canUseLimitedTargetItem(context.target)) {
                add(BcPermissionReasons.targetItemPermissionLimited(asset.group, asset.name, limited))
            }
        }
    }

    private fun localAllowItemBlockingReason(context: BcPermissionContext): BcPermissionReason? {
        val level = context.target.allowedInteractions ?: return null
        if (context.actor.memberNumber == context.target.memberNumber) return null
        if (context.actor.isOwnerOf(context.target)) return null

        val blacklisted = context.actor.memberNumber in context.target.blackList
        return when {
            level <= ALLOW_EVERYONE -> null
            level == ALLOW_EVERYONE_EXCEPT_BLACKLIST -> {
                if (blacklisted) {
                    BcPermissionReasons.targetBlacklistedActor(context.target.memberNumber, context.actor.memberNumber)
                } else {
                    null
                }
            }
            level == ALLOW_OWNER_LOVERS_WHITELIST_AND_DOMS -> {
                val actorDominant = context.actor.reputationValue("Dominant")
                val targetDominant = context.target.reputationValue("Dominant")
                val allowed = !blacklisted &&
                    (actorDominant + 25 >= targetDominant || context.actor.isWhitelistedBy(context.target) || context.actor.isLoverOf(context.target))
                if (allowed) null else BcPermissionReasons.localAllowItemDenied("owner, lover, whitelist, or dominant")
            }
            level == ALLOW_OWNER_LOVERS_WHITELIST -> {
                if (context.actor.isWhitelistedBy(context.target) || context.actor.isLoverOf(context.target)) {
                    null
                } else {
                    BcPermissionReasons.localAllowItemDenied("owner, lover, or whitelist")
                }
            }
            level == ALLOW_OWNER_LOVERS -> {
                if (context.actor.isLoverOf(context.target)) null else BcPermissionReasons.localAllowItemDenied("owner or lover")
            }
            else -> BcPermissionReasons.localAllowItemDenied("owner")
        }
    }

    private fun activityExecutable(
        candidate: BcInteractionCandidate.Activity,
        activity: BcActivityRule,
        usedItem: BcWornItemState?,
        isSelfTarget: Boolean,
    ): BcExecutableInteraction.Activity = BcExecutableInteraction.Activity(
        command = BcActivityCommand(
            targetMemberNumber = candidate.targetMemberNumber,
            targetGroup = candidate.group,
            activityName = candidate.activityName,
            activityAssetGroup = usedItem?.group,
            activityAssetName = usedItem?.name,
            fallbackText = activity.chatTextFor(candidate.group, isSelfTarget),
        ),
        usedItem = usedItem?.toPermissionItemRef(),
        effects = activity.effects,
    )

    private fun selectActivityExecutables(
        candidate: BcInteractionCandidate.Activity,
        executables: List<BcExecutableInteraction.Activity>,
    ): CandidateEvaluation {
        val selectedGroup = candidate.activityAssetGroup?.trim()?.takeIf { it.isNotEmpty() }
        val selectedName = candidate.activityAssetName?.trim()?.takeIf { it.isNotEmpty() }
        if (selectedGroup == null && selectedName == null) {
            return CandidateEvaluation.Allowed(executables)
        }

        val selected = executables.filter { executable ->
            selectedGroup != null &&
                selectedName != null &&
                executable.command.activityAssetGroup?.sameToken(selectedGroup) == true &&
                executable.command.activityAssetName?.sameToken(selectedName) == true
        }
        return if (selected.isNotEmpty()) {
            CandidateEvaluation.Allowed(selected)
        } else {
            denied(
                candidate,
                BcPermissionReasons.selectedActivityItemUnavailable(
                    activityName = candidate.activityName,
                    group = selectedGroup,
                    name = selectedName,
                ),
            )
        }
    }

    private fun denied(
        candidate: BcInteractionCandidate,
        reason: BcPermissionReason,
    ): CandidateEvaluation.Denied = CandidateEvaluation.Denied(
        candidate = candidate,
        reasons = listOf(reason),
    )

    private fun isSupportedActivityPrerequisite(prerequisite: String): Boolean =
        prerequisite.startsWith(ACTOR_NEEDS_PREFIX) ||
            prerequisite.startsWith(TARGET_NEEDS_PREFIX) ||
            prerequisite.startsWith("Luzi_") ||
            prerequisite in SUPPORTED_ACTIVITY_PREREQUISITES ||
            isSupportedTargetPrerequisite(prerequisite)

    private fun isSupportedTargetPrerequisite(prerequisite: String): Boolean =
        prerequisite in SUPPORTED_TARGET_PREREQUISITES

    private fun isSupportedItemPrerequisite(prerequisite: String): Boolean =
        prerequisite in SUPPORTED_ITEM_PREREQUISITES || isSupportedTargetPrerequisite(prerequisite)

    private sealed interface CandidateEvaluation {
        data class Allowed(val executables: List<BcExecutableInteraction>) : CandidateEvaluation
        data class Denied(
            val candidate: BcInteractionCandidate,
            val reasons: List<BcPermissionReason>,
        ) : CandidateEvaluation

        data class NeedsServerCheck(
            val candidate: BcInteractionCandidate,
            val reasons: List<BcPermissionReason>,
        ) : CandidateEvaluation
    }

    private fun resolveCharacterState(
        character: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        source: BcPermissionItemSource,
    ): BcCharacterRuleState = characterStateResolver.resolve(
        character = character,
        rules = rules,
        source = source.toCharacterItemSource(),
    )

    private fun BcPermissionItemSource.toCharacterItemSource(): BcCharacterItemSource = when (this) {
        BcPermissionItemSource.Actor -> BcCharacterItemSource.Actor
        BcPermissionItemSource.Target -> BcCharacterItemSource.Target
    }

    private fun BcWornItemState.toPermissionItemRef(): BcPermissionItemRef = BcPermissionItemRef(
        group = group,
        name = name,
        source = when (source) {
            BcCharacterItemSource.Actor -> BcPermissionItemSource.Actor
            BcCharacterItemSource.Target -> BcPermissionItemSource.Target
        },
    )

    private fun removeItemLocalBlockingReasons(
        context: BcPermissionContext,
        currentItem: BcCharacterItem,
        currentAsset: BcAssetRule,
    ): List<BcPermissionReason> = buildList {
        currentItem.lockedItemBlockingReason(context, currentAsset)?.let(::add)
        addAll(itemSharedSettingBlockingReasons(context, currentAsset, currentItem.propertyJson))
        removeExclusiveBlockingReason(context, currentAsset, currentItem)?.let(::add)
    }

    private fun removeExclusiveBlockingReason(
        context: BcPermissionContext,
        currentAsset: BcAssetRule,
        currentItem: BcCharacterItem,
    ): BcPermissionReason? {
        if (context.actor.memberNumber == context.target.memberNumber) return null

        val lockAsset = currentItem.lockAsset(context)
        val actorIsOwner = context.actor.isOwnerOf(context.target)
        val actorIsLover = context.actor.isLoverOf(context.target)
        val actorIsFamily = context.actor.isFamilyOf(context.target)

        // Mirrors ValidationCanRemoveItem's AllowRemoveExclusive exception: owner/lover/family
        // exclusive items can be removed by the corresponding relation when the lock itself does
        // not impose the same exclusive relation.
        if (currentAsset.allowRemoveExclusive) {
            if (currentAsset.ownerOnly && lockAsset?.ownerOnly != true) return null
            if (currentAsset.loverOnly && lockAsset?.loverOnly != true) return null
            if (currentAsset.familyOnly && lockAsset?.familyOnly != true) return null
        }

        // Mirrors ValidationCanRemoveItem's lock relation gates before falling back to the
        // ordinary add/remove validation for the previous item.
        if (lockAsset?.loverOnly == true && !actorIsOwner && !actorIsLover) {
            return BcPermissionReasons.targetItemLocked(currentItem.group, currentItem.name, currentItem.lockName())
        }
        if (lockAsset?.familyOnly == true && !actorIsOwner && !actorIsLover && !actorIsFamily) {
            return BcPermissionReasons.targetItemLocked(currentItem.group, currentItem.name, currentItem.lockName())
        }
        if (lockAsset?.ownerOnly == true && !actorIsOwner) {
            return BcPermissionReasons.targetItemLocked(currentItem.group, currentItem.name, currentItem.lockName())
        }

        if (currentAsset.ownerOnly && !actorIsOwner) {
            return BcPermissionReasons.itemExclusivePermissionDenied(currentAsset.group, currentAsset.name, "owner")
        }
        if (currentAsset.loverOnly && !actorIsOwner && !actorIsLover) {
            return BcPermissionReasons.itemExclusivePermissionDenied(currentAsset.group, currentAsset.name, "owner or lover")
        }
        if (currentAsset.familyOnly && !actorIsFamily) {
            return BcPermissionReasons.itemExclusivePermissionDenied(currentAsset.group, currentAsset.name, "family")
        }
        if (!currentAsset.enable) {
            return BcPermissionReasons.assetDisabled(currentAsset.group, currentAsset.name)
        }
        return null
    }

    private fun itemPermissionTypeTokens(
        context: BcPermissionContext,
        asset: BcAssetRule,
        propertyJson: String?,
    ): Set<String> = buildSet {
        val extended = context.rules.extendedItem(asset.group, asset.name)
        BcItemPropertyJson.readTypeRecord(propertyJson).forEach { (key, value) ->
            // BC TypedItem/VibratingItem does not run InventoryBlockedOrLimited for option index 0.
            // ModularItem still checks module type permissions, including index 0, so only skip
            // the typed/vibrating default option.
            val skipDefaultTypedOption = value == 0 &&
                (extended?.archetype == BcExtendedItemArchetype.Typed || extended?.archetype == BcExtendedItemArchetype.Vibrating)
            if (!skipDefaultTypedOption) add("$key$value")
        }
        propertyJson.readBcStringProperty("Type")?.takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun BcCharacterItem.lockedItemBlockingReason(
        context: BcPermissionContext,
        itemAsset: BcAssetRule? = null,
    ): BcPermissionReason? {
        val lockName = lockName() ?: return null
        if (context.canUnlockItem(this, itemAsset, lockName)) return null
        return BcPermissionReasons.targetItemLocked(group, name, lockName)
    }

    private fun BcPermissionContext.canUnlockItem(
        item: BcCharacterItem,
        itemAsset: BcAssetRule?,
        lockName: String,
    ): Boolean {
        // AFC needs an explicit unlock and companion Remove notification before equipment changes.
        if (BcAfcHeartLock.identity(target, item) != null) return false
        val actorState = resolveCharacterState(actor, rules, BcPermissionItemSource.Actor)
        if (actor.memberNumber != target.memberNumber && !actorState.canInteract()) return false

        // DialogCanUnlock special-cases ExclusivePadlock: it can be unlocked on other characters,
        // but not by the player on themselves. Other exclusive/keyholder locks remain conservative.
        if (lockName.equals("ExclusivePadlock", ignoreCase = true)) return !target.isSelf

        // DialogCanUnlock checks owner/lover/family exclusivity on the item asset before normal keys.
        if (itemAsset?.ownerOnly == true) return itemAsset.enable && actor.isOwnerOf(target)
        if (itemAsset?.loverOnly == true) return itemAsset.enable && actor.isLoverOf(target)
        if (itemAsset?.familyOnly == true) return itemAsset.enable && actor.isFamilyOf(target)

        val lockAsset = item.lockAsset(this)
        if (lockAsset?.ownerOnly == true) return actor.isOwnerOf(target)
        if (lockAsset?.loverOnly == true) return actor.isOwnerOf(target) || actor.isLoverOf(target)
        if (lockAsset?.familyOnly == true) return actor.isFamilyOf(target)

        // We cannot locally prove password/timer/high-security keyholder access from room state.
        // Keep those locked until the flow supports an explicit unlock action or server-side proof.
        if (lockName.contains("Password", ignoreCase = true)) return false
        if (lockName.contains("Timer", ignoreCase = true)) return false
        if (lockName.contains("HighSecurity", ignoreCase = true)) return false
        if (lockName.contains("Intricate", ignoreCase = true)) return false

        // Safeword locks are self-removable; generic Owner/Lover/Family string fallback keeps the
        // previous local behavior for older snapshots where the lock asset was not parsed.
        return when {
            lockName.contains("Safeword", ignoreCase = true) -> actor.memberNumber == target.memberNumber
            lockName.contains("Owner", ignoreCase = true) || lockName.contains("Mistress", ignoreCase = true) -> actor.isOwnerOf(target)
            lockName.contains("Lover", ignoreCase = true) -> actor.isOwnerOf(target) || actor.isLoverOf(target)
            lockName.contains("Family", ignoreCase = true) -> actor.isFamilyOf(target)
            else -> false
        }
    }

    private fun BcCharacterItem.lockAsset(context: BcPermissionContext): BcAssetRule? =
        lockName()?.let { context.rules.asset("ItemMisc", it) }

    private fun BcCharacterItem.lockName(): String? = propertyJson.readBcStringProperty("LockedBy")

    private fun String.removePrefixOrNull(prefix: String): String? =
        takeIf { startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() }


    private companion object {
        const val ACTOR_NEEDS_PREFIX = "Needs-"
        const val TARGET_NEEDS_PREFIX = "TargetNeeds-"
        const val SHOCK_ITEM = "ShockItem"
        const val ALLOW_EVERYONE = 0
        const val ALLOW_EVERYONE_EXCEPT_BLACKLIST = 1
        const val ALLOW_OWNER_LOVERS_WHITELIST_AND_DOMS = 2
        const val ALLOW_OWNER_LOVERS_WHITELIST = 3
        const val ALLOW_OWNER_LOVERS = 4

        val SUPPORTED_ITEM_PREREQUISITES = setOf(
            "UseHands",
            "UseArms",
            "UseFeet",
            "UseMouth",
            "UseTongue",
            "CanInteract",
        )

        val SUPPORTED_TARGET_PREREQUISITES = setOf(
            "Collared",
            "Leashed",
            "NotSuspended",
            "NotLifted",
            "NotMounted",
            "CanTalk",
            "CannotTalk",
            "HasVagina",
            "HasVulva",
            "HasPenis",
            "HasBreasts",
            "HasFlatChest",
            "AccessMouth",
            "AccessVulva",
            "AccessFullPenis",
            "AccessBreast",
            "AccessBreastSuitZip",
            "AccessButt",
            "AccessTorso",
            "AccessCrotch",
            "CanCoverVulva",
            "VulvaEmpty",
            "AssEmpty",
            "ButtEmpty",
            "ClitEmpty",
            "EyesEmpty",
            "HoodEmpty",
            "NotMasked",
            "NoItemFeet",
            "NoItemArms",
            "NoItemLegs",
            "NoItemHands",
            "NoClothLower",
            "NoOuterClothes",
            "NoChastityCage",
            "NotChaste",
            "NoMaidTray",
            "GagUnique",
            "GagFlat",
            "GagCorset",
            "BlockedMouth",
            "NotProtrudingFromMouth",
            "CuffedArms",
            "CuffedFeet",
            "CuffedLegs",
            "CuffedArmsOrEmpty",
            "CuffedFeetOrEmpty",
            "CuffedLegsOrEmpty",
            "CanLegsOpen",
            "CanLegsClosed",
            "NeedsNippleRings",
            "NeedsHipHarness",
            "CanBaseUpper",
            "CanBackBoxTie",
            "CanBackCuffs",
            "CanBackElbowTouch",
            "CanOverTheHead",
            "CanYoked",
            "CanTapedHands",
            "CanBaseLower",
            "CanKneel",
            "CanKneelingSpread",
            "CanSpread",
            "CanHogtied",
            "CanAllFours",
            "CanSuspension",
            "NakedFeet",
            "NakedHands",
            "NeedsChestHarness",
            "NotChained",
            "NotKneeling",
            "OnBed",
            "CanBeCeilingTethered",
            "CanBeLimp",
            "CanHaveErection",
            "NoErection",
            "RemotesAllowed",
            "GasMask",
            "DisplayFrame",
            "CannotHaveWand",
            "CannotBeSuited",
            "CanAttachMittens",
        )

        val SUPPORTED_ACTIVITY_PREREQUISITES = setOf(
            "UseHands",
            "UseArms",
            "UseFeet",
            "UseMouth",
            "UseTongue",
            "CantUseArms",
            "CantUseFeet",
            "TargetCanUseTongue",
            "TargetMouthOpen",
            "TargetMouthBlocked",
            "IsGagged",
            "TargetKneeling",
            "MoveHead",
            "ZoneAccessible",
            "TargetZoneAccessible",
            "ZoneNaked",
            "TargetZoneNaked",
            "VulvaEmpty",
            "AssEmpty",
            "CanUsePenis",
            "Brothers",
            "Sisters",
            "SiblingsWithDifferentGender",
            SHOCK_ITEM,
        )

    }
}

private fun BcRuleCatalog.activityGroup(group: dev.vivian.mobilebc.domain.bcrules.BcGroupRule): dev.vivian.mobilebc.domain.bcrules.BcGroupRule =
    group.mirrorActivitiesFrom?.let(::group) ?: group

private fun BcRuleCatalog.activityGroupMatches(group: String, allowedGroups: Set<String>): Boolean =
    allowedGroups.any { allowed ->
        allowed.sameToken(group) || this.group(allowed)?.mirrorActivitiesFrom?.sameToken(group) == true
    }



private fun String?.hasItemPermissionEntry(group: String, asset: String, type: String?): Boolean {
    val json = this?.takeIf { it.isNotBlank() } ?: return false
    if (!json.contains(group, ignoreCase = true) || !json.contains(asset, ignoreCase = true)) return false

    val arrayStyleObjects = Regex("""\{[^{}]*["']Group["']\s*:\s*["']${Regex.escape(group)}["'][^{}]*["']Name["']\s*:\s*["']${Regex.escape(asset)}["'][^{}]*}""", RegexOption.IGNORE_CASE)
        .findAll(json)
        .map { it.value }
        .toList()
    if (arrayStyleObjects.any { objectText ->
            if (type == null) {
                !Regex("""["']Type["']\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(objectText) ||
                    Regex("""["']Type["']\s*:\s*["']\s*["']""", RegexOption.IGNORE_CASE).containsMatchIn(objectText)
            } else {
                Regex("""["']Type["']\s*:\s*["']${Regex.escape(type)}["']""", RegexOption.IGNORE_CASE).containsMatchIn(objectText)
            }
        }) return true

    val groupObject = Regex("""["']${Regex.escape(group)}["']\s*:\s*\{([\s\S]*?)\}\s*(,|\})""", RegexOption.IGNORE_CASE)
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
    if (groupObject != null) {
        val assetValues = Regex("""["']${Regex.escape(asset)}["']\s*:\s*\[([^\]]*)]""", RegexOption.IGNORE_CASE)
            .find(groupObject)
            ?.groupValues
            ?.getOrNull(1)
        if (assetValues != null) {
            if (type == null) {
                return Regex("""["']\s*["']""").containsMatchIn(assetValues)
            }
            return Regex("""["']${Regex.escape(type)}["']""", RegexOption.IGNORE_CASE).containsMatchIn(assetValues)
        }
    }

    val keyObject = Regex("""["']${Regex.escape(group)}/${Regex.escape(asset)}["']\s*:\s*\{([\s\S]*?)\}""", RegexOption.IGNORE_CASE)
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
    if (keyObject != null) {
        if (type == null && Regex("""["']Permission["']\s*:\s*["'](?:Block|Limited)["']""", RegexOption.IGNORE_CASE).containsMatchIn(keyObject)) {
            return true
        }
        if (type != null) {
            val typePermissions = Regex("""["']TypePermissions["']\s*:\s*\{([\s\S]*?)\}""", RegexOption.IGNORE_CASE)
                .find(keyObject)
                ?.groupValues
                ?.getOrNull(1)
            if (typePermissions != null && Regex("""["']${Regex.escape(type)}["']\s*:\s*["'](?:Block|Limited)["']""", RegexOption.IGNORE_CASE).containsMatchIn(typePermissions)) {
                return true
            }
        }
    }

    return false
}

private fun BcPermissionFilterResult.interactionCount(): Int =
    allowed.size + denied.size + needsServerCheck.size
