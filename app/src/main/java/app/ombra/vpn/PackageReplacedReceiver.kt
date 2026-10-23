package app.ombra.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * An app update kills the :vpn process and removes its services without a sticky restart. The
 * system restarts only an always-on VPN, so without always-on the tunnel would stay off with no
 * notice. This starts OmbraVpnService again when the user wanted the VPN. An always-on start of
 * the system can arrive as well; both send the same connect, so the second does nothing.
 * MY_PACKAGE_REPLACED allows a foreground service start from the background.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!VpnWanted.isSet(context.noBackupFilesDir)) return
        // Never VpnService.prepare() here: with consent on record it makes Ombra the active VPN and
        // tears down another VPN app. Without consent the service posts the "needs your action" alert.
        try {
            context.startForegroundService(OmbraVpnService.restoreIntent(context))
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot restore the VPN after the update: ${e.javaClass.name}")
        }
    }

    private companion object {
        const val TAG = "OmbraUpdate"
    }
}
