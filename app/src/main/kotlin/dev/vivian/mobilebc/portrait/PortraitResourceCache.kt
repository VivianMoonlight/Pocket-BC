package dev.vivian.mobilebc.portrait

import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Stores verified resource bytes independently of the mirror that supplied them. */
internal class PortraitResourceCache(
    private val directory: File,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val legacyDirectory: File? = null,
) {
    private val requests = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val disk = Any()

    fun get(key: String, validate: (ByteArray) -> Boolean, load: () -> ByteArray?): ByteArray? {
        val result = CompletableFuture<ByteArray?>()
        requests.putIfAbsent(key, result)?.let { return it.join() }
        try {
            val bytes = readOrLoad(key, validate, load)
            result.complete(bytes)
            return bytes
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            requests.remove(key, result)
        }
    }

    private fun readOrLoad(key: String, validate: (ByteArray) -> Boolean, load: () -> ByteArray?): ByteArray? {
        val name = portraitSha256(key.toByteArray(Charsets.UTF_8))
        val file = File(directory, name)
        fun read(source: File): ByteArray? = synchronized(disk) {
            try {
                if (source.length() !in 65..(8L * 1024 * 1024 + 64)) null else {
                    val stored = source.readBytes()
                    val bytes = stored.copyOfRange(64, stored.size)
                    bytes.takeIf {
                        String(stored, 0, 64, Charsets.US_ASCII) == portraitSha256(it) && validate(it)
                    }?.also { source.setLastModified(System.currentTimeMillis()) }
                }
            } catch (_: IOException) { null }
        }
        val cached = read(file)
        if (cached != null) return cached
        val legacy = legacyDirectory?.let { File(it, name) }
        val bytes = (legacy?.let(::read) ?: load())?.takeIf { it.isNotEmpty() && it.size <= 8 * 1024 * 1024 && validate(it) }
            ?: return null
        synchronized(disk) {
            var temporary: File? = null
            try {
                directory.mkdirs()
                temporary = File.createTempFile("pending-", ".tmp", directory)
                temporary.outputStream().use {
                    it.write(portraitSha256(bytes).toByteArray(Charsets.US_ASCII))
                    it.write(bytes)
                }
                if (file.exists()) file.delete()
                if (temporary.renameTo(file)) {
                    legacy?.delete()
                    val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}")) }
                        .sortedBy { it.lastModified() }
                    var size = files.sumOf { it.length() }
                    for (entry in files) {
                        if (size <= maxBytes) break
                        val length = entry.length()
                        if (entry.delete()) size -= length
                    }
                }
            } catch (_: IOException) {
                // Disk pressure must not prevent rendering a successfully downloaded resource.
            } finally {
                temporary?.delete()
            }
        }
        return bytes
    }
}
