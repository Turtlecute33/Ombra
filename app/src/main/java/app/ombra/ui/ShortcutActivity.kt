package app.ombra.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import app.ombra.vpn.OmbraVpnService

/**
 * Invisible target of the static Connect / Disconnect shortcuts (services cannot be shortcut
 * targets). Not exported: only the launcher, acting as this app, can start it.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            OmbraVpnService.ACTION_CONNECT -> if (hasVpnConsent()) {
                startForegroundService(OmbraVpnService.connectIntent(this))
            } else {
                // Consent needs a visible activity.
                startActivity(MainActivity.connectIntent(this))
            }
            OmbraVpnService.ACTION_DISCONNECT -> startService(OmbraVpnService.disconnectIntent(this))
        }
        // Theme.NoDisplay requires finish() before onResume.
        finish()
    }

    private fun hasVpnConsent(): Boolean = try {
        VpnService.prepare(this) == null
    } catch (e: IllegalStateException) {
        false
    }
}
