package app.ombra.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ui.components.ConfirmDialog
import app.ombra.ui.components.Icons
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.openExternal
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/** Block lists, custom DNS servers and the expert switch to the network's DNS. */
@Composable
fun DnsScreen(core: CoreSource, editor: SettingsEditor, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    var confirmSystemDns by rememberSaveable { mutableStateOf(false) }
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    SettingsPage(stringResource(R.string.settings_dns), onBack) {
        state.system.privateDnsServer?.let { host ->
            Notice(
                text = stringResource(R.string.dns_private_dns, host),
                tone = NoticeTone.Error,
                actionLabel = stringResource(R.string.dns_private_dns_action),
                onAction = { openExternal(context, Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS)) },
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.s),
            )
        }

        val listsApply = settings.blockListsApply
        SectionHeader(stringResource(R.string.dns_blocking))
        RowGroup {
            BlockList.entries.forEach { list ->
                SwitchRow(
                    title = stringResource(blockListName(list)),
                    checked = settings.dnsBlock.isOn(list),
                    enabled = listsApply,
                    onCheckedChange = { on -> vm.update { it.withBlockList(list, on) } },
                )
            }
        }
        GroupNote(
            stringResource(
                when {
                    !listsApply -> R.string.dns_blocking_off
                    state.mullvadExit == false -> R.string.dns_blocking_not_mullvad
                    else -> R.string.dns_blocking_note
                },
            ),
        )

        SectionHeader(stringResource(R.string.dns_custom))
        CustomDns(
            servers = settings.customDns,
            enabled = !settings.useSystemDns,
            onAdd = { address -> vm.update { it.withDnsServer(address) } },
            onRemove = { address -> vm.update { it.withoutDnsServer(address) } },
        )
        GroupNote(stringResource(R.string.dns_custom_note))

        SectionHeader(stringResource(R.string.dns_expert))
        RowGroup {
            SwitchRow(
                title = stringResource(R.string.dns_system),
                supporting = stringResource(R.string.dns_system_detail),
                checked = settings.useSystemDns,
                onCheckedChange = { on -> if (on) confirmSystemDns = true else vm.update { it.copy(useSystemDns = false) } },
            )
        }
    }

    if (confirmSystemDns) {
        ConfirmDialog(
            title = stringResource(R.string.dns_system_confirm_title),
            text = stringResource(R.string.dns_system_confirm),
            confirmLabel = stringResource(R.string.dns_system_confirm_action),
            onConfirm = {
                confirmSystemDns = false
                vm.update { it.copy(useSystemDns = true) }
            },
            onDismiss = { confirmSystemDns = false },
            destructive = true,
        )
    }
}

/** The servers, each with a remove button, and a field that accepts only IP addresses. */
@Composable
private fun CustomDns(servers: List<String>, enabled: Boolean, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<DnsEntryProblem?>(null) }
    val add = {
        val address = input.trim()
        problem = dnsEntryProblem(address, servers)
        if (problem == null) {
            onAdd(address)
            input = ""
        }
    }
    RowGroup {
        servers.forEach { server ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(server, style = OmbraTextStyles.mono, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(server) }, enabled = enabled) {
                    Icon(Icons.Delete, contentDescription = stringResource(R.string.dns_remove, server))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    problem = null
                },
                label = { Text(stringResource(R.string.dns_add_label)) },
                placeholder = { Text(stringResource(R.string.dns_add_placeholder), style = OmbraTextStyles.mono) },
                textStyle = OmbraTextStyles.mono,
                singleLine = true,
                enabled = enabled,
                isError = problem != null,
                supportingText = problem?.let { { Text(stringResource(dnsProblemText(it))) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.s))
            FilledTonalButton(onClick = add, enabled = enabled && input.isNotBlank()) { Text(stringResource(R.string.dns_add)) }
        }
    }
}

private fun blockListName(list: BlockList): Int = when (list) {
    BlockList.Ads -> R.string.dns_block_ads
    BlockList.Trackers -> R.string.dns_block_trackers
    BlockList.Malware -> R.string.dns_block_malware
    BlockList.Adult -> R.string.dns_block_adult
    BlockList.Gambling -> R.string.dns_block_gambling
    BlockList.SocialMedia -> R.string.dns_block_social
}

private fun dnsProblemText(problem: DnsEntryProblem): Int = when (problem) {
    DnsEntryProblem.NotAnAddress -> R.string.dns_problem_address
    DnsEntryProblem.Loopback -> R.string.dns_problem_loopback
    DnsEntryProblem.Unspecified -> R.string.dns_problem_unspecified
    DnsEntryProblem.Multicast -> R.string.dns_problem_multicast
    DnsEntryProblem.Duplicate -> R.string.dns_problem_duplicate
}
