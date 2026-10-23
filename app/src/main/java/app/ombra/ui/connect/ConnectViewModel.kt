package app.ombra.ui.connect

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.VpnStatus
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.trafficStats
import app.ombra.ipc.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModel(
    private val core: CoreSource,
    private val editor: SettingsEditor,
    now: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val pending = MutableStateFlow<PendingAction?>(null)
    private var pendingTimeout: Job? = null

    private val shownError = core.status
        .map { (it?.vpnStatus as? VpnStatus.Connecting)?.connectError }
        .gated(now)

    private val lists = combine(core.exits, core.relays) { exits, relays ->
        exits?.value?.exits.orEmpty() to relays?.value.orEmpty()
    }

    val state: StateFlow<ConnectUiState> =
        combine(core.status, editor.settings, lists, pending, shownError) { status, settings, (exits, relays), pending, error ->
            connectUiState(status, settings, exits, relays, pending, error)
        }.combine(core.system) { state, system -> state.copy(alwaysOn = system?.alwaysOn == true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectUiState())

    /** Polls the core at 1 Hz while connected and while someone collects (the screen is visible). */
    val stats: StateFlow<LiveStats?> = core.status
        .map { it?.vpnStatus is VpnStatus.Connected }
        .distinctUntilChanged()
        .flatMapLatest { connected -> if (connected) pollStats() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    init {
        viewModelScope.launch {
            core.status.collect { status ->
                if (pending.value?.isDone(status?.vpnStatus) == true) clearPending()
            }
        }
    }

    /** Call when the user asks to connect, before the consent flow. */
    fun onConnectRequested() = setPending(PendingAction.Connect)

    fun onDisconnectRequested() = setPending(PendingAction.Disconnect)

    /** The start failed before it reached the VPN service (consent denied, another always-on VPN). */
    fun onStartFailed() = clearPending()

    fun apply(fix: ErrorFix) {
        val change: (Settings) -> Settings = when (fix) {
            ErrorFix.SwitchToStealth -> { s -> s.copy(mode = TunnelMode.Quic) }
            ErrorFix.UseTcpTls -> { s -> s.copy(mode = TunnelMode.TcpTls) }
            ErrorFix.UseFastestRelay -> { s -> s.copy(relay = RelaySelector.Any) }
            ErrorFix.OpenAccount -> return
        }
        viewModelScope.launch {
            try {
                editor.update(change)
            } catch (e: CoreRequestException) {
                Log.w(TAG, "settings change failed: ${e.code}")
            }
        }
    }

    private fun setPending(action: PendingAction) {
        pending.value = action
        pendingTimeout?.cancel()
        // The service may never answer (killed, refused); do not show the optimistic phase forever.
        pendingTimeout = viewModelScope.launch {
            delay(PENDING_TIMEOUT_MS)
            pending.update { if (it == action) null else it }
        }
    }

    private fun clearPending() {
        pendingTimeout?.cancel()
        pending.value = null
    }

    private fun pollStats(): Flow<LiveStats?> = flow {
        val meter = TrafficMeter()
        while (true) {
            val sample = try {
                core.trafficStats()
            } catch (e: CoreRequestException) {
                null
            }
            if (sample != null) emit(meter.next(sample))
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "OmbraConnect"
        const val POLL_INTERVAL_MS = 1_000L
        const val PENDING_TIMEOUT_MS = 20_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
