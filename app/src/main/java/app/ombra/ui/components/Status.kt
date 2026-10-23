package app.ombra.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ombra.R
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing

enum class StatusTone { Neutral, Positive, Negative }

/**
 * The state in words above the connect control. Screen readers announce a new [title] once: a
 * live-region node on top of the title holds it as its description. The fade between two titles
 * happens outside that node, so it sends no further changes.
 */
@Composable
fun StatusHeader(title: String, subtitle: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        when (tone) {
            StatusTone.Neutral -> MaterialTheme.colorScheme.onSurface
            StatusTone.Positive -> OmbraTheme.status.success
            StatusTone.Negative -> MaterialTheme.colorScheme.error
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(
                title,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "title",
                modifier = Modifier.clearAndSetSemantics {},
            ) { text ->
                Text(text, style = MaterialTheme.typography.headlineMedium, color = color, textAlign = TextAlign.Center)
            }
            Spacer(
                Modifier.matchParentSize().semantics {
                    contentDescription = title
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
        AnimatedContent(subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

enum class NoticeTone { Error, Info }

/**
 * An inline message with an optional action, for errors and hints next to the content they affect.
 * With [onDismiss], a close button ends it.
 */
@Composable
fun Notice(
    text: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val (container, content) = when (tone) {
        NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        NoticeTone.Info -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = if (actionLabel == null) Spacing.m else Spacing.xs)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (tone == NoticeTone.Error) Icons.Error else Icons.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 1.dp).size(20.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = Spacing.m))
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.offset(x = Spacing.s, y = -Spacing.s).size(32.dp)) {
                        Icon(Icons.Close, contentDescription = stringResource(R.string.action_dismiss), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                    modifier = Modifier.align(Alignment.End),
                ) { Text(actionLabel) }
            }
        }
    }
}
