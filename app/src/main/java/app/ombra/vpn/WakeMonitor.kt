package app.ombra.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Calls [onWake] when the screen turns on or Doze ends. Rust then probes the tunnel at once and
 * finds a tunnel that died during sleep after one lost probe instead of several.
 */
class WakeMonitor(private val context: Context, private val onWake: () -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isWake(intent.action, power.isDeviceIdleMode)) onWake()
        }
    }

    /** Main thread only. */
    fun setEnabled(enabled: Boolean) {
        if (enabled == registered) return
        registered = enabled
        if (enabled) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) }
            // System broadcasts still arrive; other apps cannot send them to us.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        fun isWake(action: String?, deviceIdle: Boolean): Boolean = when (action) {
            Intent.ACTION_SCREEN_ON -> true
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> !deviceIdle
            else -> false
        }
    }
}
