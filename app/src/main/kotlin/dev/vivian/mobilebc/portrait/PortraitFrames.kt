package dev.vivian.mobilebc.portrait

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow

/** Stable across process restarts and JSON object key ordering; arrays retain drawing order. */
internal fun portraitFrameKey(owner: Int, renderingVersion: String, payload: String): String {
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> "null"
    }
    return portraitSha256("$owner\n$renderingVersion\n${canonical(JSONObject(payload))}".toByteArray())
}

/** Complete PNG frames in filesDir, independent of WebView lifetime and Android cache eviction. */
internal class PortraitFrames(private val directory: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    val geometryVersion = MutableStateFlow(0L)
    @Synchronized fun readGeometry(key: String): PortraitGeometry? = try {
        val source = File(directory, file(key).name + ".geometry")
        if (source.length() !in 1..2048) null else PortraitGeometry.parse(source.readText())
    } catch (_: IOException) { null }

    @Synchronized fun saveGeometry(key: String, geometry: PortraitGeometry) {
        val target = File(directory, file(key).name + ".geometry")
        try {
            directory.mkdirs()
            target.writeText(geometry.json())
            geometryVersion.value++
        } catch (_: IOException) { }
    }
    @Synchronized fun read(key: String): ByteArray? {
        val file = file(key)
        return try {
            if (file.length() !in 89..(MaxFrameBytes + 64L)) return null
            val stored = file.readBytes()
            val bytes = stored.copyOfRange(64, stored.size)
            bytes.takeIf { validPng(it) && portraitSha256(it) == String(stored, 0, 64, Charsets.US_ASCII) }
                ?.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: IOException) { null }
    }

    @Synchronized fun save(key: String, bytes: ByteArray) {
        require(validPng(bytes))
        val target = file(key)
        var staging: File? = null
        try {
            directory.mkdirs()
            staging = File.createTempFile("pending-", ".tmp", directory)
            staging.outputStream().use {
                it.write(portraitSha256(bytes).toByteArray(Charsets.US_ASCII))
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}")) }.sortedBy { it.lastModified() }
            var size = files.sumOf { it.length() }
            for (entry in files) {
                if (size <= maxBytes) break
                val length = entry.length()
                if (entry.delete()) {
                    File(directory, entry.name + ".geometry").delete()
                    size -= length
                }
            }
        } catch (_: IOException) {
            // A visible frame remains usable when storage is full.
        } finally { staging?.delete() }
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return File(directory, key)
    }

    companion object {
        const val MaxFrameBytes = 8 * 1024 * 1024
        private fun validPng(bytes: ByteArray): Boolean = bytes.size in 25..MaxFrameBytes &&
            bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) &&
            java.nio.ByteBuffer.wrap(bytes, 16, 8).let { it.int == 500 && it.int == 1000 }
    }
}
