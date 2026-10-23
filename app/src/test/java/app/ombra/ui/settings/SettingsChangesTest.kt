package app.ombra.ui.settings

import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.TunnelMode
import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsChangesTest {
    private val base = Settings()

    @Test
    fun aBlockListPatchSendsAllSixLists() {
        // Rust needs all six fields in setDnsContentBlock.
        assertEquals(
            json("""{"dnsBlock":{"ad":false,"tracker":true,"malware":false,"adult":false,"gambling":false,"socialMedia":false}}"""),
            base.withBlockList(BlockList.Trackers, true).patchFrom(base),
        )
        val all = BlockList.entries.fold(base) { settings, list -> settings.withBlockList(list, true) }
        assertEquals(DnsContentBlock(true, true, true, true, true, true), all.dnsBlock)
        assertTrue(BlockList.entries.all { all.dnsBlock.isOn(it) })
        assertFalse(all.withBlockList(BlockList.SocialMedia, false).dnsBlock.socialMedia)
    }

    @Test
    fun dnsServersAreAddedTrimmedAndRemovedOneByOne() {
        val two = base.withDnsServer(" 9.9.9.9 ").withDnsServer("2620:fe::fe")
        assertEquals(listOf("9.9.9.9", "2620:fe::fe"), two.customDns)
        assertEquals(json("""{"customDns":["9.9.9.9","2620:fe::fe"]}"""), two.patchFrom(base))
        assertEquals(listOf("2620:fe::fe"), two.withoutDnsServer("9.9.9.9").customDns)
    }

    @Test
    fun theUdpMtuSnapsToStepsInTheRustRange() {
        assertEquals(1280, snapUdpMtu(1000))
        assertEquals(1280, snapUdpMtu(1284))
        assertEquals(1290, snapUdpMtu(1285))
        assertEquals(1380, snapUdpMtu(1377))
        assertEquals(1420, snapUdpMtu(1416))
        assertEquals(1420, snapUdpMtu(9000))
        assertEquals(json("""{"udpMtu":1400}"""), base.withUdpMtu(1401).patchFrom(base))
    }

    @Test
    fun aPatchHoldsOnlyTheChangedKeys() {
        assertEquals(json("""{"mode":"wireguardUdp"}"""), base.copy(mode = TunnelMode.WireguardUdp).patchFrom(base))
        assertEquals(json("""{"lanAccess":true}"""), base.copy(lanAccess = true).patchFrom(base))
        assertEquals(json("{}"), base.copy().patchFrom(base))
    }

    @Test
    fun blankHostsGoBackToTheCoreDefault() {
        val set = base.withSniRelay(" cdn.example.org ").withApiHostAlternate("api.example.net")
        assertEquals("cdn.example.org", set.sniRelay)
        assertEquals(json("""{"sniRelay":"cdn.example.org","apiHostAlternate":"api.example.net"}"""), set.patchFrom(base))
        val cleared = set.withSniRelay("  ").withApiHostAlternate("")
        assertEquals(json("""{"sniRelay":null,"apiHostAlternate":null}"""), cleared.patchFrom(set))
    }

    @Test
    fun splitAppsAreASet() {
        val one = base.withSplitApp("org.example.a", true).withSplitApp("org.example.a", true)
        assertEquals(setOf("org.example.a"), one.splitApps)
        assertEquals(emptySet(), one.withSplitApp("org.example.a", false).splitApps)
        assertEquals(0, one.splitAppCount, "split tunnelling is off")
        assertEquals(1, one.copy(splitMode = SplitMode.Include).splitAppCount)
    }

    @Test
    fun dnsGoesWhereTheTunnelSendsIt() {
        val blocking = base.withBlockList(BlockList.Ads, true).withBlockList(BlockList.Malware, true)
        assertEquals(DnsRoute.Tunnel(2), blocking.dnsRoute())
        assertTrue(blocking.blockListsApply)

        val custom = blocking.withDnsServer("9.9.9.9")
        assertEquals(DnsRoute.Custom(listOf("9.9.9.9")), custom.dnsRoute())
        assertFalse(custom.blockListsApply, "custom servers replace the Mullvad blocking resolver")

        assertEquals(DnsRoute.Network, custom.copy(useSystemDns = true).dnsRoute())
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
