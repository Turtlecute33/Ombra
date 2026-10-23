package app.ombra.core.model

import app.ombra.ipc.IpcJson
import app.ombra.ipc.Settings
import app.ombra.ipc.StateKind
import app.ombra.ipc.SystemState
import app.ombra.vpn.Cidr
import app.ombra.vpn.SettingsSync
import app.ombra.vpn.StateFeed
import app.ombra.vpn.TunPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON contract with the Rust core. `core-contract.json` (test resources) is written by
 * rust/obscura-core/src/kotlin_contract_test.rs from Rust values: Kotlin must send each command
 * exactly as Rust serializes it, and must decode each result without losing a field.
 */
class CoreContractTest {
    private val fixture = CoreJson.parseToJsonElement(
        requireNotNull(javaClass.getResource("/core-contract.json")) { "core-contract.json is missing" }.readText(),
    ).jsonObject
    private val commands = fixture.getValue("commands").jsonObject
    private val results = fixture.getValue("results").jsonObject
    private val enums = fixture.getValue("enums").jsonObject

    private fun result(name: String): String = results.getValue(name).toString()

    private fun wireNames(name: String): Set<String> = enums.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private val speedSettings = Settings(
        exit = ExitSelector.Country("US"),
        relay = RelaySelector.City("DE", "fra"),
        mode = TunnelMode.WireguardUdp,
        udpMtu = 1400,
    )

    /** Every command of the fixture, built as the app builds it. */
    private val kotlinCommands: Map<String, CoreCall<*>> = mapOf(
        "apiDeleteAccount" to CoreCommands.apiDeleteAccount(),
        "apiGetAccountInfo" to CoreCommands.apiGetAccountInfo(),
        "apiServiceStatus" to CoreCommands.apiServiceStatus(),
        "getExitList" to CoreCommands.getExitList("AAECAw=="),
        "getExitListFirst" to CoreCommands.getExitList(null),
        "getRelayList" to CoreCommands.getRelayList("AAEC"),
        "getRelayListFirst" to CoreCommands.getRelayList(null),
        "getStatus" to CoreCommands.getStatus(STATUS_VERSION),
        "getStatusFirst" to CoreCommands.getStatus(null),
        "getTrafficStats" to CoreCommands.getTrafficStats(),
        "login" to CoreCommands.login(ACCOUNT_NUMBER),
        "logout" to CoreCommands.logout(),
        "ping" to CoreCommands.ping(),
        "refreshExitList" to CoreCommands.refreshExitList(),
        "refreshOsNetworkConfig" to CoreCommands.refreshOsNetworkConfig(),
        "refreshRelayList" to CoreCommands.refreshRelayList(),
        "rotateWgKey" to CoreCommands.rotateWgKey(),
        "setApiHostAlternate" to CoreCommands.setApiHostAlternate("api.example.org"),
        "setApiHostAlternateDefault" to CoreCommands.setApiHostAlternate(null),
        "setApiUrl" to CoreCommands.setApiUrl(null),
        "setAutoConnect" to CoreCommands.setAutoConnect(true),
        "setDnsContentBlock" to CoreCommands.setDnsContentBlock(BLOCK),
        "setFeatureFlag.forceSmallMtu" to CoreCommands.setFeatureFlag(FeatureFlag.FORCE_SMALL_MTU, false),
        "setFeatureFlag.quicFramePadding" to CoreCommands.setFeatureFlag(FeatureFlag.QUIC_FRAME_PADDING, true),
        "setFeatureFlag.tcpTlsTunnel" to CoreCommands.setFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, true),
        "setInNewAccountFlow" to CoreCommands.setInNewAccountFlow(true),
        "setLocalNetworkAccess" to CoreCommands.setLocalNetworkAccess(false),
        "setPinnedExits" to CoreCommands.setPinnedExits(listOf(PinnedLocation("US", "nyc", 1_700_000_000))),
        "setSniRelay" to CoreCommands.setSniRelay("sni.example.org"),
        "setSniRelayDefault" to CoreCommands.setSniRelay(null),
        // OmbraVpnService: connect with the settings, connect with the stored args, disconnect.
        "setTunnelArgs.connect" to CoreCommands.connect(speedSettings.tunnelArgs()),
        "setTunnelArgs.connectStored" to CoreCommands.connect(null),
        "setTunnelArgs.disconnect" to CoreCommands.disconnect(),
        "setTunnelArgs.settings" to CoreCommands.setTunnelArgs(
            TunnelArgs.of(ExitSelector.Exit("NYC-001"), RelaySelector.Relay("relay-1"), TunnelMode.TcpTls, 1400),
            active = null,
        ),
        "setUseSystemDns" to CoreCommands.setUseSystemDns(true),
    )

    @Test
    fun everyCommandIsEncodedAsRustSerializesIt() {
        assertEquals(commands.keys, kotlinCommands.keys)
        kotlinCommands.forEach { (name, call) ->
            assertEquals(commands.getValue(name), CoreJson.parseToJsonElement(call.encode()), name)
        }
    }

    @Test
    fun settingsSyncSendsOnlyContractCommands() {
        val settings = Settings(
            exit = ExitSelector.Exit("NYC-001"),
            relay = RelaySelector.Relay("relay-1"),
            mode = TunnelMode.TcpTls,
            padding = true,
            dnsBlock = BLOCK,
            useSystemDns = true,
            pinnedExits = listOf(PinnedLocation("US", "nyc", 1_700_000_000)),
            autoConnectOnLaunch = true,
            sniRelay = "sni.example.org",
            apiHostAlternate = "api.example.org",
        )
        val sent = SettingsSync.all(settings).map { CoreJson.parseToJsonElement(it.encode()) }
        sent.forEach { assertTrue(it in commands.values, "not in the contract: $it") }
        assertEquals(11, sent.size)
    }

    @Test
    fun debugBundleCommandDecodesInRust() {
        // rust/obscura-core/src/kotlin_contract_test.rs decodes the same string.
        assertEquals(
            """{"createDebugBundle":{"userFeedback":null,"bundleInfo":{"AppVersion":"1.0","AndroidSDK":36},"androidCacheDir":"/data/user/0/app.ombra/cache"}}""",
            CoreCommands.createDebugBundle(null, BundleInfo("1.0", 36), "/data/user/0/app.ombra/cache").encode(),
        )
    }

    @Test
    fun connectedSpeedModeStatus() {
        val status = CoreCommands.getStatus(null).decode(result("getStatus.connected"))
        assertLossless(Status.serializer(), result("getStatus.connected"))
        val connected = assertIs<VpnStatus.Connected>(status.vpnStatus)
        assertEquals(TransportKind.WireguardUdp, connected.transport)
        assertEquals(speedSettings.tunnelArgs(), connected.tunnelArgs)
        assertEquals("NYC-001", connected.exit.id)
        assertEquals("Mullvad VPN", connected.exit.providerName)
        assertEquals("relay-1", connected.relay.id)
        assertEquals("192.0.2.10", connected.relay.ipV4)
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", connected.clientPublicKey)
        assertEquals(ACCOUNT_NUMBER, status.accountId)
        assertEquals(FeatureFlags(quicFramePadding = true, forceSmallMtu = true), status.featureFlags)
        assertEquals(BLOCK, status.dnsContentBlock)
        assertEquals(listOf(PinnedLocation("US", "nyc", 1_700_000_000)), status.pinnedLocations)
        assertTrue(status.autoConnect)
        val account = status.account!!
        assertTrue(account.accountInfo.active)
        assertEquals(FundingMethod.TopUp, account.accountInfo.primaryFunding)
        assertEquals(1_700_000_100L, account.lastUpdatedSec)
    }

    @Test
    fun udpHandshakeFailedWhileLoggedOut() {
        val status = CoreCommands.getStatus(null).decode(result("getStatus.connecting"))
        assertLossless(Status.serializer(), result("getStatus.connecting"))
        val connecting = assertIs<VpnStatus.Connecting>(status.vpnStatus)
        assertEquals(ConnectErrorCode.UdpHandshakeFailed, connecting.connectError)
        assertTrue(connecting.reconnecting)
        assertEquals(TunnelMode.WireguardUdp, connecting.tunnelArgs.mode)
        assertFalse(status.isLoggedIn)
        assertNull(status.account)
    }

    @Test
    fun disconnectedStatus() {
        assertEquals(VpnStatus.Disconnected, CoreCommands.getStatus(null).decode(result("getStatus.disconnected")).vpnStatus)
        assertLossless(Status.serializer(), result("getStatus.disconnected"))
    }

    @Test
    fun listsAccountAndStats() {
        val exits = CoreCommands.getExitList(null).decode(result("getExitList"))
        assertEquals("AAECAw==", exits.version)
        assertEquals(1_700_000_000L, exits.lastUpdated)
        assertEquals(42L, exits.value.exits.single().datacenterId)
        assertLossless(CachedValue.serializer(ExitList.serializer()), result("getExitList"))

        val relays = CoreCommands.getRelayList(null).decode(result("getRelayList"))
        assertEquals("AAEC", relays.version)
        assertEquals(listOf(RelayPreferredExit("FRA-001")), relays.value.single().preferredExits)
        assertLossless(CachedValue.serializer(ListSerializer(OneRelay.serializer())), result("getRelayList"))

        assertEquals(1_800_000_000L, CoreCommands.apiGetAccountInfo().decode(result("apiGetAccountInfo")).currentExpiry)
        assertLossless(AccountInfo.serializer(), result("apiGetAccountInfo"))

        assertTrue(CoreCommands.apiServiceStatus().decode(result("apiServiceStatus")).freeTrial.available)
        assertLossless(ServiceStatus.serializer(), result("apiServiceStatus"))

        assertEquals(42, CoreCommands.getTrafficStats().decode(result("getTrafficStats")).latestLatencyMs)
        assertLossless(TrafficStats.serializer(), result("getTrafficStats"))

        assertEquals(JsonNull, results.getValue("empty"))
        CoreCommands.ping().decode(result("empty"))
    }

    @Test
    fun osNetworkConfigBuildsTheTun() {
        val config = CoreJson.decodeFromString(OsNetworkConfig.serializer(), result("osNetworkConfig"))
        assertLossless(OsNetworkConfig.serializer(), result("osNetworkConfig"))
        val plan = TunPlan.of(config, Settings(), "app.ombra", isUsableDns = { true }, isInstalled = { true })
        assertEquals(1400, plan.mtu)
        assertEquals(listOf(Cidr("10.75.0.2", 32), Cidr("fc00:bbbb:bbbb:bb01::1:2", 128)), plan.addresses)
        assertEquals(listOf(Cidr("0.0.0.0", 0), Cidr("::", 0)), plan.routes)
        assertEquals(config.dns, plan.dnsServers)
    }

    @Test
    fun enumsMatchRust() {
        assertEquals(wireNames("connectErrorCode"), (ConnectErrorCode.entries - ConnectErrorCode.Unknown).map { it.wire }.toSet())
        assertEquals(wireNames("transportKind"), (TransportKind.entries - TransportKind.Unknown).map { it.wire }.toSet())
        assertEquals(
            wireNames("managerCmdErrorCode"),
            (ManagerCmdErrorCode.entries - ManagerCmdErrorCode.Unknown).map { it.wire }.toSet(),
        )
        assertEquals(
            wireNames("tunnelMode"),
            TunnelMode.entries.map { CoreJson.encodeToJsonElement(TunnelMode.serializer(), it).jsonPrimitive.content }.toSet(),
        )
        val flags = FeatureFlags.serializer().descriptor
        assertEquals(wireNames("featureFlag"), (0 until flags.elementsCount).map(flags::getElementName).toSet())
    }

    @Test
    fun statusSurvivesTheIpcPush() {
        // CoreBridge decodes with CoreJson, StateFeed sends, CoreClient decodes with IpcJson.
        val status = CoreCommands.getStatus(null).decode(result("getStatus.connected"))
        val feed = StateFeed(
            MutableStateFlow(status),
            MutableStateFlow(null),
            MutableStateFlow(null),
            MutableStateFlow(Settings()),
            MutableStateFlow(SystemState()),
        )
        val json = feed.snapshot().first { it.first == StateKind.STATUS }.second
        assertFalse(ACCOUNT_NUMBER in json)
        val pushed = IpcJson.decodeFromString(Status.serializer(), json)
        assertTrue(pushed.isLoggedIn)
        assertNull(pushed.account!!.accountInfo.id)
        assertEquals(status.copy(accountId = null, account = null), pushed.copy(account = null, loggedIn = false))
    }

    /** Decoding and encoding again gives the Rust JSON, so no Rust field is dropped or renamed. */
    private fun <T> assertLossless(serializer: KSerializer<T>, json: String) {
        val rust = CoreJson.parseToJsonElement(json)
        val kotlin = CoreJson.encodeToJsonElement(serializer, CoreJson.decodeFromString(serializer, json))
        assertEquals(comparable(rust), comparable(kotlin))
    }

    /**
     * Two known differences: `loggedIn` exists only on the Kotlin side, and Rust writes an unset
     * feature flag as null, which Kotlin reads as false.
     */
    private fun comparable(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val flags = element["featureFlags"]?.jsonObject?.let { flags ->
            JsonObject(flags.mapValues { (_, value) -> if (value == JsonNull) JsonPrimitive(false) else value })
        }
        return JsonObject(element - "loggedIn" + listOfNotNull(flags?.let { "featureFlags" to it }))
    }

    private companion object {
        const val ACCOUNT_NUMBER = "55550123456780554337"
        const val STATUS_VERSION = "2b9a3c1e-0d4f-4a9b-8c6e-1f2a3b4c5d6e"
        val BLOCK = DnsContentBlock(ad = true, malware = true, socialMedia = true)
    }
}
