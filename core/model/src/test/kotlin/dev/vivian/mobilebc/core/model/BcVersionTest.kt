package dev.vivian.mobilebc.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcVersionTest {
    @Test
    fun normalizesReleaseAndPrereleaseVersions() {
        assertEquals("R130", " r130 ".normalizedBcVersion())
        assertEquals("R131Beta2", "r131Beta2".normalizedBcVersion())
        assertEquals("R132Alpha", "R132alpha".normalizedBcVersion())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankVersionIsRejected() {
        "  ".normalizedBcVersion()
    }

    @Test
    fun blankVersionDoesNotNormalizeToAFallback() {
        assertNull("  ".normalizedBcVersionOrNull())
    }

    @Test
    fun rejectsValuesThatCouldEscapeAResourceVersionDirectory() {
        assertNull("../R130".normalizedBcVersionOrNull())
        assertNull("R130/BondageClub".normalizedBcVersionOrNull())
        assertNull("https://example.com/R130".normalizedBcVersionOrNull())
    }
}
