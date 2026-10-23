package app.ombra.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.ombra.data.StartProblem
import app.ombra.data.TunnelControl
import app.ombra.data.UiPrefs
import app.ombra.vpn.OmbraVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects through OmbraVpnService after the consents it needs: the notification permission
 * (asked once; without it the VPN works but its notification stays hidden), then the system VPN
 * dialog. Create it in onCreate: it registers activity result launchers.
 */
class VpnConsent(private val activity: ComponentActivity, private val prefs: UiPrefs) : TunnelControl {
    private val problemState = MutableStateFlow<StartProblem?>(null)
    override val problem: StateFlow<StartProblem?> = problemState.asStateFlow()

    private val vpnDialog = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) start() else problemState.value = StartProblem.ConsentDenied
    }

    private val notificationPrompt = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askVpnConsent()
    }

    override fun connect() {
        problemState.value = null
        val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted || prefs.notificationsAsked) {
            askVpnConsent()
        } else {
            prefs.notificationsAsked = true
            notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun disconnect() {
        problemState.value = null
        activity.startService(OmbraVpnService.disconnectIntent(activity))
    }

    private fun askVpnConsent() {
        val dialog = try {
            VpnService.prepare(activity)
        } catch (e: IllegalStateException) {
            // Thrown when a legacy VPN profile is the always-on VPN.
            problemState.value = StartProblem.OtherAlwaysOnVpn
            return
        }
        if (dialog != null) vpnDialog.launch(dialog) else start()
    }

    private fun start() {
        activity.startForegroundService(OmbraVpnService.connectIntent(activity))
    }
}
