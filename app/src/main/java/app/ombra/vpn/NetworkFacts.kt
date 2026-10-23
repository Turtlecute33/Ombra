package app.ombra.vpn

import app.ombra.ipc.SystemState

/**
 * What the LinkProperties of the physical network tell us. NetworkMonitor reads it only when they
 * change, because the interface lookups cost syscalls.
 */
data class LinkFacts(
    val interfaceName: String?,
    /** -1 when unknown. */
    val interfaceIndex: Int,
    /** CLAT-aware MTU (see [clatAwareMtu]), 0 when unknown. */
    val mtu: Int,
    val privateDnsActive: Boolean,
    /** Set only in strict mode (a host name). */
    val privateDnsServer: String?,
)

/** The arguments of RustFfi.setNetworkInterface. */
data class RustNetwork(val name: String, val index: Int, val networkHandle: Long, val mtuHint: Int)

/** Prefix of the IPv4 interface that clatd stacks on an IPv6-only network (464XLAT). */
const val CLAT_INTERFACE_PREFIX = "v4-"

/**
 * The MTU that IPv4 packets can use. Rust sends IPv4 only, and on an IPv6-only network they go
 * through the CLAT interface, whose MTU is smaller than the base one. 0 means unknown.
 */
fun clatAwareMtu(interfaceMtu: Int, clatMtu: Int): Int = listOf(interfaceMtu, clatMtu).filter { it > 0 }.minOrNull() ?: 0

/** Null when Rust cannot use the network yet: Rust needs a name and an index >= 1. */
fun rustNetwork(networkHandle: Long, link: LinkFacts?): RustNetwork? {
    val name = link?.interfaceName ?: return null
    if (link.interfaceIndex < 1) return null
    return RustNetwork(name, link.interfaceIndex, networkHandle, link.mtu)
}

/** [link] null with [cellular] false means "no network". */
fun SystemState.withNetwork(link: LinkFacts?, cellular: Boolean): SystemState = copy(
    privateDnsActive = link?.privateDnsActive == true,
    privateDnsServer = link?.privateDnsServer,
    cellular = cellular,
    underlyingMtu = link?.mtu ?: 0,
)
