package app.ombra.ui.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }
private val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Ombra's theme: the brand scheme by default, the wallpaper scheme when [dynamicColor] is on.
 * Material 3 Expressive shapes and motion.
 */
@Composable
fun OmbraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val reducedMotion by rememberReducedMotion()
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            shapes = OmbraShapes,
            typography = OmbraTypography,
            content = content,
        )
    }
}

/**
 * "Remove animations" sets the animator scale to 0; endless animations then stay still. It follows
 * the setting while the app runs: changing it is not a configuration change.
 */
@Composable
private fun rememberReducedMotion(): State<Boolean> = produceState(!ValueAnimator.areAnimatorsEnabled()) {
    val listener = ValueAnimator.DurationScaleChangeListener { scale -> value = scale == 0f }
    ValueAnimator.registerDurationScaleChangeListener(listener)
    // The scale can change between the first value and the registration.
    value = !ValueAnimator.areAnimatorsEnabled()
    awaitDispose { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
}

object OmbraTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable
        get() = LocalStatusColors.current

    /** True when the user turned animations off: show end states, no loops. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReducedMotion.current
}
