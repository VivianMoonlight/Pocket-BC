package dev.vivian.mobilebc.resource

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BcWebResourceSyncer(
    private val fetcher: BcWebResourceFetcher,
    private val store: BcWebResourceStore,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val baseUrlForVersion: (String) -> String = ::bcWebBaseUrl,
    private val csvResourcePaths: List<String> = BcResourcePaths.registeredCsvResources,
    private val standaloneResourcePaths: List<String> = BcResourcePaths.registeredStandaloneResources,
    private val translationLanguage: String = "CN",
    private val translationLanguageProvider: (() -> String)? = null,
    private val maxConcurrentRequests: Int = 4,
    private val maxFetchAttempts: Int = 3,
    private val retryDelay: suspend (failedAttempt: Int) -> Unit = { failedAttempt ->
        delay(250L * failedAttempt)
    },
) : BcWebResourceRepository {
    private val _syncState = MutableStateFlow<BcWebResourceSyncState>(BcWebResourceSyncState.Idle)
    private val syncMutex = Mutex()
    private val validatedSnapshots = ConcurrentHashMap<String, BcWebResourceSnapshot>()

    override val syncState: StateFlow<BcWebResourceSyncState> = _syncState.asStateFlow()

    override fun currentSnapshot(version: String): BcWebResourceSnapshot? =
        version.normalizedRequestedVersionOrNull()?.let { normalized ->
            validatedSnapshots[normalized]?.takeIf { it.baseUrl == baseUrlForVersion(normalized).ensureTrailingSlash() }
        }

    override suspend fun ensureSynced(version: String): BcWebResourceSnapshot =
        syncRequested(version, reuseCurrentSnapshot = true)

    override suspend fun sync(version: String): BcWebResourceSnapshot =
        syncRequested(version, reuseCurrentSnapshot = false)

    private suspend fun syncRequested(
        version: String,
        reuseCurrentSnapshot: Boolean,
    ): BcWebResourceSnapshot = withContext(Dispatchers.IO) {
        val normalizedVersion = version.normalizedRequestedVersion()
        val baseUri = URI(baseUrlForVersion(normalizedVersion).ensureTrailingSlash())
        syncMutex.withLock {
            val activeTranslationLanguage = activeTranslationLanguage()
            val expectedTranslationPaths = bcTranslationResourcePaths(activeTranslationLanguage, csvResourcePaths)
            val matchesActiveLanguage: (BcWebResourceSnapshot) -> Boolean = { snapshot ->
                snapshot.translationLanguage == activeTranslationLanguage &&
                    snapshot.translationResourcePaths == expectedTranslationPaths
            }
            val matchesSource: (BcWebResourceSnapshot) -> Boolean = { snapshot ->
                snapshot.version == normalizedVersion && snapshot.baseUrl == baseUri.toString()
            }
            validatedSnapshots[normalizedVersion]?.takeUnless(matchesSource)?.let {
                validatedSnapshots.remove(normalizedVersion)
            }
            if (reuseCurrentSnapshot) {
                validatedSnapshots[normalizedVersion]
                    ?.takeIf(matchesActiveLanguage)
                    ?.let { snapshot ->
                        _syncState.value = BcWebResourceSyncState.Ready(snapshot)
                        return@withLock snapshot
                    }
            }
            val persistedSnapshot = store.currentSnapshot(normalizedVersion)?.takeIf(matchesSource)
            if (persistedSnapshot == null) {
                validatedSnapshots.remove(normalizedVersion)
            } else {
                validatedSnapshots[normalizedVersion] = persistedSnapshot
                if (reuseCurrentSnapshot && matchesActiveLanguage(persistedSnapshot)) {
                    _syncState.value = BcWebResourceSyncState.Ready(persistedSnapshot)
                    return@withLock persistedSnapshot
                }
            }
            val stateBeforeSync = _syncState.value
                .takeUnless { it is BcWebResourceSyncState.Running }
                ?: BcWebResourceSyncState.Idle
            runCatching {
                syncInternal(
                    version = normalizedVersion,
                    baseUri = baseUri,
                    activeTranslationLanguage = activeTranslationLanguage,
                    translationPaths = expectedTranslationPaths,
                )
            }.getOrElse { error ->
                if (error is CancellationException) {
                    _syncState.value = stateBeforeSync
                    throw error
                }
                val surfacedError = error.asSyncFailure(normalizedVersion)
                _syncState.value = BcWebResourceSyncState.Failed(
                    version = normalizedVersion,
                    message = surfacedError.message ?: surfacedError::class.java.simpleName,
                )
                throw surfacedError
            }
        }
    }

    private suspend fun syncInternal(
        version: String,
        baseUri: URI,
        activeTranslationLanguage: String?,
        translationPaths: List<String>,
    ): BcWebResourceSnapshot {
        val stored = mutableListOf<BcStoredResource>()
        val requiredFailures = mutableListOf<ResourceFetchResult.Failed>()
        val translationFailures = mutableListOf<ResourceFetchResult.Failed>()
        var failedCount = 0

        store.prepare(version)
        val requiredPaths = (csvResourcePaths + standaloneResourcePaths)
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
        require(requiredPaths.isNotEmpty()) {
            "At least one required BC web resource must be configured."
        }
        val requests = (requiredPaths.map { relativePath ->
            resourceRequest(baseUri, relativePath, required = true, version = version)
        } + translationPaths.map { relativePath ->
            resourceRequest(baseUri, relativePath, required = false, version = version)
        }).distinctBy(ResourceRequest::relativePath)
        val queue = ArrayDeque(requests)

        while (!queue.isEmpty()) {
            val batch = queue.removeBatch(maxConcurrentRequests.coerceAtLeast(1))
            val firstPath = batch.firstOrNull()?.relativePath
            _syncState.value = BcWebResourceSyncState.Running(
                version = version,
                discoveredCount = requests.size,
                downloadedCount = stored.size,
                failedCount = failedCount,
                currentPath = firstPath,
            )

            val batchResults = fetchBatch(version, baseUri, batch)
            batchResults.forEach { result ->
                when (result) {
                    is ResourceFetchResult.Failed -> {
                        failedCount += 1
                        when {
                            result.request.required -> requiredFailures += result
                            else -> translationFailures += result
                        }
                    }
                    is ResourceFetchResult.Stored -> {
                        stored += result.resource
                    }
                }
            }
        }

        if (requiredFailures.isNotEmpty()) {
            val failureSummary = requiredFailures.joinToString { failure ->
                "${failure.request.relativePath} (${failure.error.message ?: failure.error::class.java.simpleName})"
            }
            throw BcWebResourceSyncException(
                version = version,
                retryable = requiredFailures.all { failure -> failure.error.isRetryableFetchFailure() },
                message = "Required BC $version resources could not be downloaded: $failureSummary",
            )
        }
        if (translationFailures.isNotEmpty()) {
            val failureSummary = translationFailures.joinToString { failure ->
                "${failure.request.relativePath} (${failure.error.message ?: failure.error::class.java.simpleName})"
            }
            throw BcWebResourceSyncException(
                version = version,
                retryable = translationFailures.all { failure -> failure.error.isRetryableFetchFailure() },
                message = "Translated BC $version resources could not be downloaded: $failureSummary",
            )
        }
        val storedPaths = stored.mapTo(hashSetOf(), BcStoredResource::relativePath)
        val missingExpectedPaths = (requiredPaths + translationPaths).filterNot(storedPaths::contains)
        check(missingExpectedPaths.isEmpty()) {
            "Expected BC $version resources were not staged: ${missingExpectedPaths.joinToString()}"
        }
        check(stored.isNotEmpty()) { "BC $version resource sync produced no files." }

        val snapshot = BcWebResourceSnapshot(
            version = version,
            baseUrl = baseUri.toString(),
            localRootPath = store.localRootPath(version),
            entryFilePath = "index.html",
            resourceCount = stored.size,
            totalBytes = stored.sumOf { it.byteCount },
            syncedAtEpochMillis = clockMillis(),
            translationLanguage = activeTranslationLanguage,
            translationResourcePaths = translationPaths,
        )
        store.commit(version, snapshot)
        validatedSnapshots[version] = snapshot
        _syncState.value = BcWebResourceSyncState.Ready(snapshot)
        return snapshot
    }

    private suspend fun fetchBatch(
        version: String,
        baseUri: URI,
        batch: List<ResourceRequest>,
    ): List<ResourceFetchResult> = coroutineScope {
        batch.map { request ->
            async(Dispatchers.IO) {
                val fetched = try {
                    fetchResource(request, baseUri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@async ResourceFetchResult.Failed(request, error)
                }
                ResourceFetchResult.Stored(
                    request = request,
                    resource = store.write(version, request.relativePath, fetched),
                )
            }
        }.awaitAll()
    }

    private suspend fun fetchResource(request: ResourceRequest, baseUri: URI): BcFetchedResource {
        for ((index, uri) in request.uris.withIndex()) {
            try {
                return fetchWithRetry(request, uri, baseUri)
            } catch (error: BcWebResourceHttpException) {
                // Only a missing path permits a layout fallback. Authentication,
                // server failures and invalid content keep their original meaning.
                if (error.statusCode !in listOf(404, 410) || index == request.uris.lastIndex) throw error
            }
        }
        error("No resource URL for ${request.relativePath}")
    }

    private suspend fun fetchWithRetry(
        request: ResourceRequest,
        uri: URI,
        baseUri: URI,
    ): BcFetchedResource {
        val maxAttempts = maxFetchAttempts.coerceAtLeast(1)
        var attempt = 1
        while (true) {
            try {
                return fetcher.fetch(uri.toString()).also { fetched ->
                    fetched.requireUsable(request, baseUri)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (attempt >= maxAttempts || !error.isRetryableFetchFailure()) throw error
                retryDelay(attempt)
                attempt += 1
            }
        }
    }

    private fun resourceRequest(
        baseUri: URI,
        relativePath: String,
        required: Boolean,
        version: String,
    ): ResourceRequest {
        val uris = BcResourcePaths.remotePathCandidates(version, relativePath).map { remotePath ->
            val normalized = requireNotNull(baseUri.resolve(remotePath).normalizedForFetch()) {
                "Invalid BC web resource path: $relativePath"
            }
            require(normalized.isUnder(baseUri)) { "BC web resource escapes its version root: $relativePath" }
            normalized
        }
        return ResourceRequest(
            uris = uris,
            relativePath = baseUri.resolve(relativePath).toLocalRelativePath(baseUri),
            required = required,
        )
    }

    private fun ArrayDeque<ResourceRequest>.removeBatch(limit: Int): List<ResourceRequest> =
        buildList {
            while (!this@removeBatch.isEmpty() && size < limit) {
                add(this@removeBatch.removeFirst())
            }
        }

    private fun URI.toLocalRelativePath(baseUri: URI): String {
        val relative = baseUri.relativize(this).path
        return relative.ifBlank { "index.html" }
    }

    private fun URI.normalizedForFetch(): URI? {
        if (scheme != "https" && scheme != "http") return null
        val path = rawPath.orEmpty()
        if (path.contains("..")) return null
        return URI(scheme, authority, path.ifBlank { "/" }, rawQuery, null).normalize()
    }

    private fun URI.isUnder(base: URI): Boolean =
        scheme.equals(base.scheme, ignoreCase = true) &&
            host.normalizedHost().equals(base.host.normalizedHost(), ignoreCase = true) &&
            effectivePort() == base.effectivePort() && rawUserInfo == null &&
            path.startsWith(base.path)

    private fun URI.effectivePort(): Int = if (port != -1) port else if (scheme.equals("https", ignoreCase = true)) 443 else 80

    private fun String?.normalizedHost(): String =
        orEmpty().lowercase().removePrefix("www.")

    private fun BcFetchedResource.requireUsable(request: ResourceRequest, baseUri: URI) {
        if (bytes.isEmpty()) {
            throw InvalidBcWebResourceException(
                "Empty response while fetching ${request.relativePath}",
                retryable = true,
            )
        }
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml" || bytes.looksLikeHtml()) {
            throw InvalidBcWebResourceException(
                "HTML response while fetching ${request.relativePath}",
                retryable = true,
            )
        }
        val finalUri = runCatching { URI(finalUrl) }.getOrElse { error ->
            throw InvalidBcWebResourceException(
                "Invalid final URL for ${request.relativePath}: ${error.message}",
                retryable = false,
            )
        }
        if (!finalUri.isUnder(baseUri)) {
            throw InvalidBcWebResourceException(
                "${request.relativePath} redirected outside requested version root to $finalUrl",
                retryable = false,
            )
        }
        val matchesKnownLayout = request.uris.any { it.rawPath == finalUri.rawPath && it.rawQuery == finalUri.rawQuery }
        if (!matchesKnownLayout || finalUri.rawFragment != null) {
            throw InvalidBcWebResourceException(
                "${request.relativePath} redirected to a different resource: $finalUrl",
                retryable = false,
            )
        }
    }

    private fun ByteArray.looksLikeHtml(): Boolean {
        val prefix = decodeToString(endIndex = minOf(size, HtmlProbeByteCount))
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        return prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("<head")
    }

    private fun Throwable.isRetryableFetchFailure(): Boolean = when (this) {
        is BcWebResourceHttpException ->
            statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
        is InvalidBcWebResourceException -> retryable
        is IOException -> true
        else -> false
    }

    private fun String.normalizedRequestedVersion(): String {
        require(isNotBlank()) { "BC version is required for resource sync." }
        return normalizedBcVersion()
    }

    private fun String.normalizedRequestedVersionOrNull(): String? =
        takeIf(String::isNotBlank)?.let { runCatching { it.normalizedBcVersion() }.getOrNull() }

    private fun activeTranslationLanguage(): String? =
        (translationLanguageProvider?.invoke() ?: translationLanguage)
            .normalizedBcTranslationLanguage()

    private fun Throwable.asSyncFailure(version: String): Throwable = when (this) {
        is BcWebResourceSyncException -> this
        is IOException -> BcWebResourceSyncException(
            version = version,
            retryable = true,
            message = "Could not stage or publish BC $version resources: ${message ?: this::class.java.simpleName}",
            cause = this,
        )
        else -> this
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private data class ResourceRequest(
        val uris: List<URI>,
        val relativePath: String,
        val required: Boolean,
    )

    private class InvalidBcWebResourceException(
        message: String,
        val retryable: Boolean,
    ) : IOException(message)

    private sealed interface ResourceFetchResult {
        data class Stored(
            val request: ResourceRequest,
            val resource: BcStoredResource,
        ) : ResourceFetchResult

        data class Failed(
            val request: ResourceRequest,
            val error: Throwable,
        ) : ResourceFetchResult
    }

    private companion object {
        const val HtmlProbeByteCount = 512
    }
}
