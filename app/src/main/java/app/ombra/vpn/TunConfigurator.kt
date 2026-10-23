package app.ombra.vpn

import android.content.pm.PackageManager
import android.net.InetAddresses
import android.net.Network
import android.net.VpnService
import android.util.Log
import app.ombra.core.model.CoreJson
import app.ombra.core.model.OsNetworkConfig
import app.ombra.ipc.Settings

/**
 * Builds the TUN for Rust's OsNetworkConfig (ffiSetNetworkConfig) from a [TunPlan]. Runs on a
 * Rust blocking thread. Rust keeps the TUN when the config did not change, so this runs only for
 * a new config or after `refreshOsNetworkConfig`.
 */
class TunConfigurator(
    private val service: VpnService,
    private val settings: () -> Settings,
    private val underlyingNetwork: () -> Network?,
) {
    private val packageManager = service.packageManager

    /** Returns the detached TUN fd for Rust (Rust owns and closes it), or -1. Never throws. */
    fun apply(json: String): Int = try {
        val config = CoreJson.decodeFromString(OsNetworkConfig.serializer(), json)
        val plan = TunPlan.of(config, settings(), service.packageName, ::isUsableDns, ::isInstalled)
        val pfd = builder(plan).establish()
        if (pfd == null) {
            // Consent revoked or another VPN took over.
            Log.e(TAG, "establish() returned null")
            -1
        } else {
            pfd.detachFd()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "TUN setup failed: ${t.javaClass.name}")
        -1
    }

    private fun builder(plan: TunPlan): VpnService.Builder {
        val builder = service.Builder()
            .setSession(SESSION_NAME)
            .setMtu(plan.mtu)
            // Inherit meteredness from the underlying network instead of always "metered".
            .setMetered(false)
            // Rust reads the fd with epoll and needs it non-blocking.
            .setBlocking(false)
            .setConfigureIntent(VpnNotifications.openApp(service))
        plan.addresses.forEach { builder.addAddress(it.address, it.prefix) }
        plan.routes.forEach { builder.addRoute(it.address, it.prefix) }
        plan.dnsServers.forEach { builder.addDnsServer(it) }
        // No allowFamily(): Android then blocks a family that has no address or route in the TUN.
        // Without a network the system default applies, which is right while it is unknown.
        underlyingNetwork()?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        when (val apps = plan.apps) {
            is AppScope.AllExcept -> apps.packages.forEach { builder.addDisallowedApplication(it) }
            is AppScope.Only -> apps.packages.forEach { builder.addAllowedApplication(it) }
        }
        return builder
    }

    /** The check of VpnService.Builder.addDnsServer, which throws "Bad address" otherwise. Never resolves names. */
    private fun isUsableDns(value: String): Boolean =
        InetAddresses.isNumericAddress(value) && InetAddresses.parseNumericAddress(value).let { !it.isLoopbackAddress && !it.isAnyLocalAddress }

    /** Only apps visible to us count; the split-tunnel list offers launcher apps, which are. */
    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val TAG = "OmbraTun"
        const val SESSION_NAME = "Ombra"
    }
}
