package app.ombra.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shapes from docs/research/rust-ffi.md sections 9.2, 9.4, 10.1, 10.2, 10.5 and DESIGN.md section 5. */
class CoreModelTest {
    private fun tree(json: String): JsonElement = CoreJson.parseToJsonElement(json)

    private fun assertCommand(expected: String, call: CoreCall<*>) = assertEquals(tree(expected), tree(call.encode()))

    private fun <T> roundTrip(serializer: KSerializer<T>, json: String): T {
        val value = CoreJson.decodeFromString(serializer, json)
        assertEquals(tree(json), tree(CoreJson.encodeToString(serializer, value)))
        return value
    }

    // ---- 9.2 commands -------------------------------------------------------------------------

    @Test
    fun zeroFieldCommandsEncodeAsEmptyObjects() {
        assertEquals("""{"ping":{}}""", CoreCommands.ping().encode())
        assertEquals("""{"logout":{}}""", CoreCommands.logout().encode())
        assertEquals("""{"apiDeleteAccount":{}}""", CoreCommands.apiDeleteAccount().encode())
        assertEquals("""{"apiGetAccountInfo":{}}""", CoreCommands.apiGetAccountInfo().encode())
        assertEquals("""{"apiServiceStatus":{}}""", CoreCommands.apiServiceStatus().encode())
        assertEquals("""{"getTrafficStats":{}}""", CoreCommands.getTrafficStats().encode())
        assertEquals("""{"rotateWgKey":{}}""", CoreCommands.rotateWgKey().encode())
        assertEquals("""{"refreshOsNetworkConfig":{}}""", CoreCommands.refreshOsNetworkConfig().encode())
    }

    @Test
    fun commandsMatchTheRustShapes() {
        assertCommand("""{"getStatus":{"knownVersion":null}}""", CoreCommands.getStatus(null))
        assertCommand(
            """{"getStatus":{"knownVersion":"7f1c7a58-3a5e-4e5c-9d4b-2f6a1f0e8b11"}}""",
            CoreCommands.getStatus("7f1c7a58-3a5e-4e5c-9d4b-2f6a1f0e8b11"),
        )
        assertCommand("""{"getExitList":{"knownVersion":null}}""", CoreCommands.getExitList(null))
        assertCommand("""{"getExitList":{"knownVersion":"AAECAw=="}}""", CoreCommands.getExitList("AAECAw=="))
        assertCommand("""{"refreshExitList":{"freshness":60000}}""", CoreCommands.refreshExitList())
        assertCommand(
            """{"login":{"accountId":"55550123456780554337","validate":true}}""",
            CoreCommands.login("55550123456780554337"),
        )
        assertCommand("""{"setApiHostAlternate":{"host":null}}""", CoreCommands.setApiHostAlternate(null))
        assertCommand("""{"setApiUrl":{"url":null}}""", CoreCommands.setApiUrl(null))
        assertCommand("""{"setAutoConnect":{"enable":true}}""", CoreCommands.setAutoConnect(true))
        assertCommand(
            """{"setDnsContentBlock":{"value":{"ad":false,"tracker":false,"malware":false,"adult":false,"gambling":false,"socialMedia":false}}}""",
            CoreCommands.setDnsContentBlock(DnsContentBlock()),
        )
        assertCommand(
            """{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":true}}""",
            CoreCommands.setFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, true),
        )
        assertCommand("""{"setInNewAccountFlow":{"value":false}}""", CoreCommands.setInNewAccountFlow(false))
        assertCommand(
            """{"setPinnedExits":{"exits":[{"country_code":"US","city_code":"nyc","pinned_at":1700000000}]}}""",
            CoreCommands.setPinnedExits(listOf(PinnedLocation("US", "nyc", 1_700_000_000))),
        )
        assertCommand("""{"setSniRelay":{"host":null}}""", CoreCommands.setSniRelay(null))
        assertCommand("""{"setUseSystemDns":{"enable":false}}""", CoreCommands.setUseSystemDns(false))
        assertCommand("""{"setLocalNetworkAccess":{"enable":false}}""", CoreCommands.setLocalNetworkAccess(false))
        assertCommand(
            """{"createDebugBundle":{"userFeedback":null,"bundleInfo":{"AppVersion":"x","AndroidSDK":null},"androidCacheDir":"/data/user/0/app.ombra/cache"}}""",
            CoreCommands.createDebugBundle(null, BundleInfo("x"), "/data/user/0/app.ombra/cache"),
        )
    }

    @Test
    fun disconnectIsArgsNullActiveFalse() {
        assertEquals("""{"setTunnelArgs":{"args":null,"active":false}}""", CoreCommands.disconnect().encode())
        assertEquals("""{"setTunnelArgs":{"args":null,"active":true}}""", CoreCommands.connect(null).encode())
        assertEquals("""{"setTunnelArgs":{"args":null,"active":null}}""", CoreCommands.setTunnelArgs(null, null).encode())
    }

    @Test
    fun connectSendsTheFullTunnelArgs() {
        val args = TunnelArgs.of(ExitSelector.City("US", "nyc"), RelaySelector.Country("DE"), TunnelMode.Quic, 1280)
        assertCommand(
            """{"setTunnelArgs":{"args":{"exit":{"city":{"country_code":"US","city_code":"nyc"}},"relay":{"country":{"country_code":"DE"}},"mode":"quic","udpMtu":null},"active":true}}""",
            CoreCommands.connect(args),
        )
    }

    // ---- P1 / P2 shapes -----------------------------------------------------------------------

    @Test
    fun relayListCommands() {
        assertCommand("""{"getRelayList":{"knownVersion":null}}""", CoreCommands.getRelayList(null))
        assertCommand("""{"getRelayList":{"knownVersion":"AAEC"}}""", CoreCommands.getRelayList("AAEC"))
        assertCommand("""{"refreshRelayList":{"freshness":60000}}""", CoreCommands.refreshRelayList(60_000))
    }

    @Test
    fun tunnelArgsFromUpstreamDecodeWithDefaults() {
        val upstream = CoreJson.decodeFromString(TunnelArgs.serializer(), """{"exit":{"any":{}}}""")
        assertEquals(TunnelArgs(ExitSelector.Any, RelaySelector.Any, TunnelMode.Quic, null), upstream)

        // critic.md 5.1 sample.
        val pinnedRelay = CoreJson.decodeFromString(
            TunnelArgs.serializer(),
            """{"exit":{"any":{}},"relay":{"city":{"country_code":"de","city_code":"fra"}}}""",
        )
        assertEquals(RelaySelector.City("de", "fra"), pinnedRelay.relay)
    }

    @Test
    fun tunnelArgsWireguardUdp() {
        val args = roundTrip(
            TunnelArgs.serializer(),
            """{"exit":{"exit":{"id":"NYC-001"}},"relay":{"relay":{"id":"relay-1"}},"mode":"wireguardUdp","udpMtu":1400}""",
        )
        assertEquals(TunnelMode.WireguardUdp, args.mode)
        assertEquals(1400, args.udpMtu)
    }

    @Test
    fun udpMtuOnlyForWireguardUdpAndClamped() {
        assertNull(TunnelArgs.of(ExitSelector.Any, RelaySelector.Any, TunnelMode.Quic, 1400).udpMtu)
        assertNull(TunnelArgs.of(ExitSelector.Any, RelaySelector.Any, TunnelMode.TcpTls, 1400).udpMtu)
        assertEquals(1420, TunnelArgs.of(ExitSelector.Any, RelaySelector.Any, TunnelMode.WireguardUdp, 9000).udpMtu)
        assertEquals(1280, TunnelArgs.of(ExitSelector.Any, RelaySelector.Any, TunnelMode.WireguardUdp, 576).udpMtu)
    }

    @Test
    fun tunnelModeWireNames() {
        assertEquals("\"tcpTls\"", CoreJson.encodeToString(TunnelMode.serializer(), TunnelMode.TcpTls))
        assertEquals("\"wireguardUdp\"", CoreJson.encodeToString(TunnelMode.serializer(), TunnelMode.WireguardUdp))
        // Unknown modes fall back to the property default.
        val args = CoreJson.decodeFromString(TunnelArgs.serializer(), """{"exit":{"any":{}},"mode":"carrierPigeon"}""")
        assertEquals(TunnelMode.Quic, args.mode)
    }

    // ---- 9.4 nested types ---------------------------------------------------------------------

    @Test
    fun exitSelectorShapes() {
        assertEquals(ExitSelector.Any, roundTrip(ExitSelector.serializer(), """{"any":{}}"""))
        assertEquals(ExitSelector.Exit("NYC-001"), roundTrip(ExitSelector.serializer(), """{"exit":{"id":"NYC-001"}}"""))
        assertEquals(ExitSelector.Country("US"), roundTrip(ExitSelector.serializer(), """{"country":{"country_code":"US"}}"""))
        assertEquals(
            ExitSelector.City("US", "nyc"),
            roundTrip(ExitSelector.serializer(), """{"city":{"country_code":"US","city_code":"nyc"}}"""),
        )
    }

    @Test
    fun relaySelectorShapes() {
        assertEquals(RelaySelector.Any, roundTrip(RelaySelector.serializer(), """{"any":{}}"""))
        assertEquals(RelaySelector.Relay("relay-1"), roundTrip(RelaySelector.serializer(), """{"relay":{"id":"relay-1"}}"""))
        assertEquals(RelaySelector.Country("DE"), roundTrip(RelaySelector.serializer(), """{"country":{"country_code":"DE"}}"""))
        assertEquals(
            RelaySelector.City("DE", "fra"),
            roundTrip(RelaySelector.serializer(), """{"city":{"country_code":"DE","city_code":"fra"}}"""),
        )
    }

    @Test
    fun externallyTaggedRejectsBadShapes() {
        assertFailsWith<SerializationException> { CoreJson.decodeFromString(ExitSelector.serializer(), """{"teleport":{}}""") }
        assertFailsWith<SerializationException> { CoreJson.decodeFromString(ExitSelector.serializer(), """{}""") }
        assertFailsWith<SerializationException> { CoreJson.decodeFromString(ExitSelector.serializer(), "\"any\"") }
    }

    @Test
    fun oneExitFromTheApiFixture() {
        val exit = roundTrip(OneExit.serializer(), ONE_EXIT)
        assertEquals("NYC-001", exit.id)
        assertEquals("US", exit.countryCode)
        assertEquals("New York", exit.cityName)
        assertEquals(42L, exit.datacenterId)
        assertEquals("Mullvad VPN", exit.providerName)
        assertEquals(0, exit.tier)
    }

    @Test
    fun exitListLongPollResult() {
        val list = CoreCommands.getExitList(null).decode("""{"version":"AAECAw==","last_updated":1700000000,"value":{"exits":[$ONE_EXIT]}}""")
        assertEquals("AAECAw==", list.version)
        assertEquals(1_700_000_000L, list.lastUpdated)
        assertEquals("NYC-001", list.value.exits.single().id)
    }

    @Test
    fun relayListLongPollResult() {
        val list = CoreCommands.getRelayList(null).decode("""{"version":"AAEC","last_updated":1700000001,"value":[$ONE_RELAY]}""")
        val relay = list.value.single()
        assertEquals("relay-1", relay.id)
        assertEquals("fra", relay.cityCode)
        assertEquals(listOf(RelayPreferredExit("FRA-001")), relay.preferredExits)
        assertEquals("192.0.2.10", relay.ipV4)
        assertEquals(listOf(443, 8443), relay.ports)
        roundTrip(CachedValue.serializer(ListSerializer(OneRelay.serializer())), """{"version":"AAEC","last_updated":1700000001,"value":[$ONE_RELAY]}""")
    }

    @Test
    fun pinnedLocationAndDnsContentBlock() {
        roundTrip(PinnedLocation.serializer(), """{"country_code":"US","city_code":"nyc","pinned_at":1700000000}""")
        val block = roundTrip(
            DnsContentBlock.serializer(),
            """{"ad":true,"tracker":false,"malware":true,"adult":false,"gambling":false,"socialMedia":true}""",
        )
        assertTrue(block.ad && block.malware && block.socialMedia)
    }

    // ---- 10.1 / 10.2 Status -------------------------------------------------------------------

    @Test
    fun statusDisconnectedWithNullFeatureFlags() {
        val status = CoreCommands.getStatus(null).decode(statusJson("""{"disconnected":{}}"""))
        assertEquals("2b9a3c1e-0d4f-4a9b-8c6e-1f2a3b4c5d6e", status.version)
        assertEquals(VpnStatus.Disconnected, status.vpnStatus)
        assertEquals("55550123456780554337", status.accountId)
        assertTrue(status.isLoggedIn)
        assertEquals(FeatureFlags(), status.featureFlags)
        assertEquals(listOf("quicFramePadding", "killSwitch", "forceSmallMtu", "tcpTlsTunnel"), status.featureFlagKeys)
        assertEquals(ExitSelector.City("US", "nyc"), status.lastChosenExit)
        assertEquals(ExitSelector.Any, status.lastExit)
        assertEquals(listOf(PinnedLocation("US", "nyc", 1_700_000_000)), status.pinnedLocations)
        assertTrue(status.localNetworkAccess)
        val info = status.account!!.accountInfo
        assertTrue(info.active)
        assertEquals(1_800_000_000L, info.currentExpiry)
        assertEquals(FundingMethod.TopUp, info.primaryFunding)
        assertEquals(FreeTrialInfo(active = false, expiresAt = 1_600_000_000), info.freeTrial)
        assertEquals("55550123456780554337", info.id)
        assertEquals(1_700_000_100L, status.account.lastUpdatedSec)
    }

    @Test
    fun featureFlagsTrueOrNullOrAbsent() {
        val flags = CoreJson.decodeFromString(
            FeatureFlags.serializer(),
            """{"quicFramePadding":true,"killSwitch":null,"tcpTlsTunnel":true,"someFutureFlag":true}""",
        )
        assertEquals(FeatureFlags(quicFramePadding = true, killSwitch = false, forceSmallMtu = false, tcpTlsTunnel = true), flags)
    }

    @Test
    fun statusConnecting() {
        val status = CoreJson.decodeFromString(
            Status.serializer(),
            statusJson("""{"connecting":{"tunnelArgs":{"exit":{"any":{}}},"connectError":null,"reconnecting":false}}"""),
        )
        val connecting = assertIs<VpnStatus.Connecting>(status.vpnStatus)
        assertEquals(TunnelArgs(), connecting.tunnelArgs)
        assertNull(connecting.connectError)
        assertFalse(connecting.reconnecting)

        val failing = CoreJson.decodeFromString(
            VpnStatus.serializer(),
            """{"connecting":{"tunnelArgs":{"exit":{"any":{}}},"connectError":"noInternet","reconnecting":true}}""",
        )
        assertEquals(ConnectErrorCode.NoInternet, assertIs<VpnStatus.Connecting>(failing).connectError)
    }

    @Test
    fun connectErrorCodesWithUnknownFallback() {
        val all = listOf(
            "accountExpired", "apiError", "apiRateLimitExceeded", "apiUnreachable", "invalidAccountId", "noInternet",
            "noLongerSupported", "noRelay", "noSlotsLeft", "notLoggedIn", "other",
        )
        all.forEach { assertEquals(it, ConnectErrorCode.fromWire(it).wire) }
        assertEquals(ConnectErrorCode.Unknown, ConnectErrorCode.fromWire("solarFlare"))
        val vpn = CoreJson.decodeFromString(
            VpnStatus.serializer(),
            """{"connecting":{"tunnelArgs":{"exit":{"any":{}}},"connectError":"solarFlare","reconnecting":false}}""",
        )
        assertEquals(ConnectErrorCode.Unknown, assertIs<VpnStatus.Connecting>(vpn).connectError)
    }

    @Test
    fun statusConnected() {
        val status = CoreJson.decodeFromString(Status.serializer(), statusJson(connected("quic")))
        val connected = assertIs<VpnStatus.Connected>(status.vpnStatus)
        assertEquals(ExitSelector.City("US", "nyc"), connected.tunnelArgs.exit)
        assertEquals("NYC-001", connected.exit.id)
        assertEquals("relay-1", connected.relay.id)
        assertEquals("Y2xpZW50LXB1YmxpYy1rZXktMzItYnl0ZXMtLS0tLS0=", connected.clientPublicKey)
        assertEquals(TransportKind.Quic, connected.transport)
    }

    @Test
    fun transportKinds() {
        assertEquals(TransportKind.TcpTls, (CoreJson.decodeFromString(VpnStatus.serializer(), connected("tcpTls")) as VpnStatus.Connected).transport)
        assertEquals(
            TransportKind.WireguardUdp,
            (CoreJson.decodeFromString(VpnStatus.serializer(), connected("wireguardUdp")) as VpnStatus.Connected).transport,
        )
        assertEquals(
            TransportKind.Unknown,
            (CoreJson.decodeFromString(VpnStatus.serializer(), connected("smokeSignals")) as VpnStatus.Connected).transport,
        )
    }

    @Test
    fun statusIgnoresUnknownKeys() {
        val json = statusJson("""{"disconnected":{"futureField":1}}""").replaceFirst("{", """{"brandNewTopLevel":{"a":[1,2]},""")
        assertEquals(VpnStatus.Disconnected, CoreJson.decodeFromString(Status.serializer(), json).vpnStatus)
    }

    @Test
    fun loggedOutStatus() {
        val json = statusJson("""{"disconnected":{}}""")
            .replace(""""accountId":"55550123456780554337"""", """"accountId":null""")
            .replace(Regex(""""account":\{.*\},"autoConnect""""), """"account":null,"autoConnect"""")
        val status = CoreJson.decodeFromString(Status.serializer(), json)
        assertNull(status.accountId)
        assertNull(status.account)
        assertFalse(status.isLoggedIn)
    }

    @Test
    fun secretsStayOutOfToString() {
        val status = CoreJson.decodeFromString(Status.serializer(), statusJson("""{"disconnected":{}}"""))
        assertFalse("55550123456780554337" in status.toString())
        assertFalse("55550123456780554337" in status.account!!.accountInfo.toString())
        assertFalse("55550123456780554337" in CoreCommands.login("55550123456780554337").toString())
    }

    // ---- 10.5 and other results ---------------------------------------------------------------

    @Test
    fun trafficStats() {
        val stats = CoreCommands.getTrafficStats().decode(
            """{"connectedMs":61000,"connId":"0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0","txBytes":1234,"rxBytes":567890,"latestLatencyMs":42}""",
        )
        assertEquals(TrafficStats(61_000, "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0", 1234, 567_890, 42), stats)
    }

    @Test
    fun serviceStatus() {
        val status = CoreCommands.apiServiceStatus().decode(
            """{"free_trial":{"available":true},"nnn":{"claimed_months_target":100000,"claimed_months_total":1234,"submissions_open":true}}""",
        )
        assertTrue(status.freeTrial.available)
        assertEquals(1234L, status.nnn.claimedMonthsTotal)
    }

    @Test
    fun osNetworkConfig() {
        val config = roundTrip(
            OsNetworkConfig.serializer(),
            """{"dns":["100.64.0.3"],"ipv4":"10.75.76.77","ipv6":"fc00:bbbb:bbbb:bb01::c:4c4d/128","routes":[{"address":"0.0.0.0","prefix":0},{"address":"::","prefix":0}],"mtu":1280,"useSystemDns":false}""",
        )
        assertEquals(listOf(Route("0.0.0.0", 0), Route("::", 0)), config.routes)
        assertEquals(1280, config.mtu)
    }

    @Test
    fun unitResultsAreJsonNull() {
        CoreCommands.ping().decode("null")
        assertFailsWith<IllegalArgumentException> { CoreCommands.ping().decode("{}") }
        CoreCommands.apiDeleteAccount().decode("{}")
        assertEquals("/data/user/0/app.ombra/cache/debug-archives/b.zip", CoreCommands.createDebugBundle(null, BundleInfo("x"), "/c").decode("\"/data/user/0/app.ombra/cache/debug-archives/b.zip\""))
    }

    @Test
    fun longPollsHaveNoTimeout() {
        assertNull(CoreCommands.getStatus(null).timeout)
        assertNull(CoreCommands.getExitList(null).timeout)
        assertNull(CoreCommands.getRelayList(null).timeout)
        assertEquals(CoreCommands.LOCAL_TIMEOUT, CoreCommands.ping().timeout)
        assertEquals(CoreCommands.API_TIMEOUT, CoreCommands.login("1").timeout)
    }

    @Test
    fun managerCmdErrorCodes() {
        val all = listOf(
            "apiAssociateAccountConflict", "apiError", "apiInvalidAccountId", "apiNoLongerSupported", "apiRateLimitExceeded",
            "apiSaleNotFound", "apiSignupLimitExceeded", "apiUnreachable", "configSaveError", "notLoggedIn", "other",
        )
        all.forEach { assertEquals(it, ManagerCmdErrorCode.fromWire(it).wire) }
        assertEquals(ManagerCmdErrorCode.Unknown, ManagerCmdErrorCode.fromWire("timeout"))
        assertEquals(ManagerCmdErrorCode.Unknown, ManagerCmdErrorCode.fromWire(null))
    }

    @Test
    fun fundingMethodFallback() {
        assertEquals(FundingMethod.Unknown, CoreJson.decodeFromString(FundingMethod.serializer(), "\"snail_mail\""))
        assertEquals("\"stripe_subscription\"", CoreJson.encodeToString(FundingMethod.serializer(), FundingMethod.StripeSubscription))
    }

    @Test
    fun jsonStringHelpers() {
        assertEquals("\"a\\\"b\"", jsonString("a\"b"))
        assertEquals("null", jsonString(null))
        assertEquals("a\"b", parseJsonString(jsonString("a\"b")))
        assertNull(parseJsonString("null"))
    }

    private fun connected(transport: String) =
        """{"connected":{"tunnelArgs":{"exit":{"city":{"country_code":"US","city_code":"nyc"}}},"exit":$ONE_EXIT,"relay":$ONE_RELAY,"clientPublicKey":"Y2xpZW50LXB1YmxpYy1rZXktMzItYnl0ZXMtLS0tLS0=","exitPublicKey":"ZXhpdC1wdWJsaWMta2V5LTMyLWJ5dGVzLS0tLS0tLS0=","transport":"$transport"}}"""

    companion object {
        // obscuravpn-api/src/cmd/exit2.rs test fixture.
        const val ONE_EXIT =
            """{"id":"NYC-001","country_code":"US","city_code":"nyc","city_name":"New York","datacenter_id":42,"provider_id":"us-nyc-wg-301","provider_url":"https://mullvad.net/servers","provider_name":"Mullvad VPN","provider_homepage_url":"https://mullvad.net","tier":0}"""

        const val ONE_RELAY =
            """{"id":"relay-1","country_code":"DE","city_code":"fra","city_name":"Frankfurt","preferred_exits":[{"id":"FRA-001"}],"ip_v4":"192.0.2.10","ports":[443,8443],"tls_cert":"MIIBszCCAVmgAwIBAgIUQ0VSVA=="}"""

        const val ACCOUNT_INFO =
            """{"active":true,"apple_subscription":null,"auto_renews":null,"current_expiry":1800000000,"free_month_on_next_payment":false,"free_trial":{"active":false,"expires_at":1600000000},"google_subscription":null,"has_paid":true,"has_referrer":false,"id":"55550123456780554337","monero_pending_payments":[{"id":"m-1","status":"confirming"}],"nnn":null,"primary_funding":"top_up","received_free_months":0,"referral_code":"REF123","stripe_subscription":null,"top_up":{"active":true,"credit_expires_at":1800000000}}"""

        /** rust-ffi.md 10.1 with a concrete [vpnStatus]. */
        fun statusJson(vpnStatus: String) =
            """{"version":"2b9a3c1e-0d4f-4a9b-8c6e-1f2a3b4c5d6e","vpnStatus":$vpnStatus,"accountId":"55550123456780554337","inNewAccountFlow":false,"pinnedLocations":[{"country_code":"US","city_code":"nyc","pinned_at":1700000000}],"lastChosenExit":{"city":{"country_code":"US","city_code":"nyc"}},"lastExit":{"any":{}},"apiUrl":"https://v1.api.prod.obscura.net/api","account":{"account_info":$ACCOUNT_INFO,"last_updated_sec":1700000100},"autoConnect":false,"featureFlags":{"quicFramePadding":null,"killSwitch":null,"forceSmallMtu":null,"tcpTlsTunnel":null},"featureFlagKeys":["quicFramePadding","killSwitch","forceSmallMtu","tcpTlsTunnel"],"useSystemDns":false,"localNetworkAccess":true,"tailscaleBypass":true,"wireguardBypass":true,"dnsContentBlock":{"ad":false,"tracker":false,"malware":false,"adult":false,"gambling":false,"socialMedia":false}}"""
    }
}
