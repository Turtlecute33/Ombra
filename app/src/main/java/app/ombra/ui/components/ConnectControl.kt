package app.ombra.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import app.ombra.ui.theme.BrandGradient
import app.ombra.ui.theme.BrandInk
import app.ombra.ui.theme.BrandOrange
import app.ombra.ui.theme.OmbraTheme

/** How the connect control looks; each look has its own shape, colour and glyph. */
enum class ControlLook {
    /** Ready to connect: the brand gradient, the only place where it appears. */
    Ready,

    /** Connecting, reconnecting or disconnecting: turns slowly. */
    Working,
    Protected,

    /** Connecting with an error that retries cannot fix. */
    Problem,

    /** No core status yet. */
    Unavailable,
}

/**
 * The large connect button. Its shape morphs between Material shapes when [look] changes, with the
 * theme's spatial motion; with animations off it only swaps shapes. Haptic feedback on tap and when
 * the tunnel comes up. Screen readers hear [actionLabel] and [stateLabel].
 */
@Composable
fun ConnectControl(
    look: ControlLook,
    actionLabel: String,
    stateLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 164.dp,
) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = OmbraTheme.reducedMotion
    val colors = MaterialTheme.colorScheme
    val status = OmbraTheme.status
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()

    val fill by animateColorAsState(
        when (look) {
            ControlLook.Ready -> BrandOrange
            ControlLook.Working -> colors.surfaceContainerHigh
            ControlLook.Protected -> status.successContainer
            ControlLook.Problem -> colors.errorContainer
            ControlLook.Unavailable -> colors.surfaceContainerHighest
        },
        effects,
    )
    val glyphColor by animateColorAsState(
        when (look) {
            ControlLook.Ready -> BrandInk
            ControlLook.Protected -> status.success
            ControlLook.Working -> colors.primary
            ControlLook.Problem -> colors.onErrorContainer
            ControlLook.Unavailable -> colors.onSurfaceVariant
        },
        effects,
    )
    val halo by animateColorAsState(
        when (look) {
            ControlLook.Ready -> BrandOrange
            ControlLook.Working -> colors.primary
            ControlLook.Protected -> status.success
            ControlLook.Problem -> colors.error
            ControlLook.Unavailable -> colors.outlineVariant
        },
        effects,
    )
    // The gradient fades in over the solid fill, so leaving or entering Ready blends smoothly.
    val gradientAlpha by animateFloatAsState(if (look == ControlLook.Ready) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec())
    val morph = rememberMorph(shapeOf(look), reducedMotion)
    val turning = look == ControlLook.Working && !reducedMotion
    val rotation = rememberTurn(turning)
    val breath = rememberBreath(turning)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())

    // Confirms when the tunnel comes up while the control is on screen, not when it opens protected.
    var shownLook by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        if (look == ControlLook.Protected && shownLook != ControlLook.Protected) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        shownLook = look
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter * HALO_SCALE)
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(if (look == ControlLook.Ready) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onClick()
            }
            .drawWithCache {
                val path = Path()
                val matrix = Matrix()
                val shapeSize = diameter.toPx()
                onDrawBehind {
                    val haloRadius = shapeSize / 2 * (1.06f + 0.12f * breath.value)
                    drawCircle(halo.copy(alpha = 0.09f + 0.07f * breath.value), radius = haloRadius)

                    path.rewind()
                    morph.morph.toPath(morph.progress.value, path)
                    matrix.reset()
                    matrix.translate(size.center.x, size.center.y)
                    matrix.rotateZ(rotation.value)
                    matrix.scale(shapeSize * pressScale, shapeSize * pressScale)
                    matrix.translate(-0.5f, -0.5f)
                    path.transform(matrix)
                    drawPath(path, SolidColor(fill))
                    if (gradientAlpha > 0f) drawPath(path, BrandGradient, alpha = gradientAlpha)
                }
            },
    ) {
        AnimatedContent(
            targetState = look,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
            label = "glyph",
        ) { target ->
            Icon(
                imageVector = glyphOf(target),
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier
                    .size(diameter * 0.3f)
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
            )
        }
    }
}

/** The halo reaches beyond the shape; the control's bounds include it. */
private const val HALO_SCALE = 1.22f

private fun shapeOf(look: ControlLook): RoundedPolygon = when (look) {
    ControlLook.Ready, ControlLook.Unavailable -> MaterialShapes.Cookie9Sided
    ControlLook.Working -> MaterialShapes.SoftBurst
    ControlLook.Protected -> MaterialShapes.Circle
    ControlLook.Problem -> MaterialShapes.Cookie4Sided
}

private fun glyphOf(look: ControlLook) = when (look) {
    ControlLook.Protected -> Icons.VerifiedUser
    ControlLook.Problem -> Icons.Warning
    else -> Icons.Power
}

private class MorphState(val morph: Morph, val progress: State<Float>)

/** A morph from the previous shape to [target], animated with the spatial spring. */
@Composable
private fun rememberMorph(target: RoundedPolygon, reducedMotion: Boolean): MorphState {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        from = to
        to = target
        if (reducedMotion) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }
    val morph = remember(from, to) { Morph(from, to) }
    return remember(morph) { MorphState(morph, progress.asState()) }
}

/** Slow rotation while working; it rests at 0 otherwise. */
@Composable
private fun rememberTurn(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "turn").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)),
        label = "turn",
    )
}

/** Halo pulse 0..1 while working; 0 otherwise. */
@Composable
private fun rememberBreath(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
        label = "breath",
    )
}

private const val TURN_MS = 8_000
private const val BREATH_MS = 1_400
