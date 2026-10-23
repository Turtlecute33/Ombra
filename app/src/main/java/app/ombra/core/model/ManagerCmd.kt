package app.ombra.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The commands of rustlib `ManagerCmd` that Ombra sends (externally tagged; tags and fields
 * camelCase). Field-less commands are `data object`s so they encode as `{"ping":{}}`.
 * Build them through [CoreCommands], which also knows the result type and the timeout.
 */
@Serializable(with = ManagerCmdSerializer::class)
sealed interface ManagerCmd {
    @Serializable @SerialName("apiDeleteAccount")
    data object ApiDeleteAccount : ManagerCmd

    @Serializable @SerialName("apiGetAccountInfo")
    data object ApiGetAccountInfo : ManagerCmd

    @Serializable @SerialName("apiServiceStatus")
    data object ApiServiceStatus : ManagerCmd

    @Serializable @SerialName("createDebugBundle")
    data class CreateDebugBundle(
        val userFeedback: String?,
        val bundleInfo: BundleInfo,
        val androidCacheDir: String?,
    ) : ManagerCmd

    @Serializable @SerialName("getExitList")
    data class GetExitList(val knownVersion: String?) : ManagerCmd

    /** Fork patch P2. */
    @Serializable @SerialName("getRelayList")
    data class GetRelayList(val knownVersion: String?) : ManagerCmd

    @Serializable @SerialName("getStatus")
    data class GetStatus(val knownVersion: String?) : ManagerCmd

    @Serializable @SerialName("getTrafficStats")
    data object GetTrafficStats : ManagerCmd

    @Serializable @SerialName("login")
    data class Login(val accountId: String, val validate: Boolean) : ManagerCmd {
        override fun toString(): String = "Login(validate=$validate)"
    }

    @Serializable @SerialName("logout")
    data object Logout : ManagerCmd

    @Serializable @SerialName("ping")
    data object Ping : ManagerCmd

    /** [freshness] in milliseconds: fetch only when the cached list is older. */
    @Serializable @SerialName("refreshExitList")
    data class RefreshExitList(val freshness: Long) : ManagerCmd

    /** Fork patch P2. */
    @Serializable @SerialName("refreshRelayList")
    data class RefreshRelayList(val freshness: Long) : ManagerCmd

    /** Fork patch P2: Rust calls ffiSetNetworkConfig again with the current config, no reconnect. */
    @Serializable @SerialName("refreshOsNetworkConfig")
    data object RefreshOsNetworkConfig : ManagerCmd

    @Serializable @SerialName("rotateWgKey")
    data object RotateWgKey : ManagerCmd

    @Serializable @SerialName("setApiHostAlternate")
    data class SetApiHostAlternate(val host: String?) : ManagerCmd

    /** Also rotates the WireGuard key. Debug builds only. */
    @Serializable @SerialName("setApiUrl")
    data class SetApiUrl(val url: String?) : ManagerCmd

    @Serializable @SerialName("setAutoConnect")
    data class SetAutoConnect(val enable: Boolean) : ManagerCmd

    @Serializable @SerialName("setDnsContentBlock")
    data class SetDnsContentBlock(val value: DnsContentBlock) : ManagerCmd

    /** [flag] is a [FeatureFlag] name; `active = false` unsets it. */
    @Serializable @SerialName("setFeatureFlag")
    data class SetFeatureFlag(val flag: String, val active: Boolean) : ManagerCmd

    @Serializable @SerialName("setInNewAccountFlow")
    data class SetInNewAccountFlow(val value: Boolean) : ManagerCmd

    @Serializable @SerialName("setLocalNetworkAccess")
    data class SetLocalNetworkAccess(val enable: Boolean) : ManagerCmd

    /** Replaces the whole list. */
    @Serializable @SerialName("setPinnedExits")
    data class SetPinnedExits(val exits: List<PinnedLocation>) : ManagerCmd

    @Serializable @SerialName("setSniRelay")
    data class SetSniRelay(val host: String?) : ManagerCmd

    /**
     * Connect and disconnect. `args = null` keeps the stored args; `active = null` keeps the
     * state. Connect: active true. Disconnect: args null, active false.
     */
    @Serializable @SerialName("setTunnelArgs")
    data class SetTunnelArgs(val args: TunnelArgs?, val active: Boolean?) : ManagerCmd

    @Serializable @SerialName("setUseSystemDns")
    data class SetUseSystemDns(val enable: Boolean) : ManagerCmd
}

internal object ManagerCmdSerializer : ExternallyTaggedSerializer<ManagerCmd>(
    "app.ombra.ManagerCmd",
    ManagerCmd.ApiDeleteAccount::class to ManagerCmd.ApiDeleteAccount.serializer(),
    ManagerCmd.ApiGetAccountInfo::class to ManagerCmd.ApiGetAccountInfo.serializer(),
    ManagerCmd.ApiServiceStatus::class to ManagerCmd.ApiServiceStatus.serializer(),
    ManagerCmd.CreateDebugBundle::class to ManagerCmd.CreateDebugBundle.serializer(),
    ManagerCmd.GetExitList::class to ManagerCmd.GetExitList.serializer(),
    ManagerCmd.GetRelayList::class to ManagerCmd.GetRelayList.serializer(),
    ManagerCmd.GetStatus::class to ManagerCmd.GetStatus.serializer(),
    ManagerCmd.GetTrafficStats::class to ManagerCmd.GetTrafficStats.serializer(),
    ManagerCmd.Login::class to ManagerCmd.Login.serializer(),
    ManagerCmd.Logout::class to ManagerCmd.Logout.serializer(),
    ManagerCmd.Ping::class to ManagerCmd.Ping.serializer(),
    ManagerCmd.RefreshExitList::class to ManagerCmd.RefreshExitList.serializer(),
    ManagerCmd.RefreshRelayList::class to ManagerCmd.RefreshRelayList.serializer(),
    ManagerCmd.RefreshOsNetworkConfig::class to ManagerCmd.RefreshOsNetworkConfig.serializer(),
    ManagerCmd.RotateWgKey::class to ManagerCmd.RotateWgKey.serializer(),
    ManagerCmd.SetApiHostAlternate::class to ManagerCmd.SetApiHostAlternate.serializer(),
    ManagerCmd.SetApiUrl::class to ManagerCmd.SetApiUrl.serializer(),
    ManagerCmd.SetAutoConnect::class to ManagerCmd.SetAutoConnect.serializer(),
    ManagerCmd.SetDnsContentBlock::class to ManagerCmd.SetDnsContentBlock.serializer(),
    ManagerCmd.SetFeatureFlag::class to ManagerCmd.SetFeatureFlag.serializer(),
    ManagerCmd.SetInNewAccountFlow::class to ManagerCmd.SetInNewAccountFlow.serializer(),
    ManagerCmd.SetLocalNetworkAccess::class to ManagerCmd.SetLocalNetworkAccess.serializer(),
    ManagerCmd.SetPinnedExits::class to ManagerCmd.SetPinnedExits.serializer(),
    ManagerCmd.SetSniRelay::class to ManagerCmd.SetSniRelay.serializer(),
    ManagerCmd.SetTunnelArgs::class to ManagerCmd.SetTunnelArgs.serializer(),
    ManagerCmd.SetUseSystemDns::class to ManagerCmd.SetUseSystemDns.serializer(),
)

/**
 * Debug bundle metadata (rustlib `BundleInfo`, PascalCase). Only the app version and the SDK level
 * are sent: no device model or other identifiers.
 */
@Serializable
data class BundleInfo(
    @SerialName("AppVersion") val appVersion: String,
    @SerialName("AndroidSDK") val androidSdk: Int? = null,
)

/** Error codes of `jsonFfi` (rustlib `ManagerCmdErrorCode`), with [Unknown] for app-level codes. */
enum class ManagerCmdErrorCode(override val wire: String) : WireEnum {
    ApiAssociateAccountConflict("apiAssociateAccountConflict"),
    ApiError("apiError"),
    ApiInvalidAccountId("apiInvalidAccountId"),
    ApiNoLongerSupported("apiNoLongerSupported"),
    ApiRateLimitExceeded("apiRateLimitExceeded"),
    ApiSaleNotFound("apiSaleNotFound"),
    ApiSignupLimitExceeded("apiSignupLimitExceeded"),
    ApiUnreachable("apiUnreachable"),
    ConfigSaveError("configSaveError"),
    NotLoggedIn("notLoggedIn"),
    Other("other"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(wire: String?): ManagerCmdErrorCode = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}
