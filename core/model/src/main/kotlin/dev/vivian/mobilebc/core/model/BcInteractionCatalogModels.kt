package dev.vivian.mobilebc.core.model

/**
 * A compact, mobile-friendly catalog derived from the cached BC web resources.
 *
 * It is intentionally lossy: it is good enough to browse actions/items by body group and to build
 * basic M3A/M3B commands. Full extended-item property editors still need item-specific scripts.
 */
data class BcInteractionCatalogSnapshot(
    val version: String,
    val generatedAtEpochMillis: Long,
    val groups: List<BcInteractionGroup>,
    val activities: List<BcInteractionActivity>,
    val items: List<BcInteractionItem>,
    val sourcePaths: List<String>,
) {
    fun activitiesForGroup(group: String): List<BcInteractionActivity> =
        activities.filter { it.group.equals(group, ignoreCase = true) }

    fun itemsForGroup(group: String): List<BcInteractionItem> =
        items.filter { it.group.equals(group, ignoreCase = true) }
}

data class BcInteractionGroup(
    val name: String,
    val displayName: String,
    val hasActivities: Boolean,
    val hasItems: Boolean,
)

data class BcInteractionActivity(
    val group: String,
    val name: String,
    val displayName: String,
    val selfDisplayName: String? = null,
    val otherDisplayName: String? = null,
    val requiresItem: Boolean = false,
) {
    fun toCommand(targetMemberNumber: Int, isSelfTarget: Boolean): BcActivityCommand =
        BcActivityCommand(
            targetMemberNumber = targetMemberNumber,
            targetGroup = group,
            activityName = name,
        )
}

data class BcInteractionItem(
    val group: String,
    val name: String,
    val displayName: String,
) {
    fun toWearCommand(targetMemberNumber: Int): BcCharacterItemUpdateCommand =
        BcCharacterItemUpdateCommand(
            targetMemberNumber = targetMemberNumber,
            group = group,
            name = name,
        )

    fun toRemoveCommand(targetMemberNumber: Int): BcCharacterItemUpdateCommand =
        BcCharacterItemUpdateCommand(
            targetMemberNumber = targetMemberNumber,
            group = group,
            name = null,
        )
}

sealed interface BcInteractionCatalogLoadResult {
    data class Ready(val snapshot: BcInteractionCatalogSnapshot) : BcInteractionCatalogLoadResult
    data class MissingResources(val missingPaths: List<String>) : BcInteractionCatalogLoadResult
    data class Failed(val message: String) : BcInteractionCatalogLoadResult
}
