package app.ombra.ui.connect

import app.ombra.core.model.AccountInfo
import app.ombra.core.model.AccountStatus
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.core.model.TransportKind
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.VpnStatus

val stockholm = OneExit(
    id = "se-sto-001",
    countryCode = "se",
    cityCode = "sto",
    cityName = "Stockholm",
    providerName = "Mullvad",
)
val amsterdam = OneRelay(id = "relay-nl-1", countryCode = "nl", cityCode = "ams", cityName = "Amsterdam")

fun status(vpn: VpnStatus, active: Boolean? = true) = Status(
    version = "v",
    vpnStatus = vpn,
    loggedIn = true,
    account = active?.let { AccountStatus(AccountInfo(active = it)) },
)

fun connected(transport: TransportKind = TransportKind.Quic) = VpnStatus.Connected(
    exit = stockholm,
    relay = amsterdam,
    clientPublicKey = "client",
    exitPublicKey = "exit",
    transport = transport,
)

fun connecting(error: ConnectErrorCode? = null, reconnecting: Boolean = false, args: TunnelArgs = TunnelArgs()) =
    VpnStatus.Connecting(tunnelArgs = args, connectError = error, reconnecting = reconnecting)
