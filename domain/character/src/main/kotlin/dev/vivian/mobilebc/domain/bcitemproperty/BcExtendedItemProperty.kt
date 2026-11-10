package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser

/**
 * Static property helpers for BC extended items.
 *
 * These helpers deliberately support the JSON shape emitted by [BcRuleCatalogParser] and
 * the raw Property JSON snapshots received from the server. They do not execute Inventory JS. When
 * modifying an already worn item, we keep lock/custom metadata and rebuild the fields managed by
 * the static configuration. Modular properties are composed from all current module selections.
 */
data class BcExtendedItemModificationOption(
    val label: String,
    val propertyJson: String,
    val difficulty: Int? = null,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val warnings: List<String> = emptyList(),
    val supportLevel: BcExtendedItemSupportLevel = BcExtendedItemSupportLevel.Supported,
)

/** Returns a compact human-readable description of the selected option(s) in [currentPropertyJson]. */
fun BcExtendedItemRule.currentSelectionSummary(currentPropertyJson: String?): String? {
    val typeRecord = BcItemPropertyJson.readTypeRecord(currentPropertyJson)
    return when {
        typedOptions.isNotEmpty() -> {
            val selectedIndex = typeRecord[recordKey] ?: typeRecord[archetype.wireValue]
            typedOptions.firstOrNull { it.index == selectedIndex }?.displayName
                ?: typedOptions.firstOrNull { selectedIndex == null && it.index == 0 }?.displayName
        }

        modules.isNotEmpty() -> modules.mapNotNull { module ->
            val selectedIndex = typeRecord[module.key]
            val selectedOption = module.options.firstOrNull { it.index == selectedIndex }
                ?: module.options.firstOrNull { selectedIndex == null && it.index == 0 }
            selectedOption?.let { "${module.name}: ${it.displayName}" }
        }.joinToString(" • ").ifBlank { null }

        else -> null
    }
}

/** A lazy page of static extended-item options. */
data class BcExtendedItemOptionPage(
    val options: List<BcExtendedItemModificationOption>,
    val offset: Int,
    val nextOffset: Int?,
    val totalKnownOptions: Int,
) {
    val hasMore: Boolean get() = nextOffset != null
}

/**
 * Builds a paged list of wear options for a configurable item.
 *
 * This is intentionally domain-only: callers can render the item row immediately and evaluate only
 * the currently expanded option page instead of precomputing every typed/modular option up front.
 */
fun BcExtendedItemRule.wearOptionsPage(
    offset: Int = 0,
    limit: Int = 20,
    query: String? = null,
): BcExtendedItemOptionPage {
    require(offset >= 0) { "offset must be non-negative." }
    require(limit >= 0) { "limit must be non-negative." }
    if (!isConfigurable || supportLevel == BcExtendedItemSupportLevel.Unsupported || limit == 0) {
        return BcExtendedItemOptionPage(
            options = emptyList(),
            offset = offset,
            nextOffset = null,
            totalKnownOptions = 0,
        )
    }

    val normalizedQuery = query.normalizedQuery()
    val options = mutableListOf<BcExtendedItemModificationOption>()
    var validIndex = 0

    fun maybeEmit(option: BcExtendedItemModificationOption): Boolean {
        if (normalizedQuery != null && !option.matchesQuery(normalizedQuery)) return true
        if (validIndex < offset) {
            validIndex++
            return true
        }
        if (options.size < limit) options += option
        validIndex++
        return normalizedQuery != null || options.size < limit
    }

    for (option in typedOptions) {
        val propertyJson = option.propertyJson ?: continue
        if (!maybeEmit(
            BcExtendedItemModificationOption(
                label = option.displayName,
                propertyJson = propertyJson,
                difficulty = option.difficulty ?: defaultDifficulty,
                subtitle = option.propertySummary,
                selected = option.index == 0,
                warnings = option.warnings,
                supportLevel = option.supportLevel,
            ),
        )) break
    }

    if (normalizedQuery != null || options.size < limit) {
        outer@ for (module in modules) {
            for (option in module.options) {
                val propertyJson = option.propertyJson ?: continue
                if (!maybeEmit(
                    BcExtendedItemModificationOption(
                        label = "${module.name}: ${option.displayName}",
                        propertyJson = propertyJson,
                        difficulty = option.difficulty ?: defaultDifficulty,
                        subtitle = option.propertySummary,
                        selected = option.index == 0,
                        warnings = option.warnings,
                        supportLevel = option.supportLevel,
                    ),
                )) break@outer
            }
        }
    }

    val total = if (normalizedQuery == null) knownOptionCount() else validIndex

    return BcExtendedItemOptionPage(
        options = options,
        offset = offset,
        nextOffset = (offset + options.size).takeIf { it < total },
        totalKnownOptions = total,
    )
}

/** Changes one option while preserving the other selections and lock/custom metadata. */
fun BcExtendedItemRule.modificationOptionsPage(
    currentPropertyJson: String?,
    currentDifficulty: Int? = null,
    offset: Int = 0,
    limit: Int = 20,
    query: String? = null,
): BcExtendedItemOptionPage {
    require(offset >= 0) { "offset must be non-negative." }
    require(limit >= 0) { "limit must be non-negative." }
    if (!isConfigurable || supportLevel == BcExtendedItemSupportLevel.Unsupported || limit == 0) {
        return BcExtendedItemOptionPage(
            options = emptyList(),
            offset = offset,
            nextOffset = null,
            totalKnownOptions = 0,
        )
    }

    val currentTypeRecord = BcItemPropertyJson.readTypeRecord(currentPropertyJson)
    val currentModuleOptions = modules.associate { module ->
        module.key to (module.options.firstOrNull { it.index == currentTypeRecord[module.key] }
            ?: module.options.firstOrNull { it.index == 0 })
    }
    val blockedModuleKeys = currentModuleOptions.filterValues {
        it == null || it.supportLevel == BcExtendedItemSupportLevel.Unsupported
    }.keys
    val managedPropertyKeys = BcItemPropertyJson.propertyKeys(baselinePropertyJson) +
        typedOptions.flatMap { BcItemPropertyJson.propertyKeys(it.propertyJson) } +
        modules.flatMap { module -> module.options.flatMap { BcItemPropertyJson.propertyKeys(it.modulePropertyJson) } }
    val normalizedQuery = query.normalizedQuery()
    val options = mutableListOf<BcExtendedItemModificationOption>()
    var validIndex = 0

    fun maybeEmit(option: BcExtendedItemModificationOption): Boolean {
        if (normalizedQuery != null && !option.matchesQuery(normalizedQuery)) return true
        if (validIndex < offset) {
            validIndex++
            return true
        }
        if (options.size < limit) options += option
        validIndex++
        return normalizedQuery != null || options.size < limit
    }

    for (option in typedOptions) {
        val optionPropertyJson = option.propertyJson ?: continue
        val selected = (currentTypeRecord[recordKey] ?: currentTypeRecord[archetype.wireValue] ?: 0) == option.index
        if (!maybeEmit(
            BcExtendedItemModificationOption(
                label = option.displayName,
                propertyJson = BcItemPropertyJson.mergeForModification(
                    currentPropertyJson = currentPropertyJson,
                    optionPropertyJson = optionPropertyJson,
                    managedPropertyKeys = managedPropertyKeys,
                ),
                difficulty = option.difficulty ?: currentDifficulty,
                subtitle = option.propertySummary,
                selected = selected,
                warnings = option.warnings,
                supportLevel = option.supportLevel,
            ),
        )) break
    }

    if (normalizedQuery != null || options.size < limit) {
        outer@ for (module in modules) {
            if (blockedModuleKeys.any { it != module.key }) continue
            for (option in module.options) {
                if (option.propertyJson == null || option.supportLevel == BcExtendedItemSupportLevel.Unsupported) continue
                val selections = currentTypeRecord.toMutableMap()
                modules.forEach { selectedModule ->
                    selections[selectedModule.key] = if (selectedModule.key == module.key) option.index
                    else currentModuleOptions.getValue(selectedModule.key)!!.index
                }
                val selectedOptions = modules.map { selectedModule ->
                    if (selectedModule.key == module.key) option else currentModuleOptions.getValue(selectedModule.key)!!
                }
                val optionPropertyJson = BcItemPropertyJson.composeModularProperty(
                    typeRecord = selections,
                    assetPropertyJson = assetPropertyJson,
                    propertyJsons = listOf(BcItemPropertyJson.overlayStrings(baselinePropertyJson,
                        listOf("Text", "Text2", "Text3").mapNotNull { key -> currentPropertyJson.readBcStringProperty(key)?.let { key to it } }.toMap())) +
                        selectedOptions.map { it.modulePropertyJson },
                ) ?: continue
                val selected = currentTypeRecord[module.key] == option.index ||
                    (currentTypeRecord[module.key] == null && option.index == 0)
                if (!maybeEmit(
                    BcExtendedItemModificationOption(
                        label = "${module.name}: ${option.displayName}",
                        propertyJson = BcItemPropertyJson.mergeForModification(
                            currentPropertyJson = currentPropertyJson,
                            optionPropertyJson = optionPropertyJson,
                            managedPropertyKeys = managedPropertyKeys,
                        ),
                        difficulty = optionPropertyJson.readBcIntProperty("Difficulty") ?: currentDifficulty,
                        subtitle = option.propertySummary,
                        selected = selected,
                        warnings = selectedOptions.flatMap { it.warnings }.distinct(),
                        supportLevel = if (selectedOptions.any { it.supportLevel == BcExtendedItemSupportLevel.Partial }) {
                            BcExtendedItemSupportLevel.Partial
                        } else {
                            BcExtendedItemSupportLevel.Supported
                        },
                    ),
                )) break@outer
            }
        }
    }

    val total = if (normalizedQuery == null) {
        typedOptions.count { it.propertyJson != null } + modules.sumOf { module ->
            if (blockedModuleKeys.any { it != module.key }) 0
            else module.options.count { it.propertyJson != null && it.supportLevel != BcExtendedItemSupportLevel.Unsupported }
        }
    } else {
        validIndex
    }

    return BcExtendedItemOptionPage(
        options = options,
        offset = offset,
        nextOffset = (offset + options.size).takeIf { it < total },
        totalKnownOptions = total,
    )
}

private fun BcExtendedItemModificationOption.matchesQuery(query: String): Boolean =
    label.contains(query, ignoreCase = true) ||
        (subtitle?.contains(query, ignoreCase = true) == true) ||
        warnings.any { it.contains(query, ignoreCase = true) }

private fun String?.normalizedQuery(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun BcExtendedItemRule.knownOptionCount(): Int =
    typedOptions.count { it.propertyJson != null } +
        modules.sumOf { module -> module.options.count { it.propertyJson != null } }
