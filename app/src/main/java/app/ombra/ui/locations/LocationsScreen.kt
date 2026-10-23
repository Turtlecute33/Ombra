package app.ombra.ui.locations

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ui.components.EmptyState
import app.ombra.ui.components.GroupItem
import app.ombra.ui.components.Icons
import app.ombra.ui.components.ListRow
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.countryName
import app.ombra.ui.components.flagEmoji
import app.ombra.ui.nav.LocationSide
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Exit and entry relay: fastest, pins, then countries, cities and single servers. [onChosen] runs after the core stored a choice. */
@Composable
fun LocationsScreen(
    core: CoreSource,
    editor: SettingsEditor,
    initialSide: LocationSide,
    contentPadding: PaddingValues,
    onChosen: () -> Unit,
) {
    val vm = viewModel { LocationsViewModel(core, editor, initialSide) }
    val state by vm.state.collectAsStateWithLifecycle()
    RequestErrorMessage(vm.errorCode, vm::errorShown)
    // One instance for the life of the screen: a new one on each list change would recompose every
    // visible row, not only the rows that changed.
    val chosen by rememberUpdatedState(onChosen)
    val actions = remember(vm) {
        RowActions(
            choose = { choice -> vm.choose(choice) { chosen() } },
            togglePin = vm::togglePin,
            toggleFold = vm::toggleFold,
            refresh = vm::refresh,
        )
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Text(
            stringResource(R.string.locations_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(horizontal = Spacing.screen, vertical = Spacing.l)
                .semantics { heading() },
        )
        SideSwitch(state.side, onChange = vm::selectSide)
        SearchField(vm.query, onChange = vm::updateQuery)
        LocationList(
            state = state,
            query = vm.query,
            bottomPadding = contentPadding.calculateBottomPadding(),
            refreshing = vm.refreshing,
            actions = actions,
        )
    }
}

private class RowActions(
    val choose: (Choice) -> Unit,
    val togglePin: (Choice) -> Unit,
    val toggleFold: (String) -> Unit,
    val refresh: () -> Unit,
)

@Composable
private fun SideSwitch(side: LocationSide, onChange: (LocationSide) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        LocationSide.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = side == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, LocationSide.entries.size),
            ) {
                Text(stringResource(if (option == LocationSide.Exit) R.string.locations_exit else R.string.locations_entry))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        // One line also at a large font scale: the field must not grow over the list.
        placeholder = { Text(stringResource(R.string.locations_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.m),
    )
}

@Composable
private fun LocationList(state: LocationsUiState, query: String, bottomPadding: Dp, refreshing: Boolean, actions: RowActions) {
    val reducedMotion = OmbraTheme.reducedMotion
    val listState = rememberLazyListState()
    // Another side or search starts at the top. Without this, the list keeps the first visible key
    // and can open far down, with Fastest and the pins out of view.
    LaunchedEffect(state.side, query.trim()) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl)) {
        items(state.rows, key = { it.key }, contentType = ::contentType) { row ->
            val animated = if (reducedMotion) Modifier else Modifier.animateItem()
            when (row) {
                is HeaderRow -> SectionHeader(stringResource(headerText(row.header)), animated)
                is FastestRow -> GroupItem(row.position, animated) { FastestItem(row, state.side, actions) }
                is CountryRow -> GroupItem(row.position, animated) { CountryItem(row, actions) }
                is CityRow -> GroupItem(row.position, animated) { CityItem(row, actions) }
                is ServerRow -> GroupItem(row.position, animated) { ServerItem(row, state.side, actions) }
            }
        }
        if (state.status != ListStatus.Ready) {
            item(key = "status:${state.status}") { ListStatusItem(state, query, refreshing, actions.refresh) }
        }
    }
}

@Composable
private fun LazyItemScope.ListStatusItem(state: LocationsUiState, query: String, refreshing: Boolean, onRefresh: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().animateItem()
    when (state.status) {
        ListStatus.Missing -> MissingList(state.side, refreshing, onRefresh, modifier)
        ListStatus.Empty -> EmptyState(
            icon = Icons.Globe,
            title = stringResource(R.string.locations_empty),
            message = stringResource(R.string.locations_empty_detail),
            modifier = modifier,
        ) { RefreshButton(refreshing, onRefresh) }
        ListStatus.NoMatch -> EmptyState(
            icon = Icons.Search,
            title = stringResource(R.string.locations_no_match, query.trim()),
            message = stringResource(R.string.locations_no_match_detail),
            modifier = modifier,
        )
        ListStatus.Ready -> Unit
    }
}

/** The list usually arrives within a moment of login; after a short wait, offer to load it. */
@Composable
private fun MissingList(side: LocationSide, refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier) {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LIST_WAIT_MS)
        waited = true
    }
    if (!waited) {
        Row(
            modifier.padding(Spacing.xl),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(32.dp))
            Text(stringResource(R.string.locations_loading), modifier = Modifier.padding(start = Spacing.m))
        }
    } else {
        EmptyState(
            icon = Icons.Globe,
            title = stringResource(R.string.locations_missing),
            message = stringResource(if (side == LocationSide.Exit) R.string.locations_missing_exits else R.string.locations_missing_relays),
            modifier = modifier,
        ) { RefreshButton(refreshing, onRefresh) }
    }
}

@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    Button(onClick = onRefresh, enabled = !refreshing) { Text(stringResource(R.string.locations_refresh)) }
}

@Composable
private fun FastestItem(row: FastestRow, side: LocationSide, actions: RowActions) {
    ListRow(
        title = stringResource(if (side == LocationSide.Exit) R.string.locations_fastest_exit else R.string.locations_fastest_relay),
        supporting = stringResource(R.string.locations_fastest_hint),
        icon = Icons.Speed,
        iconTint = MaterialTheme.colorScheme.primary,
        onClick = { actions.choose(Choice.Fastest) },
        modifier = Modifier.semantics { selected = row.selected },
    ) { if (row.selected) SelectedMark() }
}

@Composable
private fun CountryItem(row: CountryRow, actions: RowActions) {
    PlaceRow(
        title = row.name,
        supporting = joined(
            pluralStringResource(R.plurals.locations_cities, row.cities, row.cities),
            pluralStringResource(R.plurals.locations_servers, row.servers, row.servers),
        ),
        selected = row.selected,
        inUse = row.inUse,
        flag = flagEmoji(row.code),
        onClick = { actions.choose(Choice.Country(row.code)) },
    ) {
        row.expanded?.let { open ->
            FoldButton(open, stringResource(if (open) R.string.locations_hide_cities else R.string.locations_show_cities, row.name)) {
                actions.toggleFold(countryKey(row.code))
            }
        }
    }
}

@Composable
private fun CityItem(row: CityRow, actions: RowActions) {
    val servers = pluralStringResource(R.plurals.locations_servers, row.servers, row.servers)
    PlaceRow(
        title = row.name,
        supporting = if (row.pinnedSection) joined(countryName(row.countryCode), servers) else servers,
        selected = row.selected,
        inUse = row.inUse,
        flag = if (row.pinnedSection) flagEmoji(row.countryCode) else null,
        indent = if (row.pinnedSection) 0.dp else CITY_INDENT,
        onClick = { actions.choose(Choice.City(row.countryCode, row.code)) },
    ) {
        row.pinned?.let { pinned -> PinButton(pinned, row.name) { actions.togglePin(Choice.City(row.countryCode, row.code)) } }
        row.expanded?.let { open ->
            FoldButton(open, stringResource(if (open) R.string.locations_hide_servers else R.string.locations_show_servers, row.name)) {
                actions.toggleFold(cityKey(row.countryCode, row.code))
            }
        }
    }
}

@Composable
private fun ServerItem(row: ServerRow, side: LocationSide, actions: RowActions) {
    val server = row.server
    val facts = if (side == LocationSide.Exit) {
        joined(
            server.provider,
            server.datacenterId?.let { stringResource(R.string.locations_datacenter, it) },
            server.tier?.let { stringResource(R.string.locations_tier, it) },
        )
    } else {
        joined(if (row.pinnedSection) "${server.cityName}, ${countryName(server.countryCode)}" else server.cityName, server.address)
    }
    PlaceRow(
        title = server.id,
        titleStyle = OmbraTextStyles.mono,
        supporting = facts,
        selected = row.selected,
        inUse = row.inUse,
        flag = if (row.pinnedSection) flagEmoji(server.countryCode) else null,
        indent = if (row.pinnedSection) 0.dp else SERVER_INDENT,
        onClick = { actions.choose(Choice.Server(server.id)) },
    ) {
        row.pinned?.let { pinned -> PinButton(pinned, server.id) { actions.togglePin(Choice.Server(server.id)) } }
    }
}

/** A choosable place. The row announces "selected"; the check mark only shows it. */
@Composable
private fun PlaceRow(
    title: String,
    supporting: String,
    selected: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    flag: String? = null,
    indent: Dp = 0.dp,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val useLabel = stringResource(R.string.locations_use)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = useLabel, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = Spacing.l + indent, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag != null) {
            // The name follows; a screen reader would only repeat it as "flag of ...".
            Text(flag, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(Spacing.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(withInUse(supporting, inUse), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) SelectedMark(Modifier.padding(horizontal = Spacing.s))
        actions()
    }
}

@Composable
private fun withInUse(text: String, inUse: Boolean): AnnotatedString {
    if (!inUse) return AnnotatedString(text)
    val connected = stringResource(R.string.locations_connected)
    val color = OmbraTheme.status.success
    return buildAnnotatedString {
        append(text)
        append(SEPARATOR)
        withStyle(SpanStyle(color = color)) { append(connected) }
    }
}

@Composable
private fun FoldButton(open: Boolean, label: String, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fold")
    IconButton(onClick = onClick) {
        Icon(Icons.ExpandMore, contentDescription = label, modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun PinButton(pinned: Boolean, name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (pinned) Icons.PinFilled else Icons.Pin,
            contentDescription = stringResource(if (pinned) R.string.locations_unpin else R.string.locations_pin, name),
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(Icons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = modifier)
}

private fun contentType(row: LocationRow): String = when (row) {
    is HeaderRow -> "header"
    is FastestRow -> "fastest"
    is CountryRow -> "country"
    is CityRow -> "city"
    is ServerRow -> "server"
}

private fun headerText(header: Header): Int = when (header) {
    Header.Pinned -> R.string.locations_pinned
    Header.Locations -> R.string.locations_all
}

private fun joined(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(SEPARATOR)

private const val SEPARATOR = " · "
private const val LIST_WAIT_MS = 3_000L
// A city lines up with the name of its country, a server one step further in.
private val CITY_INDENT = 44.dp
private val SERVER_INDENT = 60.dp
