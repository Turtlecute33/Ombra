package app.ombra.vpn

import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.FeatureFlag
import app.ombra.core.model.ManagerCmd
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import app.ombra.ipc.KeyRotation
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSyncTest {
    private val base = Settings()

    private fun commands(after: Settings, before: Settings? = base): List<ManagerCmd> =
        SettingsSync.changes(before, after).map { it.command }

    @Test
    fun fullApplySendsEveryRustSettingWithTunnelArgsLast() {
        val settings = Settings(
            exit = ExitSelector.Country("DE"),
            relay = RelaySelector.City("NL", "ams"),
            mode = TunnelMode.TcpTls,
            padding = true,
            dnsBlock = DnsContentBlock(ad = true),
            lanAccess = false,
            pinnedExits = listOf(PinnedLocation("DE", "ber", 1_700_000_000)),
            autoConnectOnLaunch = true,
            sniRelay = "example.org",
            keyRotation = KeyRotation.Day1,
        )
        assertEquals(
            listOf(
                ManagerCmd.SetLocalNetworkAccess(false),
                ManagerCmd.SetDnsContentBlock(DnsContentBlock(ad = true)),
                ManagerCmd.SetUseSystemDns(false),
                ManagerCmd.SetFeatureFlag(FeatureFlag.QUIC_FRAME_PADDING, true),
                ManagerCmd.SetFeatureFlag(FeatureFlag.FORCE_SMALL_MTU, false),
                ManagerCmd.SetFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, true),
                ManagerCmd.SetSniRelay("example.org"),
                ManagerCmd.SetApiHostAlternate(null),
                ManagerCmd.SetPinnedExits(listOf(PinnedLocation("DE", "ber", 1_700_000_000))),
                ManagerCmd.SetAutoConnect(true),
                ManagerCmd.SetWgKeyMaxAge(86_400),
                ManagerCmd.SetTunnelArgs(
                    TunnelArgs(ExitSelector.Country("DE"), RelaySelector.City("NL", "ams"), TunnelMode.TcpTls),
                    active = null,
                ),
            ),
            SettingsSync.all(settings).map { it.command },
        )
    }

    @Test
    fun eachRustSettingMapsToItsCommand() {
        assertEquals(listOf(ManagerCmd.SetLocalNetworkAccess(true)), commands(base.copy(lanAccess = true)))
        assertEquals(
            listOf(ManagerCmd.SetDnsContentBlock(DnsContentBlock(malware = true))),
            commands(base.copy(dnsBlock = DnsContentBlock(malware = true))),
        )
        assertEquals(listOf(ManagerCmd.SetUseSystemDns(true)), commands(base.copy(useSystemDns = true)))
        assertEquals(
            listOf(ManagerCmd.SetFeatureFlag(FeatureFlag.QUIC_FRAME_PADDING, true)),
            commands(base.copy(padding = true)),
        )
        assertEquals(
            listOf(ManagerCmd.SetFeatureFlag(FeatureFlag.FORCE_SMALL_MTU, true)),
            commands(base.copy(forceSmallMtu = true)),
        )
        assertEquals(listOf(ManagerCmd.SetSniRelay("example.org")), commands(base.copy(sniRelay = "example.org")))
        assertEquals(
            listOf(ManagerCmd.SetApiHostAlternate("alt.example.net")),
            commands(base.copy(apiHostAlternate = "alt.example.net")),
        )
        val pinned = listOf(PinnedLocation("US", "nyc", 1))
        assertEquals(listOf(ManagerCmd.SetPinnedExits(pinned)), commands(base.copy(pinnedExits = pinned)))
        assertEquals(listOf(ManagerCmd.SetAutoConnect(true)), commands(base.copy(autoConnectOnLaunch = true)))
        assertEquals(listOf(ManagerCmd.SetWgKeyMaxAge(0)), commands(base.copy(keyRotation = KeyRotation.EachConnect)))
    }

    @Test
    fun locationChangeSendsTunnelArgsWithoutTouchingTheConnectState() {
        val city = base.copy(exit = ExitSelector.City("US", "nyc"))
        assertEquals(listOf(ManagerCmd.SetTunnelArgs(TunnelArgs(exit = ExitSelector.City("US", "nyc")), null)), commands(city))
        val relay = base.copy(relay = RelaySelector.Relay("r1"))
        assertEquals(listOf(ManagerCmd.SetTunnelArgs(TunnelArgs(relay = RelaySelector.Relay("r1")), null)), commands(relay))
    }

    @Test
    fun tcpTlsModeSetsTheFlagBeforeTheTunnelArgs() {
        val tcp = base.copy(mode = TunnelMode.TcpTls)
        assertEquals(
            listOf(
                ManagerCmd.SetFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, true),
                ManagerCmd.SetTunnelArgs(TunnelArgs(mode = TunnelMode.TcpTls), null),
            ),
            commands(tcp),
        )
        assertEquals(
            listOf(
                ManagerCmd.SetFeatureFlag(FeatureFlag.TCP_TLS_TUNNEL, false),
                ManagerCmd.SetTunnelArgs(TunnelArgs(mode = TunnelMode.WireguardUdp, udpMtu = 1280), null),
            ),
            commands(base.copy(mode = TunnelMode.WireguardUdp), before = tcp),
        )
    }

    @Test
    fun udpMtuMattersOnlyInSpeedMode() {
        assertEquals(emptyList(), commands(base.copy(udpMtu = 1400)))
        val speed = base.copy(mode = TunnelMode.WireguardUdp)
        assertEquals(
            listOf(ManagerCmd.SetTunnelArgs(TunnelArgs(mode = TunnelMode.WireguardUdp, udpMtu = 1400), null)),
            commands(speed.copy(udpMtu = 1400), before = speed),
        )
    }

    @Test
    fun kotlinOnlySettingsSendNothing() {
        val kotlinOnly = base.copy(
            customDns = listOf("9.9.9.9"),
            splitMode = SplitMode.Exclude,
            splitApps = setOf("org.example"),
            pinnedRelays = listOf("r1"),
            diagnostics = true,
            hideNotificationDetails = false,
        )
        assertEquals(emptyList(), commands(kotlinOnly))
        assertEquals(emptyList(), commands(base))
    }

    @Test
    fun tunChangesAreCustomDnsAndSplitTunnel() {
        assertTrue(SettingsSync.tunChanged(base, base.copy(customDns = listOf("9.9.9.9"))))
        assertTrue(SettingsSync.tunChanged(base, base.copy(splitMode = SplitMode.Include)))
        assertTrue(SettingsSync.tunChanged(base, base.copy(splitApps = setOf("org.example"))))
        assertFalse(SettingsSync.tunChanged(base, base.copy(lanAccess = true, exit = ExitSelector.Country("DE"))))
    }
}
