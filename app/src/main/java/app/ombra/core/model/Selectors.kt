package app.ombra.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Variant tags are camelCase; the fields inside are snake_case (serde does not rename them).

/** Which exit to use (rustlib `ExitSelector`). Copy codes verbatim from the exit list. */
@Serializable(with = ExitSelectorSerializer::class)
sealed interface ExitSelector {
    /** Fastest: any exit, ranked near the chosen relay. */
    @Serializable
    @SerialName("any")
    data object Any : ExitSelector

    @Serializable
    @SerialName("exit")
    data class Exit(val id: String) : ExitSelector

    @Serializable
    @SerialName("country")
    data class Country(@SerialName("country_code") val countryCode: String) : ExitSelector

    @Serializable
    @SerialName("city")
    data class City(
        @SerialName("country_code") val countryCode: String,
        @SerialName("city_code") val cityCode: String,
    ) : ExitSelector
}

internal object ExitSelectorSerializer : ExternallyTaggedSerializer<ExitSelector>(
    "app.ombra.ExitSelector",
    ExitSelector.Any::class to ExitSelector.Any.serializer(),
    ExitSelector.Exit::class to ExitSelector.Exit.serializer(),
    ExitSelector.Country::class to ExitSelector.Country.serializer(),
    ExitSelector.City::class to ExitSelector.City.serializer(),
)

/** Which entry relay to use (fork patch P1). An empty match fails the connect with `noRelay`. */
@Serializable(with = RelaySelectorSerializer::class)
sealed interface RelaySelector {
    /** Race all relays and take the fastest (upstream behaviour). */
    @Serializable
    @SerialName("any")
    data object Any : RelaySelector

    @Serializable
    @SerialName("relay")
    data class Relay(val id: String) : RelaySelector

    @Serializable
    @SerialName("country")
    data class Country(@SerialName("country_code") val countryCode: String) : RelaySelector

    @Serializable
    @SerialName("city")
    data class City(
        @SerialName("country_code") val countryCode: String,
        @SerialName("city_code") val cityCode: String,
    ) : RelaySelector
}

internal object RelaySelectorSerializer : ExternallyTaggedSerializer<RelaySelector>(
    "app.ombra.RelaySelector",
    RelaySelector.Any::class to RelaySelector.Any.serializer(),
    RelaySelector.Relay::class to RelaySelector.Relay.serializer(),
    RelaySelector.Country::class to RelaySelector.Country.serializer(),
    RelaySelector.City::class to RelaySelector.City.serializer(),
)

/** Transport of the tunnel (fork patch P1). */
@Serializable
enum class TunnelMode {
    /** WireGuard inside QUIC datagrams to the relay (upstream default). */
    @SerialName("quic")
    Quic,

    /** WireGuard over TLS/TCP for networks that block UDP; same effect as the `tcpTlsTunnel` flag. */
    @SerialName("tcpTls")
    TcpTls,

    /** Plain WireGuard over UDP to the relay (fork patch P5). */
    @SerialName("wireguardUdp")
    WireguardUdp,
}

/** Target of `setTunnelArgs`; every field after `exit` is a fork addition with a serde default. */
@Serializable
data class TunnelArgs(
    val exit: ExitSelector = ExitSelector.Any,
    val relay: RelaySelector = RelaySelector.Any,
    val mode: TunnelMode = TunnelMode.Quic,
    /** Only for [TunnelMode.WireguardUdp]; Rust clamps it to [UDP_MTU_MIN]..[UDP_MTU_MAX]. */
    val udpMtu: Int? = null,
) {
    companion object {
        const val UDP_MTU_MIN = 1280
        const val UDP_MTU_MAX = 1420

        fun of(exit: ExitSelector, relay: RelaySelector, mode: TunnelMode, udpMtu: Int): TunnelArgs = TunnelArgs(
            exit = exit,
            relay = relay,
            mode = mode,
            udpMtu = if (mode == TunnelMode.WireguardUdp) udpMtu.coerceIn(UDP_MTU_MIN, UDP_MTU_MAX) else null,
        )
    }
}

/** A favourite exit location (`setPinnedExits`). [pinnedAt] is in Unix seconds. */
@Serializable
data class PinnedLocation(
    @SerialName("country_code") val countryCode: String,
    @SerialName("city_code") val cityCode: String,
    @SerialName("pinned_at") val pinnedAt: Long,
)

/** Mullvad DNS content blocking. Rust requires all 6 fields. Works only on Mullvad exits. */
@Serializable
data class DnsContentBlock(
    val ad: Boolean = false,
    val tracker: Boolean = false,
    val malware: Boolean = false,
    val adult: Boolean = false,
    val gambling: Boolean = false,
    val socialMedia: Boolean = false,
)
