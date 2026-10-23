package app.ombra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ombra.ui.theme.Spacing

/** Title of a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = Spacing.screen + Spacing.xs, end = Spacing.screen, top = Spacing.xl, bottom = Spacing.s)
            .semantics { heading() },
    )
}

/** Rows on one rounded surface, the grouped list style of Settings and Locations. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = Spacing.xs), content = content)
    }
}

/** Where an item of a lazy list sits in its group: [GroupItem] draws the matching part of a [RowGroup]. */
enum class GroupPosition {
    Single,
    First,
    Middle,
    Last,
    ;

    companion object {
        fun of(index: Int, count: Int): GroupPosition = when {
            count == 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/** One item of a lazy list that looks like a row of a [RowGroup], so long groups stay lazy. */
@Composable
fun GroupItem(position: GroupPosition, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val top = position == GroupPosition.Single || position == GroupPosition.First
    val bottom = position == GroupPosition.Single || position == GroupPosition.Last
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .background(MaterialTheme.colorScheme.surfaceContainer, groupShape(top, bottom))
            .padding(top = if (top) Spacing.xs else 0.dp, bottom = if (bottom) Spacing.xs else 0.dp),
        content = content,
    )
}

@Composable
private fun groupShape(top: Boolean, bottom: Boolean): Shape {
    val large = MaterialTheme.shapes.large
    val square = CornerSize(0.dp)
    return large.copy(
        topStart = if (top) large.topStart else square,
        topEnd = if (top) large.topEnd else square,
        bottomEnd = if (bottom) large.bottomEnd else square,
        bottomStart = if (bottom) large.bottomStart else square,
    )
}

/**
 * A row with an optional leading icon or [leadingText] (a flag), a title, a short description and
 * [trailing] content, which keeps a gap to the text. A disabled row is dimmed and ignores clicks. A
 * screen reader reads the whole row as one item, also when it is not clickable (a title with its
 * value).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingText: String? = null,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = iconTint)
            leadingText != null -> Text(leadingText, style = MaterialTheme.typography.titleLarge)
        }
        if (icon != null || leadingText != null) Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.m))
            trailing()
        }
    }
}

/** A row that opens another screen. */
@Composable
fun NavRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null, enabled: Boolean = true) {
    ListRow(title = title, supporting = supporting, enabled = enabled, onClick = onClick, modifier = modifier) {
        Icon(Icons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        supporting = supporting,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        // The row dims itself and carries the semantics; a disabled Switch would dim twice.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One option of a single choice; the radio button sits in front, as in system settings. */
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = Spacing.xs, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = Spacing.xs)) { RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Spacing.m)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
