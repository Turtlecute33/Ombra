package app.ombra.data

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.SystemState
import kotlinx.coroutines.flow.StateFlow

/**
 * The core as the UI sees it: pushed state plus app-level [Request]s. [CoreClient] talks to the
 * :vpn process; debug builds can play scripted states instead (DemoCoreClient).
 */
interface CoreSource {
    /** True while requests can reach the core. */
    val connected: StateFlow<Boolean>

    /** Sanitized: accountId is null, use [Status.isLoggedIn]. Null until the first push. */
    val status: StateFlow<Status?>
    val exits: StateFlow<CachedValue<ExitList>?>

    /** Relay certificates are blank on this side. */
    val relays: StateFlow<CachedValue<List<OneRelay>>?>
    val settings: StateFlow<Settings?>
    val system: StateFlow<SystemState?>

    /** Call from the UI's onStart. */
    fun bind()

    /** Call from the UI's onStop. */
    fun unbind()

    /** Sends [request] and returns its Ok JSON. Throws [CoreRequestException]. */
    suspend fun request(request: Request): String
}

/** Starts and stops the tunnel; the real one asks for VPN consent first. */
interface TunnelControl {
    /** Why the last start failed before it reached the VPN service; null after a new attempt. */
    val problem: StateFlow<StartProblem?>

    fun connect()

    fun disconnect()
}

enum class StartProblem {
    /** The user declined the system VPN dialog. */
    ConsentDenied,

    /** Another app (or a legacy profile) is the always-on VPN, so Android shows no dialog. */
    OtherAlwaysOnVpn,
}
