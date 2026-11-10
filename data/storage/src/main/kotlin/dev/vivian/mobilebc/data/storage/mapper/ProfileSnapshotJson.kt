package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcProfilePortrait
import dev.vivian.mobilebc.core.model.BcProfileRelation
import org.json.JSONArray
import org.json.JSONObject

internal fun BcProfileRelation.toProfileJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("memberNumber", memberNumber)
    .put("stage", stage)
    .put("startEpochMillis", startEpochMillis)

private fun JSONObject.relation() = BcProfileRelation(
    name = getString("name"),
    stage = if (isNull("stage")) null else getInt("stage"),
    startEpochMillis = if (isNull("startEpochMillis")) null else getLong("startEpochMillis"),
    memberNumber = if (isNull("memberNumber")) null else getInt("memberNumber").takeIf { it > 0 },
)

internal fun decodeProfileOwner(raw: String?): BcProfileRelation? =
    raw?.let { runCatching { JSONObject(it).relation() }.getOrNull() }

internal fun decodeProfileLovers(raw: String?): List<BcProfileRelation>? = raw?.let {
    runCatching {
        val array = JSONArray(it)
        List(array.length()) { index -> array.getJSONObject(index).relation() }
    }.getOrNull()
}

internal fun BcProfilePortrait.toProfileJson(): JSONObject = JSONObject()
    .put("version", version)
    .put("assetFamily", assetFamily)
    .put("updatedAtMillis", updatedAtMillis)
    .put("activePose", JSONArray(activePose))
    .put("appearance", JSONArray(appearance.map { item ->
        JSONObject()
            .put("group", item.group)
            .put("name", item.name)
            .put("colorJson", item.colorJson)
            .put("propertyJson", item.propertyJson)
    }))

internal fun decodeProfilePortrait(raw: String?): BcProfilePortrait? = raw?.let {
    runCatching {
        val obj = JSONObject(it)
        val poses = obj.getJSONArray("activePose")
        val items = obj.getJSONArray("appearance")
        BcProfilePortrait(
            version = obj.getString("version"),
            assetFamily = obj.getString("assetFamily"),
            activePose = List(poses.length()) { index -> poses.getString(index) },
            appearance = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                BcCharacterItem(
                    group = item.getString("group"),
                    name = item.getString("name"),
                    colorJson = if (item.isNull("colorJson")) null else item.getString("colorJson"),
                    propertyJson = if (item.isNull("propertyJson")) null else item.getString("propertyJson"),
                )
            },
            updatedAtMillis = obj.getLong("updatedAtMillis"),
        )
    }.getOrNull()
}
