package dev.vivian.mobilebc.portrait

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately excludes raw account data, credentials, inventory and shared settings. */
internal fun portraitPayload(character: BcCharacterSnapshot): String = JSONObject().apply {
    put("memberNumber", character.memberNumber)
    put("assetFamily", character.assetFamily)
    put("activePose", JSONArray(character.activePose))
    put("appearance", JSONArray().apply {
        character.appearance.forEach { item ->
            put(JSONObject().apply {
                put("group", item.group)
                put("name", item.name)
                put("color", item.colorJson?.let { JSONArray("[$it]").get(0) })
                put("property", item.propertyJson?.let(::JSONObject) ?: JSONObject())
            })
        }
    })
}.toString()
