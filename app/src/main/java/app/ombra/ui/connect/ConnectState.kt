package app.ombra.ui.connect

import androidx.compose.runtime.Immutable
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.Status
import app.ombra.core.model.TransportKind
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.Settings
import app.ombra.ui.components.Hop

enum class ConnectPhase {
    /** No status from the core yet. */
    Loading,
    Disconnected,
    Connecting,

    /** A live tunnel failed; Rust holds traffic until the new one is up. */
    Reconnecting,
    Connected,
    Disconnecting,
}

/** What the user just asked for, shown until the core status catches up. */
enum class PendingAction { Connect, Disconnect }

/** User-facing names of the tunnel modes. */
enum class Protocol { Stealth, Speed, TcpTls }

@Immutable
data class ConnectUiState(
    val phase: ConnectPhase = ConnectPhase.Loading,
    val entry: Hop = Hop.Automatic,
    val exit: Hop = Hop.Automatic,
    /** In use while connected, else the chosen one. */
    val protocol: Protocol = Protocol.Stealth,
    /** A connect error that lasted long enough to show (see [ConnectErrorGate]). */
    val error: ConnectErrorCode? = null,
    /** The user chose an entry relay: `noRelay` then means that relay is down. */
    val relayChosen: Boolean = false,
    /** Account info says the account has no time left. */
    val accountInactive: Boolean = false,
    /** Only while connected. */
    val details: ConnectionDetails? = null,
    /**
     * Android's always-on VPN is set for Ombra. The VPN service then refuses to disconnect, so the
     * UI offers no Disconnect: the user turns always-on off in Android settings first.
     */
    val alwaysOn: Boolean = false,
) {
    /** The control would disconnect or cancel, which always-on does not allow. */
    val disconnectBlocked: Boolean
        get() = alwaysOn && when (phase) {
            ConnectPhase.Connecting, ConnectPhase.Reconnecting, ConnectPhase.Connected -> true
            ConnectPhase.Loading, ConnectPhase.Disconnected, ConnectPhase.Disconnecting -> false
        }
}

/** The facts of a live tunnel, for the details sheet. Keys and addresses show in a mono font. */
@Immutable
data class ConnectionDetails(
    val relayId: String,
    val relayAddress: String?,
    val relayPorts: List<Int>,
    val exitId: String,
    val provider: String?,
    /** The operator's page that lists its servers and their public keys. */
    val providerUrl: String?,
    val transport: TransportKind,
    val exitPublicKey: String,
    val clientPublicKey: String,
)

/** Maps core state to the Connect screen. Pure, so the rules are unit-tested. */
fun connectUiState(
    status: Status?,
    settings: Settings?,
    exits: List<OneExit>,
    relays: List<OneRelay>,
    pending: PendingAction?,
    error: ConnectErrorCode?,
): ConnectUiState {
    val vpn = status?.vpnStatus
    val chosen = settings ?: Settings()
    val target = (vpn as? VpnStatus.Connecting)?.tunnelArgs ?: chosen.tunnelArgs()
    return ConnectUiState(
        phase = phaseOf(vpn, pending),
        entry = if (vpn is VpnStatus.Connected) Hop(vpn.relay.countryCode, vpn.relay.cityName) else relayHop(target.relay, relays),
        exit = if (vpn is VpnStatus.Connected) {
            Hop(vpn.exit.countryCode, vpn.exit.cityName, vpn.exit.providerName.ifBlank { null })
        } else {
            exitHop(target.exit, exits)
        },
        protocol = protocolOf(vpn, target),
        error = error,
        relayChosen = target.relay != RelaySelector.Any,
        accountInactive = status?.account?.accountInfo?.active == false,
        details = (vpn as? VpnStatus.Connected)?.let(::connectionDetails),
    )
}

fun connectionDetails(vpn: VpnStatus.Connected) = ConnectionDetails(
    relayId = vpn.relay.id,
    relayAddress = vpn.relay.ipV4.ifBlank { null },
    relayPorts = vpn.relay.ports,
    exitId = vpn.exit.id,
    provider = vpn.exit.providerName.ifBlank { null },
    // Only a web page: the URL comes from the server and goes to the browser as a link.
    providerUrl = vpn.exit.providerUrl.takeIf { it.startsWith("https://") },
    transport = vpn.transport,
    exitPublicKey = vpn.exitPublicKey,
    clientPublicKey = vpn.clientPublicKey,
)

/** True once the core status reflects [action], so the optimistic phase can go. */
fun PendingAction.isDone(vpn: VpnStatus?): Boolean = when (this) {
    PendingAction.Connect -> vpn != null && vpn !is VpnStatus.Disconnected
    PendingAction.Disconnect -> vpn is VpnStatus.Disconnected
}

private fun phaseOf(vpn: VpnStatus?, pending: PendingAction?): ConnectPhase = when {
    vpn == null -> ConnectPhase.Loading
    pending == PendingAction.Disconnect && vpn !is VpnStatus.Disconnected -> ConnectPhase.Disconnecting
    vpn is VpnStatus.Connected -> ConnectPhase.Connected
    vpn is VpnStatus.Connecting -> if (vpn.reconnecting) ConnectPhase.Reconnecting else ConnectPhase.Connecting
    pending == PendingAction.Connect -> ConnectPhase.Connecting
    else -> ConnectPhase.Disconnected
}

private fun protocolOf(vpn: VpnStatus?, target: TunnelArgs): Protocol {
    val transport = (vpn as? VpnStatus.Connected)?.transport
    return when (transport) {
        TransportKind.Quic -> Protocol.Stealth
        TransportKind.TcpTls -> Protocol.TcpTls
        TransportKind.WireguardUdp -> Protocol.Speed
        TransportKind.Unknown, null -> when (target.mode) {
            TunnelMode.Quic -> Protocol.Stealth
            TunnelMode.TcpTls -> Protocol.TcpTls
            TunnelMode.WireguardUdp -> Protocol.Speed
        }
    }
}

private fun exitHop(selector: ExitSelector, exits: List<OneExit>): Hop = when (selector) {
    ExitSelector.Any -> Hop.Automatic
    is ExitSelector.Country -> Hop(selector.countryCode)
    is ExitSelector.City -> Hop(
        selector.countryCode,
        exits.firstOrNull { it.countryCode == selector.countryCode && it.cityCode == selector.cityCode }?.cityName
            ?: selector.cityCode.uppercase(),
    )
    is ExitSelector.Exit -> exits.firstOrNull { it.id == selector.id }
        ?.let { Hop(it.countryCode, it.cityName, it.id) }
        ?: Hop(detail = selector.id)
}

private fun relayHop(selector: RelaySelector, relays: List<OneRelay>): Hop = when (selector) {
    RelaySelector.Any -> Hop.Automatic
    is RelaySelector.Country -> Hop(selector.countryCode)
    is RelaySelector.City -> Hop(
        selector.countryCode,
        relays.firstOrNull { it.countryCode == selector.countryCode && it.cityCode == selector.cityCode }?.cityName
            ?: selector.cityCode.uppercase(),
    )
    is RelaySelector.Relay -> relays.firstOrNull { it.id == selector.id }
        ?.let { Hop(it.countryCode, it.cityName, it.id) }
        ?: Hop(detail = selector.id)
}
