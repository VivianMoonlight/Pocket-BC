package dev.vivian.mobilebc.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.vivian.mobilebc.core.model.AppLanguage
import dev.vivian.mobilebc.core.model.bcLanguageCode
import java.util.Locale

class AppLanguageManager(
    private val applicationContext: Context,
) {
    var current: AppLanguage
        get() = read(applicationContext)
        private set(value) {
            applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, value.storageValue)
                .apply()
        }

    fun set(language: AppLanguage) {
        if (language == current) return
        current = language
        applyPlatformLocales(applicationContext, language)
    }

    fun currentBcLanguageCode(): String = current.bcLanguageCode(effectiveSystemLocale())

    fun localizedContext(context: Context): Context = wrap(context, current)

    fun applyStoredLanguage() {
        applyPlatformLocales(applicationContext, current)
    }

    private fun effectiveSystemLocale(): Locale =
        applicationContext.resources.configuration.locales[0] ?: Locale.getDefault()

    companion object {
        private const val PREFERENCES_NAME = "mobile-bc-i18n"
        private const val KEY_LANGUAGE = "app-language"

        fun read(context: Context): AppLanguage = AppLanguage.fromStorageValue(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null),
        )

        fun wrap(context: Context, language: AppLanguage = read(context)): Context {
            if (language == AppLanguage.System) return context
            val locale = Locale.forLanguageTag(language.languageTag)
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(locale))
                setLayoutDirection(locale)
            }
            return context.createConfigurationContext(configuration)
        }

        private fun applyPlatformLocales(context: Context, language: AppLanguage) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = language.languageTag
                .takeIf(String::isNotBlank)
                ?.let(LocaleList::forLanguageTags)
                ?: LocaleList.getEmptyLocaleList()
            if (localeManager.applicationLocales != locales) {
                localeManager.applicationLocales = locales
            }
        }
    }
}
