package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.vivian.mobilebc.core.model.AppAppearance
import dev.vivian.mobilebc.core.model.ThemeMode
import dev.vivian.mobilebc.core.model.ThemePalette
import dev.vivian.mobilebc.core.model.ThemeColorRole
import org.junit.Assert.*
import org.junit.Test

class AppearanceColorsTest {
    @Test
    fun customColorsOverrideSelectedRolesWithoutChangingOthers() {
        val baseline = appearanceColors(AppAppearance(mode = ThemeMode.Black), false)
        val colors = appearanceColors(AppAppearance(mode = ThemeMode.Black,
            customColors = mapOf(ThemeColorRole.Primary to 0xFF123456L, ThemeColorRole.Background to 0xFF654321L)), false)
        assertEquals(Color(0xFF123456), colors.primary)
        assertEquals(Color(0xFF654321), colors.background)
        assertEquals(baseline.onSurface, colors.onSurface)
    }
    @Test
    fun modesOverrideSystemAndBlackHasBlackSurfaces() {
        assertFalse(ThemeMode.Light.isDark(true))
        assertTrue(ThemeMode.Dark.isDark(false))
        assertTrue(ThemeMode.Black.isDark(false))
        assertTrue(ThemeMode.System.isDark(true))
        assertFalse(ThemeMode.System.isDark(false))
        ThemePalette.entries.forEach {
            val colors = appearanceColors(AppAppearance(ThemeMode.Black, it), false)
            assertEquals(Color.Black, colors.background)
            assertEquals(Color.Black, colors.surface)
        }
    }

    @Test
    fun everyPaletteKeepsReadableTextAndWhisperSemantics() {
        for (palette in ThemePalette.entries) for (dark in listOf(false, true)) {
            val colors = appearanceColors(AppAppearance(palette = palette), dark)
            val original = appearanceColors(AppAppearance(), dark)
            assertEquals(original.secondary, colors.secondary)
            listOf(colors.primary to colors.onPrimary, colors.primaryContainer to colors.onPrimaryContainer,
                colors.surface to colors.onSurface, colors.surfaceVariant to colors.onSurfaceVariant).forEach { (bg, fg) ->
                val contrast = (maxOf(bg.luminance(), fg.luminance()) + 0.05f) /
                    (minOf(bg.luminance(), fg.luminance()) + 0.05f)
                assertTrue("$palette dark=$dark contrast=$contrast", contrast >= 4.5f)
            }
        }
    }

    @Test
    fun dynamicFallbackAndUnknownSavedValuesKeepOriginalDefaults() {
        assertEquals(AppAppearance(), AppAppearance.fromStored("unknown", "unknown"))
        assertEquals(AppAppearance(ThemeMode.Dark), AppAppearance.fromStored("Dark", "unknown"))
        assertEquals(appearanceColors(AppAppearance(), false),
            appearanceColors(AppAppearance(palette = ThemePalette.Dynamic), false))
    }
}
