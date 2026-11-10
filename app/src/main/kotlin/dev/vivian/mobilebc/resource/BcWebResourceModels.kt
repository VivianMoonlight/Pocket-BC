package dev.vivian.mobilebc.resource

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

const val DefaultBcWebHostUrl = "https://www.bondageprojects.elementfx.com/"

fun bcWebBaseUrl(version: String): String {
    require(version.isNotBlank()) { "BC version is required to build a resource URL." }
    return "$DefaultBcWebHostUrl${version.normalizedBcVersion()}/BondageClub/"
}

internal fun String?.normalizedBcTranslationLanguage(): String? =
    this?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "EN" }

internal fun bcTranslationResourcePaths(
    language: String?,
    csvResourcePaths: List<String> = BcResourcePaths.registeredCsvResources,
): List<String> = language.normalizedBcTranslationLanguage()
    ?.let { selectedLanguage ->
        csvResourcePaths.map { BcResourcePaths.translatedTextPath(it, selectedLanguage) }
    }
    .orEmpty()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

class BcWebResourceHttpException(
    val statusCode: Int,
    val requestUrl: String,
) : IOException("HTTP $statusCode while fetching $requestUrl")

class BcWebResourceSyncException(
    val version: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class BcFetchedResource(
    val finalUrl: String,
    val contentType: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BcFetchedResource) return false
        return finalUrl == other.finalUrl &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = finalUrl.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class BcStoredResource(
    val relativePath: String,
    val sourceUrl: String,
    val contentType: String?,
    val byteCount: Long,
)

data class BcWebResourceSnapshot(
    val version: String,
    val baseUrl: String,
    val localRootPath: String,
    val entryFilePath: String,
    val resourceCount: Int,
    val totalBytes: Long,
    val syncedAtEpochMillis: Long,
    /** Translation language fully represented by this snapshot; null means base English or legacy data. */
    val translationLanguage: String? = null,
    /** Complete set of translated text resources required by [translationLanguage]. */
    val translationResourcePaths: List<String> = emptyList(),
)

sealed interface BcWebResourceSyncState {
    data object Idle : BcWebResourceSyncState
    data class Running(
        val version: String,
        val discoveredCount: Int,
        val downloadedCount: Int,
        val failedCount: Int,
        val currentPath: String?,
    ) : BcWebResourceSyncState
    data class Ready(val snapshot: BcWebResourceSnapshot) : BcWebResourceSyncState
    data class Failed(
        val version: String,
        val message: String,
    ) : BcWebResourceSyncState
}

interface BcWebResourceFetcher {
    suspend fun fetch(url: String): BcFetchedResource
}

interface BcWebResourceStore {
    fun localRootPath(version: String): String
    suspend fun prepare(version: String)
    suspend fun write(version: String, relativePath: String, resource: BcFetchedResource): BcStoredResource
    suspend fun commit(version: String, snapshot: BcWebResourceSnapshot)
    fun currentSnapshot(version: String): BcWebResourceSnapshot?
}

interface BcWebResourceRepository {
    val syncState: StateFlow<BcWebResourceSyncState>

    fun currentSnapshot(version: String): BcWebResourceSnapshot?
    suspend fun ensureSynced(version: String): BcWebResourceSnapshot =
        currentSnapshot(version) ?: sync(version)
    suspend fun sync(version: String): BcWebResourceSnapshot
}
