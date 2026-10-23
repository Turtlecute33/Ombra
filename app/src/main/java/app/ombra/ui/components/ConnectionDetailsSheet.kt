package app.ombra.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ombra.R
import app.ombra.core.model.TransportKind
import app.ombra.ui.connect.ConnectionDetails
import app.ombra.ui.openLink
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/**
 * The facts of the live tunnel. The exit key can be compared with the key that the exit operator
 * publishes: the link opens the operator's page in the browser, Ombra fetches nothing itself.
 */
@Composable
fun ConnectionDetailsSheet(details: ConnectionDetails, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Sheet(title = stringResource(R.string.stats_details), onDismiss = onDismiss) {
        // Keys and addresses can be selected and copied.
        SelectionContainer {
            Column {
                DetailRow(stringResource(R.string.details_relay), details.relayId)
                details.relayAddress?.let { address ->
                    val ports = details.relayPorts
                    DetailRow(
                        stringResource(R.string.details_relay_address),
                        if (ports.isEmpty()) {
                            address
                        } else {
                            pluralStringResource(R.plurals.details_address_ports, ports.size, address, ports.joinToString(", "))
                        },
                    )
                }
                DetailRow(stringResource(R.string.details_exit), details.exitId)
                details.provider?.let { DetailRow(stringResource(R.string.details_provider), it, mono = false) }
                transportLabel(details.transport)?.let {
                    DetailRow(stringResource(R.string.details_transport), stringResource(it), mono = false)
                }
                DetailRow(stringResource(R.string.details_exit_key), details.exitPublicKey)
                DetailRow(stringResource(R.string.details_client_key), details.clientPublicKey)
            }
        }
        details.providerUrl?.let { url ->
            TextButton(onClick = { openLink(context, url) }, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                Text(stringResource(R.string.details_verify_key, details.provider ?: stringResource(R.string.details_provider_fallback)))
                Icon(Icons.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = Spacing.s).size(18.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = true) {
    ListRow(title = label) {
        Text(
            value,
            style = if (mono) OmbraTextStyles.mono else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.4f),
        )
    }
}

private fun transportLabel(transport: TransportKind): Int? = when (transport) {
    TransportKind.Quic -> R.string.transport_quic
    TransportKind.TcpTls -> R.string.transport_tcp_tls
    TransportKind.WireguardUdp -> R.string.transport_udp
    TransportKind.Unknown -> null
}
