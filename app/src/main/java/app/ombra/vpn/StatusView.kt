package app.ombra.vpn

import androidx.annotation.StringRes
import app.ombra.R
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus

/**
 * What the notification and the Quick Settings tile show about the tunnel. Both re-render only
 * when this value changes, so status changes they do not show (account info, traffic) cost nothing.
 */
data class StatusView(
    val phase: Phase,
    /** Only for [Phase.Protected], and only when the user allows details in the notification. */
    val exitCity: String? = null,
    /** The reason for [Phase.Failed], or `noInternet` while (re)connecting. */
    val problem: ConnectErrorCode? = null,
    /** False under always-on: the system owns the connection then. */
    val canDisconnect: Boolean = false,
) {
    enum class Phase { NotProtected, Connecting, Reconnecting, Protected, Failed }

    val tunnelInUse: Boolean get() = phase != Phase.NotProtected

    @get:StringRes
    val titleRes: Int
        get() = when (phase) {
            Phase.NotProtected -> R.string.state_not_protected
            Phase.Connecting -> R.string.state_connecting
            Phase.Reconnecting -> R.string.state_reconnecting
            Phase.Protected -> R.string.state_protected
            Phase.Failed -> R.string.state_cannot_connect
        }

    /** The text under the title for [problem], or null. The exit city is shown separately. */
    @get:StringRes
    val problemRes: Int?
        get() = when (problem) {
            ConnectErrorCode.NoInternet -> R.string.notification_waiting_for_network
            ConnectErrorCode.UdpHandshakeFailed -> R.string.notification_problem_udp_handshake
            ConnectErrorCode.AccountExpired -> R.string.notification_problem_account_expired
            ConnectErrorCode.NoSlotsLeft -> R.string.notification_problem_no_slots
            ConnectErrorCode.InvalidAccountId, ConnectErrorCode.NotLoggedIn -> R.string.notification_problem_login
            ConnectErrorCode.NoLongerSupported -> R.string.notification_problem_update
            else -> null
        }

    companion object {
        /** Errors that Rust's retries cannot fix: the user has to act. */
        private val BLOCKING_ERRORS = setOf(
            ConnectErrorCode.UdpHandshakeFailed,
            ConnectErrorCode.AccountExpired,
            ConnectErrorCode.NoSlotsLeft,
            ConnectErrorCode.InvalidAccountId,
            ConnectErrorCode.NotLoggedIn,
            ConnectErrorCode.NoLongerSupported,
        )

        /** [wanted] shows a fresh connect as connecting before Rust reports it. */
        fun of(status: Status?, wanted: Boolean, hideDetails: Boolean, alwaysOn: Boolean): StatusView =
            when (val vpn = status?.vpnStatus) {
                is VpnStatus.Connected -> StatusView(
                    phase = Phase.Protected,
                    exitCity = vpn.exit.cityName.takeUnless { hideDetails },
                    canDisconnect = !alwaysOn,
                )
                is VpnStatus.Connecting -> connecting(vpn, alwaysOn)
                VpnStatus.Disconnected, null -> if (wanted) {
                    StatusView(Phase.Connecting, canDisconnect = !alwaysOn)
                } else {
                    StatusView(Phase.NotProtected)
                }
            }

        private fun connecting(vpn: VpnStatus.Connecting, alwaysOn: Boolean): StatusView {
            val error = vpn.connectError
            val phase = when {
                error in BLOCKING_ERRORS -> Phase.Failed
                vpn.reconnecting -> Phase.Reconnecting
                else -> Phase.Connecting
            }
            // Other errors are usually transient; the app shows them when they last.
            val problem = error.takeIf { phase == Phase.Failed || it == ConnectErrorCode.NoInternet }
            return StatusView(phase, problem = problem, canDisconnect = !alwaysOn)
        }
    }
}
