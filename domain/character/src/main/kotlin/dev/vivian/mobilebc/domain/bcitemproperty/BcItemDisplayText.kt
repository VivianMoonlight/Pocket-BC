package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterItem

data class BcItemDisplayText(
    val customName: String?,
    val description: String?,
    val textLines: List<String>,
) {
    fun displayName(baseName: String): String = customName?.let { "$it ($baseName)" } ?: baseName
}

fun BcCharacterItem.displayText(): BcItemDisplayText = BcItemDisplayText(
    customName = craftJson.readBcStringProperty("Name"),
    description = craftJson.readBcStringProperty("Description"),
    textLines = BcItemPropertyJson.propertyKeys(propertyJson)
        .filter { it.matches(Regex("Text[0-9]*")) }
        .sortedWith(compareBy<String> { if (it == "Text") 0 else it.drop(4).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
        .mapNotNull { BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(propertyJson, it)) },
)
