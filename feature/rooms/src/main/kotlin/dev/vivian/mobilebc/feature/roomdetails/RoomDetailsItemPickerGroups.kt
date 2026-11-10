package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog

internal fun buildItemPickerGroupStatesForTarget(
    target: BcCharacterSnapshot,
    snapshot: BcInteractionCatalogSnapshot?,
    rules: BcRuleCatalog?,
): List<InteractionGroupUiState> {
    val catalogGroups = snapshot?.groups.orEmpty().associateBy { it.name.lowercase() }
    val ruleGroups = rules?.groups.orEmpty().values.associateBy { it.name.lowercase() }
    val assetGroups = rules?.assets.orEmpty().keys.mapTo(mutableSetOf()) { it.group.lowercase() }
    val rulePickerGroupNames = ruleGroups.values
        .asSequence()
        .filter { group -> group.kind.isPickerKind() }
        .map { it.name }
    val snapshotItemGroupNames = snapshot?.groups.orEmpty()
        .asSequence()
        .filter { group -> group.name.isBcItemGroupName() }
        .map { it.name }
    val currentItemGroupNames = target.appearance
        .asSequence()
        .map { it.group }
        .filter { group -> group.isBcItemGroupName() }

    val orderedNames = linkedSetOf<String>()
    rulePickerGroupNames.forEach { orderedNames += it }
    snapshotItemGroupNames.forEach { orderedNames += it }
    currentItemGroupNames.forEach { orderedNames += it }

    return orderedNames.mapNotNull { groupName ->
        val normalized = groupName.lowercase()
        val catalogGroup = catalogGroups[normalized]
        val ruleGroup = ruleGroups[normalized]
        val canonicalName = ruleGroup?.name ?: catalogGroup?.name ?: groupName
        val kind = ruleGroup?.kind ?: canonicalName.fallbackPickerKind()
        if (!kind.isPickerKind()) return@mapNotNull null
        val hasCurrentItem = target.appearance.any { it.group.equals(canonicalName, ignoreCase = true) }
        val hasItems = canonicalName.lowercase() in assetGroups ||
            catalogGroup?.hasItems == true ||
            snapshot?.itemsForGroup(canonicalName).orEmpty().isNotEmpty()
        if (!hasCurrentItem && !hasItems) return@mapNotNull null
        InteractionGroupUiState(
            name = canonicalName,
            displayName = catalogGroup?.displayName ?: canonicalName.toRoomDetailsHumanLabel(),
            hasActivities = false,
            hasItems = hasItems,
            hasCurrentItem = hasCurrentItem,
            kind = kind,
            zones = ruleGroup?.zones.orEmpty(),
        )
    }.sortedWith(
        compareByDescending<InteractionGroupUiState> { it.hasCurrentItem }
            .thenBy { it.displayName.lowercase() },
    )
}

internal fun String.toRoomDetailsHumanLabel(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { this }

private fun BcAppearanceGroupKind.isPickerKind(): Boolean =
    this == BcAppearanceGroupKind.Item || this == BcAppearanceGroupKind.Clothing

private fun String.fallbackPickerKind(): BcAppearanceGroupKind =
    if (isBcItemGroupName()) BcAppearanceGroupKind.Item else BcAppearanceGroupKind.Appearance

private fun String?.isBcItemGroupName(): Boolean =
    !isNullOrBlank() && trim().startsWith("Item", ignoreCase = true)
