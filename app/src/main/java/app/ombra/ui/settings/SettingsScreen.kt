package app.ombra.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.BuildConfig
import app.ombra.R
import app.ombra.core.model.TunnelMode
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.UiPrefs
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import app.ombra.ipc.SystemState
import app.ombra.ui.appDetailsIntent
import app.ombra.ui.components.Icons
import app.ombra.ui.components.ListRow
import app.ombra.ui.components.NavRow
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.nav.Page
import app.ombra.ui.openExternal
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing

/** The Settings tab: the account, then one row per page with its current state. */
@Composable
fun SettingsScreen(core: CoreSource, editor: SettingsEditor, prefs: UiPrefs, contentPadding: PaddingValues, onOpen: (Page) -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val settings = state.settings
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(horizontal = Spacing.screen, vertical = Spacing.l)
                .semantics { heading() },
        )
        RowGroup {
            AccountRow(state.account, onClick = { onOpen(Page.Account) })
        }

        SectionHeader(stringResource(R.string.settings_vpn))
        RowGroup {
            NavRow(stringResource(R.string.settings_connection), { onOpen(Page.Connection) }, supporting = connectionSummary(settings))
            NavRow(stringResource(R.string.settings_dns), { onOpen(Page.Dns) }, supporting = dnsSummary(settings))
            NavRow(stringResource(R.string.settings_split), { onOpen(Page.SplitTunnel) }, supporting = splitSummary(settings))
            SwitchRow(
                title = stringResource(R.string.settings_lan),
                supporting = stringResource(if (state.system.lockdown == true) R.string.settings_lan_lockdown else R.string.settings_lan_detail),
                checked = settings.lanAccess,
                onCheckedChange = { on -> vm.update { it.copy(lanAccess = on) } },
            )
        }

        SectionHeader(stringResource(R.string.settings_kill_switch))
        KillSwitch(state.system)

        SectionHeader(stringResource(R.string.settings_app))
        RowGroup {
            NavRow(stringResource(R.string.settings_appearance), { onOpen(Page.Appearance) }, supporting = stringResource(themeSummary(theme)))
            NavRow(stringResource(R.string.settings_advanced), { onOpen(Page.Advanced) }, supporting = stringResource(R.string.settings_advanced_summary))
            NavRow(
                stringResource(R.string.settings_about),
                { onOpen(Page.About) },
                supporting = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun AccountRow(account: AccountSummary, onClick: () -> Unit) {
    ListRow(
        title = stringResource(R.string.settings_account),
        supporting = accountLine(account),
        icon = Icons.Account,
        iconTint = accountColor(account.state),
        onClick = onClick,
    ) {
        Icon(Icons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Android's always-on VPN and "Block connections without VPN" are the kill switch. Apps cannot
 * turn them on; the row opens the system page. Only the running VPN service can read them, so they
 * are unknown until Ombra connected once.
 */
@Composable
private fun KillSwitch(system: SystemState) {
    val context = LocalContext.current
    RowGroup {
        ListRow(
            title = stringResource(R.string.kill_switch_always_on),
            supporting = stringResource(R.string.kill_switch_always_on_detail),
        ) { OnOffText(system.alwaysOn) }
        ListRow(
            title = stringResource(R.string.kill_switch_lockdown),
            supporting = stringResource(R.string.kill_switch_lockdown_detail),
        ) { OnOffText(system.lockdown) }
        ListRow(
            title = stringResource(R.string.kill_switch_open),
            supporting = stringResource(
                if (system.alwaysOn == null) R.string.kill_switch_unknown else R.string.kill_switch_open_detail,
            ),
            onClick = { openExternal(context, Intent(AndroidSettings.ACTION_VPN_SETTINGS)) },
        ) {
            Icon(Icons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    GroupNote(stringResource(R.string.kill_switch_note))
    if (system.backgroundRestricted) {
        Notice(
            text = stringResource(R.string.battery_hint),
            tone = NoticeTone.Error,
            actionLabel = stringResource(R.string.battery_hint_action),
            onAction = { openExternal(context, appDetailsIntent(context)) },
            modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.m),
        )
    }
}

/** "On", "Off" or "Unknown" at the end of a row. */
@Composable
private fun OnOffText(value: Boolean?) {
    Text(
        stringResource(
            when (value) {
                true -> R.string.onoff_on
                false -> R.string.onoff_off
                null -> R.string.onoff_unknown
            },
        ),
        style = MaterialTheme.typography.labelLarge,
        color = if (value == true) OmbraTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun connectionSummary(settings: Settings): String {
    val protocol = stringResource(protocolName(settings.mode))
    return when {
        settings.mode == TunnelMode.WireguardUdp -> stringResource(R.string.summary_speed, protocol, settings.udpMtu)
        settings.mode == TunnelMode.Quic && settings.padding -> stringResource(R.string.summary_padding, protocol)
        else -> protocol
    }
}

@Composable
private fun dnsSummary(settings: Settings): String = when (val route = settings.dnsRoute()) {
    DnsRoute.Network -> stringResource(R.string.summary_dns_network)
    is DnsRoute.Custom -> pluralStringResource(R.plurals.summary_dns_custom, route.servers.size, route.servers.first(), route.servers.size - 1)
    is DnsRoute.Tunnel ->
        if (route.blockLists == 0) {
            stringResource(R.string.summary_dns_tunnel)
        } else {
            pluralStringResource(R.plurals.summary_dns_blocking, route.blockLists, route.blockLists)
        }
}

@Composable
private fun splitSummary(settings: Settings): String {
    val count = settings.splitAppCount
    return when {
        settings.splitMode == SplitMode.Off -> stringResource(R.string.summary_split_off)
        settings.splitMode == SplitMode.Exclude -> pluralStringResource(R.plurals.summary_split_exclude, count, count)
        count == 0 -> stringResource(R.string.summary_split_include_none)
        else -> pluralStringResource(R.plurals.summary_split_include, count, count)
    }
}
