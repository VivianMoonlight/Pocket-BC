package dev.vivian.mobilebc.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.lerp
import dev.vivian.mobilebc.core.model.AppAppearance
import dev.vivian.mobilebc.core.model.ThemeMode
import dev.vivian.mobilebc.core.model.ThemePalette
import dev.vivian.mobilebc.core.model.ThemeColorRole

// Explicit semantic roles keep every feature in the same reading palette. In particular,
// wallpaper-derived dynamic colors must not change the meaning of the whisper accent.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7B3947),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1DDE0),
    onPrimaryContainer = Color(0xFF542431),
    inversePrimary = Color(0xFFDDA5AF),
    secondary = Color(0xFF6C4F74),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEE5EF),
    onSecondaryContainer = Color(0xFF49354F),
    tertiary = Color(0xFF785C32),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0E2C9),
    onTertiaryContainer = Color(0xFF4D3819),
    background = Color(0xFFF5EFE6),
    onBackground = Color(0xFF332A2B),
    surface = Color(0xFFFCF8F2),
    onSurface = Color(0xFF332A2B),
    surfaceVariant = Color(0xFFEDE3DB),
    onSurfaceVariant = Color(0xFF706061),
    surfaceTint = Color(0xFF7B3947),
    inverseSurface = Color(0xFF372D31),
    inverseOnSurface = Color(0xFFF9EEE7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF701B16),
    outline = Color(0xFF897878),
    outlineVariant = Color(0xFFD7C8C3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCF8F2),
    surfaceDim = Color(0xFFE3D8D0),
    surfaceContainerLowest = Color(0xFFFFFCF8),
    surfaceContainerLow = Color(0xFFF9F3EC),
    surfaceContainer = Color(0xFFF2EAE2),
    surfaceContainerHigh = Color(0xFFECE2DA),
    surfaceContainerHighest = Color(0xFFE6DCD4),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFDDA5AF),
    onPrimary = Color(0xFF4D2530),
    primaryContainer = Color(0xFF57343F),
    onPrimaryContainer = Color(0xFFFFDCE3),
    inversePrimary = Color(0xFF7B3947),
    secondary = Color(0xFFCDB1D7),
    onSecondary = Color(0xFF3D2947),
    secondaryContainer = Color(0xFF443449),
    onSecondaryContainer = Color(0xFFEBD9F0),
    tertiary = Color(0xFFDCC095),
    onTertiary = Color(0xFF422E11),
    tertiaryContainer = Color(0xFF554226),
    onTertiaryContainer = Color(0xFFF4E0BD),
    background = Color(0xFF211A1D),
    onBackground = Color(0xFFEEE1DA),
    surface = Color(0xFF2B2227),
    onSurface = Color(0xFFEEE1DA),
    surfaceVariant = Color(0xFF46383D),
    onSurfaceVariant = Color(0xFFC6B2B5),
    surfaceTint = Color(0xFFDDA5AF),
    inverseSurface = Color(0xFFF1E5DE),
    inverseOnSurface = Color(0xFF392E32),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF782620),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C90),
    outlineVariant = Color(0xFF58464D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF44373D),
    surfaceDim = Color(0xFF211A1D),
    surfaceContainerLowest = Color(0xFF191316),
    surfaceContainerLow = Color(0xFF261E22),
    surfaceContainer = Color(0xFF30262C),
    surfaceContainerHigh = Color(0xFF3A2F35),
    surfaceContainerHighest = Color(0xFF44383E),
)

private val ReadingTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 48.sp, lineHeight = 58.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)

private val ReadingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MobileBcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearance: AppAppearance = AppAppearance(),
    content: @Composable () -> Unit,
) {
    val dark = appearance.mode.isDark(darkTheme)
    val dynamic = if (appearance.palette == ThemePalette.Dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else null
    MaterialTheme(
        colorScheme = appearanceColors(appearance, darkTheme, dynamic),
        typography = ReadingTypography,
        shapes = ReadingShapes,
        content = content,
    )
}

fun appearanceColors(appearance: AppAppearance, systemDark: Boolean, dynamic: ColorScheme? = null): ColorScheme {
    val dark = appearance.mode.isDark(systemDark)
    val base = if (dark) DarkColors else LightColors
    val accent = when (appearance.palette) {
        ThemePalette.Ocean -> if (dark) Color(0xFFA0CAFF) else Color(0xFF245D91)
        ThemePalette.Forest -> if (dark) Color(0xFF9DD6AE) else Color(0xFF28633F)
        ThemePalette.Lavender -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
        ThemePalette.Graphite -> if (dark) Color(0xFFC5C6CA) else Color(0xFF55585E)
        else -> base.primary
    }
    val colors = when {
        appearance.palette == ThemePalette.Dynamic && dynamic != null -> dynamic.copy(
            secondary = base.secondary, onSecondary = base.onSecondary,
            secondaryContainer = base.secondaryContainer, onSecondaryContainer = base.onSecondaryContainer,
        )
        appearance.palette == ThemePalette.Rose || appearance.palette == ThemePalette.Dynamic -> base
        else -> {
            fun surface(light: Long, night: Long) = lerp(Color(if (dark) night else light), accent, 0.04f)
            base.copy(
                primary = accent, onPrimary = if (dark) Color(0xFF15202B) else Color.White,
                primaryContainer = lerp(if (dark) Color(0xFF202020) else Color.White, accent, 0.22f),
                onPrimaryContainer = if (dark) Color(0xFFF4F4F4) else Color(0xFF202020),
                inversePrimary = if (dark) lerp(Color.Black, accent, 0.45f) else lerp(Color.White, accent, 0.4f),
                background = surface(0xFFF7F7F7, 0xFF141414),
                surface = surface(0xFFFCFCFC, 0xFF191919),
                onBackground = if (dark) Color(0xFFE8E8E8) else Color(0xFF242424),
                onSurface = if (dark) Color(0xFFE8E8E8) else Color(0xFF242424),
                surfaceVariant = surface(0xFFE5E5E5, 0xFF373737),
                onSurfaceVariant = if (dark) Color(0xFFC4C4C4) else Color(0xFF555555),
                surfaceTint = accent,
                surfaceBright = surface(0xFFFCFCFC, 0xFF383838),
                surfaceDim = surface(0xFFDDDDDD, 0xFF141414),
                surfaceContainerLowest = surface(0xFFFFFFFF, 0xFF0F0F0F),
                surfaceContainerLow = surface(0xFFF5F5F5, 0xFF1D1D1D),
                surfaceContainer = surface(0xFFEFEFEF, 0xFF222222),
                surfaceContainerHigh = surface(0xFFE9E9E9, 0xFF2B2B2B),
                surfaceContainerHighest = surface(0xFFE3E3E3, 0xFF353535),
                outline = if (dark) Color(0xFF949494) else Color(0xFF777777),
                outlineVariant = if (dark) Color(0xFF454545) else Color(0xFFCCCCCC),
            )
        }
    }
    val modeColors = if (appearance.mode == ThemeMode.Black) colors.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF101010),
        surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF222222),
        surfaceContainerHighest = Color(0xFF2C2C2C), surfaceBright = Color(0xFF333333),
    ) else colors
    if (appearance.customColors.isEmpty()) return modeColors
    fun custom(role: ThemeColorRole, fallback: Color): Color = appearance.customColors[role]?.let(::Color) ?: fallback
    return modeColors.copy(
        primary = custom(ThemeColorRole.Primary, modeColors.primary),
        onPrimary = custom(ThemeColorRole.OnPrimary, modeColors.onPrimary),
        primaryContainer = custom(ThemeColorRole.PrimaryContainer, modeColors.primaryContainer),
        onPrimaryContainer = custom(ThemeColorRole.OnPrimaryContainer, modeColors.onPrimaryContainer),
        secondary = custom(ThemeColorRole.Secondary, modeColors.secondary),
        onSecondary = custom(ThemeColorRole.OnSecondary, modeColors.onSecondary),
        secondaryContainer = custom(ThemeColorRole.SecondaryContainer, modeColors.secondaryContainer),
        onSecondaryContainer = custom(ThemeColorRole.OnSecondaryContainer, modeColors.onSecondaryContainer),
        background = custom(ThemeColorRole.Background, modeColors.background),
        onBackground = custom(ThemeColorRole.OnBackground, modeColors.onBackground),
        surface = custom(ThemeColorRole.Surface, modeColors.surface),
        onSurface = custom(ThemeColorRole.OnSurface, modeColors.onSurface),
        surfaceVariant = custom(ThemeColorRole.SurfaceVariant, modeColors.surfaceVariant),
        onSurfaceVariant = custom(ThemeColorRole.OnSurfaceVariant, modeColors.onSurfaceVariant),
        outline = custom(ThemeColorRole.Outline, modeColors.outline),
        error = custom(ThemeColorRole.Error, modeColors.error),
        onError = custom(ThemeColorRole.OnError, modeColors.onError),
    )
}
