package dev.vivian.mobilebc.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun systemLanguagePreservesSupportedNativeBcLocales() {
        assertEquals("CN", AppLanguage.System.bcLanguageCode(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("TW", AppLanguage.System.bcLanguageCode(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("DE", AppLanguage.System.bcLanguageCode(Locale.GERMAN))
        assertEquals("EN", AppLanguage.System.bcLanguageCode(Locale.JAPANESE))
    }
}
