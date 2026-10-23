package app.ombra.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand values (docs/DESIGN.md section 8). Text colours are tuned for at least 4.5:1 on every
// surface they sit on; ThemeContrastTest checks it.

/** The brand orange. As a fill only: text on it uses [BrandInk]. */
val BrandOrange = Color(0xFFFF6025)
private val BrandOrangeLight = Color(0xFFFF7A49)

/** Ink for glyphs on the brand gradient, and the darkest neutral. */
val BrandInk = Color(0xFF1B1D20)

/** Fill of the main action only (top to bottom). */
val BrandGradient: Brush = Brush.verticalGradient(listOf(BrandOrangeLight, BrandOrange))

val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandOrangeLight,
    onPrimary = Color(0xFF2B0D00),
    primaryContainer = Color(0xFF5C2410),
    onPrimaryContainer = Color(0xFFFFC7B2),
    inversePrimary = Color(0xFFB33A12),
    secondary = Color(0xFFBCC3CA),
    onSecondary = BrandInk,
    secondaryContainer = Color(0xFF34383D),
    onSecondaryContainer = Color(0xFFE3E6E8),
    tertiary = Color(0xFFFFC7B2),
    onTertiary = Color(0xFF3A1406),
    tertiaryContainer = Color(0xFF4A2A1E),
    onTertiaryContainer = Color(0xFFFFEDE6),
    background = BrandInk,
    onBackground = Color(0xFFECEEED),
    surface = BrandInk,
    onSurface = Color(0xFFECEEED),
    surfaceVariant = Color(0xFF2B2E33),
    onSurfaceVariant = Color(0xFFAEB4BA),
    surfaceTint = BrandOrangeLight,
    inverseSurface = Color(0xFFECEEED),
    inverseOnSurface = BrandInk,
    error = Color(0xFFFF7D75),
    onError = Color(0xFF3B0705),
    errorContainer = Color(0xFF4F1715),
    onErrorContainer = Color(0xFFFFB5B3),
    outline = Color(0xFF80878E),
    outlineVariant = Color(0xFF3A3F45),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3E44),
    surfaceDim = BrandInk,
    surfaceContainerLowest = Color(0xFF141618),
    surfaceContainerLow = Color(0xFF1F2124),
    surfaceContainer = Color(0xFF222528),
    surfaceContainerHigh = Color(0xFF2B2E33),
    surfaceContainerHighest = Color(0xFF34383D),
)

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB33A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D8),
    onPrimaryContainer = Color(0xFF5C1F06),
    inversePrimary = BrandOrangeLight,
    secondary = Color(0xFF5C6066),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7E8),
    onSecondaryContainer = Color(0xFF232629),
    tertiary = Color(0xFF8A3A1C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDE6),
    onTertiaryContainer = Color(0xFF5C1F06),
    background = Color(0xFFF6F8F7),
    onBackground = BrandInk,
    surface = Color(0xFFF6F8F7),
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFE8EBEA),
    onSurfaceVariant = Color(0xFF4F555B),
    surfaceTint = Color(0xFFB33A12),
    inverseSurface = Color(0xFF2B2E33),
    inverseOnSurface = Color(0xFFECEEED),
    error = Color(0xFFC02A24),
    onError = Color.White,
    errorContainer = Color(0xFFFDE3E2),
    onErrorContainer = Color(0xFF7A0B07),
    outline = Color(0xFF6E747A),
    outlineVariant = Color(0xFFD3D8D9),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF6F8F7),
    surfaceDim = Color(0xFFD9DDDC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF1F0),
    surfaceContainerHigh = Color(0xFFE8EBEA),
    surfaceContainerHighest = Color(0xFFE1E5E4),
)

/**
 * Colours that Material has no role for. They stay the same with dynamic colour: green must
 * always mean "protected".
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF4CC35A),
    onSuccess = Color(0xFF06210A),
    successContainer = Color(0xFF173D1D),
    onSuccessContainer = Color(0xFFB0F2B6),
)

val LightStatusColors = StatusColors(
    success = Color(0xFF1A7228),
    onSuccess = Color.White,
    successContainer = Color(0xFFDCF9DF),
    onSuccessContainer = Color(0xFF0C4F15),
)
