package dev.vivian.mobilebc.resource

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Process-wide coordination for the on-disk BC resource snapshot.
 *
 * The app and foreground service run in the same process. Holding this lock while switching or
 * reading the live directory prevents readers from observing the short root -> backup -> root
 * publication window. Recovery also runs through this object so a process restart between the two
 * renames restores the last complete snapshot before any text is read.
 */
internal object AndroidBcWebResourceFiles {
    val accessLock = Any()

    const val ManifestName = "mobile-bc-resource.properties"
    const val TranslationResourcePathsProperty = "translationResourcePaths"

    val requiredResourcePaths: List<String> =
        BcResourcePaths.registeredCsvResources + BcResourcePaths.registeredStandaloneResources

    fun root(webRoot: File, version: String): File =
        File(versionDirectory(webRoot, version), "BondageClub")

    fun stagingRoot(webRoot: File, version: String): File =
        File(versionDirectory(webRoot, version), "BondageClub.tmp")

    fun backupRoot(webRoot: File, version: String): File =
        File(versionDirectory(webRoot, version), "BondageClub.bak")

    fun recoverInterruptedCommit(webRoot: File, version: String) {
        val root = root(webRoot, version)
        val backup = backupRoot(webRoot, version)
        when {
            root.isCompleteSnapshot(version) -> {
                // The live snapshot is already valid. A stale backup is only cleanup debt and
                // must never make the usable snapshot unreadable.
                if (backup.exists()) backup.deleteRecursively()
            }

            backup.isCompleteSnapshot(version) -> {
                if (root.exists() && !root.deleteRecursively()) {
                    throw IOException("Unable to discard an incomplete BC web resource snapshot.")
                }
                if (!backup.renameTo(root)) {
                    throw IOException("Unable to restore BC web resources after an interrupted publish.")
                }
            }

        }
    }

    fun readSnapshot(webRoot: File, version: String): BcWebResourceSnapshot? =
        root(webRoot, version).parseSnapshot(version)

    fun readText(webRoot: File, version: String, relativePath: String): String? {
        val root = root(webRoot, version)
        if (!root.isCompleteSnapshot(version)) return null
        val file = root.resolveSafe(relativePath)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun File.resolveSafe(relativePath: String): File {
        val output = File(this, relativePath)
        val rootPath = canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(outputPath.startsWith(rootPath)) { "Unsafe BC web resource path: $relativePath" }
        return output
    }

    private fun versionDirectory(webRoot: File, version: String): File =
        File(webRoot, version.normalizedRequestedVersion())

    private fun File.isCompleteSnapshot(requestedVersion: String): Boolean =
        parseSnapshot(requestedVersion) != null

    private fun File.parseSnapshot(requestedVersion: String): BcWebResourceSnapshot? {
        val manifest = File(this, ManifestName)
        if (!manifest.isFile) return null
        val properties = runCatching {
            Properties().also { values -> manifest.inputStream().use(values::load) }
        }.getOrNull() ?: return null
        val normalizedRequestedVersion = requestedVersion.normalizedRequestedVersion()
        val manifestVersion = properties.getProperty("version")
            ?.let { runCatching { it.normalizedRequestedVersion() }.getOrNull() }
            ?: return null
        if (manifestVersion != normalizedRequestedVersion) return null
        val translationLanguage = properties.getProperty("translationLanguage")
            .normalizedBcTranslationLanguage()
        val translationResourcePaths = properties.getProperty(TranslationResourcePathsProperty)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.toList()
            .orEmpty()
        if (translationResourcePaths != bcTranslationResourcePaths(translationLanguage)) return null
        val snapshot = BcWebResourceSnapshot(
            version = manifestVersion,
            baseUrl = properties.getProperty("baseUrl") ?: return null,
            localRootPath = absolutePath,
            entryFilePath = properties.getProperty("entryFilePath") ?: "index.html",
            resourceCount = properties.getProperty("resourceCount")?.toIntOrNull() ?: return null,
            totalBytes = properties.getProperty("totalBytes")?.toLongOrNull() ?: return null,
            syncedAtEpochMillis = properties.getProperty("syncedAtEpochMillis")?.toLongOrNull()
                ?: return null,
            translationLanguage = translationLanguage,
            translationResourcePaths = translationResourcePaths,
        )
        val expectedFiles = runCatching {
            (requiredResourcePaths + translationResourcePaths).map { relativePath ->
                resolveSafe(relativePath)
            }
        }.getOrNull() ?: return null
        if (snapshot.resourceCount < expectedFiles.size || snapshot.totalBytes <= 0L) return null
        if (expectedFiles.any { file -> !file.hasUsableResourceContent() }) return null
        if (snapshot.totalBytes < expectedFiles.sumOf(File::length)) return null
        return snapshot
    }

    fun File.hasUsableResourceContent(): Boolean {
        if (!isFile || length() <= 0L) return false
        val prefix = runCatching {
            inputStream().use { input ->
                val buffer = ByteArray(HtmlProbeByteCount)
                val byteCount = input.read(buffer)
                if (byteCount <= 0) return false
                String(buffer, 0, byteCount, Charsets.UTF_8)
            }
        }.getOrNull() ?: return false
        val normalizedPrefix = prefix
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        return !normalizedPrefix.startsWith("<!doctype html") &&
            !normalizedPrefix.startsWith("<html") &&
            !normalizedPrefix.startsWith("<head")
    }

    private fun String.normalizedRequestedVersion(): String {
        require(isNotBlank()) { "BC version is required for local resources." }
        return normalizedBcVersion()
    }

    private const val HtmlProbeByteCount = 512
}
