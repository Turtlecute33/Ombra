package app.ombra.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ui.components.RadioRow
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing
import kotlin.math.roundToInt

/** Protocol, the Stealth and Speed options, and auto-connect. Each change applies at once. */
@Composable
fun ConnectionScreen(core: CoreSource, editor: SettingsEditor, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    SettingsPage(stringResource(R.string.settings_connection), onBack) {
        SectionHeader(stringResource(R.string.connection_protocol))
        RowGroup(Modifier.selectableGroup()) {
            PROTOCOLS.forEach { (mode, detail) ->
                RadioRow(
                    title = stringResource(protocolName(mode)),
                    supporting = stringResource(detail),
                    selected = settings.mode == mode,
                    onClick = { vm.update { it.copy(mode = mode) } },
                )
            }
        }
        GroupNote(stringResource(R.string.connection_protocol_note))

        if (settings.mode == TunnelMode.WireguardUdp) {
            SectionHeader(stringResource(R.string.connection_speed_options))
            RowGroup { UdpMtuRow(settings.udpMtu) { mtu -> vm.update { it.withUdpMtu(mtu) } } }
        }

        val stealth = settings.mode == TunnelMode.Quic
        SectionHeader(stringResource(R.string.connection_stealth_options))
        RowGroup {
            SwitchRow(
                title = stringResource(R.string.connection_padding),
                supporting = stringResource(R.string.connection_padding_detail),
                checked = settings.padding,
                enabled = stealth,
                onCheckedChange = { on -> vm.update { it.copy(padding = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.connection_small_mtu),
                supporting = stringResource(R.string.connection_small_mtu_detail),
                checked = settings.forceSmallMtu,
                enabled = stealth,
                onCheckedChange = { on -> vm.update { it.copy(forceSmallMtu = on) } },
            )
        }
        GroupNote(stringResource(if (stealth) R.string.connection_stealth_note else R.string.connection_stealth_only))

        SectionHeader(stringResource(R.string.connection_start))
        RowGroup {
            SwitchRow(
                title = stringResource(R.string.settings_auto_connect),
                supporting = stringResource(R.string.settings_auto_connect_detail),
                checked = settings.autoConnectOnLaunch,
                onCheckedChange = { on -> vm.update { it.copy(autoConnectOnLaunch = on) } },
            )
        }
    }
}

/** The tunnel MTU of Speed mode. It is stored when the finger lifts, not at every step. */
@Composable
private fun UdpMtuRow(stored: Int, onChange: (Int) -> Unit) {
    val slider = remember(stored) {
        SliderState(
            stored.toFloat(),
            (TunnelArgs.UDP_MTU_MAX - TunnelArgs.UDP_MTU_MIN) / UDP_MTU_STEP - 1,
            TunnelArgs.UDP_MTU_MIN.toFloat()..TunnelArgs.UDP_MTU_MAX.toFloat(),
        )
    }
    val shown = snapUdpMtu(slider.value.roundToInt())
    val valueText = pluralStringResource(R.plurals.connection_mtu_value, shown, shown)
    Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Row {
            Text(stringResource(R.string.connection_mtu), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = OmbraTextStyles.mono, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            stringResource(R.string.connection_mtu_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            state = slider,
            onValueChange = { slider.value = it },
            // Read the state here: the last drag step may not have recomposed yet.
            onValueChangeFinished = { snapUdpMtu(slider.value.roundToInt()).let { if (it != stored) onChange(it) } },
            modifier = Modifier.semantics { stateDescription = valueText },
        )
    }
}

/** Each protocol with its honest trade-off. */
private val PROTOCOLS = listOf(
    TunnelMode.Quic to R.string.connection_stealth_detail,
    TunnelMode.WireguardUdp to R.string.connection_speed_detail,
    TunnelMode.TcpTls to R.string.connection_tcp_detail,
)
