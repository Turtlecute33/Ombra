package app.ombra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ombra.R
import app.ombra.ui.connect.FormattedRate
import app.ombra.ui.connect.LiveStats
import app.ombra.ui.connect.RateUnit
import app.ombra.ui.connect.formatDuration
import app.ombra.ui.connect.formatRate
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/**
 * Download and upload rates, then latency, protocol and session time. Figures use tabular digits.
 * The info button opens the connection details.
 */
@Composable
fun LiveStatsCard(stats: LiveStats?, protocol: String, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            // Upload lines up with the second column of the row below.
            Row(Modifier.fillMaxWidth()) {
                RateCell(Icons.ArrowDown, stringResource(R.string.stats_download), stats?.downBytesPerSecond, Modifier.weight(1f))
                Row(Modifier.weight(2f)) {
                    RateCell(Icons.ArrowUp, stringResource(R.string.stats_upload), stats?.upBytesPerSecond, Modifier.weight(1f))
                    IconButton(onClick = onDetails, modifier = Modifier.offset(x = Spacing.m, y = -Spacing.m)) {
                        Icon(Icons.Info, contentDescription = stringResource(R.string.stats_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                SmallCell(
                    stringResource(R.string.stats_latency),
                    stats?.latencyMs?.let { stringResource(R.string.stats_latency_value, it) } ?: NO_VALUE,
                    Modifier.weight(1f),
                )
                SmallCell(stringResource(R.string.stats_protocol), protocol, Modifier.weight(1f))
                SmallCell(stringResource(R.string.stats_session), stats?.let { formatDuration(it.sessionMs) } ?: NO_VALUE, Modifier.weight(1f))
            }
        }
    }
}

private const val NO_VALUE = "–"

/** The unit next to a rate, relative to the figure. */
private val UNIT_SIZE = 0.55.em

@Composable
private fun RateCell(icon: ImageVector, label: String, bytesPerSecond: Long?, modifier: Modifier) {
    val rate = bytesPerSecond?.let { formatRate(it) }
    val unit = rate?.let { unitLabel(it) }.orEmpty()
    val spoken = rate?.let { "$label ${it.value} $unit" } ?: label
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
        // One text, so a large font scale shrinks figure and unit together instead of clipping.
        BasicText(
            text = buildAnnotatedString {
                append(rate?.value ?: NO_VALUE)
                withStyle(SpanStyle(fontSize = UNIT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" $unit") }
            },
            style = OmbraTextStyles.stat.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = OmbraTextStyles.stat.fontSize),
        )
    }
}

@Composable
private fun SmallCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = OmbraTextStyles.statSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun unitLabel(rate: FormattedRate): String = stringResource(
    when (rate.unit) {
        RateUnit.Bits -> R.string.unit_bits
        RateUnit.Kilobits -> R.string.unit_kilobits
        RateUnit.Megabits -> R.string.unit_megabits
        RateUnit.Gigabits -> R.string.unit_gigabits
    },
)
