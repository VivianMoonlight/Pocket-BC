package dev.vivian.mobilebc.core.model

enum class ThemeMode {
    System, Light, Dark, Black;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark, Black -> true
    }
}

enum class ThemePalette { Rose, Ocean, Forest, Lavender, Graphite, Dynamic }

enum class ThemeColorRole {
    Primary, OnPrimary, PrimaryContainer, OnPrimaryContainer,
    Secondary, OnSecondary, SecondaryContainer, OnSecondaryContainer,
    Background, OnBackground, Surface, OnSurface, SurfaceVariant, OnSurfaceVariant,
    Outline, Error, OnError,
    ChatHeader, ChatHeaderText, ChatComposer, ChatComposerText, ChatMessage, ChatMessageText,
}

data class ChatBackground(val imageUri: String? = null, val overlayPercent: Int = 75)

data class AppAppearance(
    val mode: ThemeMode = ThemeMode.System,
    val palette: ThemePalette = ThemePalette.Rose,
    val customColors: Map<ThemeColorRole, Long> = emptyMap(),
    val roomBackground: ChatBackground = ChatBackground(),
    val directBackground: ChatBackground = ChatBackground(),
) {
    companion object {
        fun fromStored(mode: String?, palette: String?) = AppAppearance(
            ThemeMode.entries.find { it.name == mode } ?: ThemeMode.System,
            ThemePalette.entries.find { it.name == palette } ?: ThemePalette.Rose,
        )
    }
}
