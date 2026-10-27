package app.ombra.vpn

import android.net.VpnService
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus

/** The decisions of OmbraVpnService and the tile, free of Android state so that JVM tests cover them. */
object ServicePolicy {
    /** What one onStartCommand does. */
    sealed interface Start {
        /** Connect; [useStoredArgs] keeps Rust's last tunnel args instead of the settings' ones. */
        data class Connect(val useStoredArgs: Boolean) : Start

        data object Disconnect : Start

        /** A connect without VPN consent: only an activity can ask for it. */
        data object NeedConsent : Start

        /** A restart with nothing to do. */
        data object Stop : Start

        data object Ignore : Start
    }

    /** What a tap on the Quick Settings tile does. */
    enum class TileAction { Connect, Disconnect, OpenApp }

    /** Every start except DISCONNECT (sent with startService) enters the foreground at once. */
    fun needsForeground(action: String?): Boolean = action != OmbraVpnService.ACTION_DISCONNECT

    /**
     * [inForeground] is false when startForeground failed, which for a VPN app means the consent
     * is missing (VPN apps with consent may start it from the background).
     */
    fun start(action: String?, inForeground: Boolean, wanted: Boolean): Start = when (action) {
        OmbraVpnService.ACTION_CONNECT -> connectOrConsent(inForeground, useStoredArgs = false)
        // Always-on start by the system.
        VpnService.SERVICE_INTERFACE -> connectOrConsent(inForeground, useStoredArgs = true)
        // Also under always-on: the user asked for it. Lockdown then blocks traffic until a connect.
        OmbraVpnService.ACTION_DISCONNECT -> Start.Disconnect
        // Sticky restart after the process died; Rust starts inactive, so connect again if wanted.
        null -> if (wanted) connectOrConsent(inForeground, useStoredArgs = true) else Start.Stop
        else -> Start.Ignore
    }

    /**
     * A start that asks for a tunnel. When the core cannot start, the user gets an alert for it, so
     * that an always-on user knows that the VPN does not protect them (OMBRA-SEC-004).
     */
    fun wantsTunnel(start: Start): Boolean = start is Start.Connect || start == Start.NeedConsent

    /**
     * A new Rust init only after a Keystore failure that may pass (OMBRA-SEC-004): the config key is
     * read before Rust starts. Never after a missing library or a Rust error, and never a second
     * initialize of Rust.
     */
    fun retryInit(ready: Boolean, rustInitialized: Boolean, keystoreFailed: Boolean): Boolean =
        keystoreFailed && !ready && !rustInitialized

    /** The service goes once the tunnel is down and nobody wants it. A null status is not known yet. */
    fun shouldStop(status: Status?, wanted: Boolean): Boolean = !wanted && status?.vpnStatus is VpnStatus.Disconnected

    /** Under always-on the system owns the tunnel, so the tile opens the app instead of disconnecting. */
    fun tileAction(view: StatusView): TileAction = when {
        !view.tunnelInUse -> TileAction.Connect
        view.canDisconnect -> TileAction.Disconnect
        else -> TileAction.OpenApp
    }

    private fun connectOrConsent(inForeground: Boolean, useStoredArgs: Boolean): Start =
        if (inForeground) Start.Connect(useStoredArgs) else Start.NeedConsent
}
