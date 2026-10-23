package app.ombra.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.ombra.core.ffi.RustFfi
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Follows the best physical network and tells Rust, the VPN and the UI about it. Rust does not
 * move on without an interface: a connect stays at `noInternet` until one is reported. A changed
 * interface makes Rust reconnect. Callbacks run on the main thread.
 */
class NetworkMonitor(private val service: VpnService) {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var link: LinkFacts? = null
    private var cellular = false
    private var reported: RustNetwork? = null

    /** The network Rust uses now; also the VPN's underlying network. Read by the TUN builder thread. */
    @Volatile
    var current: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // The capabilities and link properties follow at once. Until they arrive the new network's
        // facts are unknown, so Rust keeps the previous network for that moment.
        override fun onAvailable(network: Network) {
            current = network
            link = null
            service.setUnderlyingNetworks(arrayOf(network))
        }

        // Called often (signal strength), so only the cheap transport check runs here.
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != current) return
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            publish()
        }

        // The interface can change on the same network (for example when clatd starts).
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != current) return
            link = readLink(linkProperties)
            publish()
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            link = null
            cellular = false
            service.setUnderlyingNetworks(emptyArray())
            publish()
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
        registered = true
        reportDefaultNetwork()
    }

    /**
     * The callback reports the network only after the connect that came with this start reached
     * Rust, and Rust then waits 1 s before it tries again. So report the default network at once;
     * the callback confirms it a moment later, or reports the better one.
     */
    private fun reportDefaultNetwork() {
        val network = connectivity.activeNetwork ?: return
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        val linkProperties = connectivity.getLinkProperties(network) ?: return
        val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (!usable) return
        current = network
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        link = readLink(linkProperties)
        service.setUnderlyingNetworks(arrayOf(network))
        publish()
    }

    /**
     * Rust forgets the network, so it never binds to one that nobody watches any more. The
     * SystemState keeps the last facts: they are the last known state of the network.
     */
    fun stop() {
        if (!registered) return
        registered = false
        connectivity.unregisterNetworkCallback(callback)
        current = null
        report(null)
    }

    private fun publish() {
        val network = current
        if (network != null && link == null) return
        CoreHost.updateSystemState { it.withNetwork(link, cellular) }
        report(network?.let { rustNetwork(it.networkHandle, link) })
    }

    private fun report(next: RustNetwork?) {
        if (next == reported) return
        reported = next
        if (next != null) {
            RustFfi.setNetworkInterface(next.name, next.index, next.networkHandle, next.mtuHint)
        } else {
            RustFfi.unsetNetworkInterface()
        }
    }

    private fun readLink(linkProperties: LinkProperties): LinkFacts {
        val name = linkProperties.interfaceName
        val interfaces = name?.let(::interfacesNamed).orEmpty()
        val base = name?.let(interfaces::get)
        val clat = name?.let { interfaces[CLAT_INTERFACE_PREFIX + it] }
        val baseMtu = base?.let(::mtuOf)?.takeIf { it > 0 } ?: linkProperties.mtu
        return LinkFacts(
            interfaceName = name,
            interfaceIndex = base?.index ?: -1,
            mtu = clatAwareMtu(baseMtu, clat?.let(::mtuOf) ?: 0),
            privateDnsActive = linkProperties.isPrivateDnsActive,
            privateDnsServer = linkProperties.privateDnsServerName,
        )
    }

    /** One interface enumeration for [name] and its CLAT interface. */
    private fun interfacesNamed(name: String): Map<String, NetworkInterface> = try {
        val wanted = setOf(name, CLAT_INTERFACE_PREFIX + name)
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.name in wanted }
            .associateBy { it.name }
    } catch (e: SocketException) {
        Log.w(TAG, "cannot list network interfaces")
        emptyMap()
    }

    private fun mtuOf(networkInterface: NetworkInterface): Int = try {
        networkInterface.mtu
    } catch (e: SocketException) {
        0
    }

    private companion object {
        const val TAG = "OmbraNetwork"
    }
}
