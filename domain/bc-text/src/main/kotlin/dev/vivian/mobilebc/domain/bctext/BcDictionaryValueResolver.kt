package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface BcDictionaryValueResolver {
    fun groupDisplayName(version: String, groupName: String): String?

    fun assetDisplayName(version: String, groupName: String?, assetName: String): String?

    data object None : BcDictionaryValueResolver {
        override fun groupDisplayName(version: String, groupName: String): String? = null

        override fun assetDisplayName(version: String, groupName: String?, assetName: String): String? = null
    }
}

/**
 * Resolves the user-facing names used by BC's chat dictionary from Female3DCG.csv.
 *
 * This mirrors the static part of AssetGroup.Description and Asset.DynamicDescription. The
 * character-dependent overrides used by the web client are intentionally out of scope because
 * the message pipeline does not own a complete BC Character runtime.
 */
class BcCachedDictionaryValueResolver(
    private val resourceReader: (version: String, relativePath: String) -> String?,
    private val languageProvider: () -> String = { "EN" },
) : BcDictionaryValueResolver {
    private val catalogs = ConcurrentHashMap<String, DisplayNameCatalog>()

    override fun groupDisplayName(version: String, groupName: String): String? =
        catalog(version).groups[groupName.lowercase(Locale.ROOT)]

    override fun assetDisplayName(version: String, groupName: String?, assetName: String): String? {
        val catalog = catalog(version)
        val normalizedAsset = assetName.lowercase(Locale.ROOT)
        val normalizedGroup = groupName?.lowercase(Locale.ROOT)
        if (normalizedGroup != null) {
            return catalog.assets[AssetKey(normalizedGroup, normalizedAsset)]
        }
        return catalog.assets.entries
            .firstOrNull { (key, _) -> key.assetName == normalizedAsset }
            ?.value
    }

    private fun catalog(version: String): DisplayNameCatalog {
        val normalizedVersion = version.normalizedBcVersion()
        val language = languageProvider().trim().uppercase().ifBlank { "EN" }
        val cacheKey = "$normalizedVersion:$language"
        catalogs[cacheKey]?.let { return it }

        val csv = resourceReader(
            normalizedVersion,
            BcResourcePaths.Assets.Female3DCG.Female3DCGCsv,
        ) ?: return DisplayNameCatalog.Empty
        val translationText = if (language == "EN") {
            null
        } else {
            resourceReader(
                normalizedVersion,
                BcResourcePaths.translatedTextPath(
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCsv,
                    language,
                ),
            )
        }
        val translations = translationText?.let { parseBcTranslationPairs(it, language) }.orEmpty()
        val groups = linkedMapOf<String, String>()
        val assets = linkedMapOf<AssetKey, String>()
        val names = parseBcItemNames(csv, translations, resourceReader(normalizedVersion, "echo/names.json"), language)
        names.groups.forEach { (group, label) -> groups[group.lowercase(Locale.ROOT)] = label }
        names.items.forEach { item ->
            assets[AssetKey(item.group.lowercase(Locale.ROOT), item.name.lowercase(Locale.ROOT))] = item.displayName
        }
        val parsed = DisplayNameCatalog(groups = groups, assets = assets)
        // Do not retain an English fallback while a localized resource is still being synced.
        if (language == "EN" || translationText != null) catalogs[cacheKey] = parsed
        return parsed
    }

    private data class AssetKey(
        val groupName: String,
        val assetName: String,
    )

    private data class DisplayNameCatalog(
        val groups: Map<String, String>,
        val assets: Map<AssetKey, String>,
    ) {
        companion object {
            val Empty = DisplayNameCatalog(emptyMap(), emptyMap())
        }
    }
}

internal fun BcDictionaryValueResolver.userFacingGroupName(version: String, rawName: String): String =
    groupDisplayName(version, rawName)
        ?.takeIf(String::isNotBlank)
        ?: rawName.removePrefix("Item").toBcHumanLabel()

internal fun BcDictionaryValueResolver.userFacingAssetName(
    version: String,
    groupName: String?,
    rawName: String,
): String = assetDisplayName(version, groupName, rawName)
    ?.takeIf(String::isNotBlank)
    ?: rawName.toBcHumanLabel()

private fun String.toBcHumanLabel(): String =
    replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { this }
