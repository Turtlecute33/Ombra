package app.ombra.vpn

import app.ombra.core.model.OsNetworkConfig
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import java.net.InetAddress

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
         * fails every TUN build. [isInstalled] is false for packages that are not on the device (any
         * more) and for those that Ombra cannot see: the system ignores uninstalled ones, so an
         * allow-list of only such packages would leave every app outside the tunnel.
         */
        fun of(
            config: OsNetworkConfig,
            settings: Settings,
            ownPackage: String,
            isUsableDns: (String) -> Boolean,
            isInstalled: (String) -> Boolean,
        ): TunPlan {
            val routes = config.routes.map { Cidr(it.address, it.prefix) }
            val dns = dnsServers(config, settings, isUsableDns)
            val plan = TunPlan(
                mtu = config.mtu,
                addresses = listOf(cidr(config.ipv4, IPV4_HOST_PREFIX), cidr(config.ipv6, IPV6_HOST_PREFIX)),
                routes = routes + dnsHostRoutes(dns, routes),
                dnsServers = dns,
                apps = appScope(settings, ownPackage, isInstalled),
            )
            // Fail closed: a TUN without the routes of Rust captures nothing (the DNS host routes do not count), and one without DNS servers sends
            // queries to the resolvers of the physical network. Rust then keeps the TUN it has.
            require(routes.isNotEmpty()) { "no routes" }
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

        /**
         * With local network access on, Rust keeps only its own DNS servers inside the tunnel. A custom
         * server in a LAN range gets a host route here, so its queries do not go in clear on the
         * local network (VPN-INTEGRATION-5).
         */
        fun dnsHostRoutes(dns: List<String>, routes: List<Cidr>): List<Cidr> = dns.mapNotNull { server ->
            val address = ipLiteral(server) ?: return@mapNotNull null
            if (routes.any { route -> ipLiteral(route.address)?.let { covers(it, route.prefix, address) } == true }) null
            else Cidr(server, address.size * Byte.SIZE_BITS)
        }

        /** The bytes of an IP literal; null for anything else, so that a name is never resolved. */
        private fun ipLiteral(text: String): ByteArray? {
            if (!IP_LITERAL.matches(text) || (':' !in text && text.count { it == '.' } != 3)) return null
            return runCatching { InetAddress.getByName(text).address }.getOrNull()
        }

        private fun covers(network: ByteArray, prefix: Int, address: ByteArray): Boolean {
            if (network.size != address.size) return false
            var bits = prefix
            for (i in network.indices) {
                if (bits <= 0) return true
                val mask = (0xFF shl (Byte.SIZE_BITS - bits.coerceAtMost(Byte.SIZE_BITS))) and 0xFF
                if ((network[i].toInt() and mask) != (address[i].toInt() and mask)) return false
                bits -= Byte.SIZE_BITS
            }
            return true
        }

        private val IP_LITERAL = Regex("[0-9.]+|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*")

        private fun usesSystemDns(config: OsNetworkConfig, settings: Settings) = config.useSystemDns && settings.useSystemDns

        /**
         * Rust never calls protect(): its sockets leave the tunnel only because our own package is
         * outside it. So "all except" always lists us and "only" never does. API requests thus go
         * outside too, with the API name in DNS and TLS, also in Stealth mode (OMBRA-PRIV-003).
         */
        fun appScope(settings: Settings, ownPackage: String, isInstalled: (String) -> Boolean): AppScope {
            val listed = (settings.splitApps - ownPackage).sorted()
            val installed = listed.filter(isInstalled)
            return when (settings.splitMode) {
                SplitMode.Off -> AppScope.AllExcept(listOf(ownPackage))
                SplitMode.Exclude -> AppScope.AllExcept(listOf(ownPackage) + installed)
                // With no chosen app left, protect every app rather than none. system_server resolves
                // the allowed packages itself and skips uninstalled ones, so a hidden app stays inside.
                SplitMode.Include -> if (installed.isEmpty()) AppScope.AllExcept(listOf(ownPackage)) else AppScope.Only(listed)
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
