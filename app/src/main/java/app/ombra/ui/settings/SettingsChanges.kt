package app.ombra.ui.settings

import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.TunnelArgs
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode

// Pure changes of Settings. A screen sends one through SettingsEditor, which turns it into a patch
// of the changed keys only (Settings.patchFrom).

/** The six Mullvad DNS block lists. */
enum class BlockList { Ads, Trackers, Malware, Adult, Gambling, SocialMedia }

fun DnsContentBlock.isOn(list: BlockList): Boolean = when (list) {
    BlockList.Ads -> ad
    BlockList.Trackers -> tracker
    BlockList.Malware -> malware
    BlockList.Adult -> adult
    BlockList.Gambling -> gambling
    BlockList.SocialMedia -> socialMedia
}

fun DnsContentBlock.with(list: BlockList, on: Boolean): DnsContentBlock = when (list) {
    BlockList.Ads -> copy(ad = on)
    BlockList.Trackers -> copy(tracker = on)
    BlockList.Malware -> copy(malware = on)
    BlockList.Adult -> copy(adult = on)
    BlockList.Gambling -> copy(gambling = on)
    BlockList.SocialMedia -> copy(socialMedia = on)
}

fun Settings.withBlockList(list: BlockList, on: Boolean): Settings = copy(dnsBlock = dnsBlock.with(list, on))

/** Adds a DNS server that passed [dnsEntryProblem]. */
fun Settings.withDnsServer(address: String): Settings = copy(customDns = customDns + address.trim())

fun Settings.withoutDnsServer(address: String): Settings = copy(customDns = customDns - address)

/** The UDP MTU snaps to [UDP_MTU_STEP] within the range that Rust accepts. */
fun Settings.withUdpMtu(mtu: Int): Settings = copy(udpMtu = snapUdpMtu(mtu))

fun snapUdpMtu(mtu: Int): Int {
    val min = TunnelArgs.UDP_MTU_MIN
    val steps = Math.round((mtu - min).toFloat() / UDP_MTU_STEP)
    return (min + steps * UDP_MTU_STEP).coerceIn(min, TunnelArgs.UDP_MTU_MAX)
}

const val UDP_MTU_STEP = 10

/** Adds [packageName] to the split tunnel list, or removes it. */
fun Settings.withSplitApp(packageName: String, selected: Boolean): Settings =
    copy(splitApps = if (selected) splitApps + packageName else splitApps - packageName)

/** A host name field of Advanced: blank goes back to the default of the core. */
fun Settings.withSniRelay(host: String): Settings = copy(sniRelay = host.trim().ifEmpty { null })

fun Settings.withApiHostAlternate(host: String): Settings = copy(apiHostAlternate = host.trim().ifEmpty { null })

/** Where DNS queries go, in the order the tunnel decides it. */
sealed interface DnsRoute {
    /** The resolvers of the physical network, outside the tunnel. */
    data object Network : DnsRoute

    data class Custom(val servers: List<String>) : DnsRoute

    /** Obscura's DNS through the tunnel, with [blockLists] on (they work only on Mullvad exits). */
    data class Tunnel(val blockLists: Int) : DnsRoute
}

fun Settings.dnsRoute(): DnsRoute = when {
    useSystemDns -> DnsRoute.Network
    customDns.isNotEmpty() -> DnsRoute.Custom(customDns)
    else -> DnsRoute.Tunnel(BlockList.entries.count { dnsBlock.isOn(it) })
}

/** The block lists apply only when the tunnel DNS answers. */
val Settings.blockListsApply: Boolean get() = dnsRoute() is DnsRoute.Tunnel

/** The number of apps that split tunnelling affects; zero when it is off. */
val Settings.splitAppCount: Int get() = if (splitMode == SplitMode.Off) 0 else splitApps.size

/** Upstream's check (rustlib network_config.rs): DNS blocking works only on exits run by Mullvad. */
const val MULLVAD_PROVIDER_NAME = "Mullvad VPN"
