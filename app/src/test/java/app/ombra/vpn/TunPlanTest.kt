package app.ombra.vpn

import app.ombra.core.model.CoreJson
import app.ombra.core.model.OsNetworkConfig
import app.ombra.core.model.Route
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TunPlanTest {
    private val own = "app.ombra"
    private val installed = setOf("org.browser", "org.chat", "org.mail")

    /** The dummy config that Rust installs while it connects (rustlib network_config.rs). */
    private val dummy = CoreJson.decodeFromString(
        OsNetworkConfig.serializer(),
        """{"dns":["10.64.0.99"],"ipv4":"10.75.76.77","ipv6":"fc00:bbbb:bbbb:bb01::c:4c4d/128",
           "routes":[{"address":"0.0.0.0","prefix":0},{"address":"::","prefix":0}],"mtu":1280,"useSystemDns":false}""",
    )

    /** Addresses that VpnService.Builder.addDnsServer refuses (loopback, any-local). */
    private val refused = setOf("127.0.0.1", "::1", "0.0.0.0", "::", "::ffff:127.0.0.1")

    /**
     * No resolver here: anything with a dot-digit or a colon shape counts as an IP literal, and
     * [refused] stands in for the Builder's check.
     */
    private fun isUsableDns(value: String): Boolean = value.matches(Regex("""[0-9.]+|[0-9a-fA-F:]+""")) && value !in refused

    private fun plan(config: OsNetworkConfig = dummy, settings: Settings = Settings()) =
        TunPlan.of(config, settings, own, ::isUsableDns) { it in installed }

    @Test
    fun mapsTheDummyConfig() {
        val plan = plan()
        assertEquals(1280, plan.mtu)
        assertEquals(listOf(Cidr("10.75.76.77", 32), Cidr("fc00:bbbb:bbbb:bb01::c:4c4d", 128)), plan.addresses)
        assertEquals(listOf(Cidr("0.0.0.0", 0), Cidr("::", 0)), plan.routes)
        assertEquals(listOf("10.64.0.99"), plan.dnsServers)
        assertEquals(AppScope.AllExcept(listOf(own)), plan.apps)
    }

    @Test
    fun keepsTheIpv6PrefixAndEveryRoute() {
        val config = dummy.copy(
            ipv6 = "fc00:bbbb:bbbb:bb01::1:2/64",
            routes = listOf(Route("0.0.0.0", 1), Route("128.0.0.0", 1), Route("::", 1), Route("8000::", 1)),
            mtu = 1420,
        )
        val plan = plan(config)
        assertEquals(Cidr("fc00:bbbb:bbbb:bb01::1:2", 64), plan.addresses[1])
        assertEquals(config.routes.map { Cidr(it.address, it.prefix) }, plan.routes)
        assertEquals(1420, plan.mtu)
    }

    @Test
    fun customDnsReplacesTheTunnelDns() {
        val plan = plan(settings = Settings(customDns = listOf("9.9.9.9", "2620:fe::fe")))
        assertEquals(listOf("9.9.9.9", "2620:fe::fe"), plan.dnsServers)
    }

    @Test
    fun invalidCustomDnsEntriesAreSkipped() {
        assertEquals(listOf("9.9.9.9"), plan(settings = Settings(customDns = listOf("dns.example", "9.9.9.9"))).dnsServers)
    }

    @Test
    fun onlyInvalidCustomDnsFallsBackToTheTunnelDns() {
        // A TUN without DNS servers would send queries to the resolvers of the physical network.
        assertEquals(listOf("10.64.0.99"), plan(settings = Settings(customDns = listOf("dns.example"))).dnsServers)
    }

    @Test
    fun customDnsThatTheBuilderRefusesIsSkipped() {
        // One such entry would make every TUN build fail, so the tunnel would never come up.
        assertEquals(listOf("9.9.9.9"), plan(settings = Settings(customDns = listOf("127.0.0.1", "9.9.9.9"))).dnsServers)
        for (bad in refused) {
            assertEquals(listOf("10.64.0.99"), plan(settings = Settings(customDns = listOf(bad))).dnsServers, bad)
        }
    }

    @Test
    fun systemDnsAddsNoDnsServer() {
        val plan = plan(dummy.copy(useSystemDns = true), Settings(useSystemDns = true, customDns = listOf("9.9.9.9")))
        assertEquals(emptyList(), plan.dnsServers)
    }

    @Test
    fun systemDnsNeedsRustAndTheSettingsToAgree() {
        assertEquals(listOf("10.64.0.99"), plan(dummy.copy(useSystemDns = true), Settings()).dnsServers)
        assertEquals(listOf("10.64.0.99"), plan(dummy, Settings(useSystemDns = true)).dnsServers)
    }

    @Test
    fun splitTunnelOffExcludesOnlyOurOwnPackage() {
        val plan = plan(settings = Settings(splitMode = SplitMode.Off, splitApps = setOf("org.chat")))
        assertEquals(AppScope.AllExcept(listOf(own)), plan.apps)
    }

    @Test
    fun excludeModeAddsTheInstalledAppsToOurOwnPackage() {
        val settings = Settings(splitMode = SplitMode.Exclude, splitApps = setOf("org.mail", "org.gone", own, "org.chat"))
        assertEquals(AppScope.AllExcept(listOf(own, "org.chat", "org.mail")), plan(settings = settings).apps)
    }

    @Test
    fun includeModeListsOnlyTheInstalledAppsAndNeverOurPackage() {
        val settings = Settings(splitMode = SplitMode.Include, splitApps = setOf("org.mail", "org.gone", own, "org.browser"))
        assertEquals(AppScope.Only(listOf("org.browser", "org.mail")), plan(settings = settings).apps)
    }

    @Test
    fun includeModeWithoutInstalledAppsProtectsEveryApp() {
        // An empty allow-list must not turn into "every app outside" nor put Ombra's own sockets inside.
        val settings = Settings(splitMode = SplitMode.Include, splitApps = setOf("org.gone", own))
        assertEquals(AppScope.AllExcept(listOf(own)), plan(settings = settings).apps)
    }
}
