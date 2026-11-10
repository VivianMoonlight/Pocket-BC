package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionActivity
import dev.vivian.mobilebc.domain.bcitemproperty.currentSelectionSummary
import dev.vivian.mobilebc.domain.bcitemproperty.displayText
import dev.vivian.mobilebc.domain.bcpermission.BcExecutableInteraction
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionFilterRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionFilterResult
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRoomContext
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcInteractionPermissionEngine
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcActivitySource
import dev.vivian.mobilebc.domain.bcrules.BcActivityEffect
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.character.BcCharacterRosterState
import dev.vivian.mobilebc.domain.bcpermission.withItemPrivateState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot

/** Pure-ish projection boundary between permission/domain state and RoomDetails UI rows. */
internal class RoomDetailsInteractionProjector(
    private val permissionEngine: DefaultBcInteractionPermissionEngine,
    private val privateSnapshot: () -> BcSessionSnapshot? = { null },
    private val rosterState: () -> BcCharacterRosterState,
) {
    private var activityContext: BcPermissionContext? = null
    private var activityCatalog: BcInteractionCatalogSnapshot? = null
    private var activityRules: BcRuleCatalog? = null
    private var activityTarget: BcCharacterSnapshot? = null
    private val activityRows = mutableMapOf<Pair<String, Boolean>, List<InteractionActivityUiState>>()

    fun groupStates(
        mode: InteractionMode,
        target: BcCharacterSnapshot,
        snapshot: BcInteractionCatalogSnapshot?,
        rules: BcRuleCatalog?,
    ): List<InteractionGroupUiState> = when (mode) {
        InteractionMode.Closed -> emptyList()
        InteractionMode.ActionPicker -> actionPickerGroups(target, snapshot, rules)
        InteractionMode.ItemPicker -> buildItemPickerGroupStatesForTarget(target, snapshot, rules)
            .filter { it.kind == BcAppearanceGroupKind.Item }
        InteractionMode.ClothingPicker -> buildItemPickerGroupStatesForTarget(target, snapshot, rules)
            .filter { it.kind == BcAppearanceGroupKind.Clothing }
        InteractionMode.FullSheet -> allGroups(target, snapshot, rules)
    }

    fun selectGroup(
        groups: List<InteractionGroupUiState>,
        selectedGroupName: String?,
        mode: InteractionMode,
    ): InteractionGroupUiState? =
        groups.firstOrNull { it.name.equals(selectedGroupName, ignoreCase = true) }
            ?: if (mode == InteractionMode.FullSheet) groups.firstOrNull() else null

    fun currentItemUiState(
        target: BcCharacterSnapshot,
        group: String,
        snapshot: BcInteractionCatalogSnapshot?,
        rules: BcRuleCatalog?,
    ): InteractionCurrentItemUiState? {
        val item = target.appearance.firstOrNull { it.group.equals(group, ignoreCase = true) } ?: return null
        return item.toCurrentItemUiState(
            snapshot = snapshot,
            rules = rules,
            permission = removePermission(group = group, target = target, rules = rules),
        )
    }

    fun itemUiStates(
        group: String,
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog?,
        snapshot: BcInteractionCatalogSnapshot?,
    ): List<InteractionItemUiState> {
        val permissions = itemPermissions(group = group, target = target, rules = rules, snapshot = snapshot)
        return snapshot?.itemsForGroup(group).orEmpty().map { item ->
            val extended = rules?.extendedItem(item.group, item.name)
            val permission = extended?.let { rule ->
                extendedItemDefaultStatus(
                    group = item.group,
                    itemName = item.name,
                    target = target,
                    rules = checkNotNull(rules),
                    extended = rule,
                    fallback = permissions.status(ItemKey(item.group, item.name)),
                )
            } ?: permissions.status(ItemKey(item.group, item.name))
            InteractionItemUiState(
                group = item.group,
                name = item.name,
                displayName = item.displayName,
                enabled = permission.enabled,
                permissionMessage = permission.message,
                defaultPropertyJson = extended?.defaultPropertyJson,
                defaultDifficulty = extended?.defaultDifficulty,
                extendedKind = extended?.archetype?.wireValue,
                extendedStatusMessage = extended?.statusMessage,
            )
        }
    }

    fun activityUiStates(
        group: String,
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog?,
        snapshot: BcInteractionCatalogSnapshot?,
        allowedOnly: Boolean,
    ): List<InteractionActivityUiState> {
        val context = rules?.let { permissionContext(target, it) }
        // Keep only the current permission context; ordinary chat updates can reuse every group.
        if (activityCatalog !== snapshot || activityRules !== rules ||
            activityTarget != target || activityContext != context
        ) {
            activityRows.clear()
            activityCatalog = snapshot
            activityRules = rules
            activityTarget = target
            activityContext = context
        }
        return activityRows.getOrPut(group to allowedOnly) {
            buildActivityUiStates(group, rules, snapshot, allowedOnly, context)
        }
    }

    private fun buildActivityUiStates(
        group: String,
        rules: BcRuleCatalog?,
        snapshot: BcInteractionCatalogSnapshot?,
        allowedOnly: Boolean,
        context: BcPermissionContext?,
    ): List<InteractionActivityUiState> {
        val sourceGroup = rules?.group(group)?.mirrorActivitiesFrom
            ?.takeIf { rules.group(it) != null } ?: group
        val activityGroups = (listOf(group, sourceGroup) + rules?.groups.orEmpty().values
            .filter { it.mirrorActivitiesFrom.equals(sourceGroup, ignoreCase = true) }
            .map { it.name }).distinctBy { it.lowercase() }
        val catalogActivities = buildList {
            activityGroups.forEach { source ->
                addAll(snapshot?.activitiesForGroup(source).orEmpty().map { it.copy(group = group) })
            }
            rules?.activities?.values
                .orEmpty()
                .asSequence()
                .filter { activity ->
                    activity.source == BcActivitySource.Echo &&
                        (activity.targetGroups + activity.selfTargetGroups).any { allowed ->
                            activityGroups.any { it.equals(allowed, ignoreCase = true) }
                        }
                }
                .map { activity ->
                    BcInteractionActivity(
                        group = group,
                        name = activity.name,
                        displayName = activity.displayName,
                    )
                }
                .forEach(::add)
        }.distinctBy { ActivityKey(it.group, it.name) }
        if (catalogActivities.isEmpty()) return emptyList()

        if (rules == null) {
            if (allowedOnly) return emptyList()
            return catalogActivities.map { activity ->
                InteractionActivityUiState(
                    group = activity.group,
                    name = activity.name,
                    displayName = activity.displayName,
                    enabled = false,
                    permissionMessage = InteractionUserMessage.General(
                        InteractionUserMessageKey.InteractionDataPreparing,
                    ),
                )
            }
        }

        if (context == null) return if (allowedOnly) {
                emptyList()
            } else {
                catalogActivities.map { activity ->
                    InteractionActivityUiState(
                        group = activity.group,
                        name = activity.name,
                        displayName = activity.displayName,
                        enabled = false,
                        permissionMessage = InteractionUserMessage.General(
                            InteractionUserMessageKey.SelfCharacterUnavailable,
                        ),
                    )
                }
            }

        val displayNames = catalogActivities.associate { activity ->
            ActivityKey(activity.group, activity.name) to activity.displayName
        }
        val candidates = catalogActivities.map { activity ->
            BcInteractionCandidate.Activity(
                targetMemberNumber = context.target.memberNumber,
                group = activity.group,
                activityName = activity.name,
            )
        }
        val result = permissionEngine.filter(
            BcPermissionFilterRequest(
                context = context,
                candidates = candidates,
            ),
        )

        val rows = mutableListOf<InteractionActivityUiState>()
        result.allowed.filterIsInstance<BcExecutableInteraction.Activity>().forEach { executable ->
            val command = executable.command
            val key = ActivityKey(command.targetGroup, command.activityName)
            val usedItem = executable.usedItem
            rows += InteractionActivityUiState(
                group = command.targetGroup,
                name = command.activityName,
                displayName = displayNames[key] ?: command.activityName.toRoomDetailsHumanLabel(),
                activityAssetGroup = usedItem?.group,
                activityAssetName = usedItem?.name,
                usesWornItem = usedItem != null,
                warningMessage = executable.warnings.toUserMessageOrNull(),
                enabled = true,
                permissionMessage = null,
                effects = rules.activity(command.activityName).toUiEffects(),
            )
        }
        result.needsServerCheck.forEach { pending ->
            val candidate = pending.candidate as? BcInteractionCandidate.Activity ?: return@forEach
            rows += InteractionActivityUiState(
                group = candidate.group,
                name = candidate.activityName,
                displayName = displayNames[ActivityKey(candidate.group, candidate.activityName)]
                    ?: candidate.activityName.toRoomDetailsHumanLabel(),
                enabled = true,
                permissionMessage = pending.reasons.toUserMessageOrNull()
                    ?: InteractionUserMessage.Permission(BcPermissionReasonCode.ServerAllowItemRequired),
                effects = rules.activity(candidate.activityName).toUiEffects(),
            )
        }
        if (!allowedOnly) {
            result.denied.forEach { denied ->
                val candidate = denied.candidate as? BcInteractionCandidate.Activity ?: return@forEach
                rows += InteractionActivityUiState(
                    group = candidate.group,
                    name = candidate.activityName,
                    displayName = displayNames[ActivityKey(candidate.group, candidate.activityName)]
                        ?: candidate.activityName.toRoomDetailsHumanLabel(),
                    enabled = false,
                    permissionMessage = denied.reasons.toUserMessageOrNull()
                        ?: InteractionUserMessage.General(InteractionUserMessageKey.ItemUnavailable),
                    effects = rules.activity(candidate.activityName).toUiEffects(),
                )
            }
        }

        return rows
            .distinctBy { ActivityKey(it.group, it.name) }
            .filter { !allowedOnly || it.enabled }
    }

    fun permissionContext(
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        serverAllowItem: Boolean? = null,
    ): BcPermissionContext? {
        val roster = rosterState() as? BcCharacterRosterState.InRoom ?: return null
        val actor = roster.characters.firstOrNull { it.isSelf } ?: return null
        return BcPermissionContext(
            actor = actor,
            target = target,
            room = BcPermissionRoomContext(
                roomName = roster.roomName.value,
                memberNumbers = roster.characters.map { it.memberNumber }.toSet(),
                blockCategories = roster.blockCategories,
            ),
            rules = rules,
            serverAllowItem = serverAllowItem,
        ).withItemPrivateState(privateSnapshot())
    }

    fun permissionRequest(
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        candidate: BcInteractionCandidate,
    ): BcPermissionRequest? = permissionContext(target = target, rules = rules)
        ?.let { context -> BcPermissionRequest(context = context, candidate = candidate) }

    private fun itemPermissions(
        group: String,
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog?,
        snapshot: BcInteractionCatalogSnapshot?,
    ): PermissionIndex {
        if (rules == null) {
            return PermissionIndex.notReady(
                InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing),
            )
        }
        val candidates = snapshot?.itemsForGroup(group).orEmpty().map { item ->
            BcInteractionCandidate.WearItem(
                targetMemberNumber = target.memberNumber,
                group = item.group,
                itemName = item.name,
            )
        }
        return permissionIndex(target = target, rules = rules, candidates = candidates)
    }

    private fun extendedItemDefaultStatus(
        group: String,
        itemName: String,
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        extended: BcExtendedItemRule,
        fallback: PermissionStatus,
    ): PermissionStatus {
        if (extended.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
            return PermissionStatus.Disabled(
                InteractionUserMessage.ItemAvailability(
                    dev.vivian.mobilebc.domain.bcpermission.BcItemManagementAvailabilityReason.ExtendedItemUnsupported,
                ),
            )
        }
        val defaultPropertyJson = extended.defaultPropertyJson ?: return fallback
        val context = permissionContext(target = target, rules = rules)
            ?: return PermissionStatus.Disabled(
                InteractionUserMessage.General(InteractionUserMessageKey.SelfCharacterUnavailable),
            )
        return candidateStatus(
            context = context,
            candidate = BcInteractionCandidate.WearItem(
                targetMemberNumber = target.memberNumber,
                group = group,
                itemName = itemName,
                propertyJson = defaultPropertyJson,
                difficulty = extended.defaultDifficulty,
            ),
        )
    }

    private fun candidateStatus(
        context: BcPermissionContext,
        candidate: BcInteractionCandidate,
    ): PermissionStatus = when (val decision = permissionEngine.evaluate(BcPermissionRequest(context, candidate))) {
        is BcPermissionDecision.Allowed -> PermissionStatus.Enabled(null)
        is BcPermissionDecision.NeedsServerCheck -> PermissionStatus.Enabled(
            decision.reasons.toUserMessageOrNull()
                ?: InteractionUserMessage.Permission(BcPermissionReasonCode.ServerAllowItemRequired),
        )
        is BcPermissionDecision.Denied -> PermissionStatus.Disabled(
            decision.reasons.toUserMessageOrNull()
                ?: InteractionUserMessage.General(InteractionUserMessageKey.ItemUnavailable),
        )
    }

    private fun removePermission(
        group: String,
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog?,
    ): PermissionStatus {
        if (rules == null) {
            return PermissionStatus.Disabled(
                InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing),
            )
        }
        val context = permissionContext(target = target, rules = rules)
            ?: return PermissionStatus.Disabled(
                InteractionUserMessage.General(InteractionUserMessageKey.SelfCharacterUnavailable),
            )
        return candidateStatus(
            context = context,
            candidate = BcInteractionCandidate.RemoveItem(
                targetMemberNumber = target.memberNumber,
                group = group,
            ),
        )
    }

    private fun permissionIndex(
        target: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        candidates: List<BcInteractionCandidate>,
    ): PermissionIndex {
        if (candidates.isEmpty()) return PermissionIndex.Empty
        val context = permissionContext(target = target, rules = rules)
            ?: return PermissionIndex.defaultDisabled(
                InteractionUserMessage.General(InteractionUserMessageKey.SelfCharacterUnavailable),
            )
        return PermissionIndex.from(
            permissionEngine.filter(
                BcPermissionFilterRequest(
                    context = context,
                    candidates = candidates,
                ),
            ),
        )
    }

    private fun actionPickerGroups(
        target: BcCharacterSnapshot,
        snapshot: BcInteractionCatalogSnapshot?,
        rules: BcRuleCatalog?,
    ): List<InteractionGroupUiState> {
        val actionGroups = (snapshot?.groups.orEmpty().map { it.name to it.displayName } + echoActivityGroups(rules))
            .distinctBy { it.first.lowercase() }
            .asSequence()
            .filter { (name, _) -> name.startsWith("Item", ignoreCase = true) }
            .filter { (name, _) ->
                snapshot?.activitiesForGroup(name).orEmpty().isNotEmpty() ||
                    rules?.activities?.values.orEmpty().any { activity ->
                        activity.source == BcActivitySource.Echo &&
                            (activity.targetGroups + activity.selfTargetGroups).any { it.equals(name, ignoreCase = true) }
                    }
            }
            .map { (name, displayName) ->
                InteractionGroupUiState(
                    name = name,
                    displayName = displayName,
                    hasActivities = true,
                    hasItems = false,
                    hasCurrentItem = target.appearance.any { it.group.equals(name, ignoreCase = true) },
                    kind = rules?.group(name)?.kind ?: BcAppearanceGroupKind.Item,
                    zones = rules?.group(name)?.zones.orEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<InteractionGroupUiState> { it.hasCurrentItem }
                    .thenBy { it.displayName.lowercase() },
            )
            .toList()
        val actionsByName = actionGroups.associateBy { it.name.lowercase() }
        val mirroredGroupsBySource = rules?.groups.orEmpty().values
            .mapNotNull { group -> group.mirrorActivitiesFrom?.lowercase()?.let { it to group.name } }
            .groupBy({ it.first }, { it.second })
        val itemGroups = buildItemPickerGroupStatesForTarget(target, snapshot, rules)
            .filter { it.kind == BcAppearanceGroupKind.Item }
            .map { group ->
                val sourceGroup = rules?.group(group.name)?.mirrorActivitiesFrom
                    ?.takeIf { rules.group(it) != null } ?: group.name
                val mirroredGroups = mirroredGroupsBySource[sourceGroup.lowercase()].orEmpty()
                group.copy(hasActivities = group.name.lowercase() in actionsByName ||
                    sourceGroup.lowercase() in actionsByName || mirroredGroups.any { it.lowercase() in actionsByName })
            }
        val itemNames = itemGroups.map { it.name.lowercase() }.toSet()
        return itemGroups + actionGroups.filterNot { it.name.lowercase() in itemNames }
    }

    private fun allGroups(
        target: BcCharacterSnapshot,
        snapshot: BcInteractionCatalogSnapshot?,
        rules: BcRuleCatalog?,
    ): List<InteractionGroupUiState> {
        val catalogGroups = snapshot?.groups.orEmpty().associateBy { it.name.lowercase() }
        val orderedNames = linkedSetOf<String>()
        target.appearance.forEach { item ->
            if (item.group.isNotBlank()) orderedNames += item.group
        }
        snapshot?.groups.orEmpty()
            .filter { it.hasActivities || it.hasItems }
            .forEach { orderedNames += it.name }
        echoActivityGroups(rules).forEach { (name, _) -> orderedNames += name }

        return orderedNames.map { groupName ->
            val catalogGroup = catalogGroups[groupName.lowercase()]
            val canonicalName = catalogGroup?.name ?: groupName
            InteractionGroupUiState(
                name = canonicalName,
                displayName = catalogGroup?.displayName
                    ?: groupName.toRoomDetailsHumanLabel(),
                hasActivities = catalogGroup?.hasActivities
                    ?: (snapshot?.activitiesForGroup(groupName).orEmpty().isNotEmpty() ||
                        rules?.activities?.values.orEmpty().any { activity ->
                            activity.source == BcActivitySource.Echo &&
                                (activity.targetGroups + activity.selfTargetGroups).any { it.equals(groupName, ignoreCase = true) }
                        }),
                hasItems = catalogGroup?.hasItems ?: snapshot?.itemsForGroup(groupName).orEmpty().isNotEmpty(),
                hasCurrentItem = target.appearance.any { it.group.equals(groupName, ignoreCase = true) },
                kind = rules?.group(canonicalName)?.kind
                    ?: if (canonicalName.startsWith("Item", ignoreCase = true)) {
                        BcAppearanceGroupKind.Item
                    } else {
                        BcAppearanceGroupKind.Appearance
                    },
                zones = rules?.group(canonicalName)?.zones.orEmpty(),
            )
        }.sortedWith(
            compareByDescending<InteractionGroupUiState> { it.hasCurrentItem }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private fun echoActivityGroups(rules: BcRuleCatalog?): List<Pair<String, String>> =
        rules?.activities?.values
            .orEmpty()
            .asSequence()
            .filter { it.source == BcActivitySource.Echo }
            .flatMap { (it.targetGroups + it.selfTargetGroups).asSequence() }
            .distinctBy(String::lowercase)
            .map { it to it.toRoomDetailsHumanLabel() }
            .toList()

    private fun BcCharacterItem.toCurrentItemUiState(
        snapshot: BcInteractionCatalogSnapshot?,
        rules: BcRuleCatalog?,
        permission: PermissionStatus,
    ): InteractionCurrentItemUiState {
        val displayName = snapshot?.itemsForGroup(group)
            ?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.displayName
            ?: name.toRoomDetailsHumanLabel()
        val extended = rules?.extendedItem(group, name)
        return InteractionCurrentItemUiState(
            group = group,
            name = name,
            displayName = displayText().displayName(displayName),
            difficulty = difficulty,
            selectedOptionSummary = extended?.currentSelectionSummary(propertyJson),
            extendedKind = extended?.archetype?.wireValue,
            extendedStatusMessage = extended?.statusMessage,
            configurable = extended?.let {
                it.isConfigurable && it.supportLevel != BcExtendedItemSupportLevel.Unsupported
            } == true,
            removeEnabled = permission.enabled,
            removePermissionMessage = permission.message,
        )
    }

    private data class ActivityKey(val group: String, val name: String)
    private data class ItemKey(val group: String, val name: String)

    private sealed interface PermissionStatus {
        val enabled: Boolean
        val message: InteractionUserMessage?

        data class Enabled(override val message: InteractionUserMessage?) : PermissionStatus {
            override val enabled: Boolean = true
        }

        data class Disabled(override val message: InteractionUserMessage?) : PermissionStatus {
            override val enabled: Boolean = false
        }
    }

    private data class PermissionIndex(
        private val defaultStatus: PermissionStatus = PermissionStatus.Enabled(null),
        private val items: Map<ItemKey, PermissionStatus> = emptyMap(),
    ) {
        fun status(key: ItemKey): PermissionStatus = items[key] ?: defaultStatus

        companion object {
            val Empty = PermissionIndex()

            fun notReady(message: InteractionUserMessage): PermissionIndex = defaultDisabled(message)

            fun defaultDisabled(message: InteractionUserMessage): PermissionIndex = PermissionIndex(
                defaultStatus = PermissionStatus.Disabled(message),
            )

            fun from(result: BcPermissionFilterResult): PermissionIndex {
                val itemStatuses = linkedMapOf<ItemKey, PermissionStatus>()
                result.allowed.filterIsInstance<BcExecutableInteraction.ItemUpdate>().forEach { executable ->
                    executable.command.name?.let { name ->
                        itemStatuses[ItemKey(executable.command.group, name)] = PermissionStatus.Enabled(null)
                    }
                }
                result.needsServerCheck.forEach { pending ->
                    when (val candidate = pending.candidate) {
                        is BcInteractionCandidate.WearItem -> itemStatuses[ItemKey(candidate.group, candidate.itemName)] =
                            PermissionStatus.Enabled(
                                pending.reasons.toUserMessageOrNull()
                                    ?: InteractionUserMessage.Permission(BcPermissionReasonCode.ServerAllowItemRequired),
                            )
                        is BcInteractionCandidate.ModifyItem -> itemStatuses[ItemKey(candidate.group, candidate.itemName)] =
                            PermissionStatus.Enabled(
                                pending.reasons.toUserMessageOrNull()
                                    ?: InteractionUserMessage.Permission(BcPermissionReasonCode.ServerAllowItemRequired),
                            )
                        is BcInteractionCandidate.Activity,
                        is BcInteractionCandidate.RemoveItem,
                        is BcInteractionCandidate.UnlockItem,
                        is BcInteractionCandidate.LockItem,
                        is BcInteractionCandidate.ConfigureLock,
                        is BcInteractionCandidate.ChangeLockTimer,
                        -> Unit
                    }
                }
                result.denied.forEach { denied ->
                    val message = denied.reasons.toUserMessageOrNull()
                        ?: InteractionUserMessage.General(InteractionUserMessageKey.ItemUnavailable)
                    when (val candidate = denied.candidate) {
                        is BcInteractionCandidate.WearItem -> itemStatuses[ItemKey(candidate.group, candidate.itemName)] =
                            PermissionStatus.Disabled(message)
                        is BcInteractionCandidate.ModifyItem -> itemStatuses[ItemKey(candidate.group, candidate.itemName)] =
                            PermissionStatus.Disabled(message)
                        is BcInteractionCandidate.Activity,
                        is BcInteractionCandidate.RemoveItem,
                        is BcInteractionCandidate.UnlockItem,
                        is BcInteractionCandidate.LockItem,
                        is BcInteractionCandidate.ConfigureLock,
                        is BcInteractionCandidate.ChangeLockTimer,
                        -> Unit
                    }
                }
                return PermissionIndex(items = itemStatuses)
            }
        }
    }

}

private fun dev.vivian.mobilebc.domain.bcrules.BcActivityRule?.toUiEffects(): Set<InteractionActivityEffectUiState> =
    this?.effects.orEmpty().mapTo(linkedSetOf()) { effect ->
        when (effect) {
            is BcActivityEffect.Pose -> InteractionActivityEffectUiState.Pose
            is BcActivityEffect.Expression -> InteractionActivityEffectUiState.Expression
            is BcActivityEffect.EchoPairedItem -> InteractionActivityEffectUiState.PairedItem
        }
    }

private fun List<BcPermissionReason>.toUserMessageOrNull(): InteractionUserMessage? =
    primaryReasonCode()?.let(InteractionUserMessage::Permission)

private fun List<BcPermissionReason>.primaryReasonCode(): BcPermissionReasonCode? =
    minByOrNull { reason -> reason.code.userFacingPriority }?.code

private val BcPermissionReasonCode.userFacingPriority: Int
    get() = when (this) {
        BcPermissionReasonCode.TargetItemLocked -> 0
        BcPermissionReasonCode.ActorCannotUseHands,
        BcPermissionReasonCode.ActorCannotUseArms,
        BcPermissionReasonCode.ActorCannotUseFeet,
        BcPermissionReasonCode.ActorCannotUseMouth,
        BcPermissionReasonCode.TargetCannotUseTongue,
        BcPermissionReasonCode.TargetMouthBlocked,
        BcPermissionReasonCode.TargetMouthNotBlocked,
        BcPermissionReasonCode.TargetMouthNotOpen,
        BcPermissionReasonCode.TargetNotKneeling,
        BcPermissionReasonCode.TargetZoneBlocked,
        BcPermissionReasonCode.TargetZoneNotNaked,
        BcPermissionReasonCode.TargetZoneOccupied,
        BcPermissionReasonCode.ActorZoneOccupied,
        BcPermissionReasonCode.ActorZoneBlocked,
        BcPermissionReasonCode.ActorZoneNotNaked,
        BcPermissionReasonCode.TargetPoseNotAllowed,
        -> 1
        BcPermissionReasonCode.MissingRequiredActorItem,
        BcPermissionReasonCode.MissingRequiredTargetItem,
        BcPermissionReasonCode.MissingShockTriggerItem,
        BcPermissionReasonCode.MissingShockReceiverItem,
        BcPermissionReasonCode.SelectedActivityItemUnavailable,
        -> 2
        BcPermissionReasonCode.ServerAllowItemDenied,
        BcPermissionReasonCode.LocalAllowItemDenied,
        BcPermissionReasonCode.TargetBlacklistedActor,
        BcPermissionReasonCode.TargetItemPermissionBlocked,
        BcPermissionReasonCode.TargetItemPermissionLimited,
        BcPermissionReasonCode.ItemExclusivePermissionDenied,
        -> 3
        BcPermissionReasonCode.ServerAllowItemRequired -> 4
        else -> 5
    }
