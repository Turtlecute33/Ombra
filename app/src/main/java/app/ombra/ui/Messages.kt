package app.ombra.ui

import androidx.annotation.StringRes
import app.ombra.R
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.ManagerCmdErrorCode
import app.ombra.data.StartProblem
import app.ombra.ipc.IpcError
import app.ombra.ui.connect.ConnectUiState
import app.ombra.ui.connect.ErrorFix

/** Text for a failed request: a Rust ManagerCmdErrorCode or an [IpcError] code. */
@StringRes
fun requestErrorText(code: String): Int = when (code) {
    IpcError.TIMEOUT -> R.string.request_error_timeout
    IpcError.DISCONNECTED, IpcError.CORE_NOT_READY -> R.string.request_error_core
    IpcError.INVALID_ACCOUNT_NUMBER -> R.string.request_error_invalid_account
    IpcError.NOT_ALLOWED -> R.string.request_error_not_allowed
    else -> when (ManagerCmdErrorCode.fromWire(code)) {
        ManagerCmdErrorCode.ApiUnreachable -> R.string.request_error_unreachable
        ManagerCmdErrorCode.ApiRateLimitExceeded -> R.string.request_error_rate_limit
        ManagerCmdErrorCode.ApiSignupLimitExceeded -> R.string.request_error_signup_limit
        ManagerCmdErrorCode.ApiError -> R.string.request_error_api
        ManagerCmdErrorCode.ApiInvalidAccountId -> R.string.request_error_invalid_account
        ManagerCmdErrorCode.ApiNoLongerSupported -> R.string.request_error_too_old
        ManagerCmdErrorCode.ConfigSaveError -> R.string.request_error_save
        else -> R.string.request_error_other
    }
}

/** Text for a connect error; `noRelay` depends on whether the user chose the entry relay. */
@StringRes
fun connectErrorText(error: ConnectErrorCode, state: ConnectUiState): Int = when (error) {
    ConnectErrorCode.AccountExpired -> R.string.error_account_expired
    ConnectErrorCode.ApiError -> R.string.error_api
    ConnectErrorCode.ApiRateLimitExceeded -> R.string.error_api_rate_limit
    ConnectErrorCode.ApiUnreachable -> R.string.error_api_unreachable
    ConnectErrorCode.InvalidAccountId -> R.string.error_invalid_account
    ConnectErrorCode.NoInternet -> R.string.error_no_internet
    ConnectErrorCode.NoLongerSupported -> R.string.error_no_longer_supported
    ConnectErrorCode.NoRelay -> if (state.relayChosen) R.string.error_no_relay_chosen else R.string.error_no_relay
    ConnectErrorCode.NoSlotsLeft -> R.string.error_no_slots
    ConnectErrorCode.NotLoggedIn -> R.string.error_not_logged_in
    ConnectErrorCode.UdpHandshakeFailed -> R.string.error_udp_handshake
    ConnectErrorCode.Other, ConnectErrorCode.Unknown -> R.string.error_other
}

@StringRes
fun fixLabel(fix: ErrorFix): Int = when (fix) {
    ErrorFix.SwitchToStealth -> R.string.fix_switch_to_stealth
    ErrorFix.UseTcpTls -> R.string.fix_use_tcp_tls
    ErrorFix.UseFastestRelay -> R.string.fix_use_fastest_relay
    ErrorFix.OpenAccount -> R.string.fix_open_account
}

@StringRes
fun startProblemText(problem: StartProblem): Int = when (problem) {
    StartProblem.ConsentDenied -> R.string.start_consent_denied
    StartProblem.OtherAlwaysOnVpn -> R.string.start_other_always_on
}
