package app.ombra.vpn

import app.ombra.core.model.OsNetworkConfig
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode

/** An address or route with its prefix length. */
data class Cidr(val address: String, val prefix: Int)

/** Which apps the TUN covers. VpnService.Builder accepts only one of the two lists. */
sealed interface AppScope {
    data class AllExcept(val packages: List<String>) : AppScope

    data class Only(val packages: List<String>) : AppScope
}

/**
 * The TUN for one OsNetworkConfig from Rust, as plain values so that JVM tests cover the mapping.
 * TunConfigurator turns it into VpnService.Builder calls. The same mapping serves the dummy config
 * that Rust sets while it connects.
 */
data class TunPlan(
    val mtu: Int,
    val addresses: List<Cidr>,
    val routes: List<Cidr>,
    val dnsServers: List<String>,
    val apps: AppScope,
) {
    companion object {
        /**
         * [isUsableDns] accepts what VpnService.Builder.addDnsServer accepts: an IP literal (never
         * resolve names) that is not loopback or any-local. One entry that the Builder refuses
         * fails every TUN build. [isInstalled] filters out packages that are not on the device (any
         * more): the system ignores them, so an allow-list of only such packages would leave every
         * app outside the tunnel.
         */
        fun of(
            config: OsNetworkConfig,
            settings: Settings,
            ownPackage: String,
            isUsableDns: (String) -> Boolean,
            isInstalled: (String) -> Boolean,
        ): TunPlan {
            val plan = TunPlan(
                mtu = config.mtu,
                addresses = listOf(cidr(config.ipv4, IPV4_HOST_PREFIX), cidr(config.ipv6, IPV6_HOST_PREFIX)),
                routes = config.routes.map { Cidr(it.address, it.prefix) },
                dnsServers = dnsServers(config, settings, isUsableDns),
                apps = appScope(settings, ownPackage, isInstalled),
            )
            // Fail closed: a TUN without routes captures nothing, and one without DNS servers sends
            // queries to the resolvers of the physical network. Rust then keeps the TUN it has.
            require(plan.routes.isNotEmpty()) { "no routes" }
            require(plan.dnsServers.isNotEmpty() || usesSystemDns(config, settings)) { "no usable DNS server" }
            return plan
        }

        /**
         * System DNS only when Rust and the settings both ask for it, so a stale value on one side
         * never sends queries outside the tunnel. A TUN without DNS servers makes Android use the
         * resolvers of the physical network, so unusable custom entries fall back to the tunnel DNS.
         */
        fun dnsServers(config: OsNetworkConfig, settings: Settings, isUsableDns: (String) -> Boolean): List<String> {
            if (usesSystemDns(config, settings)) return emptyList()
            return settings.customDns.filter(isUsableDns).ifEmpty { config.dns.filter(isUsableDns) }
        }

        private fun usesSystemDns(config: OsNetworkConfig, settings: Settings) = config.useSystemDns && settings.useSystemDns

        /**
         * Rust never calls protect(): its sockets leave the tunnel only because our own package is
         * outside it. So "all except" always lists us and "only" never does.
         */
        fun appScope(settings: Settings, ownPackage: String, isInstalled: (String) -> Boolean): AppScope {
            val chosen = (settings.splitApps - ownPackage).filter(isInstalled).sorted()
            return when (settings.splitMode) {
                SplitMode.Off -> AppScope.AllExcept(listOf(ownPackage))
                SplitMode.Exclude -> AppScope.AllExcept(listOf(ownPackage) + chosen)
                // With no chosen app left, protect every app rather than none.
                SplitMode.Include -> if (chosen.isEmpty()) AppScope.AllExcept(listOf(ownPackage)) else AppScope.Only(chosen)
            }
        }

        private const val IPV4_HOST_PREFIX = 32
        private const val IPV6_HOST_PREFIX = 128

        /** Rust sends the IPv4 address bare and the IPv6 one as `addr/prefix`. */
        private fun cidr(value: String, defaultPrefix: Int): Cidr {
            val parts = value.split('/', limit = 2)
            return Cidr(parts[0], parts.getOrNull(1)?.toInt() ?: defaultPrefix)
        }
    }
}
