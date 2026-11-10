package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterColorsTest {
    @Test
    fun parsesBcHexColorsAsOpaque() {
        assertEquals(Color(0xFFAABBCC), bcLabelColor("#abc"))
        assertEquals(Color(0xFF12AB34), bcLabelColor("#12aB34"))
    }

    @Test
    fun malformedOrTransparentColorsFallBack() {
        listOf(null, "", "red", "#gggggg", "#1234", "#00123456", "#-12345").forEach {
            assertNull(bcLabelColor(it))
        }
    }
}
