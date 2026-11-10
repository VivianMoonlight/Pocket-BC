package dev.vivian.mobilebc.resource

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.platform.text.AndroidBcTextResourceReader
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidBcWebResourceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagingDirectoryCreationFailureIsReportedAsIOException() = runTest {
        val webRoot = temporaryFolder.newFolder("bc-web-unwritable-version")
        assertTrue(File(webRoot, "R131").createNewFile())
        val store = AndroidBcWebResourceStore(webRoot)

        val failure = runCatching { store.prepare("R131") }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun interruptedPublishRestoresThePreviousCompleteSnapshot() = runTest {
        val webRoot = temporaryFolder.newFolder("bc-web")
        val store = AndroidBcWebResourceStore(webRoot)
        val snapshot = stageCompleteSnapshot(store, "R131")
        store.commit("R131", snapshot)
        val root = File(store.localRootPath("R131"))
        val backup = File(root.parentFile, "BondageClub.bak")

        assertTrue(root.renameTo(backup))
        assertFalse(root.exists())

        val recovered = store.currentSnapshot("R131")

        assertEquals(snapshot, recovered)
        assertTrue(root.isDirectory)
        assertFalse(backup.exists())
    }

    @Test
    fun textReaderRestoresAnInterruptedPublishBeforeReading() = runTest {
        val webRoot = temporaryFolder.newFolder("bc-web-reader-recovery")
        val store = AndroidBcWebResourceStore(webRoot)
        val reader = AndroidBcTextResourceReader(webRoot)
        val snapshot = stageCompleteSnapshot(store, "R131")
        store.commit("R131", snapshot)
        val root = File(store.localRootPath("R131"))
        val backup = File(root.parentFile, "BondageClub.bak")
        val expectedPath = requiredPaths.first()

        assertTrue(root.renameTo(backup))
        assertFalse(root.exists())

        assertEquals(expectedPath, reader.readText("R131", expectedPath))
        assertTrue(root.isDirectory)
        assertFalse(backup.exists())
    }

    @Test
    fun textReaderCannotObserveTheDirectorySwitchGap() = runTest {
        val webRoot = temporaryFolder.newFolder("bc-web-reader-lock")
        val store = AndroidBcWebResourceStore(webRoot)
        val reader = AndroidBcTextResourceReader(webRoot)
        val snapshot = stageCompleteSnapshot(store, "R131")
        store.commit("R131", snapshot)
        val root = File(store.localRootPath("R131"))
        val backup = File(root.parentFile, "BondageClub.bak")
        val expectedPath = requiredPaths.first()
        val started = CountDownLatch(1)
        val readResult = AtomicReference<String?>()
        val readFailure = AtomicReference<Throwable?>()
        val readCompleted = CountDownLatch(1)
        val readerThread = Thread {
            started.countDown()
            runCatching { reader.readText("R131", expectedPath) }
                .onSuccess(readResult::set)
                .onFailure(readFailure::set)
            readCompleted.countDown()
        }

        synchronized(AndroidBcWebResourceFiles.accessLock) {
            assertTrue(root.renameTo(backup))
            readerThread.start()
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(readCompleted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(backup.renameTo(root))
        }

        assertTrue(readCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        readerThread.join()
        assertFalse(readerThread.isAlive)
        assertNull(readFailure.get())
        assertEquals(expectedPath, readResult.get())
    }

    @Test
    fun emptySnapshotCannotReplaceAnExistingCompleteCache() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-empty"))
        val healthy = stageCompleteSnapshot(store, "R131")
        store.commit("R131", healthy)
        store.prepare("R131")
        val empty = healthy.copy(resourceCount = 0, totalBytes = 0L)

        val failure = runCatching { store.commit("R131", empty) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(healthy, store.currentSnapshot("R131"))
    }

    @Test
    fun selectedTranslationLanguageSurvivesManifestRoundTrip() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-language"))
        val snapshot = stageCompleteSnapshot(store, "R131", translationLanguage = "CN")

        store.commit("R131", snapshot)

        assertEquals(snapshot, store.currentSnapshot("R131"))
    }

    @Test
    fun legacySnapshotWithoutRoomBackgroundSourceIsNotReady() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-missing-backgrounds"))
        val snapshot = stageCompleteSnapshot(store, "R131")
        store.commit("R131", snapshot)
        File(store.localRootPath("R131"), BcResourcePaths.Backgrounds.BackgroundsJs).delete()

        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun missingTranslationFileInvalidatesThePublishedLanguageSnapshot() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-missing-translation"))
        val snapshot = stageCompleteSnapshot(store, "R131", translationLanguage = "CN")
        store.commit("R131", snapshot)
        File(store.localRootPath("R131"), snapshot.translationResourcePaths.first()).delete()

        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun zeroByteTranslationFileInvalidatesThePublishedLanguageSnapshot() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-zero-translation"))
        val snapshot = stageCompleteSnapshot(store, "R131", translationLanguage = "CN")
        store.commit("R131", snapshot)
        File(store.localRootPath("R131"), snapshot.translationResourcePaths.first()).writeBytes(byteArrayOf())

        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun htmlTranslationFileInvalidatesThePublishedLanguageSnapshot() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-html-translation"))
        val snapshot = stageCompleteSnapshot(store, "R131", translationLanguage = "CN")
        store.commit("R131", snapshot)
        File(store.localRootPath("R131"), snapshot.translationResourcePaths.first())
            .writeText("<html><title>Not Found</title></html>")

        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun commitRejectsALanguageSnapshotWithoutAllManifestTranslations() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-incomplete-translation"))
        val baseSnapshot = stageCompleteSnapshot(store, "R131")
        val translationPaths = bcTranslationResourcePaths("CN")
        val incomplete = baseSnapshot.copy(
            translationLanguage = "CN",
            translationResourcePaths = translationPaths,
        )

        val failure = runCatching { store.commit("R131", incomplete) }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun legacyBaseSnapshotIsReusedForEnglishWithoutDownloading() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-legacy-en"))
        val legacySnapshot = stageCompleteSnapshot(store, "R131")
        store.commit("R131", legacySnapshot)
        var fetchCount = 0
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    fetchCount += 1
                    error("English legacy snapshot should be reused")
                }
            },
            store = store,
            translationLanguageProvider = { "EN" },
        )

        val ensured = syncer.ensureSynced("R131")

        assertEquals(legacySnapshot, ensured)
        assertEquals(0, fetchCount)
    }

    @Test
    fun legacyBaseSnapshotIsRefreshedForTheSelectedTranslationLanguage() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-legacy-cn"))
        store.commit("R131", stageCompleteSnapshot(store, "R131"))
        val requestedUrls = Collections.synchronizedList(mutableListOf<String>())
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    requestedUrls += url
                    return fetched(url)
                }
            },
            store = store,
            translationLanguageProvider = { "CN" },
        )

        val ensured = syncer.ensureSynced("R131")

        assertEquals("CN", ensured.translationLanguage)
        assertTrue(requestedUrls.any { it.endsWith("_CN.txt") })
        assertEquals(ensured, store.currentSnapshot("R131"))
    }

    @Test
    fun transientTranslationFailureLeavesThePreviousLiveSnapshotReadable() = runTest {
        val webRoot = temporaryFolder.newFolder("bc-web-transient-translation")
        val store = AndroidBcWebResourceStore(webRoot)
        val reader = AndroidBcTextResourceReader(webRoot)
        val existing = stageCompleteSnapshot(store, "R131")
        store.commit("R131", existing)
        val syncer = BcWebResourceSyncer(
            fetcher = object : BcWebResourceFetcher {
                override suspend fun fetch(url: String): BcFetchedResource {
                    if (url.endsWith("_CN.txt")) throw BcWebResourceHttpException(503, url)
                    return fetched(url)
                }
            },
            store = store,
            translationLanguageProvider = { "CN" },
            maxFetchAttempts = 1,
            retryDelay = {},
        )

        val failure = runCatching { syncer.ensureSynced("R131") }.exceptionOrNull()

        assertTrue(failure is BcWebResourceSyncException)
        assertTrue((failure as BcWebResourceSyncException).retryable)
        assertEquals(existing, store.currentSnapshot("R131"))
        assertEquals(requiredPaths.first(), reader.readText("R131", requiredPaths.first()))
    }

    @Test
    fun zeroByteRequiredFileInvalidatesThePublishedSnapshot() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-zero-byte"))
        store.commit("R131", stageCompleteSnapshot(store, "R131"))
        File(store.localRootPath("R131"), requiredPaths.first()).writeBytes(byteArrayOf())

        assertNull(store.currentSnapshot("R131"))
    }

    @Test
    fun htmlRequiredFileInvalidatesThePublishedSnapshot() = runTest {
        val store = AndroidBcWebResourceStore(temporaryFolder.newFolder("bc-web-html"))
        store.commit("R131", stageCompleteSnapshot(store, "R131"))
        File(store.localRootPath("R131"), requiredPaths.first())
            .writeText("<!doctype html><title>Not Found</title>")

        assertNull(store.currentSnapshot("R131"))
    }

    private suspend fun stageCompleteSnapshot(
        store: AndroidBcWebResourceStore,
        version: String,
        translationLanguage: String? = null,
    ): BcWebResourceSnapshot {
        store.prepare(version)
        val translationPaths = bcTranslationResourcePaths(translationLanguage)
        val resources = (requiredPaths + translationPaths).map { relativePath ->
            val resource = BcFetchedResource(
                finalUrl = bcWebBaseUrl(version) + relativePath,
                contentType = "text/plain",
                bytes = relativePath.encodeToByteArray(),
            )
            store.write(version, relativePath, resource)
        }
        return BcWebResourceSnapshot(
            version = version,
            baseUrl = bcWebBaseUrl(version),
            localRootPath = store.localRootPath(version),
            entryFilePath = "index.html",
            resourceCount = resources.size,
            totalBytes = resources.sumOf { it.byteCount },
            syncedAtEpochMillis = 123L,
            translationLanguage = translationLanguage,
            translationResourcePaths = translationPaths,
        )
    }

    private fun fetched(url: String): BcFetchedResource = BcFetchedResource(
        finalUrl = url,
        contentType = "text/plain",
        bytes = url.encodeToByteArray(),
    )

    private companion object {
        val requiredPaths =
            BcResourcePaths.registeredCsvResources + BcResourcePaths.registeredStandaloneResources
    }
}
