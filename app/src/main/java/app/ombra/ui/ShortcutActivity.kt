package app.ombra.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import app.ombra.vpn.OmbraVpnService

/**
 * Invisible target of the static Connect / Disconnect shortcuts (services cannot be shortcut
 * targets), and on Android 11 of the notification's Disconnect action. Not exported: only the
 * launcher and the notification, acting as this app, can start it.
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

    companion object {
        /** Disconnect through this activity: on a locked phone Android asks for the unlock first. */
        fun disconnectIntent(context: Context): Intent = Intent(context, ShortcutActivity::class.java)
            .setAction(OmbraVpnService.ACTION_DISCONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
