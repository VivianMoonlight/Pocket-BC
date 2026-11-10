package dev.vivian.mobilebc.resource

import android.content.Context
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import java.io.File
import java.io.IOException
import java.util.Properties

class AndroidBcWebResourceStore internal constructor(
    private val webRoot: File,
) : BcWebResourceStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "bc-web"),
    )

    override fun localRootPath(version: String): String =
        AndroidBcWebResourceFiles.root(webRoot, version).absolutePath

    override suspend fun prepare(version: String) {
        synchronized(AndroidBcWebResourceFiles.accessLock) {
            AndroidBcWebResourceFiles.recoverInterruptedCommit(webRoot, version)
            val stagingRoot = AndroidBcWebResourceFiles.stagingRoot(webRoot, version)
            if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
                throw IOException("Unable to clear the BC web resource staging directory.")
            }
            if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) {
                throw IOException("Unable to prepare the BC web resource staging directory.")
            }
        }
    }

    override suspend fun write(
        version: String,
        relativePath: String,
        resource: BcFetchedResource,
    ): BcStoredResource {
        val output = AndroidBcWebResourceFiles
            .stagingRoot(webRoot, version)
            .let { stagingRoot -> AndroidBcWebResourceFiles.run { stagingRoot.resolveSafe(relativePath) } }
        output.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IOException(
                    "Unable to create a BC web resource staging directory for $relativePath.",
                )
            }
        }
        output.writeBytes(resource.bytes)
        return BcStoredResource(
            relativePath = relativePath,
            sourceUrl = resource.finalUrl,
            contentType = resource.contentType,
            byteCount = resource.bytes.size.toLong(),
        )
    }

    override suspend fun commit(version: String, snapshot: BcWebResourceSnapshot) {
        synchronized(AndroidBcWebResourceFiles.accessLock) {
            AndroidBcWebResourceFiles.recoverInterruptedCommit(webRoot, version)
            val root = AndroidBcWebResourceFiles.root(webRoot, version)
            val stagingRoot = AndroidBcWebResourceFiles.stagingRoot(webRoot, version)
            val backupRoot = AndroidBcWebResourceFiles.backupRoot(webRoot, version)
            val normalizedVersion = version.normalizedRequestedVersion()
            require(snapshot.version.normalizedRequestedVersion() == normalizedVersion) {
                "Refusing to publish BC ${snapshot.version} resources into the $normalizedVersion cache."
            }
            require(snapshot.resourceCount > 0 && snapshot.totalBytes > 0L) {
                "Refusing to publish an empty BC web resource snapshot."
            }
            val expectedTranslationPaths =
                bcTranslationResourcePaths(snapshot.translationLanguage)
            require(snapshot.translationResourcePaths == expectedTranslationPaths) {
                "Refusing to publish an incomplete ${snapshot.translationLanguage ?: "English"} translation manifest."
            }
            val invalidRequiredPaths =
                (AndroidBcWebResourceFiles.requiredResourcePaths + snapshot.translationResourcePaths)
                    .filterNot { relativePath ->
                        AndroidBcWebResourceFiles.run {
                            stagingRoot.resolveSafe(relativePath).hasUsableResourceContent()
                        }
                    }
            require(invalidRequiredPaths.isEmpty()) {
                "Refusing to publish BC web resources with missing or invalid files: ${invalidRequiredPaths.joinToString()}"
            }
            stagingRoot.writeSnapshot(snapshot)
            if (backupRoot.exists() && !backupRoot.deleteRecursively()) {
                throw IOException("Unable to clear the previous BC web resource backup.")
            }
            if (root.exists() && !root.renameTo(backupRoot)) {
                throw IOException("Unable to move old BC web resources to backup.")
            }
            if (!stagingRoot.renameTo(root)) {
                if (backupRoot.exists() && !backupRoot.renameTo(root)) {
                    throw IOException(
                        "Unable to publish BC web resources or restore the previous cache.",
                    )
                }
                throw IOException("Unable to publish downloaded BC web resources.")
            }
            val published = AndroidBcWebResourceFiles.readSnapshot(webRoot, normalizedVersion)
            if (published == null) {
                if (root.exists() && !root.deleteRecursively()) {
                    throw IOException(
                        "Published BC web resources failed validation and could not be removed.",
                    )
                }
                if (backupRoot.exists() && !backupRoot.renameTo(root)) {
                    throw IOException(
                        "Published BC web resources failed validation and the previous cache could not be restored.",
                    )
                }
                throw IOException("Published BC web resources failed validation.")
            }
            // Publication is already complete. Keep serving the valid live root even if obsolete
            // backup cleanup has to be retried by a later prepare/read.
            if (backupRoot.exists()) backupRoot.deleteRecursively()
        }
    }

    override fun currentSnapshot(version: String): BcWebResourceSnapshot? =
        synchronized(AndroidBcWebResourceFiles.accessLock) {
            if (runCatching {
                    AndroidBcWebResourceFiles.recoverInterruptedCommit(webRoot, version)
                }.isFailure
            ) {
                return@synchronized null
            }
            AndroidBcWebResourceFiles.readSnapshot(webRoot, version)
        }

    private fun File.writeSnapshot(snapshot: BcWebResourceSnapshot) {
        val properties = Properties().apply {
            setProperty("version", snapshot.version)
            setProperty("baseUrl", snapshot.baseUrl)
            setProperty("entryFilePath", snapshot.entryFilePath)
            setProperty("resourceCount", snapshot.resourceCount.toString())
            setProperty("totalBytes", snapshot.totalBytes.toString())
            setProperty("syncedAtEpochMillis", snapshot.syncedAtEpochMillis.toString())
            snapshot.translationLanguage?.let { language ->
                setProperty("translationLanguage", language)
            }
            if (snapshot.translationResourcePaths.isNotEmpty()) {
                setProperty(
                    AndroidBcWebResourceFiles.TranslationResourcePathsProperty,
                    snapshot.translationResourcePaths.joinToString("\n"),
                )
            }
        }
        File(this, AndroidBcWebResourceFiles.ManifestName).outputStream().use { output ->
            properties.store(output, "mobile-bc web resource snapshot")
        }
    }

    private fun String.normalizedRequestedVersion(): String {
        require(isNotBlank()) { "BC version is required for local resources." }
        return normalizedBcVersion()
    }
}
