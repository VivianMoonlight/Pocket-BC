package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.util.concurrent.ConcurrentHashMap

interface BcRuleCatalogRepository {
    fun currentSnapshot(version: String): BcRuleCatalog?

    fun refresh(version: String): BcRuleCatalogLoadResult
}

sealed interface BcRuleCatalogLoadResult {
    data class Ready(
        val catalog: BcRuleCatalog,
    ) : BcRuleCatalogLoadResult

    data class MissingResources(
        val missingPaths: List<String>,
    ) : BcRuleCatalogLoadResult

    data class Failed(
        val reason: String,
    ) : BcRuleCatalogLoadResult
}

/**
 * Builds the permission-oriented rule catalog from BC web resources already cached on device.
 *
 * This repository deliberately does not fetch resources. Fetch/cache ownership stays in the
 * app-owned BC resource cache. The reader abstraction lets Android reuse the same cached files
 * reader that M3C uses for the display catalog.
 */
class BcCachedRuleCatalogRepository(
    private val resourceReader: (version: String, relativePath: String) -> String?,
    private val parser: BcRuleCatalogParser = BcRuleCatalogParser(),
    private val languageProvider: () -> String = { "CN" },
) : BcRuleCatalogRepository {
    private val snapshots = ConcurrentHashMap<String, BcRuleCatalog>()
    @Volatile
    private var lastHealthySnapshot: BcRuleCatalog? = null

    override fun currentSnapshot(version: String): BcRuleCatalog? =
        snapshots[cacheKey(version.normalizedBcVersion(), activeLanguage())]

    override fun refresh(version: String): BcRuleCatalogLoadResult {
        val normalizedVersion = version.normalizedBcVersion()
        val language = activeLanguage()
        return runCatching {
            val missing = mutableListOf<String>()
            val female3dJs = resourceReader(
                normalizedVersion,
                BcResourcePaths.Assets.Female3DCG.Female3DCGJs,
            ) ?: run {
                missing += BcResourcePaths.Assets.Female3DCG.Female3DCGJs
                ""
            }
            val female3dExtendedJs = resourceReader(
                normalizedVersion,
                BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs,
            ) ?: run {
                missing += BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs
                ""
            }

            if (missing.isNotEmpty()) {
                return BcRuleCatalogLoadResult.MissingResources(missing)
            }

            val nativeCatalog = parser.parse(
                version = normalizedVersion,
                female3dJs = female3dJs,
                female3dExtendedJs = female3dExtendedJs,
            )
            val health = BcCatalogHealthReport.fromNative(nativeCatalog)
                .comparedWith(lastHealthySnapshot?.health)
            if (!health.isCompatible) {
                return BcRuleCatalogLoadResult.Failed(
                    "BC rule catalog is incompatible: ${health.issues.joinToString()}"
                )
            }
            val catalog = BcEchoActivityCatalog.mergeInto(
                catalog = nativeCatalog.copy(health = health),
                language = language,
            )
            snapshots[cacheKey(normalizedVersion, language)] = catalog
            lastHealthySnapshot = catalog
            BcRuleCatalogLoadResult.Ready(catalog)
        }.getOrElse { error ->
            BcRuleCatalogLoadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    private fun activeLanguage(): String =
        languageProvider().trim().uppercase().ifBlank { "EN" }

    private fun cacheKey(version: String, language: String): String = "$version:$language"

}
