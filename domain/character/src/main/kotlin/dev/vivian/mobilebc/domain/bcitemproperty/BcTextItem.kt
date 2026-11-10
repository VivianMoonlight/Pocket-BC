package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule

/** BC DynamicDrawTextRegex and HTML input maxlength both use the original string (UTF-16). */
fun validBcItemText(value: String, limit: Int): Boolean = value.length <= limit && value.matches(Regex("^[\\p{L}\\p{N}\\x20-\\x7e]*$"))

fun BcExtendedItemRule.validText(values: Map<String, String>): Boolean = textFields.isNotEmpty() &&
    values.keys == textFields.keys && textFields.all { (key, limit) ->
        val value = values.getValue(key)
        validBcItemText(value, limit)
    }

fun BcExtendedItemRule.textValues(propertyJson: String?): Map<String, String> = textFields.keys.associateWith { key ->
    BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(propertyJson, key))
        ?: BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(defaultPropertyJson, key)).orEmpty()
}
