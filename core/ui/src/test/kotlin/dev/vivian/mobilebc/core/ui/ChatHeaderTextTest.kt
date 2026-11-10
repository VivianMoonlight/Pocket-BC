package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHeaderTextTest {
    @Test
    fun colorsOnlyIdentityNotPrefixSeparatorOrTime() {
        val header = chatHeaderText("Whisper from Alice", "Alice", "12:34", Color.Red)
        assertEquals("Whisper from Alice · 12:34", header.text)
        val span = header.spanStyles.single()
        assertEquals("Alice", header.text.substring(span.start, span.end))
        assertEquals(Color.Red, span.item.color)
    }

    @Test
    fun doesNotApplySenderColorToOutgoingRecipientOrSystemLabel() {
        assertTrue(chatHeaderText("To Alice", null, "12:34", Color.Red).spanStyles.isEmpty())
        assertTrue(chatHeaderText(null, "12:34", "12:34", Color.Red).spanStyles.isEmpty())
        assertTrue(chatHeaderText("Alice", "Alice", "12:34", null).spanStyles.isEmpty())
    }
}
