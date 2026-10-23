package app.ombra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ombra.R

/** Manrope variable (OFL, bundled): one file, one instance per weight. */
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

/** JetBrains Mono NL (OFL, bundled, no ligatures): account numbers, keys and IP addresses. */
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono))

/** Tabular figures, so changing numbers do not shift the layout. */
private const val TABULAR = "tnum"

/**
 * Each paragraph takes its direction from its first strong letter, as a TextView does. With an
 * RTL system language the layout mirrors, and the English texts still read left to right ("10 of
 * 20 digits", not "of 20 digits 10").
 */
val OmbraTypography: Typography = Typography().run {
    fun TextStyle.manrope(weight: FontWeight, tracking: Float = 0f) =
        copy(fontFamily = Manrope, fontWeight = weight, letterSpacing = tracking.em, textDirection = TextDirection.Content)
    copy(
        displayLarge = displayLarge.manrope(FontWeight.SemiBold, -0.02f),
        displayMedium = displayMedium.manrope(FontWeight.SemiBold, -0.02f),
        displaySmall = displaySmall.manrope(FontWeight.SemiBold, -0.015f),
        headlineLarge = headlineLarge.manrope(FontWeight.SemiBold, -0.015f),
        headlineMedium = headlineMedium.manrope(FontWeight.SemiBold, -0.01f),
        headlineSmall = headlineSmall.manrope(FontWeight.SemiBold, -0.005f),
        titleLarge = titleLarge.manrope(FontWeight.SemiBold),
        titleMedium = titleMedium.manrope(FontWeight.SemiBold),
        titleSmall = titleSmall.manrope(FontWeight.SemiBold),
        bodyLarge = bodyLarge.manrope(FontWeight.Normal),
        bodyMedium = bodyMedium.manrope(FontWeight.Normal),
        bodySmall = bodySmall.manrope(FontWeight.Medium),
        labelLarge = labelLarge.manrope(FontWeight.SemiBold),
        labelMedium = labelMedium.manrope(FontWeight.SemiBold),
        labelSmall = labelSmall.manrope(FontWeight.SemiBold, 0.02f),
    )
}

/** Styles outside the Material scale. */
object OmbraTextStyles {
    /** A live figure (rates, latency, session time). */
    val stat = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = TABULAR,
        textDirection = TextDirection.Content,
    )

    /** A small live figure. */
    val statSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
        textDirection = TextDirection.Content,
    )

    /**
     * Account numbers, keys, IP addresses. Always left to right: in an RTL paragraph the groups of
     * an account number would swap places and the padding of a key would move to its start.
     */
    val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 20.sp, textDirection = TextDirection.Ltr)

    /** The account number as the hero of a screen. */
    val monoLarge = mono.copy(fontSize = 22.sp, lineHeight = 30.sp)
}
