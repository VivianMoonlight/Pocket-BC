package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.model.BcInteractionItem

/** Shared display data; an item name does not grant permission to wear or configure it. */
internal data class BcItemNameCatalog(
    val groups: Map<String, String>,
    val items: List<BcInteractionItem>,
)

internal fun parseBcItemNames(
    csv: String,
    translations: Map<String, String>,
    echoJson: String?,
    language: String,
): BcItemNameCatalog {
    val groups = linkedMapOf<String, String>()
    val items = linkedMapOf<Pair<String, String>, BcInteractionItem>()
    parseBcCsvRows(csv).forEach { row ->
        val group = row.getOrNull(0)?.trim().orEmpty()
        val name = row.getOrNull(1)?.trim().orEmpty()
        val englishLabel = row.getOrNull(2)?.trim().orEmpty()
        if (group.isBlank()) return@forEach
        val label = translations[englishLabel] ?: englishLabel
        if (name.isBlank()) {
            groups[group] = label.ifBlank { group.toHumanLabel() }
        } else {
            items.putIfAbsent(group to name, BcInteractionItem(group, name, label.ifBlank { name.toHumanLabel() }))
        }
    }
    val echo = BcEchoItemNames(echoJson)
    return BcItemNameCatalog(groups + echo.groups(language), echo.items(items.values.toList(), language))
}

internal fun String.toHumanLabel(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { this }
