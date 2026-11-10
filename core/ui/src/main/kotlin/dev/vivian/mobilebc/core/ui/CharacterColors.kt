package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color

/** BC label colors are opaque CSS hex colors; malformed values use the caller's theme fallback. */
fun bcLabelColor(value: String?): Color? {
    val hex = value?.takeIf { it.startsWith("#") }?.drop(1) ?: return null
    val expanded = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("")
        6 -> hex
        else -> return null
    }
    if (expanded.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return Color(0xFF000000 or (expanded.toLongOrNull(16) ?: return null))
}
