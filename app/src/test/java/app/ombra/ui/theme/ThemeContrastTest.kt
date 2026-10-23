package app.ombra.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

/** WCAG AA: text needs 4.5:1 against every background it is drawn on. */
class ThemeContrastTest {
    @Test
    fun darkTextRolesMeetAa() = assertTextContrast(DarkColors, DarkStatusColors)

    @Test
    fun lightTextRolesMeetAa() = assertTextContrast(LightColors, LightStatusColors)

    @Test
    fun inkOnBrandFillsMeetAa() {
        assertAtLeast(BrandInk, BrandOrange, "ink on brand orange")
        assertAtLeast(BrandInk, Color(0xFFFF7A49), "ink on gradient start")
    }

    private fun assertTextContrast(c: ColorScheme, s: StatusColors) {
        val surfaces = mapOf(
            "surface" to c.surface,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainer" to c.surfaceContainer,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
        )
        val texts = mapOf(
            "onSurface" to c.onSurface,
            "onSurfaceVariant" to c.onSurfaceVariant,
            "primary" to c.primary,
            "error" to c.error,
            "success" to s.success,
        )
        for ((textName, text) in texts) {
            for ((surfaceName, surface) in surfaces) assertAtLeast(text, surface, "$textName on $surfaceName")
        }
        assertAtLeast(c.onPrimary, c.primary, "onPrimary")
        assertAtLeast(c.onPrimaryContainer, c.primaryContainer, "onPrimaryContainer")
        assertAtLeast(c.onSecondary, c.secondary, "onSecondary")
        assertAtLeast(c.onSecondaryContainer, c.secondaryContainer, "onSecondaryContainer")
        assertAtLeast(c.onTertiary, c.tertiary, "onTertiary")
        assertAtLeast(c.onTertiaryContainer, c.tertiaryContainer, "onTertiaryContainer")
        assertAtLeast(c.onError, c.error, "onError")
        assertAtLeast(c.onErrorContainer, c.errorContainer, "onErrorContainer")
        assertAtLeast(c.inverseOnSurface, c.inverseSurface, "inverseOnSurface")
        assertAtLeast(s.onSuccess, s.success, "onSuccess")
        assertAtLeast(s.onSuccessContainer, s.successContainer, "onSuccessContainer")
        // The shield of the connect control while protected.
        assertAtLeast(s.success, s.successContainer, "success on successContainer")
    }

    private fun assertAtLeast(text: Color, background: Color, what: String, minimum: Double = 4.5) {
        val ratio = contrast(text, background)
        assertTrue(ratio >= minimum, "$what: ${"%.2f".format(ratio)} < $minimum")
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
