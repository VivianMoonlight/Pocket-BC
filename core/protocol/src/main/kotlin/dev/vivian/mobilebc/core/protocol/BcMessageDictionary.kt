package dev.vivian.mobilebc.core.protocol

import com.google.gson.JsonParser

fun bcMessageIdFromDictionaryOrNull(dictionaryJson: String?): String? =
    dictionaryMessageIdOrNull(dictionaryJson, "MsgId")

fun bcReplyIdFromDictionaryOrNull(dictionaryJson: String?): String? =
    dictionaryMessageIdOrNull(dictionaryJson, "ReplyId")

private fun dictionaryMessageIdOrNull(dictionaryJson: String?, name: String): String? {
    if (dictionaryJson.isNullOrBlank()) return null
    return runCatching {
        val dictionary = JsonParser.parseString(dictionaryJson)
        if (!dictionary.isJsonArray) return@runCatching null
        var id: String? = null
        dictionary.asJsonArray.forEach { element ->
            if (element.isJsonObject) {
                val value = element.asJsonObject.get(name)
                // Native BC identifies these entries by the field and string type, without
                // requiring Tag. Later entries replace earlier metadata in the dictionary.
                if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
                    id = value.asString.takeIf(String::isNotBlank)
                }
            }
        }
        id
    }.getOrNull()
}
