package app.ombra.vpn

import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.ombra.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings toggle. An active tile in :vpn next to the state it shows: the system binds it
 * only for a click or after [requestUpdate].
 */
class QuickTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onCreate() {
        super.onCreate()
        CoreHost.init(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { CoreHost.statusView.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (!CoreHost.isReady) return
        // Always-on can change in Settings while nothing here watches it, so read it again now.
        CoreHost.refreshSystemState()
        val view = CoreHost.statusView.value.copy(canDisconnect = CoreHost.systemState.value.alwaysOn != true)
        when (ServicePolicy.tileAction(view)) {
            ServicePolicy.TileAction.Connect -> connect()
            // Only the owner of a locked phone may turn the protection off.
            ServicePolicy.TileAction.Disconnect -> if (isSecure) unlockAndRun(::disconnect) else disconnect()
            ServicePolicy.TileAction.OpenApp -> startActivityAndCollapse(VpnNotifications.openApp(this))
        }
    }

    private fun connect() {
        if (hasVpnConsent()) {
            startForegroundService(OmbraVpnService.connectIntent(this))
        } else {
            // Consent can only be given in an activity.
            startActivityAndCollapse(VpnNotifications.openApp(this, OmbraVpnService.ACTION_CONNECT))
        }
    }

    private fun disconnect() {
        startService(OmbraVpnService.disconnectIntent(this))
    }

    /** Only before a connect: with consent on record, prepare() makes Ombra the active VPN. */
    private fun hasVpnConsent(): Boolean = try {
        VpnService.prepare(this) == null
    } catch (e: IllegalStateException) {
        // A legacy VPN is always-on; the app explains it.
        false
    }

    private fun render(view: StatusView) {
        val tile = qsTile ?: return
        tile.state = when {
            !CoreHost.isReady -> Tile.STATE_UNAVAILABLE
            view.tunnelInUse -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(view.titleRes)
        // Quick Settings on the lock screen shows the tile too; the notification hides the city there.
        tile.subtitle = view.exitCity.takeUnless { isLocked } ?: getString(R.string.app_name)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "OmbraTile"

        /** Asks the system to bind the tile so that it shows the current state. */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, QuickTileService::class.java))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "tile update refused")
            }
        }
    }
}
