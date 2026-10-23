package app.ombra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.UiPrefs
import app.ombra.ipc.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the app shows at the top level. */
enum class AppStage {
    /** No status from the core yet. */
    Loading,

    /** Logged out, or a new account whose number the user has not confirmed yet. */
    Onboarding,
    Main,
}

fun stageOf(status: Status?): AppStage = when {
    status == null -> AppStage.Loading
    !status.isLoggedIn || status.inNewAccountFlow -> AppStage.Onboarding
    else -> AppStage.Main
}

/** True when a fresh launch should connect: auto-connect is on and the tunnel is down. */
fun shouldAutoConnect(status: Status, settings: Settings): Boolean =
    stageOf(status) == AppStage.Main && settings.autoConnectOnLaunch && status.vpnStatus is VpnStatus.Disconnected

/** Waits for the first status and settings, up to [wait]; false when they do not come. */
suspend fun CoreSource.shouldAutoConnectOnLaunch(wait: Duration): Boolean = withTimeoutOrNull(wait) {
    combine(status.filterNotNull(), settings.filterNotNull(), ::shouldAutoConnect).first()
} ?: false

/**
 * Owns what lives as long as the activity's ViewModelStore: the core connection (real or, in
 * debug builds, a demo), the settings editor and the UI preferences, so configuration changes
 * keep one instance of each.
 */
class AppViewModel(val core: CoreSource, val prefs: UiPrefs, val isDemo: Boolean = false) : ViewModel() {
    val editor = SettingsEditor(core, viewModelScope)

    val stage: StateFlow<AppStage> = core.status
        .map(::stageOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, stageOf(core.status.value))

    suspend fun autoConnectOnLaunch(): Boolean = core.shouldAutoConnectOnLaunch(AUTO_CONNECT_WAIT)

    private companion object {
        val AUTO_CONNECT_WAIT = 10.seconds
    }
}
