package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class BcRoomBackgroundCatalog internal constructor(
    val version: String,
    allNames: List<String>,
    generalNames: List<String>,
    asylumNames: List<String>,
) {
    val allNames: Set<String> = Collections.unmodifiableSet(LinkedHashSet(allNames))
    val generalNames: List<String> = Collections.unmodifiableList(ArrayList(generalNames))
    val asylumNames: List<String> = Collections.unmodifiableList(ArrayList(asylumNames))

    fun namesForSpace(space: BcRoomSpace): List<String> = when (space) {
        BcRoomSpace.Female, BcRoomSpace.Mixed, BcRoomSpace.Male -> generalNames
        BcRoomSpace.Asylum -> asylumNames
    }
}

sealed interface BcRoomBackgroundCatalogLoadResult {
    data class Ready(val catalog: BcRoomBackgroundCatalog) : BcRoomBackgroundCatalogLoadResult
    data class MissingResources(val missingPaths: List<String>) : BcRoomBackgroundCatalogLoadResult
    data class Failed(val reason: String) : BcRoomBackgroundCatalogLoadResult
}

/** Reads cached versioned BC source. Resource fetching remains owned by the app resource cache. */
class BcCachedRoomBackgroundCatalogRepository(
    private val resourceReader: (version: String, relativePath: String) -> String?,
    private val parser: BcRoomBackgroundCatalogParser = BcRoomBackgroundCatalogParser(),
) {
    private val snapshots = ConcurrentHashMap<String, BcRoomBackgroundCatalog>()

    /** Safe for gateway preflight and UI queries: no disk reads or parsing occur here. */
    fun currentSnapshot(version: String): BcRoomBackgroundCatalog? =
        snapshots[version.normalizedBcVersion()]

    fun refresh(version: String): BcRoomBackgroundCatalogLoadResult {
        val normalizedVersion = version.normalizedBcVersion()
        val result = runCatching {
            val path = BcResourcePaths.Backgrounds.BackgroundsJs
            val source = resourceReader(normalizedVersion, path)
                ?: return@runCatching BcRoomBackgroundCatalogLoadResult.MissingResources(listOf(path))
            BcRoomBackgroundCatalogLoadResult.Ready(parser.parse(normalizedVersion, source))
        }.getOrElse { error ->
            BcRoomBackgroundCatalogLoadResult.Failed(error.message ?: error::class.java.simpleName)
        }
        if (result is BcRoomBackgroundCatalogLoadResult.Ready) {
            snapshots[normalizedVersion] = result.catalog
        } else {
            snapshots.remove(normalizedVersion)
        }
        return result
    }
}
