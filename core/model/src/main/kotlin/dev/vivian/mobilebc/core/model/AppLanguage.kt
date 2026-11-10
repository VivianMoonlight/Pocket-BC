package dev.vivian.mobilebc.core.model

import java.util.Locale

/** User-selectable application language. Protocol identifiers are never derived from labels. */
enum class AppLanguage(
    val storageValue: String,
    val languageTag: String,
) {
    System("system", ""),
    English("en", "en"),
    SimplifiedChinese("zh-Hans", "zh-Hans"),
    ;

    companion object {
        fun fromStorageValue(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: System
    }
}

/** Maps the effective app locale to the language codes used by native Bondage Club resources. */
fun AppLanguage.bcLanguageCode(systemLocale: Locale = Locale.getDefault()): String {
    val locale = languageTag.takeIf(String::isNotBlank)?.let(Locale::forLanguageTag) ?: systemLocale
    return when (locale.language.lowercase(Locale.ROOT)) {
        "de" -> "DE"
        "fr" -> "FR"
        "ru" -> "RU"
        "uk" -> "UA"
        "zh" -> when {
            locale.script.equals("Hant", ignoreCase = true) -> "TW"
            locale.country.equals("TW", ignoreCase = true) ||
                locale.country.equals("HK", ignoreCase = true) ||
                locale.country.equals("MO", ignoreCase = true) -> "TW"
            else -> "CN"
        }
        else -> "EN"
    }
}
