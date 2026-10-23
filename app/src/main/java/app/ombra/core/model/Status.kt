package app.ombra.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of `getStatus` (rustlib `Status`, camelCase; nested types keep their own naming).
 * Contains the account number in [accountId] and in [account]: never log it, and send it over
 * IPC only through [app.ombra.ipc.StatusSanitizer].
 */
@Serializable
data class Status(
    /** Opaque UUID; send it back as `knownVersion` to wait for the next change. */
    val version: String,
    val vpnStatus: VpnStatus = VpnStatus.Disconnected,
    val accountId: String? = null,
    val inNewAccountFlow: Boolean = false,
    val pinnedLocations: List<PinnedLocation> = emptyList(),
    val lastChosenExit: ExitSelector = ExitSelector.Any,
    val lastExit: ExitSelector = ExitSelector.Any,
    val apiUrl: String = "",
    val account: AccountStatus? = null,
    val autoConnect: Boolean = false,
    val featureFlags: FeatureFlags = FeatureFlags(),
    val featureFlagKeys: List<String> = emptyList(),
    val useSystemDns: Boolean = false,
    val localNetworkAccess: Boolean = false,
    val tailscaleBypass: Boolean = false,
    val wireguardBypass: Boolean = false,
    val dnsContentBlock: DnsContentBlock = DnsContentBlock(),
    /** Not from Rust: set by StatusSanitizer, which replaces [accountId] with null. */
    val loggedIn: Boolean = false,
) {
    val isLoggedIn: Boolean get() = loggedIn || accountId != null

    override fun toString(): String = "Status(version=$version, vpnStatus=$vpnStatus, loggedIn=$isLoggedIn)"
}

/** Feature flags as Rust reports them: `true` or `null`; absent and `null` decode as false. */
@Serializable
data class FeatureFlags(
    val quicFramePadding: Boolean = false,
    val killSwitch: Boolean = false,
    val forceSmallMtu: Boolean = false,
    val tcpTlsTunnel: Boolean = false,
)

/** Flag names for `setFeatureFlag`. `killSwitch` has no effect on Android. */
object FeatureFlag {
    const val QUIC_FRAME_PADDING = "quicFramePadding"
    const val FORCE_SMALL_MTU = "forceSmallMtu"
    const val TCP_TLS_TUNNEL = "tcpTlsTunnel"
}

@Serializable(with = VpnStatusSerializer::class)
sealed interface VpnStatus {
    @Serializable
    @SerialName("disconnected")
    data object Disconnected : VpnStatus

    /**
     * Rust retries forever while connecting. [connectError] is the last failure; `other` and
     * `noInternet` are often transient.
     */
    @Serializable
    @SerialName("connecting")
    data class Connecting(
        val tunnelArgs: TunnelArgs = TunnelArgs(),
        val connectError: ConnectErrorCode? = null,
        /** True when a live tunnel failed and Rust reconnects. */
        val reconnecting: Boolean = false,
    ) : VpnStatus

    @Serializable
    @SerialName("connected")
    data class Connected(
        val tunnelArgs: TunnelArgs = TunnelArgs(),
        val exit: OneExit,
        val relay: OneRelay,
        val clientPublicKey: String,
        val exitPublicKey: String,
        val transport: TransportKind = TransportKind.Unknown,
    ) : VpnStatus
}

internal object VpnStatusSerializer : ExternallyTaggedSerializer<VpnStatus>(
    "app.ombra.VpnStatus",
    VpnStatus.Disconnected::class to VpnStatus.Disconnected.serializer(),
    VpnStatus.Connecting::class to VpnStatus.Connecting.serializer(),
    VpnStatus.Connected::class to VpnStatus.Connected.serializer(),
)

@Serializable(with = TransportKindSerializer::class)
enum class TransportKind(override val wire: String) : WireEnum {
    Quic("quic"),
    TcpTls("tcpTls"),
    WireguardUdp("wireguardUdp"),
    Unknown("unknown"),
}

internal object TransportKindSerializer :
    WireEnumSerializer<TransportKind>("app.ombra.TransportKind", TransportKind.entries, TransportKind.Unknown)

/** `vpnStatus.connecting.connectError` (rustlib `ConnectErrorCode`). */
@Serializable(with = ConnectErrorCodeSerializer::class)
enum class ConnectErrorCode(override val wire: String) : WireEnum {
    AccountExpired("accountExpired"),
    ApiError("apiError"),
    ApiRateLimitExceeded("apiRateLimitExceeded"),
    ApiUnreachable("apiUnreachable"),
    InvalidAccountId("invalidAccountId"),
    NoInternet("noInternet"),
    NoLongerSupported("noLongerSupported"),
    NoRelay("noRelay"),
    NoSlotsLeft("noSlotsLeft"),
    NotLoggedIn("notLoggedIn"),
    Other("other"),

    /** Ombra P5: no WireGuard handshake over UDP in about 5 s (UDP blocked?). Offer Stealth. */
    UdpHandshakeFailed("udpHandshakeFailed"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(wire: String?): ConnectErrorCode = ConnectErrorCodeSerializer.fromWire(wire)
    }
}

internal object ConnectErrorCodeSerializer :
    WireEnumSerializer<ConnectErrorCode>("app.ombra.ConnectErrorCode", ConnectErrorCode.entries, ConnectErrorCode.Unknown)

/** Result of `getTrafficStats`. Bytes are inner IP bytes; [connId] is stable for one session. */
@Serializable
data class TrafficStats(
    val connectedMs: Long = 0,
    val connId: String = "",
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    /** In-tunnel RTT to the exit gateway. */
    val latestLatencyMs: Int = 0,
)

/** Argument of `ffiSetNetworkConfig` (rustlib `OsNetworkConfig`, Android fields). */
@Serializable
data class OsNetworkConfig(
    val dns: List<String> = emptyList(),
    /** Bare address, no prefix. */
    val ipv4: String,
    /** `addr/prefix`. */
    val ipv6: String,
    val routes: List<Route> = emptyList(),
    val mtu: Int = 1280,
    val useSystemDns: Boolean = false,
)

@Serializable
data class Route(val address: String, val prefix: Int)
