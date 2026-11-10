package dev.vivian.mobilebc

import dev.vivian.mobilebc.resource.BcWebResourceSnapshot
import dev.vivian.mobilebc.resource.bcTranslationResourcePaths
import dev.vivian.mobilebc.resource.AndroidBcWebResourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCompositionResourceReadinessTest {
    @Test
    fun snapshotMustMatchTheActiveBcTextLanguage() {
        val completeChineseSnapshot = snapshot(language = "CN")

        assertTrue(snapshot(language = null).matchesBcTextLanguage("EN"))
        assertTrue(completeChineseSnapshot.matchesBcTextLanguage("cn"))
        assertFalse(snapshot(language = null).matchesBcTextLanguage("CN"))
        assertFalse(completeChineseSnapshot.matchesBcTextLanguage("EN"))
        assertFalse(
            completeChineseSnapshot.copy(translationResourcePaths = emptyList())
                .matchesBcTextLanguage("CN"),
        )
        assertFalse(completeChineseSnapshot.copy(resourceCount = 0).matchesBcTextLanguage("CN"))
        assertFalse(completeChineseSnapshot.copy(totalBytes = 0L).matchesBcTextLanguage("CN"))
        assertFalse((null as BcWebResourceSnapshot?).matchesBcTextLanguage("CN"))
    }

    private fun snapshot(language: String?): BcWebResourceSnapshot =
        BcWebResourceSnapshot(
            version = "R131",
            baseUrl = "https://example.test/R131/BondageClub/",
            localRootPath = "/bc-web/R131/BondageClub",
            entryFilePath = "index.html",
            resourceCount = AndroidBcWebResourceFiles.requiredResourcePaths.size +
                bcTranslationResourcePaths(language).size,
            totalBytes = 1L,
            syncedAtEpochMillis = 1L,
            translationLanguage = language,
            translationResourcePaths =
                bcTranslationResourcePaths(language),
        )
}
