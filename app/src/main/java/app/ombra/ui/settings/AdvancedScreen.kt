package app.ombra.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.BuildConfig
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ipc.KeyRotation
import app.ombra.ui.components.ConnectionDetailsSheet
import app.ombra.ui.components.Icons
import app.ombra.ui.components.ListRow
import app.ombra.ui.components.NavRow
import app.ombra.ui.components.RadioRow
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/** WireGuard key, censorship hosts, connection details and diagnostics. */
@Composable
fun AdvancedScreen(core: CoreSource, editor: SettingsEditor, onBack: () -> Unit, onDebugBundle: () -> Unit, onConsole: () -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val tunnel = state.tunnel
    var showDetails by rememberSaveable(tunnel != null) { mutableStateOf(false) }
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    SettingsPage(stringResource(R.string.settings_advanced), onBack) {
        SectionHeader(stringResource(R.string.advanced_key))
        RowGroup {
            ListRow(
                title = stringResource(R.string.advanced_rotate),
                supporting = stringResource(R.string.advanced_rotate_detail),
            ) {
                FilledTonalButton(onClick = vm::rotateKey, enabled = !vm.rotatingKey && !vm.keyRotated) {
                    if (vm.keyRotated) Icon(Icons.Check, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = Spacing.xs))
                    Text(stringResource(if (vm.keyRotated) R.string.advanced_rotated else R.string.advanced_rotate_action))
                }
            }
            KeyValueRow(
                title = stringResource(R.string.details_client_key),
                value = tunnel?.clientPublicKey,
                missing = stringResource(R.string.advanced_key_when_connected),
            )
        }

        SectionHeader(stringResource(R.string.advanced_rotation))
        RowGroup(Modifier.selectableGroup()) {
            KEY_ROTATIONS.forEach { (rotation, title, detail) ->
                RadioRow(
                    title = stringResource(title),
                    supporting = stringResource(detail),
                    selected = settings.keyRotation == rotation,
                    onClick = { vm.update { it.copy(keyRotation = rotation) } },
                )
            }
        }
        GroupNote(stringResource(R.string.advanced_rotation_note))

        SectionHeader(stringResource(R.string.advanced_hosts))
        RowGroup {
            HostRow(
                title = stringResource(R.string.advanced_sni),
                detail = stringResource(R.string.advanced_sni_detail),
                stored = settings.sniRelay,
                onSave = { host -> vm.update { it.withSniRelay(host) } },
            )
            HostRow(
                title = stringResource(R.string.advanced_api_host),
                detail = stringResource(R.string.advanced_api_host_detail),
                stored = settings.apiHostAlternate,
                onSave = { host -> vm.update { it.withApiHostAlternate(host) } },
            )
        }
        GroupNote(stringResource(R.string.advanced_hosts_note))

        SectionHeader(stringResource(R.string.stats_details))
        RowGroup {
            NavRow(
                title = stringResource(R.string.advanced_details),
                supporting = stringResource(if (tunnel != null) R.string.advanced_details_detail else R.string.advanced_details_off),
                enabled = tunnel != null,
                onClick = { showDetails = true },
            )
        }

        SectionHeader(stringResource(R.string.advanced_diagnostics))
        RowGroup {
            SwitchRow(
                title = stringResource(R.string.advanced_logs),
                supporting = stringResource(R.string.advanced_logs_detail),
                checked = settings.diagnostics,
                onCheckedChange = { on -> vm.update { it.copy(diagnostics = on) } },
            )
            NavRow(stringResource(R.string.bundle_title), onDebugBundle, supporting = stringResource(R.string.advanced_bundle_detail))
            if (BuildConfig.DEBUG) {
                NavRow(stringResource(R.string.settings_console), onConsole, supporting = stringResource(R.string.settings_console_detail))
            }
        }
    }

    if (showDetails && tunnel != null) ConnectionDetailsSheet(tunnel, onDismiss = { showDetails = false })
}

private data class RotationOption(val rotation: KeyRotation, val title: Int, val detail: Int)

private val KEY_ROTATIONS = listOf(
    RotationOption(KeyRotation.Days30, R.string.rotation_days30, R.string.rotation_days30_detail),
    RotationOption(KeyRotation.Day1, R.string.rotation_day1, R.string.rotation_day1_detail),
    RotationOption(KeyRotation.EachConnect, R.string.rotation_each_connect, R.string.rotation_each_connect_detail),
)

/** A key or address in the mono font, selectable for copying; [missing] when there is none. */
@Composable
private fun KeyValueRow(title: String, value: String?, missing: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            SelectionContainer { Text(value, style = OmbraTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text(missing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A host name that is saved only when it is valid; empty goes back to the core default. */
@Composable
private fun HostRow(title: String, detail: String, stored: String?, onSave: (String) -> Unit) {
    var input by rememberSaveable(stored) { mutableStateOf(stored.orEmpty()) }
    var invalid by rememberSaveable(stored) { mutableStateOf(false) }
    val changed = input.trim() != stored.orEmpty()
    val save = {
        val host = input.trim()
        invalid = host.isNotEmpty() && !isHostName(host)
        if (!invalid && changed) onSave(host)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    invalid = false
                },
                placeholder = { Text(stringResource(R.string.advanced_host_default)) },
                textStyle = OmbraTextStyles.mono,
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.advanced_host_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.s))
            FilledTonalButton(onClick = save, enabled = changed) { Text(stringResource(R.string.action_save)) }
        }
    }
}
