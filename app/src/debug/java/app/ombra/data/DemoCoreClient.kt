package app.ombra.data

import android.os.SystemClock
import app.ombra.core.AccountNumber
import app.ombra.core.model.AccountInfo
import app.ombra.core.model.AccountStatus
import app.ombra.core.model.CachedValue
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.ExitList
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.FreeTrialInfo
import app.ombra.core.model.FreeTrialStatus
import app.ombra.core.model.FundingMethod
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.Status
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.TransportKind
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.VpnStatus
import app.ombra.core.model.jsonString
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import app.ombra.ipc.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sin

/** A scripted starting state of [DemoCoreClient]. */
sealed interface DemoScenario {
    /** The core never answers. */
    data object Loading : DemoScenario
    data object LoggedOut : DemoScenario

    /** A new account whose number the user has not confirmed yet (after a process death). */
    data object NewAccount : DemoScenario
    data object Disconnected : DemoScenario

    /** Logged in, no time left. */
    data object Expired : DemoScenario
    data object Connecting : DemoScenario
    data object Reconnecting : DemoScenario
    data object Connected : DemoScenario

    /** Connecting, and every attempt fails with [error]. */
    data class Failing(val error: ConnectErrorCode) : DemoScenario

    companion object {
        /** `loading`, `loggedout`, `newaccount`, `disconnected`, `expired`, `connecting`, `reconnecting`, `connected`, `error:<connectError>`. */
        fun parse(name: String): DemoScenario = when (name.lowercase()) {
            "loading" -> Loading
            "loggedout" -> LoggedOut
            "newaccount" -> NewAccount
            "expired" -> Expired
            "connecting" -> Connecting
            "reconnecting" -> Reconnecting
            "connected" -> Connected
            else -> if (name.startsWith(ERROR_PREFIX)) Failing(ConnectErrorCode.fromWire(name.removePrefix(ERROR_PREFIX))) else Disconnected
        }

        private const val ERROR_PREFIX = "error:"
    }
}

/** How the demo account is funded. */
enum class DemoAccount { TopUp, Trial, Renews, Expiring }

/**
 * Extras after the scenario, comma-separated: `alwayson`, `lockdown` (always-on with "Block
 * connections without VPN"), `privatedns`, `restricted` (battery), `trial`, `renews`, `expiring`,
 * `custom` (custom DNS, split tunnel, pins), `speed` (Speed mode), `norelays`, `nolists`.
 * Example: `--es demo connected,lockdown,custom`.
 */
data class DemoOptions(
    val system: SystemState = SystemState(alwaysOn = false, lockdown = false),
    val account: DemoAccount = DemoAccount.TopUp,
    val customSettings: Boolean = false,
    val speedMode: Boolean = false,
    val relays: Boolean = true,
    val exits: Boolean = true,
) {
    companion object {
        fun parse(flags: List<String>): DemoOptions = flags.fold(DemoOptions()) { options, flag ->
            when (flag.lowercase()) {
                "alwayson" -> options.copy(system = options.system.copy(alwaysOn = true))
                "lockdown" -> options.copy(system = options.system.copy(alwaysOn = true, lockdown = true))
                "privatedns" -> options.copy(system = options.system.copy(privateDnsActive = true, privateDnsServer = "dns.example.net"))
                "restricted" -> options.copy(system = options.system.copy(backgroundRestricted = true))
                "trial" -> options.copy(account = DemoAccount.Trial)
                "renews" -> options.copy(account = DemoAccount.Renews)
                "expiring" -> options.copy(account = DemoAccount.Expiring)
                "custom" -> options.copy(customSettings = true)
                "speed" -> options.copy(speedMode = true)
                "norelays" -> options.copy(relays = false)
                "nolists" -> options.copy(relays = false, exits = false)
                else -> options
            }
        }
    }
}

/**
 * Debug builds only: plays a [DemoScenario] in place of the core, offline. Requests answer like
 * the real core would; connect and disconnect run a short script. The account number is a fresh
 * random one that exists nowhere.
 */
class DemoCoreClient(scenario: DemoScenario, private val options: DemoOptions = DemoOptions()) : CoreSource, TunnelControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tunnelJob: Job? = null
    private var sessionStart = SystemClock.elapsedRealtime() - if (scenario == DemoScenario.Connected) LIVED_IN_SESSION_MS else 0
    private val number = AccountNumber.generate()

    private val settingsState = MutableStateFlow<Settings?>(settingsFor(scenario, options))
    private val statusState = MutableStateFlow(statusFor(scenario))

    override val status: StateFlow<Status?> = statusState.asStateFlow()
    override val exits: StateFlow<CachedValue<ExitList>?> = MutableStateFlow(CachedValue(VERSION, 0, ExitList(EXITS)).takeIf { options.exits })
    override val relays: StateFlow<CachedValue<List<OneRelay>>?> = MutableStateFlow(CachedValue(VERSION, 0, RELAYS).takeIf { options.relays })
    override val settings: StateFlow<Settings?> = settingsState.asStateFlow()
    override val system: StateFlow<SystemState?> = MutableStateFlow(options.system)
    override val problem: StateFlow<StartProblem?> = MutableStateFlow(null)

    override fun bind() = Unit

    override fun unbind() = Unit

    override suspend fun request(request: Request): String {
        delay(REPLY_DELAY_MS)
        return when (request) {
            is Request.Login -> signIn(newAccount = false, active = true).let { JSON_NULL }
            is Request.CreateAccount -> signIn(newAccount = true, active = true).let { jsonString(number) }
            Request.EndNewAccountFlow -> statusState.update { it?.copy(inNewAccountFlow = false) }.let { JSON_NULL }
            Request.Logout, Request.DeleteAccount -> signOut().let { JSON_NULL }
            Request.ServiceStatus -> IpcJson.encodeToString(ServiceStatus.serializer(), ServiceStatus(FreeTrialStatus(available = true)))
            Request.RevealAccountNumber -> jsonString(number.takeIf { statusState.value?.isLoggedIn == true })
            is Request.UpdateSettings -> IpcJson.encodeToString(Settings.serializer(), updateSettings(request))
            Request.TrafficStats -> IpcJson.encodeToString(TrafficStats.serializer(), trafficStats())
            Request.RefreshAccount -> refreshAccount().let { JSON_NULL }
            Request.RotateWgKey, Request.RefreshLists -> JSON_NULL
            is Request.CreateDebugBundle -> noBundle()
            is Request.RawCommand -> """{"demo":true}"""
        }
    }

    override fun connect() = runTunnel {
        setVpn(connecting(error = null))
        delay(CONNECT_MS)
        sessionStart = SystemClock.elapsedRealtime()
        setVpn(connectedTo(currentSettings()))
    }

    override fun disconnect() = runTunnel {
        // Like OmbraVpnService: always-on keeps the tunnel up.
        if (options.system.alwaysOn == true) return@runTunnel
        delay(DISCONNECT_MS)
        setVpn(VpnStatus.Disconnected)
    }

    private fun runTunnel(script: suspend () -> Unit) {
        tunnelJob?.cancel()
        tunnelJob = scope.launch { script() }
    }

    private fun signIn(newAccount: Boolean, active: Boolean) {
        statusState.value = loggedIn(VpnStatus.Disconnected, active, options.account).copy(inNewAccountFlow = newAccount)
    }

    private fun refreshAccount() = statusState.update { status ->
        status?.copy(account = status.account?.copy(lastUpdatedSec = System.currentTimeMillis() / 1000))
    }

    /** A bundle probes the network: the demo makes none, and says so after a short wait. */
    private suspend fun noBundle(): Nothing {
        delay(BUNDLE_MS)
        throw CoreRequestException(IpcError.NOT_ALLOWED)
    }

    private fun signOut() {
        tunnelJob?.cancel()
        statusState.value = LOGGED_OUT
    }

    /**
     * A fix for a failing connect (another mode or relay) lets the next attempt succeed. Like the
     * core, a new exit, relay or mode while connected reconnects at once.
     */
    private fun updateSettings(request: Request.UpdateSettings): Settings {
        val before = currentSettings()
        val updated = before.applyPatch(request.patch)
        settingsState.value = updated
        val vpn = statusState.value?.vpnStatus
        val failing = (vpn as? VpnStatus.Connecting)?.connectError != null
        val moved = vpn is VpnStatus.Connected && updated.tunnelArgs() != before.tunnelArgs()
        if (failing || moved) connect()
        return updated
    }

    private fun trafficStats(): TrafficStats {
        if (statusState.value?.vpnStatus !is VpnStatus.Connected) throw CoreRequestException(IpcError.TIMEOUT)
        val elapsedMs = SystemClock.elapsedRealtime() - sessionStart
        val t = elapsedMs / 1000.0
        // Smooth, always-increasing counters, so the rates wander a little like real traffic.
        return TrafficStats(
            connectedMs = elapsedMs,
            connId = "demo-$sessionStart",
            rxBytes = (6.2e6 * t + 1.5e6 * sin(t * 0.9)).toLong().coerceAtLeast(0),
            txBytes = (0.42e6 * t + 0.1e6 * sin(t * 1.3)).toLong().coerceAtLeast(0),
            latestLatencyMs = (27 + 3 * sin(t * 0.5)).toInt(),
        )
    }

    private fun setVpn(vpn: VpnStatus) = statusState.update { it?.copy(vpnStatus = vpn) }

    private fun currentSettings(): Settings = settingsState.value ?: Settings()

    private fun connecting(error: ConnectErrorCode?, reconnecting: Boolean = false) =
        VpnStatus.Connecting(currentSettings().tunnelArgs(), error, reconnecting)

    private fun statusFor(scenario: DemoScenario): Status? {
        val account = options.account
        return when (scenario) {
            DemoScenario.Loading -> null
            DemoScenario.LoggedOut -> LOGGED_OUT
            DemoScenario.NewAccount -> loggedIn(VpnStatus.Disconnected, active = false, account).copy(inNewAccountFlow = true)
            DemoScenario.Disconnected -> loggedIn(VpnStatus.Disconnected, active = true, account)
            DemoScenario.Expired -> loggedIn(VpnStatus.Disconnected, active = false, account)
            DemoScenario.Connecting -> loggedIn(connecting(error = null), active = true, account)
            DemoScenario.Reconnecting -> loggedIn(connecting(error = null, reconnecting = true), active = true, account)
            DemoScenario.Connected -> loggedIn(connectedTo(currentSettings()), active = true, account)
            is DemoScenario.Failing ->
                loggedIn(connecting(scenario.error), active = scenario.error != ConnectErrorCode.AccountExpired, account)
        }
    }

    private companion object {
        const val VERSION = "demo"
        const val JSON_NULL = "null"
        const val REPLY_DELAY_MS = 350L
        const val CONNECT_MS = 1_600L
        const val DISCONNECT_MS = 300L
        const val LIVED_IN_SESSION_MS = 754_000L
        const val BUNDLE_MS = 2_500L
        const val DAY_SECONDS = 86_400L

        const val MULLVAD = "Mullvad VPN"
        const val OTHER_PROVIDER = "Example Hosting"

        val LOGGED_OUT = Status(version = VERSION)

        // Addresses from the documentation ranges (RFC 5737); keys are made up. Two exits are not
        // run by Mullvad, so the DNS block list note shows.
        val EXITS = listOf(
            exit("se-sto-wg-001", "se", "sto", "Stockholm", tier = 1),
            exit("se-sto-wg-002", "se", "sto", "Stockholm", datacenter = 2),
            exit("se-got-wg-001", "se", "got", "Gothenburg"),
            exit("nl-ams-wg-001", "nl", "ams", "Amsterdam", tier = 1),
            exit("nl-ams-wg-002", "nl", "ams", "Amsterdam", datacenter = 2),
            exit("nl-ams-wg-003", "nl", "ams", "Amsterdam", provider = OTHER_PROVIDER, datacenter = 3),
            exit("ch-zrh-wg-001", "ch", "zrh", "Zürich"),
            exit("ch-zrh-wg-002", "ch", "zrh", "Zürich", datacenter = 2),
            exit("de-fra-wg-001", "de", "fra", "Frankfurt"),
            exit("de-fra-wg-002", "de", "fra", "Frankfurt", datacenter = 2),
            exit("de-ber-wg-001", "de", "ber", "Berlin"),
            exit("gb-lon-wg-001", "gb", "lon", "London"),
            exit("gb-lon-wg-002", "gb", "lon", "London", provider = OTHER_PROVIDER, datacenter = 2),
            exit("gb-man-wg-001", "gb", "man", "Manchester"),
            exit("us-nyc-wg-001", "us", "nyc", "New York"),
            exit("us-nyc-wg-002", "us", "nyc", "New York", datacenter = 2),
            exit("us-lax-wg-001", "us", "lax", "Los Angeles"),
            exit("us-mia-wg-001", "us", "mia", "Miami"),
            exit("ca-tor-wg-001", "ca", "tor", "Toronto"),
            exit("fr-par-wg-001", "fr", "par", "Paris"),
            exit("es-mad-wg-001", "es", "mad", "Madrid"),
            exit("it-mil-wg-001", "it", "mil", "Milan"),
            exit("at-vie-wg-001", "at", "vie", "Vienna"),
            exit("no-osl-wg-001", "no", "osl", "Oslo"),
            exit("dk-cph-wg-001", "dk", "cph", "Copenhagen"),
            exit("fi-hel-wg-001", "fi", "hel", "Helsinki"),
            exit("pl-waw-wg-001", "pl", "waw", "Warsaw"),
            exit("ro-buh-wg-001", "ro", "buh", "Bucharest"),
            exit("jp-tyo-wg-001", "jp", "tyo", "Tokyo"),
            exit("sg-sin-wg-001", "sg", "sin", "Singapore"),
            exit("au-syd-wg-001", "au", "syd", "Sydney"),
            exit("br-sao-wg-001", "br", "sao", "São Paulo"),
        )
        val RELAYS = listOf(
            relay("nl-ams-r1", "nl", "ams", "Amsterdam", "203.0.113.7"),
            relay("nl-ams-r2", "nl", "ams", "Amsterdam", "203.0.113.8"),
            relay("de-fra-r1", "de", "fra", "Frankfurt", "203.0.113.21"),
            relay("gb-lon-r1", "gb", "lon", "London", "203.0.113.40"),
            relay("us-nyc-r1", "us", "nyc", "New York", "198.51.100.4"),
            relay("us-lax-r1", "us", "lax", "Los Angeles", "198.51.100.9"),
            relay("sg-sin-r1", "sg", "sin", "Singapore", "192.0.2.15"),
            relay("jp-tyo-r1", "jp", "tyo", "Tokyo", "192.0.2.30"),
        )

        fun exit(id: String, country: String, city: String, name: String, provider: String = MULLVAD, datacenter: Long = 1, tier: Int = 2) = OneExit(
            id = id,
            countryCode = country,
            cityCode = city,
            cityName = name,
            providerId = id,
            providerName = provider,
            providerUrl = if (provider == MULLVAD) "https://mullvad.net/servers" else "https://example.com/servers",
            datacenterId = datacenter,
            tier = tier,
        )

        fun relay(id: String, country: String, city: String, name: String, ip: String) =
            OneRelay(id, country, city, name, ipV4 = ip, ports = listOf(443, 8443))

        fun settingsFor(scenario: DemoScenario, options: DemoOptions): Settings {
            val failingUdp = (scenario as? DemoScenario.Failing)?.error == ConnectErrorCode.UdpHandshakeFailed
            val base = if (failingUdp || options.speedMode) Settings(mode = TunnelMode.WireguardUdp, udpMtu = 1380) else Settings()
            if (!options.customSettings) return base
            return base.copy(
                exit = ExitSelector.City("ch", "zrh"),
                relay = RelaySelector.Relay("de-fra-r1"),
                dnsBlock = DnsContentBlock(ad = true, tracker = true, malware = true),
                customDns = listOf("9.9.9.9", "2620:fe::fe"),
                lanAccess = true,
                splitMode = SplitMode.Exclude,
                splitApps = setOf("com.android.chrome", "org.example.removed"),
                pinnedExits = listOf(PinnedLocation("ch", "zrh", 1), PinnedLocation("se", "sto", 2)),
                pinnedRelays = listOf("de-fra-r1"),
                sniRelay = "cdn.example.org",
            )
        }

        fun loggedIn(vpn: VpnStatus, active: Boolean, account: DemoAccount = DemoAccount.TopUp): Status {
            val now = System.currentTimeMillis() / 1000
            val info = when {
                !active -> AccountInfo(active = false, primaryFunding = FundingMethod.TopUp, hasPaid = true)
                account == DemoAccount.Trial -> AccountInfo(
                    active = true,
                    currentExpiry = now + 6 * DAY_SECONDS,
                    primaryFunding = FundingMethod.FreeTrial,
                    freeTrial = FreeTrialInfo(active = true, expiresAt = now + 6 * DAY_SECONDS),
                )
                account == DemoAccount.Renews -> AccountInfo(
                    active = true,
                    autoRenews = now + 17 * DAY_SECONDS,
                    primaryFunding = FundingMethod.StripeSubscription,
                    hasPaid = true,
                )
                account == DemoAccount.Expiring ->
                    AccountInfo(active = true, currentExpiry = now + 5 * 3_600, primaryFunding = FundingMethod.TopUp, hasPaid = true)
                else -> AccountInfo(active = true, currentExpiry = now + 23 * DAY_SECONDS, primaryFunding = FundingMethod.TopUp, hasPaid = true)
            }
            return Status(version = VERSION, vpnStatus = vpn, loggedIn = true, account = AccountStatus(info, now - 240))
        }

        fun connectedTo(settings: Settings): VpnStatus.Connected {
            val exit = when (val selector = settings.exit) {
                is ExitSelector.Country -> EXITS.firstOrNull { it.countryCode == selector.countryCode }
                is ExitSelector.City -> EXITS.firstOrNull { it.countryCode == selector.countryCode && it.cityCode == selector.cityCode }
                is ExitSelector.Exit -> EXITS.firstOrNull { it.id == selector.id }
                ExitSelector.Any -> null
            } ?: EXITS.first()
            val relay = when (val selector = settings.relay) {
                is RelaySelector.Country -> RELAYS.firstOrNull { it.countryCode == selector.countryCode }
                is RelaySelector.City -> RELAYS.firstOrNull { it.countryCode == selector.countryCode && it.cityCode == selector.cityCode }
                is RelaySelector.Relay -> RELAYS.firstOrNull { it.id == selector.id }
                RelaySelector.Any -> null
            } ?: RELAYS.first()
            return VpnStatus.Connected(
                tunnelArgs = settings.tunnelArgs(),
                exit = exit,
                relay = relay,
                clientPublicKey = "b21icmEtZGVtby1jbGllbnQtcHVibGljLWtleS0zMmI=",
                exitPublicKey = "b21icmEtZGVtby1leGl0LXB1YmxpYy1rZXktMzJieSE=",
                transport = when (settings.mode) {
                    TunnelMode.Quic -> TransportKind.Quic
                    TunnelMode.TcpTls -> TransportKind.TcpTls
                    TunnelMode.WireguardUdp -> TransportKind.WireguardUdp
                },
            )
        }
    }
}
