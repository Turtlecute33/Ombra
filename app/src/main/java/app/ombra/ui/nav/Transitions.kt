package app.ombra.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A transition spec of AnimatedContent or NavDisplay. */
typealias Transition<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform

/** Material shared axis X with the theme's expressive motion: a short slide plus a cross-fade. */
@Composable
fun <S> sharedAxis(forward: Boolean): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val sign = if (forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1 else -1
    val offset = sign * with(LocalDensity.current) { 48.dp.roundToPx() }
    return {
        (slideInHorizontally(slide) { offset } + fadeIn(fadeIn)) togetherWith
            (slideOutHorizontally(slide) { -offset } + fadeOut(fadeOut))
    }
}

/** Material fade through, for switching between peers such as bottom navigation tabs. */
@Composable
fun <S> fadeThrough(): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val scale = motion.defaultSpatialSpec<Float>()
    return { (fadeIn(fadeIn) + scaleIn(scale, initialScale = 0.96f)) togetherWith fadeOut(fadeOut) }
}
