package dev.vivian.mobilebc.portrait

import org.json.JSONArray
import org.json.JSONObject

/** A data-only snapshot. Executable adapters and their bindings remain owned by the APK. */
internal data class EchoDataPack(
    val sequence: Long,
    val revision: String,
    val catalog: String,
    val supplement: String,
    val names: String,
    val imageHashes: Map<String, String>,
) {
    companion object {
        const val MaxBytes = 12 * 1024 * 1024

        fun decode(text: String, bundledSupplement: String): EchoDataPack {
            require(text.toByteArray().size <= MaxBytes) { "Echo pack too large" }
            val root = JSONObject(text)
            checkData(root)
            require(root.getInt("format") == 1 && root.getString("bcVersion") == PortraitVersion)
            require(root.getString("capability") == "portrait-data-v1")
            val sequence = root.getLong("sequence").also { require(it > 0) }
            val catalog = root.getJSONObject("catalog")
            val revision = catalog.getString("revision").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
            val supplement = root.getJSONObject("supplement")
            val bundled = JSONObject(bundledSupplement)
            require(supplement.getString("echoRevision") == revision)
            // Updating source code or binding a different asset needs an APK adaptation review.
            val comparable = JSONObject(supplement.toString()).put("echoRevision", bundled.getString("echoRevision"))
            require(sameJson(comparable, bundled)) { "Echo behavior requires a client update" }
            require(catalog.getInt("supplementVersion") == bundled.getInt("version"))
            val names = root.getJSONObject("names")
            require(names.getString("revision") == revision)
            for (key in listOf("groups", "assets", "aliases", "mirrors")) names.getJSONArray(key)
            require(sameJson(names.getJSONArray("aliases"), catalog.getJSONArray("aliases")))
            for (kind in listOf("groups", "assets")) {
                val entries = names.getJSONArray(kind)
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    require(entry.getString("group").isNotBlank())
                    if (kind == "assets") require(entry.getString("name").isNotBlank())
                    entry.optJSONObject("translation")?.let { translations ->
                        translations.keys().forEach { translations.getString(it) }
                    }
                }
            }
            val manifest = root.getJSONObject("images")
            require(manifest.getString("revision") == revision)
            val hashes = manifest.getJSONObject("images")
            val images = hashes.keys().asSequence().associateWith { path ->
                require(validImagePath(path))
                hashes.getString(path).also { require(it.matches(Regex("[a-f0-9]{40}"))) }
            }
            val listed = catalog.getJSONArray("images").strings()
            require(listed.size == images.size && listed.toSet() == images.keys)
            for (key in listOf("groups", "mirrors", "aliases", "sources")) catalog.getJSONArray(key)
            val groups = catalog.getJSONArray("groups")
            val groupNames = mutableSetOf<String>()
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                require(groupNames.add(group.getString("Group")))
                group.getJSONArray("Asset")
            }
            val mirrors = catalog.getJSONArray("mirrors")
            val nameMirrors = names.getJSONArray("mirrors")
            require(mirrors.length() == nameMirrors.length())
            for (i in 0 until mirrors.length()) {
                val mirror = mirrors.getJSONObject(i)
                require(groupNames.add(mirror.getString("group")))
                mirror.getJSONObject("overrides")
                require(mirror.getString("group") == nameMirrors.getJSONObject(i).getString("group"))
                require(mirror.getString("from") == nameMirrors.getJSONObject(i).getString("from"))
            }
            val aliases = catalog.getJSONArray("aliases")
            for (i in 0 until aliases.length()) {
                for (key in listOf("group", "oldName", "name")) require(aliases.getJSONObject(i).getString(key).isNotBlank())
            }
            val mappings = catalog.getJSONObject("imageMappings")
            mappings.keys().forEach { require(validImagePath(it) && validImagePath(mappings.getString(it))) }
            val adapters = bundled.getJSONArray("adapters")
            val sources = catalog.getJSONArray("sources").strings().toSet()
            val bindings = buildMap {
                for (i in 0 until adapters.length()) {
                    val entry = adapters.getJSONObject(i)
                    if (entry.getString("source") == "echo") {
                        require(entry.getString("path") in sources)
                        for (group in entry.getJSONArray("groups").strings()) {
                            put(group to entry.getString("name"), entry.getString("id"))
                        }
                    }
                }
            }
            val seen = mutableSetOf<Pair<String, String>>()
            val records = catalog.getJSONArray("assets")
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val definition = record.getJSONObject("definition")
                val key = record.getString("group") to definition.getString("Name")
                require(seen.add(key)) { "Duplicate Echo asset" }
                val adapter = record.optString("portraitAdapter").ifEmpty { null }
                require(adapter == bindings[key]) { "Unsupported Echo adapter binding" }
                if (adapter != null || definition.optBoolean("Extended")) record.getJSONObject("extended")
                record.optJSONObject("extended")?.let { extended ->
                    requireEchoExtension(extended, "Echo extension requires a client update")
                }
            }
            require(seen.containsAll(bindings.keys))
            root.getString("license").also { require(it.isNotBlank()) }
            root.getString("licenseAppendix").also { require(it.isNotBlank()) }
            return EchoDataPack(sequence, revision, catalog.toString(), supplement.toString(), names.toString(), images)
        }

        private fun checkData(value: Any?, depth: Int = 0) {
            require(depth <= 64) { "Echo data nesting too deep" }
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    require(isEchoDataKey(key)) { "Unsupported Echo behavior: $key" }
                    checkData(value.get(key), depth + 1)
                }
                is JSONArray -> for (i in 0 until value.length()) checkData(value.get(i), depth + 1)
            }
        }
    }
}

internal fun validImagePath(path: String): Boolean = (path.startsWith("Assets/Female3DCG/") || path.startsWith("Icons/")) &&
    path.endsWith(".png") && path.none { it == '%' || it == '\\' || it == '?' || it == '#' || it.isISOControl() } &&
    path.split('/').none { it.isEmpty() || it == "." || it == ".." }

internal fun sameJson(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { sameJson(left.get(it), right.get(it)) }
    }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
    else -> left == right
}
