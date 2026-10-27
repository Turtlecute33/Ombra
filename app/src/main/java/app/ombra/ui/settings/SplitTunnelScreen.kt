package app.ombra.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.InstalledApps
import app.ombra.data.SettingsEditor
import app.ombra.ipc.SplitMode
import app.ombra.ui.components.GroupItem
import app.ombra.ui.components.GroupPosition
import app.ombra.ui.components.Icons
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.components.RadioRow
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SubScreen
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Split tunnelling: off, all apps except some, or only some apps. The app list is one lazy list
 * with the options, so a phone with hundreds of apps scrolls smoothly.
 */
@Composable
fun SplitTunnelScreen(core: CoreSource, editor: SettingsEditor, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps = remember { InstalledApps(context.applicationContext) }
    val picker = viewModel {
        // Not before the settings are known: the first choice orders the rows.
        SplitTunnelViewModel(selected = editor.settings.filterNotNull().map { it.splitApps }, loadApps = apps::launchable)
    }
    val rows by picker.rows.collectAsStateWithLifecycle()
    val reducedMotion = OmbraTheme.reducedMotion
    val settings = state.settings
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    SubScreen(title = stringResource(R.string.settings_split), onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl),
        ) {
            item(key = "modes") {
                RowGroup(Modifier.selectableGroup()) {
                    SPLIT_MODES.forEach { (mode, title, detail) ->
                        RadioRow(
                            title = stringResource(title),
                            supporting = stringResource(detail),
                            selected = settings.splitMode == mode,
                            onClick = { vm.update { it.copy(splitMode = mode) } },
                        )
                    }
                }
                GroupNote(stringResource(R.string.split_note))
            }
            if (state.system.lockdown == true && settings.splitMode != SplitMode.Off) {
                item(key = "lockdown") {
                    Notice(
                        text = stringResource(R.string.split_lockdown),
                        tone = NoticeTone.Error,
                        modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.m),
                    )
                }
            }
            if (settings.splitMode == SplitMode.Off) return@LazyColumn

            item(key = "apps-header") {
                val count = settings.splitApps.size
                SectionHeader(
                    if (settings.splitMode == SplitMode.Exclude) {
                        pluralStringResource(R.plurals.split_apps_excluded, count, count)
                    } else {
                        pluralStringResource(R.plurals.split_apps_included, count, count)
                    },
                )
                if (settings.splitMode == SplitMode.Include && count == 0) {
                    Notice(
                        text = stringResource(R.string.split_include_empty),
                        tone = NoticeTone.Info,
                        modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.m),
                    )
                }
                AppSearch(picker.query, picker::updateQuery)
                RowGroup {
                    SwitchRow(
                        title = stringResource(R.string.split_show_system),
                        supporting = stringResource(R.string.split_show_system_detail),
                        checked = picker.showSystem,
                        onCheckedChange = picker::showSystemApps,
                    )
                }
                Spacer(Modifier.size(Spacing.m))
            }
            val list = rows
            when {
                list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                list.isEmpty() -> item(key = "none") { GroupNote(stringResource(R.string.split_no_match)) }
                else -> items(list.size, key = { list[it].packageName }, contentType = { "app" }) { index ->
                    val row = list[index]
                    GroupItem(GroupPosition.of(index, list.size), if (reducedMotion) Modifier else Modifier.animateItem()) {
                        AppItem(row, apps) { selected -> vm.update { it.withSplitApp(row.packageName, selected) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSearch(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.split_search)) },
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
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.m),
    )
}

/** An app with its icon, name and package; the whole row toggles it. */
@Composable
private fun AppItem(row: AppRow, apps: InstalledApps, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = row.selected, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.packageName, apps)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(
                row.label ?: stringResource(R.string.split_app_missing),
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.label == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The row carries the semantics.
        Checkbox(checked = row.selected, onCheckedChange = null, modifier = Modifier.padding(start = Spacing.s))
    }
}

/** Loads off the main thread; a neutral square shows until then. */
@Composable
private fun AppIcon(packageName: String, apps: InstalledApps) {
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName, sizePx) { value = apps.icon(packageName, sizePx) }
    val modifier = Modifier.size(ICON_SIZE)
    icon?.let { Image(it, contentDescription = null, modifier = modifier) }
        ?: Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small))
}

private data class SplitOption(val mode: SplitMode, val title: Int, val detail: Int)

private val SPLIT_MODES = listOf(
    SplitOption(SplitMode.Off, R.string.split_off, R.string.split_off_detail),
    SplitOption(SplitMode.Exclude, R.string.split_exclude, R.string.split_exclude_detail),
    SplitOption(SplitMode.Include, R.string.split_include, R.string.split_include_detail),
)

private val ICON_SIZE = 40.dp
