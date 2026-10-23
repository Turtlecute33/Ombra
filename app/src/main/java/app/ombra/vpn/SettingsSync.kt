package app.ombra.vpn

import app.ombra.core.model.CoreCall
import app.ombra.core.model.CoreCommands
import app.ombra.core.model.FeatureFlag
import app.ombra.core.model.TunnelMode
import app.ombra.ipc.Settings

/**
 * The Rust side of [Settings]: which core commands bring Rust from one settings value to another.
 * Kotlin-only values (split tunnel, custom DNS, pinned relays, notification, diagnostics) have no
 * command. setTunnelArgs comes last, so a reconnect that it starts already uses the new flags.
 */
object SettingsSync {
    /** Every Rust-side value, for a core whose config is new or was just reset (init, login, logout). */
    fun all(settings: Settings): List<CoreCall<Unit>> = changes(before = null, after = settings)

    /** The commands for the values that differ; [before] null means all of them. */
    fun changes(before: Settings?, after: Settings): List<CoreCall<Unit>> = buildList {
        fun changed(value: (Settings) -> Any?): Boolean = before == null || value(before) != value(after)

        if (changed { it.lanAccess }) add(CoreCommands.setLocalNetworkAccess(after.lanAccess))
        if (changed { it.dnsBlock }) add(CoreCommands.setDnsContentBlock(after.dnsBlock))
        if (changed { it.useSystemDns }) add(CoreCommands.setUseSystemDns(after.useSystemDns))
        if (changed { it.padding }) add(CoreCommands.setFeatureFlag(FeatureFlag.QUIC_FRAME_PADDING, after.padding))
        if (changed { it.forceSmallMtu }) add(CoreCommands.setFeatureFlag(FeatureFlag.FORCE_SMALL_MTU, after.forceSmallMtu))
        // Cores without patch P1 ignore TunnelArgs.mode; there the flag selects TCP/TLS.
        if (changed(::usesTcpTls)) add(CoreCommands.setFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, usesTcpTls(after)))
        if (changed { it.sniRelay }) add(CoreCommands.setSniRelay(after.sniRelay))
        if (changed { it.apiHostAlternate }) add(CoreCommands.setApiHostAlternate(after.apiHostAlternate))
        if (changed { it.pinnedExits }) add(CoreCommands.setPinnedExits(after.pinnedExits))
        if (changed { it.autoConnectOnLaunch }) add(CoreCommands.setAutoConnect(after.autoConnectOnLaunch))
        // active = null: a running tunnel reconnects with the new args, a stopped one only stores them.
        if (changed { it.tunnelArgs() }) add(CoreCommands.setTunnelArgs(after.tunnelArgs(), active = null))
    }

    /**
     * True when a value changed that only Kotlin applies, while it builds the TUN in
     * ffiSetNetworkConfig. A live tunnel then needs `refreshOsNetworkConfig`.
     */
    fun tunChanged(before: Settings, after: Settings): Boolean =
        before.customDns != after.customDns || before.splitMode != after.splitMode || before.splitApps != after.splitApps

    private fun usesTcpTls(settings: Settings): Boolean = settings.mode == TunnelMode.TcpTls
}
