package dev.vivian.mobilebc.portrait

import java.net.URI
import org.json.JSONObject

internal const val EchoGithubRepository = "SugarChain-Studio/echo-clothing-ext"
private const val GithubApi = "https://api.github.com/repos/$EchoGithubRepository"

/** Resolve once, then all subsequent reads use that immutable commit and its Git tree. */
internal fun updateEchoFromGithub(
    selected: EchoDataPack,
    store: EchoPackStore,
    bundledText: String,
    sourceIndex: String,
    download: (String, Int) -> ByteArray,
): String? {
    fun json(url: String, limit: Int): JSONObject {
        val bytes = download(url, limit)
        require(bytes.size <= limit)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    val commit = json("$GithubApi/commits/main", 256 * 1024)
    val revision = commit.getString("sha")
    require(revision.matches(Regex("[a-f0-9]{40}")))
    if (revision == selected.revision) return null
    val comparison = json("$GithubApi/compare/${selected.revision}...$revision", 4 * 1024 * 1024)
    require(comparison.getString("status") == "ahead" && comparison.getInt("behind_by") == 0) {
        "Echo upstream is not a forward update"
    }
    val tree = json("$GithubApi/git/trees/$revision?recursive=1", 12 * 1024 * 1024)
    require(!tree.getBoolean("truncated")) { "Incomplete Echo tree" }
    require(tree.getString("sha") == commit.getJSONObject("commit").getJSONObject("tree").getString("sha"))
    val entries = tree.getJSONArray("tree")
    val hashes = linkedMapOf<String, String>()
    val blobs = mutableSetOf<String>()
    for (i in 0 until entries.length()) {
        val entry = entries.getJSONObject(i)
        val path = entry.getString("path")
        if (entry.getString("type") in setOf("blob", "commit")) {
            require(safeSourcePath(path))
            val hash = entry.getString("sha")
            require(hash.matches(Regex("[a-f0-9]{40}")) && hashes.put(path, hash) == null)
            if (entry.getString("type") == "blob") blobs += path
        }
    }
    var files = 0
    var bytesRead = 0
    val compiler = EchoGithubCompiler(bundledText, sourceIndex)
    val sequence = Math.addExact(selected.sequence, 1)
    val text = compiler.compile(revision, sequence, hashes, blobs) { path ->
        require(++files <= 128) { "Too many changed Echo source files" }
        val url = URI("https", "raw.githubusercontent.com", "/$EchoGithubRepository/$revision/$path", null).toASCIIString()
        val bytes = download(url, 512 * 1024)
        bytesRead += bytes.size
        require(bytes.size <= 512 * 1024 && bytesRead <= 16 * 1024 * 1024)
        require(portraitGitBlobHash(bytes) == hashes[path]) { "Echo source hash mismatch: $path" }
        bytes.toString(Charsets.UTF_8)
    }
    store.install(text, sequence, selected)
    return JSONObject(text).getJSONObject("extractionReport").toString()
}

internal fun safeSourcePath(path: String): Boolean = path.isNotBlank() &&
    path.none { it == '\\' || it == '%' || it == '?' || it == '#' || it.isISOControl() } &&
    path.split('/').none { it.isEmpty() || it == "." || it == ".." }
