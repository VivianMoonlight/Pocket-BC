package dev.vivian.mobilebc.resource

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcWebResourceSyncerTest {
    @Test
    fun missingAssetPathsTryOnlyKnownLayoutsWithinTheExactReleaseOrPrerelease() = runTest {
        for (version in listOf("R132", "R133Alpha1", "R133Beta2", "R133", "R134")) {
            for (status in listOf(404, 410)) {
                val urls = mutableListOf<String>()
                val local = "Assets/Female3DCG/Female3DCG.js"
                val primary = if (version == "R132") local else "Assets/Female3DCG.js"
                val alternate = if (version == "R132") "Assets/Female3DCG.js" else local
                val store = RecordingStore()
                val syncer = BcWebResourceSyncer(
                    fetcher = object : BcWebResourceFetcher {
                        override suspend fun fetch(url: String): BcFetchedResource {
                            urls += url
                            if (url == bcWebBaseUrl(version) + primary) throw BcWebResourceHttpException(status, url)
                            return fetched(url)
                        }
                    },
                    store = store, csvResourcePaths = emptyList(), standaloneResourcePaths = listOf(local),
                    translationLanguage = "EN", retryDelay = {},
                )

                assertEquals(version, syncer.sync(version).version)
                assertEquals(listOf(bcWebBaseUrl(version) + primary, bcWebBaseUrl(version) + alternate), urls)
                assertEquals(listOf(version to local), store.writes)
            }
        }
    }

    @Test
    fun unavailableReleaseExhaustsOnlyItsOwnLayoutsAndKeepsTheCompleteSnapshot() = runTest {
        val existing = snapshot("R132", resourceCount = 7, totalBytes = 1234L)
        val store = RecordingStore().apply { seed(existing) }
        val urls = mutableListOf<String>()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    urls += url
                    throw BcWebResourceHttpException(404, url)
                }
            },
            store = store, csvResourcePaths = emptyList(),
            standaloneResourcePaths = listOf("Assets/Female3DCG/Female3DCG.js"),
            translationLanguage = "EN", retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R132") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertFalse((failure as BcWebResourceSyncException).retryable)
        assertEquals(listOf(
            bcWebBaseUrl("R132") + "Assets/Female3DCG/Female3DCG.js",
            bcWebBaseUrl("R132") + "Assets/Female3DCG.js",
        ), urls)
        assertTrue(store.commits.isEmpty())
        assertEquals(existing, syncer.currentSnapshot("R132"))
    }

    @Test
    fun translatedAssetFallbackKeepsItsLanguageAndStableLocalPath() = runTest {
        val fetcher = object : BcWebResourceFetcher {
            override suspend fun fetch(url: String): BcFetchedResource {
                if (url.endsWith("/Assets/AssetStrings_CN.txt")) throw BcWebResourceHttpException(404, url)
                return fetched(url)
            }
        }
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(fetcher, store,
            csvResourcePaths = listOf("Assets/Female3DCG/AssetStrings.csv"), standaloneResourcePaths = emptyList())

        val result = syncer.sync("R133Beta2")

        assertEquals(listOf("Assets/Female3DCG/AssetStrings_CN.txt"), result.translationResourcePaths)
        assertTrue(store.writes.contains("R133Beta2" to "Assets/Female3DCG/AssetStrings_CN.txt"))
    }

    @Test
    fun permissionServerAndContentFailuresNeverTriggerLayoutFallback() = runTest {
        for (kind in listOf("403", "429", "503", "html")) {
            val urls = mutableListOf<String>()
            val store = RecordingStore()
            val syncer = BcWebResourceSyncer(
                fetcher = object : BcWebResourceFetcher {
                    override suspend fun fetch(url: String): BcFetchedResource {
                        urls += url
                        if (kind != "html") throw BcWebResourceHttpException(kind.toInt(), url)
                        return fetched(url).copy(bytes = "<html>unavailable</html>".encodeToByteArray())
                    }
                },
                store = store, csvResourcePaths = emptyList(),
                standaloneResourcePaths = listOf("Assets/Female3DCG/Female3DCG.js"),
                translationLanguage = "EN", retryDelay = {},
            )

            assertTrue(runCatching { syncer.sync("R133") }.exceptionOrNull() is BcWebResourceSyncException)
            assertEquals(listOf(bcWebBaseUrl("R133") + "Assets/Female3DCG.js"), urls.distinct())
            assertEquals(if (kind == "403") 1 else 3, urls.size)
            assertTrue(store.commits.isEmpty())
        }
    }

    @Test
    fun redirectedFilesPortsQueriesAndEncodedTraversalAreRejectedWithoutFallback() = runTest {
        val base = bcWebBaseUrl("R133")
        for (destination in listOf(
            base + "Assets/Female3DCGExtended.js",
            base + "Assets/Female3DCG.js?version=R132",
            base + "Assets/Female3DCG.js#other",
            base + "Assets/%2e%2e/Female3DCG.js",
            base.replace(".com/", ".com:8443/") + "Assets/Female3DCG.js",
            base.replace("https://", "https://user@") + "Assets/Female3DCG.js",
        )) {
            val store = RecordingStore()
            var attempts = 0
            val syncer = BcWebResourceSyncer(
                fetcher = object : BcWebResourceFetcher {
                    override suspend fun fetch(url: String): BcFetchedResource {
                        attempts += 1
                        return fetched(destination)
                    }
                },
                store = store, csvResourcePaths = emptyList(),
                standaloneResourcePaths = listOf("Assets/Female3DCG/Female3DCG.js"),
                translationLanguage = "EN", retryDelay = {},
            )

            val failure = runCatching { syncer.sync("R133") }.exceptionOrNull()
            assertTrue(destination, failure is BcWebResourceSyncException)
            assertFalse((failure as BcWebResourceSyncException).retryable)
            assertEquals(1, attempts)
            assertTrue(store.writes.isEmpty())
            assertTrue(store.commits.isEmpty())
        }
    }

    @Test
    fun redirectBetweenKnownLayoutsOfTheSameFileIsAccepted() = runTest {
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String) = fetched(url.replace("/Assets/Female3DCG.js", "/Assets/Female3DCG/Female3DCG.js"))
            },
            store = store, csvResourcePaths = emptyList(),
            standaloneResourcePaths = listOf("Assets/Female3DCG/Female3DCG.js"), translationLanguage = "EN",
        )
        assertEquals(1, syncer.sync("R133Beta2").resourceCount)
        assertEquals(listOf("R133Beta2" to "Assets/Female3DCG/Female3DCG.js"), store.writes)
    }

    @Test
    fun canonicalHostRedirectStillAcceptsTheSameResource() = runTest {
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String) = fetched(url.replace("https://www.", "https://"))
            },
            store = RecordingStore(), csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(), translationLanguage = "EN",
        )
        assertEquals(1, syncer.sync("R133").resourceCount)
    }

    @Test
    fun changingSourceInvalidatesMemoryAndPersistedSnapshotEvenIfRefetchFails() = runTest {
        var base = bcWebBaseUrl("R133")
        var fail = false
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    if (fail) throw BcWebResourceHttpException(404, url)
                    return fetched(url)
                }
            },
            store = store, baseUrlForVersion = { base }, csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(), translationLanguage = "EN", retryDelay = {},
        )
        val original = syncer.ensureSynced("R133")
        base = "https://mirror.example/R133/BondageClub/"
        fail = true

        assertNull(syncer.currentSnapshot("R133"))
        assertTrue(runCatching { syncer.ensureSynced("R133") }.isFailure)
        assertNull(syncer.currentSnapshot("R133"))
        assertEquals(original, store.currentSnapshot("R133"))

        fail = false
        assertEquals(base, syncer.ensureSynced("R133").baseUrl)
        assertEquals(2, store.commits.size)
    }

    @Test
    fun r133DownloadsRelocatedAssetsButPreservesLocalCachePaths() = runTest {
        val fetcher = RecordingFetcher()
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(fetcher = fetcher, store = store)

        val snapshot = syncer.sync("R133")

        assertEquals(11, snapshot.resourceCount)
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG.csv") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/AssetStrings.csv") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG_CN.txt") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/AssetStrings_CN.txt") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG.js") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCGExtended.js") })
        assertFalse(fetcher.urls.any { it.contains("/Assets/Female3DCG/") })
        assertTrue(store.writes.contains("R133" to "Assets/Female3DCG/Female3DCG.csv"))
        assertTrue(store.writes.contains("R133" to "Assets/Female3DCG/AssetStrings_CN.txt"))
        assertTrue(store.writes.contains("R133" to "Assets/Female3DCG/Female3DCGExtended.js"))
    }

    @Test
    fun r132KeepsOriginalAssetUrls() = runTest {
        val fetcher = RecordingFetcher()
        BcWebResourceSyncer(fetcher = fetcher, store = RecordingStore()).sync("R132")

        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG/Female3DCG.csv") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG/AssetStrings_CN.txt") })
        assertTrue(fetcher.urls.any { it.endsWith("/Assets/Female3DCG/Female3DCGExtended.js") })
    }

    @Test
    fun ensureSyncedReusesACompleteSnapshotWithoutDownloadingAgain() = runTest {
        val existing = snapshot("R131", resourceCount = 6, totalBytes = 1234L)
        val fetcher = RecordingFetcher()
        val store = RecordingStore().apply { seed(existing) }
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
        )

        val ensured = syncer.ensureSynced(" r131 ")

        assertEquals(existing, ensured)
        assertTrue(fetcher.urls.isEmpty())
        assertTrue(store.commits.isEmpty())
        assertEquals(BcWebResourceSyncState.Ready(existing), syncer.syncState.value)
    }

    @Test
    fun currentSnapshotUsesOnlySnapshotsAlreadyValidatedOffThread() = runTest {
        val existing = snapshot("R131", resourceCount = 6, totalBytes = 1234L)
        val store = RecordingStore().apply { seed(existing) }
        val syncer = BcWebResourceSyncer(
            fetcher = RecordingFetcher(),
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
        )

        assertNull(syncer.currentSnapshot("R131"))
        assertEquals(0, store.snapshotReads)

        assertEquals(existing, syncer.ensureSynced("R131"))
        assertEquals(1, store.snapshotReads)

        repeat(10) {
            assertEquals(existing, syncer.currentSnapshot("R131"))
        }
        assertEquals(1, store.snapshotReads)
    }

    @Test
    fun ensureSyncedRefreshesAnExistingSnapshotForTheSelectedTranslationLanguage() = runTest {
        val existing = snapshot("R131", resourceCount = 6, totalBytes = 1234L)
        val fetcher = RecordingFetcher()
        val store = RecordingStore().apply { seed(existing) }
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "cn" },
        )

        val ensured = syncer.ensureSynced("R131")

        assertEquals("CN", ensured.translationLanguage)
        assertEquals(listOf("Screens/Interface_CN.txt"), ensured.translationResourcePaths)
        assertTrue(fetcher.urls.any { it.endsWith("/Screens/Interface.csv") })
        assertTrue(fetcher.urls.any { it.endsWith("/Screens/Interface_CN.txt") })
        assertEquals(listOf("R131"), store.commits.map { it.first })
    }

    @Test
    fun ensureSyncedRefreshesALanguageSnapshotWithAnIncompleteTranslationManifest() = runTest {
        val incomplete = snapshot("R131", resourceCount = 1, totalBytes = 1234L).copy(
            translationLanguage = "CN",
        )
        val fetcher = RecordingFetcher()
        val store = RecordingStore().apply { seed(incomplete) }
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
        )

        val ensured = syncer.ensureSynced("R131")

        assertEquals(listOf("Screens/Interface_CN.txt"), ensured.translationResourcePaths)
        assertTrue(fetcher.urls.any { it.endsWith("/Screens/Interface_CN.txt") })
        assertEquals(1, store.commits.size)
    }

    @Test
    fun englishLanguageDownloadsOnlyBaseCsvResources() = runTest {
        val fetcher = RecordingFetcher()
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = RecordingStore(),
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
        )

        val snapshot = syncer.sync("R130")

        assertNull(snapshot.translationLanguage)
        assertTrue(fetcher.urls.any { it.endsWith("/Screens/Interface.csv") })
        assertFalse(fetcher.urls.any { it.endsWith("/Screens/Interface_CN.txt") })
    }

    @Test
    fun selectedTranslationLanguageDownloadsMatchingTextResource() = runTest {
        val fetcher = RecordingFetcher()
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = RecordingStore(),
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
        )

        val snapshot = syncer.sync("R130")

        assertEquals("CN", snapshot.translationLanguage)
        assertEquals(listOf("Screens/Interface_CN.txt"), snapshot.translationResourcePaths)
        assertTrue(fetcher.urls.any { it.endsWith("/Screens/Interface_CN.txt") })
    }

    @Test
    fun syncBuildsAndStoresIndependentResourceSetsForEachVersion() = runTest {
        val fetcher = RecordingFetcher()
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = listOf("Scripts/Game.js"),
            maxConcurrentRequests = 2,
        )

        val release = syncer.sync(" r130 ")
        val prerelease = syncer.sync("r131Beta2")

        assertEquals("R130", release.version)
        assertEquals("https://www.bondageprojects.elementfx.com/R130/BondageClub/", release.baseUrl)
        assertEquals("R131Beta2", prerelease.version)
        assertEquals("https://www.bondageprojects.elementfx.com/R131Beta2/BondageClub/", prerelease.baseUrl)
        assertTrue(fetcher.urls.any { it.contains("/R130/BondageClub/Screens/Interface.csv") })
        assertTrue(fetcher.urls.any { it.contains("/R131Beta2/BondageClub/Scripts/Game.js") })
        assertEquals(
            setOf("R130", "R131Beta2"),
            store.writes.mapTo(linkedSetOf()) { it.first },
        )
        assertNotNull(syncer.currentSnapshot("r130"))
        assertNotNull(syncer.currentSnapshot("R131beta2"))
    }

    @Test
    fun syncUsesExplicitRequestedVersionAndNeverFallsBackForBlankInput() = runTest {
        val fetcher = RecordingFetcher()
        val syncer = BcWebResourceSyncer(
            fetcher = fetcher,
            store = RecordingStore(),
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
        )

        val snapshot = syncer.sync(" r131 ")
        val blankFailure = runCatching { syncer.sync("   ") }.exceptionOrNull()

        assertEquals("R131", snapshot.version)
        assertTrue(fetcher.urls.all { it.contains("/R131/BondageClub/") })
        assertTrue(blankFailure is IllegalArgumentException)
        assertNull(syncer.currentSnapshot("   "))
    }

    @Test
    fun required404DoesNotCommitOrReplaceExistingSnapshot() = runTest {
        val existing = snapshot("R131", resourceCount = 6, totalBytes = 1234L)
        val store = RecordingStore().apply { seed(existing) }
        var attempts = 0
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    attempts += 1
                    throw BcWebResourceHttpException(404, url)
                }
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertFalse((failure as BcWebResourceSyncException).retryable)
        assertTrue(failure?.message.orEmpty().contains("Screens/Interface.csv"))
        assertEquals(1, attempts)
        assertTrue(store.commits.isEmpty())
        assertEquals(existing, syncer.currentSnapshot("R131"))
        assertTrue(syncer.syncState.value is BcWebResourceSyncState.Failed)
    }

    @Test
    fun translation404DoesNotPublishACompletedLanguageSnapshot() = runTest {
        val existing = snapshot("R131", resourceCount = 1, totalBytes = 1234L)
        val store = RecordingStore().apply { seed(existing) }
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    if (url.endsWith("_CN.txt")) throw BcWebResourceHttpException(404, url)
                    return fetched(url)
                }
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
            retryDelay = {},
        )

        val failure = runCatching { syncer.ensureSynced("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertFalse((failure as BcWebResourceSyncException).retryable)
        assertTrue(store.commits.isEmpty())
        assertEquals(existing, syncer.currentSnapshot("R131"))
        assertTrue(syncer.syncState.value is BcWebResourceSyncState.Failed)
    }

    @Test
    fun transientOptionalTranslationFailureDoesNotPublishAndRemainsRetryable() = runTest {
        val existing = snapshot("R131", resourceCount = 6, totalBytes = 1234L)
        val store = RecordingStore().apply { seed(existing) }
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    if (url.endsWith("_CN.txt")) throw BcWebResourceHttpException(503, url)
                    return fetched(url)
                }
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
            maxFetchAttempts = 1,
            retryDelay = {},
        )

        val failure = runCatching { syncer.ensureSynced("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertTrue((failure as BcWebResourceSyncException).retryable)
        assertTrue(store.commits.isEmpty())
        assertEquals(existing, syncer.currentSnapshot("R131"))
        assertTrue(syncer.syncState.value is BcWebResourceSyncState.Failed)
    }

    @Test
    fun htmlErrorPageForRequiredResourceIsRejected() = runTest {
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource = BcFetchedResource(
                    finalUrl = url,
                    contentType = "text/html; charset=UTF-8",
                    bytes = "<!doctype html><title>Outdated Version</title>".encodeToByteArray(),
                )
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
            maxFetchAttempts = 1,
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("HTML response"))
        assertTrue(store.commits.isEmpty())
    }

    @Test
    fun redirectToAnotherBcVersionIsRejected() = runTest {
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource =
                    fetched(url.replace("/R131/", "/R132/"))
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("outside requested version root"))
        assertTrue(store.commits.isEmpty())
    }

    @Test
    fun transientServerFailuresAreRetriedBeforePublishing() = runTest {
        var attempts = 0
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    attempts += 1
                    if (attempts < 3) throw BcWebResourceHttpException(503, url)
                    return fetched(url)
                }
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
            maxFetchAttempts = 3,
            retryDelay = {},
        )

        val snapshot = syncer.sync("R131")

        assertEquals(3, attempts)
        assertEquals(1, snapshot.resourceCount)
        assertEquals(1, store.commits.size)
    }

    @Test
    fun exhaustedTransientFailuresRemainRetryableForTheSessionCoordinator() = runTest {
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    throw BcWebResourceHttpException(503, url)
                }
            },
            store = RecordingStore(),
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "EN" },
            maxFetchAttempts = 1,
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertTrue((failure as BcWebResourceSyncException).retryable)
    }

    @Test
    fun optionalResourceWriteFailureAbortsWithoutPublishingPartialStaging() = runTest {
        val store = RecordingStore(failWritePath = "Screens/Interface_CN.txt")
        val syncer = BcWebResourceSyncer(
            fetcher = RecordingFetcher(),
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        val syncFailure = failure as BcWebResourceSyncException
        assertTrue(syncFailure.retryable)
        assertTrue(syncFailure.cause is IOException)
        assertTrue(store.commits.isEmpty())
        assertTrue(syncer.syncState.value is BcWebResourceSyncState.Failed)
    }

    @Test
    fun commitIOExceptionIsExposedAsARetryableSyncFailure() = runTest {
        val store = RecordingStore(failCommit = true)
        val syncer = BcWebResourceSyncer(
            fetcher = RecordingFetcher(),
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            translationLanguageProvider = { "CN" },
            retryDelay = {},
        )

        val failure = runCatching { syncer.sync("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        val syncFailure = failure as BcWebResourceSyncException
        assertTrue(syncFailure.retryable)
        assertTrue(syncFailure.cause is IOException)
        assertTrue(syncer.currentSnapshot("R131") == null)
        assertTrue(syncer.syncState.value is BcWebResourceSyncState.Failed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun baseUrlRejectsUnsafeVersionPath() {
        bcWebBaseUrl("../R130")
    }

    @Test
    fun cancellingAnObsoleteVersionSyncDoesNotPublishFailure() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val store = RecordingStore()
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    fetchStarted.complete(Unit)
                    awaitCancellation()
                }
            },
            store = store,
            csvResourcePaths = listOf("Screens/Interface.csv"),
            standaloneResourcePaths = emptyList(),
            maxConcurrentRequests = 1,
        )

        val syncJob = launch { syncer.sync("R130") }
        fetchStarted.await()
        syncJob.cancelAndJoin()

        assertEquals(BcWebResourceSyncState.Idle, syncer.syncState.value)
        assertTrue(store.commits.isEmpty())
    }

    private class RecordingFetcher : BcWebResourceFetcher {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun fetch(url: String): BcFetchedResource {
            urls += url
            return fetched(url)
        }
    }

    private class RecordingStore(
        private val failWritePath: String? = null,
        private val failCommit: Boolean = false,
    ) : BcWebResourceStore {
        val writes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val commits: MutableList<Pair<String, BcWebResourceSnapshot>> =
            Collections.synchronizedList(mutableListOf())
        private val snapshots = mutableMapOf<String, BcWebResourceSnapshot>()
        var snapshotReads: Int = 0
            private set

        override fun localRootPath(version: String): String = "/bc-web/$version/BondageClub"

        override suspend fun prepare(version: String) = Unit

        override suspend fun write(
            version: String,
            relativePath: String,
            resource: BcFetchedResource,
        ): BcStoredResource {
            if (relativePath == failWritePath) {
                throw IOException("disk full while writing $relativePath")
            }
            writes += version to relativePath
            return BcStoredResource(
                relativePath = relativePath,
                sourceUrl = resource.finalUrl,
                contentType = resource.contentType,
                byteCount = resource.bytes.size.toLong(),
            )
        }

        override suspend fun commit(version: String, snapshot: BcWebResourceSnapshot) {
            if (failCommit) throw IOException("disk unavailable while publishing $version")
            commits += version to snapshot
            snapshots[version] = snapshot
        }

        override fun currentSnapshot(version: String): BcWebResourceSnapshot? {
            snapshotReads += 1
            return snapshots[version]
        }

        fun seed(snapshot: BcWebResourceSnapshot) {
            snapshots[snapshot.version] = snapshot
        }
    }

    private companion object {
        fun fetched(url: String): BcFetchedResource = BcFetchedResource(
            finalUrl = url,
            contentType = "text/plain",
            bytes = url.encodeToByteArray(),
        )

        fun snapshot(
            version: String,
            resourceCount: Int,
            totalBytes: Long,
        ): BcWebResourceSnapshot = BcWebResourceSnapshot(
            version = version,
            baseUrl = bcWebBaseUrl(version),
            localRootPath = "/bc-web/$version/BondageClub",
            entryFilePath = "index.html",
            resourceCount = resourceCount,
            totalBytes = totalBytes,
            syncedAtEpochMillis = 123L,
        )
    }
}
