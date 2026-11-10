package dev.vivian.mobilebc

import android.app.Application
import dev.vivian.mobilebc.i18n.AppLanguageManager
import dev.vivian.mobilebc.core.ui.configureChatImageLoader

class MobileBcApplication : Application() {
    lateinit var appComposition: AppComposition
        private set
    lateinit var appLanguageManager: AppLanguageManager
        private set

    override fun onCreate() {
        super.onCreate()
        configureChatImageLoader(this)
        appLanguageManager = AppLanguageManager(this).also { it.applyStoredLanguage() }
        BcAppVisibilityTracker.register(this)
        appComposition = AppComposition.create(this, appLanguageManager)
    }
}
