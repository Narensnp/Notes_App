package com.layers.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layers.app.data.repo.ThemeMode

// Paper & ink: warm off-white sheets, slate-blue ink, clay and sage accents.
private val LightColors = lightColorScheme(
    primary = Color(0xFF34526E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2EE),
    onPrimaryContainer = Color(0xFF10283D),
    secondary = Color(0xFF8E5A4B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5DDD3),
    onSecondaryContainer = Color(0xFF3A1A10),
    tertiary = Color(0xFF56715C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7E8D7),
    onTertiaryContainer = Color(0xFF13291A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F3EE),
    onBackground = Color(0xFF1E1D1B),
    surface = Color(0xFFF6F3EE),
    onSurface = Color(0xFF1E1D1B),
    surfaceVariant = Color(0xFFEAE4DA),
    onSurfaceVariant = Color(0xFF5D5A54),
    outline = Color(0xFF8E8981),
    outlineVariant = Color(0xFFDDD6CB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFDF9),
    surfaceContainer = Color(0xFFF1EDE6),
    surfaceContainerHigh = Color(0xFFEBE6DE),
    surfaceContainerHighest = Color(0xFFE5DFD5),
    inverseSurface = Color(0xFF32302D),
    inverseOnSurface = Color(0xFFF5F0E8),
    inversePrimary = Color(0xFFA9C4DE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C4DE),
    onPrimary = Color(0xFF0E2A42),
    primaryContainer = Color(0xFF2A4560),
    onPrimaryContainer = Color(0xFFD6E4F2),
    secondary = Color(0xFFE6B5A5),
    onSecondary = Color(0xFF452419),
    secondaryContainer = Color(0xFF5E3A2E),
    onSecondaryContainer = Color(0xFFFADDD2),
    tertiary = Color(0xFFB5CFB8),
    onTertiary = Color(0xFF203626),
    tertiaryContainer = Color(0xFF384E3D),
    onTertiaryContainer = Color(0xFFD2EBD4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131416),
    onBackground = Color(0xFFE6E2DB),
    surface = Color(0xFF131416),
    onSurface = Color(0xFFE6E2DB),
    surfaceVariant = Color(0xFF2E2E31),
    onSurfaceVariant = Color(0xFFBBB5AC),
    outline = Color(0xFF8B867E),
    outlineVariant = Color(0xFF3B3B3E),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF1C1D20),
    surfaceContainer = Color(0xFF212226),
    surfaceContainerHigh = Color(0xFF292A2E),
    surfaceContainerHighest = Color(0xFF323338),
    inverseSurface = Color(0xFFE6E2DB),
    inverseOnSurface = Color(0xFF2B2A28),
    inversePrimary = Color(0xFF34526E),
)

/** Refined serif for titles, clean sans-serif for reading. */
val TitleFamily: FontFamily = FontFamily.Serif
val BodyFamily: FontFamily = FontFamily.SansSerif

private val LayersTypography = Typography(
    displayLarge = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Normal, fontSize = 42.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = TitleFamily, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/** Extra colors for the paper look that Material's scheme doesn't name. */
@Immutable
data class PaperColors(
    val sheet: Color,
    val sheetBehind: Color,
    val sheetDeep: Color,
    val shadow: Color,
    val hairline: Color,
    val highlight: Color,
    val pin: Color,
    val onPin: Color,
    val overdue: Color,
    val isDark: Boolean,
)

val LocalPaperColors = staticCompositionLocalOf {
    PaperColors(
        sheet = Color.White, sheetBehind = Color.White, sheetDeep = Color.White, shadow = Color.Black,
        hairline = Color.LightGray, highlight = Color.Yellow, pin = Color.Red, onPin = Color.White,
        overdue = Color.Red, isDark = false,
    )
}

private fun paperColorsFor(scheme: ColorScheme, dark: Boolean): PaperColors = if (dark) {
    PaperColors(
        sheet = scheme.surfaceContainerLow,
        sheetBehind = scheme.surfaceContainer,
        sheetDeep = scheme.surfaceContainerHigh,
        shadow = Color(0xFF000000),
        hairline = Color(0x1FFFFFFF),
        highlight = Color(0x66C9A227),
        pin = Color(0xFFE08A7A),
        onPin = Color(0xFF2A0F09),
        overdue = scheme.error,
        isDark = true,
    )
} else {
    PaperColors(
        sheet = scheme.surfaceContainerLow,
        sheetBehind = Color(0xFFF0EBE3),
        sheetDeep = Color(0xFFE6E0D6),
        shadow = Color(0xFF3B2F22),
        hairline = Color(0x14000000),
        highlight = Color(0x80FFE083),
        pin = Color(0xFFC0605E),
        onPin = Color(0xFFFFFFFF),
        overdue = scheme.error,
        isDark = false,
    )
}

@Composable
fun LayersTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalPaperColors provides paperColorsFor(scheme, dark)) {
        MaterialTheme(colorScheme = scheme, typography = LayersTypography, content = content)
    }
}

object Paper {
    val colors: PaperColors
        @Composable get() = LocalPaperColors.current
}
