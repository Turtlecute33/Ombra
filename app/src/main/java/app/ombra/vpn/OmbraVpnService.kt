package app.ombra.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.util.Log
import app.ombra.core.ffi.Callbacks
import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.ffi.RustFfi
import app.ombra.core.model.CoreCommands
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The VPN (foreground, type systemExempted, :vpn process). Started to connect by the UI, the tile,
 * a shortcut or the system (always-on); it stops itself once the tunnel is down and not wanted.
 * While it exists it builds the TUN for Rust, reports the physical network and wakes Rust after
 * sleep. Lifecycle methods and the state collectors run on the main thread.
 */
class OmbraVpnService : VpnService(), Callbacks.NetworkConfigHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var wakeMonitor: WakeMonitor
    private lateinit var packageMonitor: PackageMonitor
    private lateinit var tun: TunConfigurator
    private var inForeground = false
    private var observing = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        CoreHost.init(applicationContext)
        VpnNotifications.createChannels(this)
        networkMonitor = NetworkMonitor(this)
        wakeMonitor = WakeMonitor(this) {
            RustFfi.wake()
            // Always-on can change in Settings without a start of this service.
            CoreHost.refreshSystemState()
        }
        packageMonitor = PackageMonitor(this, { CoreHost.settingsStore.current }) {
            CoreHost.scope.launch { CoreHost.controller.refreshTun() }
        }
        tun = TunConfigurator(this, { CoreHost.settingsStore.current }, { networkMonitor.current })
        Callbacks.setNetworkConfigHandler(this)
        CoreHost.attachVpnService(this)
        if (CoreHost.isReady) networkMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        if (ServicePolicy.needsForeground(action) && !inForeground) inForeground = enterForeground()
        if (!CoreHost.isReady) {
            Log.e(TAG, "Rust core unavailable: ${CoreHost.initError}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A system start can mean that always-on or lockdown changed; a disconnect needs the current value.
        CoreHost.refreshSystemState()
        val alwaysOn = CoreHost.systemState.value.alwaysOn == true
        when (val start = ServicePolicy.start(action, inForeground, CoreHost.vpnWanted, alwaysOn)) {
            is ServicePolicy.Start.Connect -> connect(if (start.useStoredArgs) null else CoreHost.settingsStore.current.tunnelArgs())
            ServicePolicy.Start.Disconnect -> disconnect()
            ServicePolicy.Start.NeedConsent -> {
                needConsent(startId)
                return START_NOT_STICKY
            }
            ServicePolicy.Start.Stop -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ServicePolicy.Start.Ignore -> Log.w(TAG, "start ignored: $action")
        }
        // An earlier stop is refused while this start is pending, so check again now.
        if (observing) stopIfIdle() else observe()
        return START_STICKY
    }

    /** Runs on a binder thread. Another VPN took over or the user revoked consent: the TUN is gone. */
    override fun onRevoke() {
        scope.launch {
            disconnect()
            leaveForeground()
            stopSelf()
        }
    }

    override fun onDestroy() {
        Callbacks.clearNetworkConfigHandler(this)
        CoreHost.detachVpnService(this)
        networkMonitor.stop()
        wakeMonitor.setEnabled(false)
        packageMonitor.setEnabled(false)
        scope.cancel()
        if (CoreHost.isReady) {
            CoreHost.vpnWanted = false
            // No VpnService can own the TUN after this point.
            if (CoreHost.status.value?.vpnStatus !is VpnStatus.Disconnected) launchCore { CoreBridge.call(CoreCommands.disconnect()) }
        }
        super.onDestroy()
    }

    override fun setNetworkConfig(json: String): Int = tun.apply(json)

    /**
     * systemExempted needs the VPN consent, so this fails without it. Never VpnService.prepare()
     * here: with consent on record it makes Ombra the active VPN and tears down another VPN app.
     */
    private fun enterForeground(): Boolean = try {
        // Every start that enters the foreground is about a wanted tunnel.
        val view = StatusView.of(
            CoreHost.status.value,
            wanted = true,
            hideDetails = CoreHost.settingsStore.current.hideNotificationDetails,
            alwaysOn = CoreHost.systemState.value.alwaysOn == true,
        )
        startForeground(VpnNotifications.STATUS_ID, VpnNotifications.status(this, view), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        true
    } catch (e: RuntimeException) {
        // SecurityException without consent, ForegroundServiceStartNotAllowedException from the background.
        Log.e(TAG, "cannot enter the foreground: ${e.javaClass.name}")
        false
    }

    private fun leaveForeground() {
        // After this no update may post the notification again: it would stay without a service.
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun observe() {
        observing = true
        scope.launch { merge(CoreHost.status, CoreHost.vpnWantedFlow).collect { stopIfIdle() } }
        scope.launch { CoreHost.statusView.collect(::showStatus) }
        scope.launch {
            CoreHost.vpnWantedFlow.collect {
                wakeMonitor.setEnabled(it)
                packageMonitor.setEnabled(it)
            }
        }
    }

    /** Reads the current values, not the ones that triggered it: a connect sets "wanted" first. */
    private fun stopIfIdle() {
        if (!ServicePolicy.shouldStop(CoreHost.status.value, CoreHost.vpnWanted)) return
        // False while a newer start is pending; that start checks again.
        if (stopSelfResult(lastStartId)) leaveForeground()
    }

    private fun showStatus(view: StatusView) {
        if (inForeground) VpnNotifications.update(this, VpnNotifications.status(this, view))
    }

    /** [args] null keeps the stored tunnel args (always-on, restart). */
    private fun connect(args: TunnelArgs?) {
        CoreHost.vpnWanted = true
        launchCore {
            try {
                CoreBridge.call(CoreCommands.connect(args))
            } catch (e: ErrorCodeException) {
                // Rust stays disconnected, so the service would wait for nothing.
                CoreHost.vpnWanted = false
                throw e
            }
        }
    }

    private fun disconnect() {
        CoreHost.vpnWanted = false
        launchCore { CoreBridge.call(CoreCommands.disconnect()) }
    }

    private fun needConsent(startId: Int) {
        CoreHost.vpnWanted = false
        VpnNotifications.showPermissionNeeded(this)
        stopSelf(startId)
    }

    /**
     * Core commands must finish even if the service goes away meanwhile. Undispatched, so each
     * command reaches Rust on this thread in start order (a quick disconnect and connect must not
     * swap); the wait for its result then moves off this thread.
     */
    private fun launchCore(block: suspend () -> Unit) {
        CoreHost.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { block() }
            } catch (e: ErrorCodeException) {
                Log.w(TAG, "core command failed: ${e.errorCode}")
            }
        }
    }

    companion object {
        private const val TAG = "OmbraVpnService"
        const val ACTION_CONNECT = "app.ombra.action.CONNECT"
        const val ACTION_DISCONNECT = "app.ombra.action.DISCONNECT"

        /** Start with startForegroundService, and only after VpnService.prepare() returned null. */
        fun connectIntent(context: Context): Intent = Intent(context, OmbraVpnService::class.java).setAction(ACTION_CONNECT)

        /** Start with startService. */
        fun disconnectIntent(context: Context): Intent = Intent(context, OmbraVpnService::class.java).setAction(ACTION_DISCONNECT)

        /**
         * No action, like a sticky restart: connects with the stored args when wanted, else stops.
         * Start with startForegroundService.
         */
        fun restoreIntent(context: Context): Intent = Intent(context, OmbraVpnService::class.java)
    }
}
