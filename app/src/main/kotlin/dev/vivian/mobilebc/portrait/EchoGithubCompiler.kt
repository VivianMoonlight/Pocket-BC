package dev.vivian.mobilebc.portrait

import org.json.JSONArray
import org.json.JSONObject

/** Rebuild from the reviewed baseline, so removed or unsupported sources leave no stale items. */
internal class EchoGithubCompiler(bundledText: String, sourceIndex: String) {
    private val bundled = JSONObject(bundledText)
    private val index = JSONObject(sourceIndex)

    fun compile(
        revision: String,
        sequence: Long,
        hashes: Map<String, String>,
        blobs: Set<String>,
        read: (String) -> String,
    ): String {
        require(index.getInt("format") == 1 && index.getString("revision") == bundled.getJSONObject("catalog").getString("revision"))
        val dependencies = index.getJSONObject("dependencies")
        dependencies.keys().forEach { path ->
            require(hashes[path] == dependencies.getString(path)) { "Echo dependency or license requires client review: $path" }
        }
        val required = index.getJSONArray("required").strings().toSet()
        require(required.all { it in blobs }) { "Required Echo source was removed" }

        val omitted = JSONObject()
        val components = readComponents(hashes, blobs, required, omitted, read)
        val groups = components.values.flatMap { it.getJSONArray("groups").objects() }
        val nativeAssets = index.getJSONObject("nativeAssets").let { assets ->
            assets.keys().asSequence().associateWith { assets.getJSONArray(it).strings().toSet() }
        }
        val mirrors = components.values.flatMap { it.getJSONArray("mirrors").objects() }
        val knownGroups = nativeAssets.keys + groups.map { it.getString("Group") } + mirrors.map { it.getString("group") }
        val imageGroups = index.getJSONObject("imageGroups").let { mappings ->
            mappings.keys().asSequence().associateWith { mappings.getString(it) }.toMutableMap()
        }
        groups.forEach { group ->
            val name = group.getString("Group")
            imageGroups[name] = group.optString("DynamicGroupName", name)
        }
        mirrors.forEach { mirror ->
            imageGroups[mirror.getString("group")] = imageGroups[mirror.getString("from")] ?: mirror.getString("from")
        }

        // Resolve each asset's images once; reuse the result for existence checks and the allowlist.
        val imageFiles = blobs.filter {
            it.startsWith("resources/") && validImagePath(it.removePrefix("resources/")) && !it.contains("/Preview/")
        }
        val imageFilesByGroup = imageFiles.filter { it.startsWith("resources/Assets/Female3DCG/") }.groupBy {
            it.removePrefix("resources/Assets/Female3DCG/").substringBefore('/')
        }
        val imagesByAsset = mutableMapOf<Pair<String, String>, List<String>>()
        fun imagesFor(asset: Pair<String, String>): List<String> = imagesByAsset.getOrPut(asset) {
            imageFilesByGroup[asset.first].orEmpty().filter { path ->
                val name = path.substringAfterLast('/').removeSuffix(".png")
                asset.second.isEmpty() || name == asset.second || name.startsWith(asset.second + "_")
            }
        }

        val catalog = JSONObject().put("revision", revision)
            .put("supplementVersion", bundled.getJSONObject("catalog").getInt("supplementVersion"))
        listOf("groups", "mirrors", "assets", "aliases", "sources").forEach { catalog.put(it, JSONArray()) }
        catalog.put("imageMappings", JSONObject())
        val names = JSONObject().put("revision", revision).put("groups", JSONArray()).put("assets", JSONArray())
        val selectedImages = imageFiles.filterTo(mutableSetOf()) { it.startsWith("resources/Assets/Female3DCG/LuziArmMask/") }
        nativeAssets.forEach { (group, assets) ->
            assets.forEach { name -> selectedImages.addAll(imagesFor((imageGroups[group] ?: group) to name)) }
        }

        for ((path, data) in components) {
            var records = data.getJSONArray("assets").objects()
            val conflicting = records.any { record ->
                val group = record.getString("group")
                val original = mirrors.find { it.getString("group") == group }?.getString("from") ?: group
                group !in knownGroups || record.getJSONObject("definition").getString("Name") in nativeAssets[original].orEmpty()
            }
            if (conflicting) {
                require(path !in required) { "Required Echo assets conflict with native catalog: $path" }
                omitted.put(path, "Unknown group or native asset collision")
                continue
            }
            val mappings = data.getJSONObject("imageMappings")
            val ownedImages = records.map { record ->
                val definition = record.getJSONObject("definition")
                val group = record.getString("group")
                val imageGroup = definition.optString("DynamicGroupName", imageGroups[group] ?: group)
                val name = definition.getString("Name")
                val mapped = mappings.keys().asSequence().filter {
                    it.startsWith("Assets/Female3DCG/$imageGroup/") &&
                        (name.isEmpty() || it.substringAfterLast('/').removeSuffix(".png") == name || it.substringAfterLast('/').startsWith(name + "_"))
                }.map { "resources/" + mappings.getString(it) }.filter { it in imageFiles }.toList()
                imagesFor(imageGroup to name) + mapped
            }
            val missingRecords = records.indices.filter {
                val record = records[it]
                val definition = record.getJSONObject("definition")
                val layers = definition.optJSONArray("Layer")?.objects().orEmpty()
                val imageFree = layers.isNotEmpty() && layers.all { layer -> !layer.optBoolean("HasImage", true) }
                definition.optBoolean("Visible", true) && !imageFree && ownedImages[it].isEmpty()
            }.map { records[it].getString("group") to records[it].getJSONObject("definition").getString("Name") }.toSet()
            if (missingRecords.isNotEmpty()) {
                require(path !in required) { "Required Echo images missing: $path" }
                omitted.put(path, "No matching images in pinned tree: " + missingRecords.joinToString { "${it.first}/${it.second}" })
                records = records.filter { (it.getString("group") to it.getJSONObject("definition").getString("Name")) !in missingRecords }
                if (records.isEmpty()) continue
            }
            ownedImages.forEach(selectedImages::addAll)
            data.getJSONArray("groups").objects().forEach { group ->
                group.getJSONArray("Asset").objects().forEach { asset ->
                    selectedImages.addAll(imagesFor(group.getString("Group") to asset.getString("Name")))
                }
            }
            for (key in listOf("groups", "mirrors")) catalog.getJSONArray(key).append(data.getJSONArray(key))
            catalog.getJSONArray("assets").append(JSONArray(records))
            catalog.getJSONArray("aliases").append(JSONArray(data.getJSONArray("aliases").objects().filter {
                (it.getString("group") to it.getString("name")) !in missingRecords
            }))
            names.getJSONArray("groups").append(data.getJSONObject("names").getJSONArray("groups"))
            names.getJSONArray("assets").append(JSONArray(data.getJSONObject("names").getJSONArray("assets").objects().filter {
                (it.getString("group") to it.getString("name")) !in missingRecords
            }))
            mappings.keys().forEach {
                catalog.getJSONObject("imageMappings").put(it, mappings.get(it))
                val target = "resources/" + mappings.getString(it)
                require(!target.startsWith("resources/Icons/") || target in imageFiles) { "Mapped Echo image missing: $target" }
                if (target in imageFiles) selectedImages.add(target)
            }
            catalog.getJSONArray("sources").put(path)
        }
        val images = JSONObject()
        selectedImages.sorted().forEach { images.put(it.removePrefix("resources/"), hashes.getValue(it)) }
        return buildPack(sequence, revision, catalog, names, images, omitted)
    }

    private fun readComponents(
        hashes: Map<String, String>,
        blobs: Set<String>,
        required: Set<String>,
        omitted: JSONObject,
        read: (String) -> String,
    ): Map<String, JSONObject> {
        val baseline = index.getJSONObject("components")
        val known = baseline.keys().asSequence().toSet()
        val excluded = index.getJSONArray("excludedNames").strings().toSet()
        val roots = index.getJSONArray("assetSourceRoots").strings()
        val excludedPrefixes = index.getJSONArray("excludedSourcePrefixes").strings()
        val candidates = blobs.filter { path ->
            path in known || (roots.any(path::startsWith) && path.endsWith(".js") &&
                excludedPrefixes.none(path::startsWith) && path.substringAfterLast('/').removeSuffix(".js") !in excluded)
        }.sorted()
        val parsed = linkedMapOf<String, JSONObject>()
        for (path in candidates) {
            val previous = baseline.optJSONObject(path)
            if (previous != null && hashes[path] == previous.getString("sha")) {
                val data = previous.optJSONObject("data")
                if (data != null) parsed[path] = data else omitted.put(path, previous.getString("reason"))
                continue
            }
            require(path !in required) { "Required Echo definition changed: $path" }
            val source = read(path) // Transport/integrity failures must not become component omissions.
            try {
                parsed[path] = EchoStaticSource(source).extract()
            } catch (error: IllegalArgumentException) {
                omitted.put(path, error.message ?: "Unsupported static definition")
            } catch (error: IllegalStateException) {
                omitted.put(path, error.message ?: "Unsupported static definition")
            } catch (_: ClassCastException) {
                omitted.put(path, "Unsupported static value type")
            } catch (_: org.json.JSONException) {
                omitted.put(path, "Incomplete static definition")
            }
        }
        return parsed
    }

    private fun buildPack(
        sequence: Long,
        revision: String,
        catalog: JSONObject,
        names: JSONObject,
        images: JSONObject,
        omitted: JSONObject,
    ): String {
        catalog.put("images", JSONArray(images.keys().asSequence().toList()))
        names.put("aliases", catalog.getJSONArray("aliases"))
        names.put("mirrors", JSONArray(catalog.getJSONArray("mirrors").objects().map {
            JSONObject().put("group", it.getString("group")).put("from", it.getString("from"))
        }))
        val result = JSONObject(bundled.toString()).put("sequence", sequence).put("catalog", catalog).put("names", names)
            .put("images", JSONObject().put("revision", revision).put("images", images))
            .put("extractionReport", JSONObject().put("omitted", omitted).put("source", EchoGithubRepository))
        result.getJSONObject("supplement").put("echoRevision", revision)
        val text = result.toString()
        EchoDataPack.decode(text, bundled.getJSONObject("supplement").toString())
        return text
    }
}
