package app.ombra.ipc

import app.ombra.core.model.DnsContentBlock
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * App settings, owned by the :vpn process (SettingsStore). They are the source of truth: Rust
 * resets its config at login and logout, so CoreHost pushes them again afterwards.
 */
@Serializable
data class Settings(
    val exit: ExitSelector = ExitSelector.Any,
    val relay: RelaySelector = RelaySelector.Any,
    val mode: TunnelMode = TunnelMode.Quic,
    /** Used only in [TunnelMode.WireguardUdp]. */
    val udpMtu: Int = TunnelArgs.UDP_MTU_MIN,
    val padding: Boolean = false,
    val forceSmallMtu: Boolean = false,
    val dnsBlock: DnsContentBlock = DnsContentBlock(),
    /** IP literals. When not empty, they replace the tunnel DNS servers. */
    val customDns: List<String> = emptyList(),
    /** Expert option: DNS goes to the resolvers of the physical network. */
    val useSystemDns: Boolean = false,
    /** Upstream default is true; Ombra keeps LAN traffic inside the tunnel by default. */
    val lanAccess: Boolean = false,
    val splitMode: SplitMode = SplitMode.Off,
    /** Package names for [SplitMode.Exclude] or [SplitMode.Include]. */
    val splitApps: Set<String> = emptySet(),
    val pinnedExits: List<PinnedLocation> = emptyList(),
    /** Relay ids. */
    val pinnedRelays: List<String> = emptyList(),
    val autoConnectOnLaunch: Boolean = false,
    val sniRelay: String? = null,
    val apiHostAlternate: String? = null,
    /**
     * Persisted Rust logs and verbose RUST_LOG, both from the next :vpn process start. Off also
     * stops the persisted log at once and deletes it.
     */
    val diagnostics: Boolean = false,
    val hideNotificationDetails: Boolean = true,
) {
    /** Rust tunnel arguments for these settings. */
    fun tunnelArgs(): TunnelArgs = TunnelArgs.of(exit, relay, mode, udpMtu)

    /** Clamps and cleans values that a patch could set out of range. */
    fun normalized(): Settings = copy(
        udpMtu = udpMtu.coerceIn(TunnelArgs.UDP_MTU_MIN, TunnelArgs.UDP_MTU_MAX),
        customDns = customDns.map(String::trim).filter(String::isNotEmpty).distinct(),
        sniRelay = sniRelay?.trim()?.takeIf(String::isNotEmpty),
        apiHostAlternate = apiHostAlternate?.trim()?.takeIf(String::isNotEmpty),
    )

    /** Top-level keys whose value differs from [base]: the payload of [Request.UpdateSettings]. */
    fun patchFrom(base: Settings): JsonObject {
        val new = encode(this)
        val old = encode(base)
        return buildJsonObject { new.forEach { (key, value) -> if (old[key] != value) put(key, value) } }
    }

    /** [patch] on top of this; unknown keys are ignored, bad values fail with SerializationException. */
    fun applyPatch(patch: JsonObject): Settings =
        IpcJson.decodeFromJsonElement(serializer(), JsonObject(encode(this) + patch)).normalized()

    companion object {
        private fun encode(settings: Settings): JsonObject = IpcJson.encodeToJsonElement(serializer(), settings).jsonObject
    }
}

/** Per-app split tunnelling. Ombra itself always stays outside the tunnel. */
@Serializable
enum class SplitMode {
    /** All apps use the VPN. */
    @SerialName("off")
    Off,

    /** All apps except [Settings.splitApps] use the VPN. */
    @SerialName("exclude")
    Exclude,

    /** Only [Settings.splitApps] use the VPN. */
    @SerialName("include")
    Include,
}
