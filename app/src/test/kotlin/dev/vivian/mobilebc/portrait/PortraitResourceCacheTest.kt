package dev.vivian.mobilebc.portrait

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortraitResourceCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "resource".toByteArray()
    private val valid: (ByteArray) -> Boolean = { it.contentEquals(bytes) }

    @Test fun restartReadsOfflineAndDifferentVersionsMiss() {
        val directory = temporary.newFolder()
        PortraitResourceCache(directory).get("v1", valid) { bytes }
        val restarted = PortraitResourceCache(directory)
        assertArrayEquals(bytes, restarted.get("v1", valid) { error("Offline cache tried network") })
        assertNull(restarted.get("v2", valid) { null })
    }

    @Test fun legacyCacheMigratesToPersistentStorageWithoutDownloadingAgain() {
        val legacy = temporary.newFolder("old-cache")
        val persistent = temporary.newFolder("files-resources")
        PortraitResourceCache(legacy).get("v1", valid) { bytes }
        assertArrayEquals(bytes, PortraitResourceCache(persistent, legacyDirectory = legacy).get("v1", valid) {
            error("Migrating cached bytes tried the network")
        })
        assertTrue(legacy.listFiles()!!.isEmpty())
        assertArrayEquals(bytes, PortraitResourceCache(persistent).get("v1", valid) { error("Not persisted") })
    }

    @Test fun corruptFilesAndRejectedDownloadsAreNotReused() {
        val directory = temporary.newFolder()
        val cache = PortraitResourceCache(directory)
        assertNull(cache.get("image", valid) { "wrong".toByteArray() })
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        cache.get("image", valid) { bytes }
        directory.listFiles()!!.single().writeText("broken")
        var downloads = 0
        assertArrayEquals(bytes, cache.get("image", valid) { downloads++; bytes })
        assertEquals(1, downloads)
        assertNull(cache.get("failed", valid) { null })
        assertArrayEquals(bytes, cache.get("failed", valid) { bytes })
    }

    @Test fun concurrentRequestsShareOneDownload() {
        val cache = PortraitResourceCache(temporary.newFolder())
        val executor = Executors.newFixedThreadPool(8)
        val count = java.util.concurrent.atomic.AtomicInteger()
        try {
            val results = executor.invokeAll(List(16) { Callable {
                cache.get("same-blob", valid) { count.incrementAndGet(); bytes }
            } })
            results.forEach { assertArrayEquals(bytes, it.get()) }
            assertEquals(1, count.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun slowDownloadDoesNotBlockAnUnrelatedKeyWithTheSameHash() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val cache = PortraitResourceCache(temporary.newFolder())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val slow = executor.submit<ByteArray?> {
                cache.get("Aa", valid) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    bytes
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val other = executor.submit<ByteArray?> { cache.get("BB", valid) { bytes } }
            assertArrayEquals(bytes, other.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertArrayEquals(bytes, slow.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun capacityEvictsOldestAndDiskFailureStillReturnsDownload() {
        val directory = temporary.newFolder()
        val cache = PortraitResourceCache(directory, 144)
        cache.get("old", valid) { bytes }
        directory.listFiles()!!.single().setLastModified(1)
        cache.get("new", valid) { bytes }
        File(directory, portraitSha256("new".toByteArray())).setLastModified(2)
        cache.get("newest", valid) { bytes }
        assertNull(cache.get("old", valid) { null })
        assertArrayEquals(bytes, cache.get("newest", valid) { error("Evicted newest") })
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 144)
        assertArrayEquals(bytes, PortraitResourceCache(temporary.newFile()).get("image", valid) { bytes })
    }
}
