package dev.vivian.mobilebc.portrait

import org.json.JSONArray
import org.json.JSONObject

internal val EchoPrototypeKeys = setOf("__proto__", "prototype", "constructor")
private val EchoBehaviorKeys = setOf("ScriptHooks", "ScriptDraw", "BeforeDraw", "AfterDraw", "CopyConfig")

/** Both source extraction and package loading enforce the same data-only capability. */
internal fun isEchoDataKey(key: String): Boolean =
    key !in EchoPrototypeKeys && key !in EchoBehaviorKeys &&
        (!key.startsWith("Dynamic") || key == "DynamicGroupName")

internal fun requireEchoExtension(extended: JSONObject, unsupportedMessage: String) {
    when (extended.getString("Archetype")) {
        "typed" -> extended.getJSONArray("Options")
        "modular" -> extended.getJSONArray("Modules")
        "text", "noarch" -> Unit
        "vibrating" -> require(extended.keys().asSequence().all { it == "Archetype" }) { unsupportedMessage }
        else -> error(unsupportedMessage)
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
internal fun JSONArray.append(other: JSONArray) {
    for (i in 0 until other.length()) put(other.get(i))
}
