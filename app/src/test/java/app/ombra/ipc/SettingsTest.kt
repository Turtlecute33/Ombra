package app.ombra.ipc

import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsTest {
    private val custom = Settings(
        exit = ExitSelector.City("US", "nyc"),
        relay = RelaySelector.Relay("relay-1"),
        mode = TunnelMode.WireguardUdp,
        udpMtu = 1400,
        padding = true,
        forceSmallMtu = true,
        dnsBlock = DnsContentBlock(ad = true, tracker = true),
        customDns = listOf("9.9.9.9", "2620:fe::fe"),
        useSystemDns = true,
        lanAccess = true,
        splitMode = SplitMode.Exclude,
        splitApps = setOf("org.example.bank", "org.example.tv"),
        pinnedExits = listOf(PinnedLocation("US", "nyc", 1_700_000_000)),
        pinnedRelays = listOf("relay-1"),
        autoConnectOnLaunch = true,
        sniRelay = "example.org",
        apiHostAlternate = "alt.example.net",
        diagnostics = true,
        hideNotificationDetails = false,
    )

    private fun roundTrip(settings: Settings): Settings =
        IpcJson.decodeFromString(Settings.serializer(), IpcJson.encodeToString(Settings.serializer(), settings))

    @Test
    fun defaultsArePrivacyFirst() {
        val defaults = Settings()
        assertEquals(false, defaults.lanAccess)
        assertEquals(true, defaults.hideNotificationDetails)
        assertEquals(false, defaults.diagnostics)
        assertEquals(TunnelMode.Quic, defaults.mode)
        assertEquals(1280, defaults.udpMtu)
        assertEquals(SplitMode.Off, defaults.splitMode)
    }

    @Test
    fun roundTripsDefaultsAndCustomValues() {
        assertEquals(Settings(), roundTrip(Settings()))
        assertEquals(custom, roundTrip(custom))
    }

    @Test
    fun missingAndUnknownKeysDecode() {
        assertEquals(Settings(), IpcJson.decodeFromString(Settings.serializer(), "{}"))
        val decoded = IpcJson.decodeFromString(Settings.serializer(), """{"lanAccess":true,"fromTheFuture":[1]}""")
        assertEquals(Settings(lanAccess = true), decoded)
    }

    @Test
    fun splitModeWireNames() {
        val json = IpcJson.encodeToString(Settings.serializer(), Settings(splitMode = SplitMode.Include))
        assertTrue(""""splitMode":"include"""" in json, json)
    }

    @Test
    fun patchHoldsOnlyChangedKeys() {
        val base = Settings()
        val patch = base.copy(lanAccess = true, sniRelay = "example.org").patchFrom(base)
        assertEquals(setOf("lanAccess", "sniRelay"), patch.keys)
        assertEquals(JsonPrimitive(true), patch["lanAccess"])
        assertEquals(emptySet(), base.patchFrom(base).keys)
    }

    @Test
    fun applyPatchRoundTrip() {
        val base = Settings()
        assertEquals(custom, base.applyPatch(custom.patchFrom(base)))
        assertEquals(base, custom.applyPatch(base.patchFrom(custom)))
    }

    @Test
    fun patchCanClearNullableValues() {
        val patch = buildJsonObject { put("sniRelay", JsonNull) }
        assertEquals(null, custom.applyPatch(patch).sniRelay)
    }

    @Test
    fun patchIsNormalized() {
        val patch = buildJsonObject {
            put("udpMtu", 9000)
            put("sniRelay", "  ")
        }
        val patched = Settings().applyPatch(patch)
        assertEquals(TunnelArgs.UDP_MTU_MAX, patched.udpMtu)
        assertEquals(null, patched.sniRelay)
    }

    @Test
    fun badPatchValueFails() {
        assertFailsWith<SerializationException> {
            Settings().applyPatch(buildJsonObject { put("exit", "nowhere") })
        }
    }

    @Test
    fun normalizedCleansCustomDns() {
        val settings = Settings(customDns = listOf(" 9.9.9.9 ", "", "9.9.9.9", "1.1.1.1")).normalized()
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), settings.customDns)
    }

    @Test
    fun tunnelArgsFollowSettings() {
        assertEquals(
            TunnelArgs(ExitSelector.City("US", "nyc"), RelaySelector.Relay("relay-1"), TunnelMode.WireguardUdp, 1400),
            custom.tunnelArgs(),
        )
        assertEquals(TunnelArgs(), Settings().tunnelArgs())
    }
}
