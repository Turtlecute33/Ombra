package app.ombra.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.SystemState
import app.ombra.ui.connect.ConnectionDetails
import app.ombra.ui.connect.connectionDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Settings pages show. Settings are the editor's: a change shows before :vpn confirms it. */
@Immutable
data class SettingsUiState(
    val settings: Settings = Settings(),
    val system: SystemState = SystemState(),
    val account: AccountSummary = AccountSummary.Unknown,
    /** The live tunnel; null when not connected. */
    val tunnel: ConnectionDetails? = null,
    /** The exit in use is run by Mullvad, so DNS block lists work; null when not connected. */
    val mullvadExit: Boolean? = null,
)

fun settingsUiState(settings: Settings?, system: SystemState?, status: Status?, nowSeconds: Long): SettingsUiState {
    val connected = status?.vpnStatus as? VpnStatus.Connected
    return SettingsUiState(
        settings = settings ?: Settings(),
        system = system ?: SystemState(),
        account = accountSummary(status?.account, nowSeconds),
        tunnel = connected?.let(::connectionDetails),
        mullvadExit = connected?.let { it.exit.providerName == MULLVAD_PROVIDER_NAME },
    )
}

/**
 * The Settings pages: each change applies at once through the [SettingsEditor]. One instance per
 * page; they all read the same core state.
 */
class SettingsViewModel(
    private val core: CoreSource,
    private val editor: SettingsEditor,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(editor.settings, core.system, core.status) { settings, system, status ->
        settingsUiState(settings, system, status, nowSeconds())
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        settingsUiState(editor.settings.value, core.system.value, core.status.value, nowSeconds()),
    )

    /** Code of the last failed request (see [app.ombra.ui.requestErrorText]); the screen shows it once. */
    var errorCode by mutableStateOf<String?>(null)
        private set

    /** True for a moment after a new WireGuard key was made. */
    var keyRotated by mutableStateOf(false)
        private set

    var rotatingKey by mutableStateOf(false)
        private set

    fun update(transform: (Settings) -> Settings) = launchRequest { editor.update(transform) }

    /** The new key is used from the next connect. */
    fun rotateKey() {
        if (rotatingKey) return
        rotatingKey = true
        launchRequest {
            try {
                core.request(Request.RotateWgKey)
            } finally {
                rotatingKey = false
            }
            keyRotated = true
            delay(ROTATED_SHOWN_MS)
            keyRotated = false
        }
    }

    fun errorShown() {
        errorCode = null
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            errorCode = null
            try {
                block()
            } catch (e: CoreRequestException) {
                errorCode = e.code
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val ROTATED_SHOWN_MS = 2_000L
    }
}
