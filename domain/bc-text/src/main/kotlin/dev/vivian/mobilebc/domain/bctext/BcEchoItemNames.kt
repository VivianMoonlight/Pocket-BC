package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.model.BcInteractionItem
import org.json.JSONObject

/** Name data extracted from the same pinned sources as the supported Echo portrait catalog. */
internal class BcEchoItemNames(json: String? = null) {
    private val data = json?.let(::JSONObject) ?: bundled

    fun groups(language: String): Map<String, String> = buildMap {
        val groups = data.getJSONArray("groups")
        for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            val name = group.getString("group")
            put(name, group.translation(language) ?: name.toHumanLabel())
        }
    }

    fun items(native: List<BcInteractionItem>, language: String): List<BcInteractionItem> {
        val items = native.associateByTo(linkedMapOf()) { it.group to it.name }
        val assets = data.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val group = asset.getString("group")
            val name = asset.getString("name")
            items[group to name] = BcInteractionItem(group, name, asset.translation(language) ?: name.toHumanLabel())
        }
        val aliases = data.getJSONArray("aliases")
        for (index in 0 until aliases.length()) {
            val alias = aliases.getJSONObject(index)
            val group = alias.getString("group")
            val oldName = alias.getString("oldName")
            items[group to alias.getString("name")]?.let { items[group to oldName] = it.copy(name = oldName) }
        }
        val mirrors = data.getJSONArray("mirrors")
        for (index in 0 until mirrors.length()) {
            val mirror = mirrors.getJSONObject(index)
            val group = mirror.getString("group")
            items.values.filter { it.group == mirror.getString("from") }.forEach {
                items.putIfAbsent(group to it.name, it.copy(group = group))
            }
        }
        return items.values.toList()
    }

    private fun JSONObject.translation(language: String): String? = optJSONObject("translation")?.let {
        it.optString(language).ifBlank { it.optString("EN") }.ifBlank { it.optString("CN") }.takeIf(String::isNotBlank)
    }

    private companion object {
        val bundled by lazy {
            JSONObject(checkNotNull(BcEchoItemNames::class.java.getResourceAsStream("/echo/names.json"))
                .bufferedReader().use { it.readText() })
        }
    }
}
