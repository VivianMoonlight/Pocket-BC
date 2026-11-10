package dev.vivian.mobilebc.domain.bctext

data class BcTextParseRequest(
    val version: String,
    val key: String,
    val dictionaryJson: String?,
    val memberLookup: Map<Int, String>,
)

data class BcParsedText(
    val key: String,
    val text: String,
)

fun interface BcActivityTextParser {
    fun parse(request: BcTextParseRequest): BcParsedText?
}

object BcDictionaryFallbackTextParser {
    fun parse(
        key: String,
        dictionaryJson: String?,
        memberLookup: Map<Int, String>,
        version: String = "",
        dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
    ): BcParsedText? {
        if (dictionaryJson.isNullOrBlank()) return null
        val entries = runCatching { org.json.JSONArray(dictionaryJson) }.getOrNull() ?: return null
        val fallback = findFallbackEntry(entries, key) ?: return null
        return BcParsedText(
            key = key,
            text = substituteBcDictionaryText(
                version = version,
                template = fallback,
                dictionary = entries,
                memberLookup = memberLookup,
                valueResolver = dictionaryValueResolver,
            ),
        )
    }

    fun renderTemplate(
        template: String,
        dictionaryJson: String?,
        memberLookup: Map<Int, String>,
        version: String = "",
        dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
    ): String? {
        if (dictionaryJson.isNullOrBlank()) return null
        val entries = runCatching { org.json.JSONArray(dictionaryJson) }.getOrNull() ?: return null
        return substituteBcDictionaryText(
            version = version,
            template = template,
            dictionary = entries,
            memberLookup = memberLookup,
            valueResolver = dictionaryValueResolver,
        )
    }

    private fun findFallbackEntry(entries: org.json.JSONArray, key: String): String? {
        var firstMissingText: String? = null
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val tag = entry.optString("Tag")
            if (!tag.startsWith("MISSING ", ignoreCase = true)) continue
            val text = entry.opt("Text")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            if (tag.contains(key)) return text
            if (firstMissingText == null) firstMissingText = text
        }
        return firstMissingText
    }

}
