package app.ombra.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode

/**
 * Calls [onSplitAppInstalled] when an app of the split-tunnel list is installed, also again after
 * an uninstall. Android resolves the listed apps to UIDs only when it builds the TUN, so until the
 * next build such an app would stay outside an "only these apps" tunnel, or inside an "all except"
 * one.
 */
class PackageMonitor(
    private val context: Context,
    private val settings: () -> Settings,
    private val onSplitAppInstalled: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (needsTunRebuild(intent.data?.schemeSpecificPart, replacing, settings())) onSplitAppInstalled()
        }
    }

    /** Main thread only. */
    fun setEnabled(enabled: Boolean) {
        if (enabled == registered) return
        registered = enabled
        if (enabled) {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            // System broadcasts still arrive; other apps cannot send them to us.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        /** An update keeps the UID, so only a new install counts. */
        fun needsTunRebuild(packageName: String?, replacing: Boolean, settings: Settings): Boolean =
            packageName != null && !replacing && settings.splitMode != SplitMode.Off && packageName in settings.splitApps
    }
}
