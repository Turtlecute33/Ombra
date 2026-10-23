package app.ombra.ipc

import kotlinx.serialization.Serializable

/**
 * Android-side facts that only the :vpn process can read, pushed as [StateKind.SYSTEM].
 * Null means "not known yet" (for example [alwaysOn] before OmbraVpnService ran once).
 */
@Serializable
data class SystemState(
    /** `VpnService.isAlwaysOn()` of the running service. */
    val alwaysOn: Boolean? = null,
    /** `VpnService.isLockdownEnabled()`: "Block connections without VPN" (the kill switch). */
    val lockdown: Boolean? = null,
    /** Private DNS is active on the underlying network. */
    val privateDnsActive: Boolean = false,
    /** Host name when Private DNS is in strict mode; it then overrides the VPN DNS servers. */
    val privateDnsServer: String? = null,
    /** `ActivityManager.isBackgroundRestricted()`: the OEM battery mode can stop the VPN. */
    val backgroundRestricted: Boolean = false,
    /** The underlying network is cellular. */
    val cellular: Boolean = false,
    /** Effective MTU of the underlying network (CLAT-aware), 0 when unknown. */
    val underlyingMtu: Int = 0,
)
