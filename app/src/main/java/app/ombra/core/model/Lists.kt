package app.ombra.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A WireGuard exit server (obscuravpn-api `OneExit`, snake_case). */
@Serializable
data class OneExit(
    val id: String,
    @SerialName("country_code") val countryCode: String,
    @SerialName("city_code") val cityCode: String,
    @SerialName("city_name") val cityName: String,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("provider_url") val providerUrl: String = "",
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("provider_homepage_url") val providerHomepageUrl: String = "",
    @SerialName("datacenter_id") val datacenterId: Long = 0,
    /** Higher is preferred; serde's default is 255. */
    val tier: Int = 255,
)

@Serializable
data class RelayPreferredExit(val id: String)

/** A QUIC entry relay (obscuravpn-api `OneRelay`, snake_case). */
@Serializable
data class OneRelay(
    val id: String,
    @SerialName("country_code") val countryCode: String,
    @SerialName("city_code") val cityCode: String,
    @SerialName("city_name") val cityName: String,
    @SerialName("preferred_exits") val preferredExits: List<RelayPreferredExit> = emptyList(),
    @SerialName("ip_v4") val ipV4: String = "",
    val ports: List<Int> = emptyList(),
    /** Base64 DER certificate that the client pins. */
    @SerialName("tls_cert") val tlsCert: String = "",
)

@Serializable
data class ExitList(val exits: List<OneExit> = emptyList())

/**
 * Result of the `getExitList` / `getRelayList` long-polls. [version] is opaque base64 to send back
 * as `knownVersion`; [lastUpdated] is in Unix seconds.
 */
@Serializable
data class CachedValue<T>(
    val version: String,
    @SerialName("last_updated") val lastUpdated: Long,
    val value: T,
)
