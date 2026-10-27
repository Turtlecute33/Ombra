package app.ombra.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.system.Os
import android.util.Log
import app.ombra.BuildConfig
import app.ombra.core.ffi.Callbacks
import app.ombra.core.ffi.RustFfi
import app.ombra.core.model.BundleInfo
import app.ombra.core.model.Status
import app.ombra.ipc.SystemState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors

/**
 * Owner of the Rust core in the :vpn process. [init] loads and initializes Rust once and starts the
 * [CoreController]; every :vpn component calls it in onCreate and then uses the members below.
 */
object CoreHost {
    private const val TAG = "OmbraCoreHost"
    private const val QUIET_RUST_LOG = "obscuravpn_client=warn,obscuravpn_api=warn,quinn=warn,quinn_proto=warn,neptun=warn"
    private const val DIAGNOSTICS_RUST_LOG = "info"

    /** Where Rust persists its logs (rustlib android/ffi.rs: config dir + "rust-log"). */
    private const val RUST_LOG_DIR = "core/rust-log"

    /** Process-lifetime scope: work here finishes even when the component that started it goes away. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "core task failed: ${t.javaClass.name}")
        },
    )

    /**
     * The one thread that makes outgoing binder calls (replies and pushes): oneway calls never run on
     * a binder or Rust thread, and RemoteCallbackList broadcasts never overlap.
     */
    val ipcDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "ombra-ipc") }.asCoroutineDispatcher()
    }

    lateinit var controller: CoreController
        private set
    lateinit var requests: RequestHandler
        private set
    lateinit var feed: StateFeed
        private set

    private lateinit var appContext: Context
    private lateinit var wgKeyVault: WgKeyVault
    private lateinit var configKeyVault: ConfigKeyVault
    private lateinit var wanted: VpnWanted
    private var initialized = false

    private val systemStateFlow = MutableStateFlow(SystemState())

    /** Android-side facts for the UI. OmbraVpnService fills in the VPN and network part. */
    val systemState: StateFlow<SystemState> = systemStateFlow.asStateFlow()

    /** OmbraVpnService while it exists: only the running service can read the always-on state. */
    private val vpnService = MutableStateFlow<VpnService?>(null)

    /** What the notification and the Quick Settings tile show. */
    lateinit var statusView: StateFlow<StatusView>
        private set

    /** True once Rust is initialized; false forever in this process if [initError] is set. */
    @Volatile
    var isReady: Boolean = false
        private set

    /** Class name of the init failure (for example UnsatisfiedLinkError when the .so is missing). */
    @Volatile
    var initError: String? = null
        private set

    /** Unsanitized: never send it out of this process without StatusSanitizer. */
    val status: StateFlow<Status?> get() = controller.status

    val settingsStore: SettingsStore get() = controller.settingsStore

    var vpnWanted: Boolean
        get() = wanted.value
        set(value) {
            wanted.value = value
        }

    val vpnWantedFlow: StateFlow<Boolean> get() = wanted.flow

    /** Idempotent. Call on the main thread from any :vpn component's onCreate. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val dir = appContext.noBackupFilesDir
        wgKeyVault = WgKeyVault(dir)
        configKeyVault = ConfigKeyVault(dir)
        wanted = VpnWanted(File(dir, VpnWanted.FILE_NAME))
        controller = CoreController(CoreBridge, SettingsStore(dir), wanted, scope)
        val bundleInfo = BundleInfo(appVersion = BuildConfig.VERSION_NAME, androidSdk = Build.VERSION.SDK_INT)
        requests = RequestHandler(
            controller = controller,
            core = CoreBridge,
            debugBundles = DebugBundles(appContext.cacheDir, bundleInfo, CoreBridge),
            allowRawCommands = BuildConfig.DEBUG,
            deleteWgKey = wgKeyVault::delete,
            // As when Diagnostics goes off: the log starts again only in a new process.
            forgetLogs = {
                if (isReady) RustFfi.flushAndStopPersistedLog()
                deleteRustLogs(dir)
            },
        )
        feed = StateFeed(controller.status, controller.exits, controller.relays, settingsStore.settings, systemState)
        statusView = combine(status, wanted.flow, vpnService, settingsStore.settings, systemState) { current, isWanted, service, settings, system ->
            // "Wanted" without a running service is stale, for example after a force stop.
            StatusView.of(current, isWanted && service != null, settings.hideNotificationDetails, system.alwaysOn == true)
        }.stateIn(scope, SharingStarted.Eagerly, StatusView(StatusView.Phase.NotProtected))
        scope.launch { statusView.collect { QuickTileService.requestUpdate(appContext) } }
        refreshSystemState()
        Callbacks.setHostHandler(hostHandler)

        val diagnostics = settingsStore.current.diagnostics
        // Logs that an earlier process wrote before the user turned diagnostics off.
        if (!diagnostics) deleteRustLogs(dir)
        if (initializeRust(dir, diagnostics)) {
            isReady = true
            Log.i(TAG, "Rust core initialized")
            controller.start()
            scope.launch {
                // Off stops the log now; on needs a new process (Rust cannot start it again).
                settingsStore.settings.map { it.diagnostics }.distinctUntilChanged().drop(1).filter { !it }.collect {
                    RustFfi.flushAndStopPersistedLog()
                    deleteRustLogs(dir)
                }
            }
        }
    }

    /**
     * The logs, and the debug bundle that holds a copy of them. A file that a late log rotation
     * writes after this goes at the next process start.
     */
    private fun deleteRustLogs(dir: File) {
        if (!File(dir, RUST_LOG_DIR).deleteRecursively()) Log.w(TAG, "cannot delete all Rust logs")
        DebugBundles.deleteAll(appContext.cacheDir)
    }

    fun updateSystemState(transform: (SystemState) -> SystemState) = systemStateFlow.update(transform)

    /** Re-reads the facts that can change while nothing in this process watches them. */
    fun refreshSystemState() {
        val restricted = appContext.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        // Without a running service the last known always-on values stay.
        val service = vpnService.value
        val alwaysOn = service?.isAlwaysOn
        val lockdown = service?.isLockdownEnabled
        updateSystemState {
            it.copy(
                backgroundRestricted = restricted,
                alwaysOn = alwaysOn ?: it.alwaysOn,
                lockdown = lockdown ?: it.lockdown,
            )
        }
    }

    fun attachVpnService(service: VpnService) {
        vpnService.value = service
        refreshSystemState()
    }

    fun detachVpnService(service: VpnService) {
        vpnService.compareAndSet(service, null)
    }

    private fun initializeRust(dir: File, diagnostics: Boolean): Boolean = try {
        // Rust aborts the process when the config dir is missing.
        val configDir = File(dir, "core").apply { mkdirs() }
        Os.setenv("RUST_LOG", if (diagnostics) DIAGNOSTICS_RUST_LOG else QUIET_RUST_LOG, true)
        // Throws when the Keystore fails in a way that may pass: no start this time, and the config stays.
        val configKey = configKeyVault.loadOrCreate()
        val secret = wgKeyVault.load()
        try {
            RustFfi.initialize(configDir.absolutePath, "Ombra-Android/${BuildConfig.VERSION_NAME}", secret, configKey, diagnostics)
        } finally {
            secret?.fill(0)
            configKey?.fill(0)
        }
        true
    } catch (t: Throwable) {
        initError = t.javaClass.name
        Log.e(TAG, "Rust core init failed: ${t.javaClass.name}")
        false
    }

    private val hostHandler = object : Callbacks.HostHandler {
        override fun processExitReasons(): String {
            val am = appContext.getSystemService(ActivityManager::class.java)
            return buildJsonArray {
                for (info in am.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)) {
                    addJsonObject {
                        put("processName", info.processName)
                        put("reason", reasonName(info.reason))
                        put("status", info.status)
                        put("importance", info.importance)
                        put("pssKb", info.pss)
                        put("rssKb", info.rss)
                        put("timestamp", info.timestamp)
                    }
                }
            }.toString()
        }

        override fun storeWgSecretKey(secret: ByteArray): Boolean = wgKeyVault.store(secret)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    } + "($reason)"
}
