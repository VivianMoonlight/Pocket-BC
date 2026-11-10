package dev.vivian.mobilebc.domain.bctext

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun substituteBcDictionaryText(
    version: String,
    template: String,
    dictionary: JSONArray,
    memberLookup: Map<Int, String>,
    valueResolver: BcDictionaryValueResolver,
    textLookup: (String) -> String? = { null },
): String {
    val substitutions = linkedMapOf<String, String>()
    for (index in 0 until dictionary.length()) {
        val entry = dictionary.optJSONObject(index) ?: continue
        entry.characterNumber("SourceCharacter")?.let { memberNumber ->
            substitutions["SourceCharacter"] = memberName(memberNumber, memberLookup)
        }
        entry.characterNumber("TargetCharacter")?.let { memberNumber ->
            val name = memberName(memberNumber, memberLookup)
            substitutions["TargetCharacter"] = name
            substitutions["TargetCharacterName"] = name
            substitutions.putIfAbsent("DestinationCharacter", name)
            substitutions.putIfAbsent("DestinationCharacterName", name)
        }
        entry.characterNumber("MemberNumber")?.let { memberNumber ->
            entry.optString("Tag").takeIf(String::isNotBlank)?.let { tag ->
                substitutions.putCharacterSubstitutionIfAbsent(tag, memberName(memberNumber, memberLookup))
            }
        }
        entry.optString("Tag")
            .takeIf { it.isNotBlank() && !it.startsWith("MISSING ", ignoreCase = true) }
            ?.let { tag ->
                when {
                    entry.has("Text") -> substitutions.putCharacterSubstitution(
                        tag,
                        entry.opt("Text")?.toString().orEmpty(),
                    )
                    entry.has("TextToLookUp") -> entry.optString("TextToLookUp")
                        .takeIf(String::isNotBlank)
                        ?.let { lookupKey ->
                            val localizedText = textLookup(lookupKey)
                                ?.takeIf(String::isNotBlank)
                                ?: lookupKey
                            substitutions.putCharacterSubstitution(
                                tag,
                                localizedText,
                            )
                        }
                    entry.has("AssetName") -> {
                        val groupName = entry.optString("GroupName").takeIf(String::isNotBlank)
                        val assetName = valueResolver.userFacingAssetName(
                            version = version,
                            groupName = groupName,
                            rawName = entry.optString("AssetName"),
                        ).lowercase(Locale.ROOT)
                        val craftName = (entry.opt("CraftName") as? String)?.takeIf(String::isNotBlank)
                        substitutions[tag] = craftName?.let { "$it ($assetName)" } ?: assetName
                    }
                    entry.has("GroupName") -> substitutions[tag] = valueResolver.userFacingGroupName(
                        version,
                        entry.optString("GroupName"),
                    ).lowercase(Locale.ROOT)
                }
            }
        entry.optString("FocusGroupName").takeIf(String::isNotBlank)?.let { rawGroupName ->
            val groupName = valueResolver.userFacingGroupName(version, rawGroupName).lowercase(Locale.ROOT)
            entry.optString("Tag").takeIf(String::isNotBlank)?.let { tag -> substitutions[tag] = groupName }
            substitutions["FocusAssetGroup"] = groupName
            substitutions["FocusGroup"] = groupName
            substitutions["GroupName"] = groupName
        }
        entry.optString("AssetGroupName").takeIf(String::isNotBlank)?.let { rawGroupName ->
            val groupName = valueResolver.userFacingGroupName(version, rawGroupName).lowercase(Locale.ROOT)
            substitutions["FocusAssetGroup"] = groupName
            substitutions["FocusGroup"] = groupName
            substitutions["GroupName"] = groupName
        }
        entry.optString("ActivityName").takeIf(String::isNotBlank)?.let { activityName ->
            substitutions["ActivityName"] = activityName
        }
    }
    substitutions.putIfAbsent("PronounPossessive", "their")
    substitutions.putIfAbsent("PronounObject", "them")
    substitutions.putIfAbsent("PronounSubject", "they")
    substitutions.putIfAbsent("TargetPronounObject", "them")
    substitutions.putIfAbsent("TargetPronounPossessive", "their")
    substitutions.putIfAbsent("TargetPronounSubject", "they")
    // Match the original template only; member names and other inserted values are literal text.
    val tagPattern = substitutions.keys.sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    return Regex(tagPattern).replace(template) { match -> substitutions.getValue(match.value) }
}

private fun MutableMap<String, String>.putCharacterSubstitution(tag: String, value: String) {
    put(tag, value)
    if (tag.endsWith("Character") && !tag.endsWith("Name")) put("${tag}Name", value)
}

private fun MutableMap<String, String>.putCharacterSubstitutionIfAbsent(tag: String, value: String) {
    putIfAbsent(tag, value)
    if (tag.endsWith("Character") && !tag.endsWith("Name")) putIfAbsent("${tag}Name", value)
}

private fun JSONObject.characterNumber(key: String): Int? =
    when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

private fun memberName(memberNumber: Int, memberLookup: Map<Int, String>): String =
    memberLookup[memberNumber]?.takeIf(String::isNotBlank) ?: "#$memberNumber"
