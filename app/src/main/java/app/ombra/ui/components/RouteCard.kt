package app.ombra.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ombra.R
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing

/**
 * One hop of the route. All null means automatic (the core picks the fastest). [detail] is the
 * exit provider or a chosen server id.
 */
@Immutable
data class Hop(val countryCode: String? = null, val city: String? = null, val detail: String? = null) {
    val isAutomatic: Boolean get() = countryCode == null && city == null && detail == null

    companion object {
        val Automatic = Hop()
    }
}

/**
 * The path of the traffic: this device, entry relay, exit, internet. Entry and exit open the
 * location picker. The rail turns green while protected.
 */
@Composable
fun RouteCard(
    entry: Hop,
    exit: Hop,
    protected: Boolean,
    onEntryClick: () -> Unit,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rail by animateColorAsState(
        if (protected) OmbraTheme.status.success else MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = Spacing.xs)) {
            EndStop(Icons.Device, stringResource(R.string.route_this_device), rail, top = false)
            HopStop(
                label = stringResource(R.string.route_entry),
                hint = stringResource(R.string.route_entry_hint),
                hop = entry,
                rail = rail,
                clickLabel = stringResource(R.string.route_choose_entry),
                onClick = onEntryClick,
            )
            HopStop(
                label = stringResource(R.string.route_exit),
                hint = stringResource(R.string.route_exit_hint),
                hop = exit,
                rail = rail,
                clickLabel = stringResource(R.string.route_choose_exit),
                onClick = onExitClick,
            )
            EndStop(Icons.Globe, stringResource(R.string.route_internet), rail, top = true)
        }
    }
}

private val NodeSize = 40.dp
private val RailColumn = 72.dp

@Composable
private fun EndStop(icon: ImageVector, label: String, rail: Color, top: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RailColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Rail(rail, top = top, bottom = !top)
            // The card colour behind the icon stops the rail at its edge.
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HopStop(label: String, hint: String, hop: Hop, rail: Color, clickLabel: String, onClick: () -> Unit) {
    val title = hopTitle(hop)
    val subtitle = hopSubtitle(hop)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .height(IntrinsicSize.Min)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RailColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Rail(rail, top = true, bottom = true)
            HopNode(hop)
        }
        Column(Modifier.weight(1f).padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "$label · $hint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l),
        )
    }
}

@Composable
private fun HopNode(hop: Hop) {
    val flag = hop.countryCode?.let(::flagEmoji).orEmpty()
    Box(
        modifier = Modifier
            .size(NodeSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (flag.isNotEmpty()) {
            Text(flag, fontSize = 20.sp)
        } else {
            Icon(
                if (hop.isAutomatic) Icons.Speed else Icons.Relay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The vertical line through a stop; it stops at the first and the last stop. */
@Composable
private fun Rail(color: Color, top: Boolean, bottom: Boolean) {
    Canvas(Modifier.fillMaxHeight().width(2.dp)) {
        val middle = size.height / 2
        val from = if (top) 0f else middle
        val to = if (bottom) size.height else middle
        drawRect(color, topLeft = Offset(0f, from), size = Size(size.width, to - from))
    }
}

@Composable
private fun hopTitle(hop: Hop): String = when {
    hop.isAutomatic -> stringResource(R.string.route_fastest)
    hop.city != null -> hop.city
    hop.countryCode != null -> countryName(hop.countryCode)
    else -> hop.detail.orEmpty()
}

@Composable
private fun hopSubtitle(hop: Hop): String? = when {
    hop.isAutomatic -> stringResource(R.string.route_fastest_hint)
    hop.city != null -> listOfNotNull(hop.countryCode?.let { countryName(it) }, hop.detail).joinToString(" · ")
    hop.countryCode != null -> stringResource(R.string.route_any_city)
    else -> null
}
