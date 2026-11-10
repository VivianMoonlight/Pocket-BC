package dev.vivian.mobilebc.portrait

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal const val PortraitOrigin = "https://portrait.mobilebc.invalid"
internal const val PortraitEntryUrl = "$PortraitOrigin/portrait/index.html"
internal const val PortraitVersion = "R133"
private const val ResourcePrefix = "/R133/BondageClub/"
internal const val EchoPortraitRevision = "4478e33044a6d87a4d1b0c6ed8d38a549e1d4204"

/** Chinese filenames are decoded once and matched against the packaged exact-file allowlist. */
internal fun echoPortraitResourcePath(url: String, images: Set<String>, revision: String = EchoPortraitRevision): String? {
    val echoResourcePrefix = "/echo/$revision/"
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "portrait.mobilebc.invalid" || uri.port != -1 ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        !uri.path.startsWith(echoResourcePrefix)
    ) return null
    val path = uri.path.removePrefix(echoResourcePrefix)
    return path.takeIf { validImagePath(it) && it in images }
}

internal fun portraitResourcePath(url: String, scripts: Set<String>): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "portrait.mobilebc.invalid" || uri.port != -1 ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.rawPath != uri.path || !uri.path.startsWith(ResourcePrefix)
    ) return null
    val path = uri.path.removePrefix(ResourcePrefix)
    if (path.split('/').any { it == ".." || it == "." || it.isEmpty() } || '\\' in path) return null
    return path.takeIf {
        it in scripts || (it.startsWith("Assets/Female3DCG/") && it.endsWith(".png")) ||
            it == "Assets/Female3DCG.csv" || it == "Assets/AssetStrings.csv"
    }
}

/** This host serves only packaged renderer files and approved, versioned BC resources. */
internal class BcPortraitResources(context: Context, private val echoData: () -> EchoDataPack) {
    private val assets = context.applicationContext.assets
    private val scriptHashes = JSONArray(assets.open("portrait/scripts.json").bufferedReader().use { it.readText() })
        .let { entries ->
            (0 until entries.length()).associate { index ->
                entries.getJSONObject(index).let { it.getString("path") to it.getString("sha256") }
            }
        }
    private val echo by lazy(echoData)
    private val resourceCache = PortraitResourceCache(
        File(context.filesDir, "portrait-resources-v1"), legacyDirectory = File(context.cacheDir, "portrait-resources-v1"),
    )
    val renderingVersion: String by lazy {
        // Every executable/rendering input participates, including local mask PNGs.
        fun hashes(path: String): List<String> = assets.list(path).orEmpty().sorted().flatMap { name ->
            val file = "$path/$name"
            if (file == "portrait/echo") emptyList()
            else if (assets.list(file).orEmpty().isNotEmpty()) hashes(file)
            else if (file.substringAfterLast('.') !in setOf("js", "json", "png", "html", "css")) emptyList()
            else listOf("$file:${assets.open(file).use { portraitSha256(it.readBytes()) }}")
        }
        portraitSha256((hashes("portrait") + listOf(
            PortraitVersion, echo.sequence.toString(), echo.revision, echo.catalog, echo.supplement,
            echo.imageHashes.toSortedMap().toString(),
        )).joinToString("\n").toByteArray())
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun intercept(url: String, method: String): WebResourceResponse {
        if (method != "GET") return rejected()
        val data = when (url) {
            "$PortraitOrigin/portrait/echo/catalog.json" -> echo.catalog
            "$PortraitOrigin/portrait/supplement/manifest.json" -> echo.supplement
            else -> null
        }
        if (data != null) return WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(data.toByteArray()))
        val local = when (url) {
            PortraitEntryUrl -> "portrait/index.html"
            "$PortraitOrigin/portrait/renderer.js" -> "portrait/renderer.js"
            "$PortraitOrigin/portrait/dump.js" -> "portrait/dump.js"
            "$PortraitOrigin/portrait/supplement/drawing.js" -> "portrait/supplement/drawing.js"
            "$PortraitOrigin/portrait/supplement/body-drawing.js" -> "portrait/supplement/body-drawing.js"
            "$PortraitOrigin/portrait/supplement/gl.js" -> "portrait/supplement/gl.js"
            "$PortraitOrigin/portrait/supplement/stocking-masks.js" -> "portrait/supplement/stocking-masks.js"
            "$PortraitOrigin/portrait/supplement/half-black.png" -> "portrait/supplement/half-black.png"
            "$PortraitOrigin/portrait/scripts.json" -> "portrait/scripts.json"
            "$PortraitOrigin/portrait/echo.js" -> "portrait/echo.js"
            "$PortraitOrigin/portrait/supplement/adapters.js" -> "portrait/supplement/adapters.js"
            else -> null
        }
        if (local != null) return WebResourceResponse(mimeType(local), "UTF-8", assets.open(local))
        val echoPath = echoPortraitResourcePath(url, echo.imageHashes.keys, echo.revision)
        val path = echoPath ?: portraitResourcePath(url, scriptHashes.keys) ?: return rejected()
        val bytes = if (echoPath != null) {
            val hash = echo.imageHashes.getValue(path)
            resourceCache.get("echo-blob:$hash", { portraitGitBlobHash(it) == hash }) {
                fetchEchoPortraitImage(path, echo.revision, hash, ::download)
            }
        } else {
            resourceCache.get("$PortraitVersion/$path:${scriptHashes[path].orEmpty()}", { bytes ->
                scriptHashes[path]?.let { portraitSha256(bytes) == it } ?: true
            }) {
                download("https://www.bondageprojects.elementfx.com$ResourcePrefix$path")
            }
        } ?: return rejected()
        return WebResourceResponse(mimeType(path), if (path.endsWith(".png")) null else "UTF-8", ByteArrayInputStream(bytes))
    }

    private fun download(url: String): ByteArray? {
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body() ?: return null
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val input = body.byteStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun rejected() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    private fun mimeType(path: String) = when {
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".html") -> "text/html"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        else -> "text/plain"
    }
}

internal fun portraitSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Git tree hashes cover the blob header as well as the exact downloaded image bytes. */
internal fun portraitGitBlobHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
    update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
    digest(bytes).joinToString("") { "%02x".format(it) }
}

/** All transports serve the same pinned blob; an invalid response never enters the renderer. */
internal fun fetchEchoPortraitImage(
    path: String,
    revision: String,
    hash: String,
    download: (String) -> ByteArray?,
): ByteArray? {
    val urls = listOf(
        "https://cdn.jsdelivr.net/gh/$EchoGithubRepository@$revision/resources/$path",
        "https://raw.githubusercontent.com/$EchoGithubRepository/$revision/resources/$path",
        "https://github.com/$EchoGithubRepository/raw/$revision/resources/$path",
        "https://gcore.jsdelivr.net/gh/$EchoGithubRepository@$revision/resources/$path",
    )
    for (url in urls) {
        val bytes = download(url) ?: continue
        if (bytes.size <= 8 * 1024 * 1024 && portraitGitBlobHash(bytes) == hash) return bytes
    }
    return null
}
