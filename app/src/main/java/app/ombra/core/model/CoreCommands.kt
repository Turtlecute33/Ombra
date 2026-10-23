package app.ombra.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One typed core command: the wire command, how to decode its Ok payload, and how long to wait.
 * [timeout] is null for long-polls, which return only when the value changes.
 */
class CoreCall<out R> internal constructor(
    val command: ManagerCmd,
    val timeout: Duration?,
    private val decoder: (String) -> R,
) {
    fun encode(): String = CoreJson.encodeToString(ManagerCmd.serializer(), command)

    /** Decodes the Ok payload. Throws SerializationException or IllegalArgumentException on bad JSON. */
    fun decode(json: String): R = decoder(json)

    override fun toString(): String = "CoreCall($command)"
}

/** Builders for every core command Ombra sends. */
object CoreCommands {
    /** Local commands answer in milliseconds. */
    val LOCAL_TIMEOUT: Duration = 15.seconds

    /** API commands may retry several hosts and solve a proof-of-work challenge. */
    val API_TIMEOUT: Duration = 2.minutes

    const val DEFAULT_LIST_FRESHNESS_MS: Long = 60_000

    private val relayListSerializer = CachedValue.serializer(ListSerializer(OneRelay.serializer()))
    private val exitListSerializer = CachedValue.serializer(ExitList.serializer())

    fun getStatus(knownVersion: String?) = CoreCall(ManagerCmd.GetStatus(knownVersion), null, decoding(Status.serializer()))

    fun getExitList(knownVersion: String?) = CoreCall(ManagerCmd.GetExitList(knownVersion), null, decoding(exitListSerializer))

    fun getRelayList(knownVersion: String?) = CoreCall(ManagerCmd.GetRelayList(knownVersion), null, decoding(relayListSerializer))

    fun refreshExitList(freshnessMs: Long = DEFAULT_LIST_FRESHNESS_MS) =
        CoreCall(ManagerCmd.RefreshExitList(freshnessMs), API_TIMEOUT, ::decodeUnit)

    fun refreshRelayList(freshnessMs: Long = DEFAULT_LIST_FRESHNESS_MS) =
        CoreCall(ManagerCmd.RefreshRelayList(freshnessMs), API_TIMEOUT, ::decodeUnit)

    fun refreshOsNetworkConfig() = CoreCall(ManagerCmd.RefreshOsNetworkConfig, LOCAL_TIMEOUT, ::decodeUnit)

    fun getTrafficStats() = CoreCall(ManagerCmd.GetTrafficStats, LOCAL_TIMEOUT, decoding(TrafficStats.serializer()))

    fun ping() = CoreCall(ManagerCmd.Ping, LOCAL_TIMEOUT, ::decodeUnit)

    /** [accountNumber] must be the normalized 20 digits. validate = true fetches a token (network). */
    fun login(accountNumber: String, validate: Boolean = true) =
        CoreCall(ManagerCmd.Login(accountNumber, validate), API_TIMEOUT, ::decodeUnit)

    fun logout() = CoreCall(ManagerCmd.Logout, LOCAL_TIMEOUT, ::decodeUnit)

    fun apiGetAccountInfo() = CoreCall(ManagerCmd.ApiGetAccountInfo, API_TIMEOUT, decoding(AccountInfo.serializer()))

    fun apiServiceStatus() = CoreCall(ManagerCmd.ApiServiceStatus, API_TIMEOUT, decoding(ServiceStatus.serializer()))

    fun apiDeleteAccount() = CoreCall(ManagerCmd.ApiDeleteAccount, API_TIMEOUT, ::decodeIgnored)

    fun rotateWgKey() = CoreCall(ManagerCmd.RotateWgKey, LOCAL_TIMEOUT, ::decodeUnit)

    /** Returns the absolute path of the zip. The bundle probes the network and contains logcat. */
    fun createDebugBundle(userFeedback: String?, bundleInfo: BundleInfo, androidCacheDir: String) = CoreCall(
        ManagerCmd.CreateDebugBundle(userFeedback, bundleInfo, androidCacheDir),
        API_TIMEOUT,
        decoding(String.serializer()),
    )

    fun setTunnelArgs(args: TunnelArgs?, active: Boolean?) =
        CoreCall(ManagerCmd.SetTunnelArgs(args, active), LOCAL_TIMEOUT, ::decodeUnit)

    fun connect(args: TunnelArgs?) = setTunnelArgs(args, active = true)

    fun disconnect() = setTunnelArgs(args = null, active = false)

    fun setLocalNetworkAccess(enable: Boolean) = CoreCall(ManagerCmd.SetLocalNetworkAccess(enable), LOCAL_TIMEOUT, ::decodeUnit)

    fun setDnsContentBlock(value: DnsContentBlock) = CoreCall(ManagerCmd.SetDnsContentBlock(value), LOCAL_TIMEOUT, ::decodeUnit)

    fun setUseSystemDns(enable: Boolean) = CoreCall(ManagerCmd.SetUseSystemDns(enable), LOCAL_TIMEOUT, ::decodeUnit)

    fun setFeatureFlag(flag: String, active: Boolean) =
        CoreCall(ManagerCmd.SetFeatureFlag(flag, active), LOCAL_TIMEOUT, ::decodeUnit)

    fun setSniRelay(host: String?) = CoreCall(ManagerCmd.SetSniRelay(host), LOCAL_TIMEOUT, ::decodeUnit)

    fun setApiHostAlternate(host: String?) = CoreCall(ManagerCmd.SetApiHostAlternate(host), LOCAL_TIMEOUT, ::decodeUnit)

    fun setApiUrl(url: String?) = CoreCall(ManagerCmd.SetApiUrl(url), LOCAL_TIMEOUT, ::decodeUnit)

    fun setAutoConnect(enable: Boolean) = CoreCall(ManagerCmd.SetAutoConnect(enable), LOCAL_TIMEOUT, ::decodeUnit)

    fun setInNewAccountFlow(value: Boolean) = CoreCall(ManagerCmd.SetInNewAccountFlow(value), LOCAL_TIMEOUT, ::decodeUnit)

    fun setPinnedExits(exits: List<PinnedLocation>) = CoreCall(ManagerCmd.SetPinnedExits(exits), LOCAL_TIMEOUT, ::decodeUnit)

    private fun <T> decoding(serializer: KSerializer<T>): (String) -> T = { CoreJson.decodeFromString(serializer, it) }

    /** Setters answer the 4-character JSON `null`. */
    private fun decodeUnit(json: String) {
        require(CoreJson.parseToJsonElement(json) == JsonNull) { "expected null" }
    }

    /** For results whose content Ombra does not use (for example `{}` from apiDeleteAccount). */
    private fun decodeIgnored(json: String) {
        CoreJson.parseToJsonElement(json)
    }
}

/** JSON string literal, for app-level results that are a single string or null. */
fun jsonString(value: String?): String = CoreJson.encodeToString(String.serializer().nullable, value)

/** Inverse of [jsonString]. */
fun parseJsonString(json: String): String? = (CoreJson.parseToJsonElement(json) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
