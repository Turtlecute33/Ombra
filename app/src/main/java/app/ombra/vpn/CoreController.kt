package app.ombra.vpn

import android.util.Log
import app.ombra.core.AccountNumber
import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.model.CachedValue
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.CoreCall
import app.ombra.core.model.CoreCommands
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.IpcError
import app.ombra.ipc.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Rust core of the :vpn process, free of Android types so that it runs in JVM tests: the one
 * status, exit-list and relay-list long-poll, the account info refresh, the account operations,
 * and Rust kept in step with [Settings]. Rust rebuilds its config at login and logout, so the
 * settings are pushed again after both and at start.
 */
class CoreController(
    private val core: CoreChannel,
    val settingsStore: SettingsStore,
    private val vpnWanted: VpnWanted,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val started = AtomicBoolean(false)

    // Settings reach Rust in the order they were stored, and a full apply never interleaves with a patch.
    private val settingsMutex = Mutex()

    private val statusState = MutableStateFlow<Status?>(null)
    private val exitsState = MutableStateFlow<CachedValue<ExitList>?>(null)
    private val relaysState = MutableStateFlow<CachedValue<List<OneRelay>>?>(null)

    /** Unsanitized, it holds the account number: it leaves this process only through StatusSanitizer. */
    val status: StateFlow<Status?> = statusState.asStateFlow()
    val exits: StateFlow<CachedValue<ExitList>?> = exitsState.asStateFlow()
    val relays: StateFlow<CachedValue<List<OneRelay>>?> = relaysState.asStateFlow()

    @Volatile
    private var lastAccountRefresh: TimeMark? = null

    /** The split-tunnel TUN rebuild that waits for more changes. Only under [settingsMutex]. */
    private var tunRefresh: Job? = null

    /** Applies the settings and starts the long-polls and the periodic refresh. Later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { applyAllSettings() }
        scope.launch { longPoll("status", CoreCommands::getStatus, Status::version, ::onStatus) }
        scope.launch { longPoll("exit list", CoreCommands::getExitList, { it.version }) { exitsState.value = it } }
        scope.launch { longPoll("relay list", CoreCommands::getRelayList, { it.version }) { relaysState.value = it } }
        scope.launch { periodicRefresh() }
    }

    /** Pushes every Rust-side setting. */
    suspend fun applyAllSettings() = settingsMutex.withLock { send(SettingsSync.all(settingsStore.current)) }

    /**
     * Stores [patch] (see [Settings.applyPatch]) and sends Rust the commands for what changed.
     * Throws SerializationException for a bad patch and IOException when the file cannot be written.
     */
    suspend fun updateSettings(patch: JsonObject): Settings = settingsMutex.withLock {
        val before = settingsStore.current
        val after = settingsStore.update(patch)
        send(SettingsSync.changes(before, after))
        if (SettingsSync.tunChanged(before, after)) {
            // A rebuild reads all current settings, so one is enough.
            tunRefresh?.cancel()
            // Android updates DNS in place, but a new app list replaces the VPN network and every app
            // loses its connections: quick split-tunnel taps give one rebuild (OMBRA-PERF-003).
            if (before.customDns != after.customDns) {
                refreshTun()
            } else {
                tunRefresh = scope.launch {
                    delay(SPLIT_TUN_REFRESH_DELAY)
                    refreshTun()
                }
            }
        }
        after
    }

    /** Builds the TUN again for OS-side facts that Rust does not see, such as split tunnelling and custom DNS. */
    suspend fun refreshTun() {
        if (tunnelInUse()) quietly("TUN refresh") { core.call(CoreCommands.refreshOsNetworkConfig()) }
    }

    /** Checks [accountNumber] locally (length and Verhoeff) first, so a typo costs no network call. */
    suspend fun login(accountNumber: String) {
        val number = AccountNumber.normalize(accountNumber)
        if (!AccountNumber.isValid(number)) throw ErrorCodeException(IpcError.INVALID_ACCOUNT_NUMBER)
        core.call(CoreCommands.setInNewAccountFlow(false))
        signIn(number)
    }

    /**
     * Creates an account with a new client-side number and returns that number. The new-account
     * flow stays on until [endNewAccountFlow], so the UI can show the number until it is saved.
     * With [referral], the account also gets [REFERRAL_CODE] in the background: a failure (for
     * example a code that the API does not know) only goes to the log (DESIGN §10).
     */
    suspend fun createAccount(referral: Boolean): String {
        val number = AccountNumber.generate()
        core.call(CoreCommands.setInNewAccountFlow(true))
        try {
            signIn(number)
        } catch (e: ErrorCodeException) {
            quietly("new-account flow reset") { endNewAccountFlow() }
            throw e
        }
        if (referral) scope.launch { quietly("referral") { core.call(CoreCommands.apiUseReferralCode(REFERRAL_CODE)) } }
        return number
    }

    suspend fun endNewAccountFlow() = core.call(CoreCommands.setInNewAccountFlow(false))

    /** Also disconnects: Rust stops the tunnel when the account goes away. */
    suspend fun logout() {
        vpnWanted.value = false
        try {
            core.call(CoreCommands.logout())
        } finally {
            applyAllSettings()
        }
    }

    /** Deletes the account on the server, then logs out. */
    suspend fun deleteAccount() {
        core.call(CoreCommands.apiDeleteAccount())
        logout()
    }

    /** The new account info reaches the UI in Status.account. */
    suspend fun refreshAccount() {
        lastAccountRefresh = timeSource.markNow()
        core.call(CoreCommands.apiGetAccountInfo())
    }

    /** Both lists at once, so the request fits in one API timeout. New lists arrive through the long-polls. */
    suspend fun refreshLists() = coroutineScope {
        val relays = async { refreshRelayList() }
        core.call(CoreCommands.refreshExitList())
        relays.await()
    }

    /** The full account number, or null when logged out. */
    suspend fun accountNumber(): String? = (statusState.value ?: core.call(CoreCommands.getStatus(null))).accountId

    private suspend fun signIn(number: String) {
        // Rust stops the tunnel when the account changes, except a tunnel that waits for a login from
        // the logged-out state (for example always-on): Rust keeps that one and its TUN. A tunnel that
        // is wanted and in use is started again after the login.
        val reconnect = vpnWanted.value && tunnelInUse()
        if (!reconnect && statusState.value?.accountId != number) vpnWanted.value = false
        try {
            core.call(CoreCommands.login(number))
        } finally {
            // Rust rebuilds its config from defaults on an account change, even when saving it then fails.
            applyAllSettings()
            // Uses the tunnel args just restored. Without a reset Rust is still active, so this does nothing.
            if (reconnect) {
                try {
                    core.call(CoreCommands.connect(null))
                } catch (e: ErrorCodeException) {
                    Log.w(TAG, "reconnect after login failed: ${e.errorCode}")
                    vpnWanted.value = false
                }
            }
        }
        scope.launch {
            quietly("account refresh") { refreshAccount() }
            quietly("list refresh") { refreshLists() }
        }
    }

    private suspend fun refreshRelayList() {
        try {
            core.call(CoreCommands.refreshRelayList())
        } catch (e: ErrorCodeException) {
            // A core without patch P2 has no relay list; the exit list is what matters then.
            if (e.errorCode != IpcError.BAD_COMMAND) throw e
        }
    }

    private suspend fun send(commands: List<CoreCall<Unit>>) {
        // The stored settings stay the source of truth: a failed command is sent again at the next start or login.
        commands.forEach { quietly("applying a setting") { core.call(it) } }
    }

    private fun tunnelInUse(): Boolean = statusState.value.let { it != null && it.vpnStatus !is VpnStatus.Disconnected }

    /** Waits for each new version of a value forever. Rust cannot cancel a long-poll, so there is one per value. */
    private suspend fun <T> longPoll(name: String, poll: (String?) -> CoreCall<T>, version: (T) -> String, publish: (T) -> Unit) {
        var known: String? = null
        while (true) {
            val value = try {
                core.call(poll(known))
            } catch (e: ErrorCodeException) {
                if (e.errorCode == IpcError.BAD_COMMAND) {
                    Log.w(TAG, "core has no $name command")
                    return
                }
                Log.w(TAG, "$name poll failed: ${e.errorCode}")
                delay(POLL_RETRY_DELAY)
                continue
            }
            known = version(value)
            publish(value)
        }
    }

    private fun onStatus(status: Status) {
        statusState.value = status
        val vpn = status.vpnStatus
        if (vpn is VpnStatus.Connecting && vpn.connectError == ConnectErrorCode.AccountExpired && accountInfoIsOld()) {
            // UNDISPATCHED: refreshAccount() stamps lastAccountRefresh before the next status arrives.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { quietly("account refresh") { refreshAccount() } }
        }
    }

    private fun accountInfoIsOld(): Boolean = lastAccountRefresh.let { it == null || it.elapsedNow() >= EXPIRED_REFRESH_GAP }

    /** When logged in: at start, then every [ACCOUNT_REFRESH_INTERVAL] while the process lives. */
    private suspend fun periodicRefresh() {
        statusState.filterNotNull().first()
        while (true) {
            if (statusState.value?.isLoggedIn == true) {
                quietly("account refresh") { refreshAccount() }
                quietly("list refresh") { refreshLists() }
            }
            delay(ACCOUNT_REFRESH_INTERVAL)
        }
    }

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ErrorCodeException) {
            Log.w(TAG, "$what failed: ${e.errorCode}")
        }
    }

    companion object {
        private const val TAG = "OmbraCore"

        /** The Obscura referral code of the Ombra developer. A new account and the developer each get a free month at the first payment. */
        const val REFERRAL_CODE = "e22kby"
        val ACCOUNT_REFRESH_INTERVAL = 12.hours

        /** Rust retries a connect about every second; an expired account refreshes at most this often. */
        val EXPIRED_REFRESH_GAP = 1.minutes
        private val POLL_RETRY_DELAY = 2.seconds

        /** The time after the last split-tunnel change until the TUN rebuild. */
        val SPLIT_TUN_REFRESH_DELAY = 1.5.seconds
    }
}
