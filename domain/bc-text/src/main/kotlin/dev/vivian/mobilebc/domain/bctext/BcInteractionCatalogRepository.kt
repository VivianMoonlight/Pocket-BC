package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcInteractionActivity
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionGroup
import dev.vivian.mobilebc.core.model.BcInteractionItem
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.util.concurrent.ConcurrentHashMap

interface BcInteractionCatalogRepository {
    fun currentSnapshot(version: String): BcInteractionCatalogSnapshot?
    fun refresh(version: String): BcInteractionCatalogLoadResult

}

/**
 * Builds a per-body-group action/item catalog from the BC web resources already cached on device.
 *
 * Raw resource ownership stays in the app-owned BC resource cache. This class only reads the
 * cached text files and keeps a parsed in-memory snapshot for fast UI queries.
 */
class BcCachedInteractionCatalogRepository(
    private val resourceReader: (version: String, relativePath: String) -> String?,
    private val languageProvider: () -> String = { "EN" },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : BcInteractionCatalogRepository {
    private val snapshots = ConcurrentHashMap<String, BcInteractionCatalogSnapshot>()

    override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? =
        snapshots[cacheKey(version.normalizedBcVersion(), activeLanguage())]

    override fun refresh(version: String): BcInteractionCatalogLoadResult {
        val normalizedVersion = version.normalizedBcVersion()
        val language = activeLanguage()
        return runCatching {
            val missing = mutableListOf<String>()
            val activityDictionaryCsv = resourceReader(
                normalizedVersion,
                BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv,
            ) ?: run {
                missing += BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv
                ""
            }
            val female3dCsv = resourceReader(
                normalizedVersion,
                BcResourcePaths.Assets.Female3DCG.Female3DCGCsv,
            ) ?: run {
                missing += BcResourcePaths.Assets.Female3DCG.Female3DCGCsv
                ""
            }
            if (missing.isNotEmpty()) {
                return BcInteractionCatalogLoadResult.MissingResources(missing)
            }

            val activityTranslations = translationsFor(
                normalizedVersion,
                BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv,
                language,
            )
            val female3dTranslations = translationsFor(
                normalizedVersion,
                BcResourcePaths.Assets.Female3DCG.Female3DCGCsv,
                language,
            )
            val activityEntries = parseKeyValueCsv(activityDictionaryCsv).mapValues { (key, value) ->
                activityTranslations[key] ?: activityTranslations[value.trim()] ?: value
            }
            val names = parseBcItemNames(
                female3dCsv, female3dTranslations, resourceReader(normalizedVersion, "echo/names.json"), language,
            )
            val activities = buildActivities(activityEntries)
            val items = names.items.sortedWith(
                compareBy<BcInteractionItem> { it.group.lowercase() }.thenBy { it.displayName.lowercase() },
            )
            val groups = buildGroups(
                groupLabels = names.groups,
                activityGroups = activities.map { it.group }.toSet(),
                itemGroups = items.map { it.group }.toSet(),
            )

            val snapshot = BcInteractionCatalogSnapshot(
                version = normalizedVersion,
                generatedAtEpochMillis = clockMillis(),
                groups = groups,
                activities = activities,
                items = items,
                sourcePaths = listOf(
                    BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv,
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCsv,
                ),
            )
            snapshots[cacheKey(normalizedVersion, language)] = snapshot
            BcInteractionCatalogLoadResult.Ready(snapshot)
        }.getOrElse { error ->
            BcInteractionCatalogLoadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    private fun buildActivities(entries: Map<String, String>): List<BcInteractionActivity> {
        val activityDisplayNames = entries.mapNotNull { (key, value) ->
            key.removePrefix("Activity")
                .takeIf { key.startsWith("Activity") && it.isNotBlank() }
                ?.let { it to value }
        }.toMap()

        val selfLabels = linkedMapOf<ActivityKey, String>()
        val otherLabels = linkedMapOf<ActivityKey, String>()
        val keys = linkedSetOf<ActivityKey>()

        entries.forEach { (key, value) ->
            val parsed = parseActivityDictionaryKey(key) ?: return@forEach
            keys += parsed.activityKey
            if (parsed.isLabel) {
                when (parsed.scope) {
                    ActivityScope.Self -> selfLabels[parsed.activityKey] = value
                    ActivityScope.Other -> otherLabels[parsed.activityKey] = value
                }
            }
        }

        return keys.map { activityKey ->
            val selfLabel = selfLabels[activityKey]
            val otherLabel = otherLabels[activityKey]
            val genericLabel = activityDisplayNames[activityKey.activityName]
            BcInteractionActivity(
                group = activityKey.group,
                name = activityKey.activityName,
                displayName = otherLabel ?: selfLabel ?: genericLabel ?: activityKey.activityName.toHumanLabel(),
                selfDisplayName = selfLabel,
                otherDisplayName = otherLabel,
                requiresItem = activityKey.activityName.endsWith("Item"),
            )
        }.distinctBy { it.group to it.name }
            .sortedWith(compareBy<BcInteractionActivity> { it.group.lowercase() }.thenBy { it.displayName.lowercase() })
    }

    private fun buildGroups(
        groupLabels: Map<String, String>,
        activityGroups: Set<String>,
        itemGroups: Set<String>,
    ): List<BcInteractionGroup> {
        val allGroups = (groupLabels.keys + activityGroups + itemGroups).toSortedSet(String.CASE_INSENSITIVE_ORDER)
        return allGroups.map { group ->
            BcInteractionGroup(
                name = group,
                displayName = groupLabels[group] ?: group.toHumanLabel(),
                hasActivities = activityGroups.any { it.equals(group, ignoreCase = true) },
                hasItems = itemGroups.any { it.equals(group, ignoreCase = true) },
            )
        }
    }

    private fun parseActivityDictionaryKey(key: String): ParsedActivityKey? {
        val match = ActivityKeyRegex.matchEntire(key) ?: return null
        val scope = when (match.groupValues[2]) {
            "ChatSelf" -> ActivityScope.Self
            "ChatOther" -> ActivityScope.Other
            else -> return null
        }
        return ParsedActivityKey(
            isLabel = match.groupValues[1].isNotEmpty(),
            scope = scope,
            activityKey = ActivityKey(
                group = match.groupValues[3],
                activityName = match.groupValues[4],
            ),
        )
    }

    private fun parseKeyValueCsv(csv: String): Map<String, String> =
        parseBcCsvRows(csv).mapNotNull { row ->
            val key = row.getOrNull(0)?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            key to row.drop(1).joinToString(",").trim()
        }.toMap()

    private fun translationsFor(
        version: String,
        csvPath: String,
        language: String,
    ): Map<String, String> = if (language == "EN") {
        emptyMap()
    } else {
        resourceReader(version, BcResourcePaths.translatedTextPath(csvPath, language))
            ?.let { parseBcTranslationPairs(it, language) }
            .orEmpty()
    }

    private fun activeLanguage(): String =
        languageProvider().trim().uppercase().ifBlank { "EN" }

    private fun cacheKey(version: String, language: String): String = "$version:$language"

    private data class ActivityKey(
        val group: String,
        val activityName: String,
    )

    private data class ParsedActivityKey(
        val isLabel: Boolean,
        val scope: ActivityScope,
        val activityKey: ActivityKey,
    )

    private enum class ActivityScope { Self, Other }

    private companion object {
        val ActivityKeyRegex = Regex("^(Label-)?(ChatOther|ChatSelf)-([^-]+)-(.+)$")
    }
}
