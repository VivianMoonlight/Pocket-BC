package dev.vivian.mobilebc.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerLayoutTest {
    @Test fun bottomNavigationAlreadyOutsideTheComposerIsNotReservedTwice() {
        val navigation = 104 // App navigation and system navigation together.
        assertEquals(496, composerKeyboardSpace(600, navigation, 0, panelHasFocus = false))
        assertEquals(0, composerKeyboardSpace(80, navigation, 0, panelHasFocus = false))
        assertEquals(136, composerKeyboardSpace(600, navigation, 360, panelHasFocus = false))
        assertEquals(496, composerKeyboardSpace(600, navigation, 360, panelHasFocus = true))
    }

    @Test fun keyboardAndPanelShareSpaceThroughoutHandoff() {
        listOf(600 to 0, 450 to 120, 300 to 240, 150 to 360, 24 to 360).forEach { (ime, panel) ->
            val gap = composerKeyboardSpace(ime, navigation = 24, panel, panelHasFocus = false)
            assertEquals(maxOf((ime - 24).coerceAtLeast(0), panel), panel + gap)
        }
    }

    @Test fun searchKeyboardDoesNotCoverPanelAndNavigationIsConsumedOnce() {
        assertEquals(576, composerKeyboardSpace(600, 24, 360, panelHasFocus = true))
        assertEquals(0, composerKeyboardSpace(0, 24, 360, panelHasFocus = false))
        assertEquals(0, composerKeyboardSpace(24, 24, 0, panelHasFocus = false))
        assertEquals(600, composerKeyboardSpace(600, 0, 0, panelHasFocus = false))
    }
}
