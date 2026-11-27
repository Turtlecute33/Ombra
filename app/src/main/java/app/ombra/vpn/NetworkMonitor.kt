package app.ombra.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.ombra.core.ffi.RustFfi
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Follows the best physical network and tells Rust, the VPN and the UI about it. Rust does not
 * move on without an interface: a connect stays at `noInternet` until one is reported. A changed
 * interface makes Rust reconnect. The callbacks and the read of the interfaces run on an own
 * [HandlerThread]: the main thread of :vpn makes no netlink or ioctl call.
 */
class NetworkMonitor(private val service: VpnService) {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val stateLock = Any()
    private var thread: HandlerThread? = null
    private var registered = false
    private var link: LinkFacts? = null
    private var reported: RustNetwork? = null

    /** The network Rust uses now; read by the TUN builder thread. */
    @Volatile
    var current: Network? = null
        private set

    /**
     * The underlying networks for the TUN builder: the network while there is one, an empty array
     * after onLost (a TUN that is built without a network stays offline), and null before the first
     * network (the system default).
     */
    @Volatile
    var underlyingNetworks: Array<Network>? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // The capabilities and link properties follow at once. Until they arrive the new network's
        // facts are unknown, so Rust keeps the previous network for that moment.
        override fun onAvailable(network: Network) {
            synchronized(stateLock) {
                if (!registered) return
                current = network
                link = null
                val nets = arrayOf(network)
                underlyingNetworks = nets
                service.setUnderlyingNetworks(nets)
            }
        }

        // The interface can change on the same network (for example when clatd starts).
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(stateLock) {
                if (!registered || network != current) return
                link = readLink(linkProperties)
                publishLocked()
            }
        }

        override fun onLost(network: Network) {
            synchronized(stateLock) {
                if (!registered || network != current) return
                current = null
                link = null
                val empty = emptyArray<Network>()
                underlyingNetworks = empty
                service.setUnderlyingNetworks(empty)
                publishLocked()
            }
        }
    }

    fun start() {
        val handler: Handler
        synchronized(stateLock) {
            if (registered) return
            val worker = HandlerThread("ombra-net").apply { start() }
            thread = worker
            handler = Handler(worker.looper)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            registered = true
        }
        handler.post(::reportDefaultNetwork)
    }

    /**
     * The callback reports the network only after the connect that came with this start reached
     * Rust. So report the default network at once, on the worker thread; the callback confirms it
     * a moment later, or reports the better one.
     */
    private fun reportDefaultNetwork() {
        synchronized(stateLock) {
            if (!registered || current != null) return
            val network = connectivity.activeNetwork ?: return
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return
            val linkProperties = connectivity.getLinkProperties(network) ?: return
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (!usable) return
            current = network
            link = readLink(linkProperties)
            val nets = arrayOf(network)
            underlyingNetworks = nets
            service.setUnderlyingNetworks(nets)
            publishLocked()
        }
    }

    /**
     * Rust forgets the network, so it never binds to one that nobody watches any more. The
     * SystemState keeps the last facts: they are the last known state of the network.
     */
    fun stop() {
        val worker: HandlerThread?
        synchronized(stateLock) {
            if (!registered) return
            registered = false
            connectivity.unregisterNetworkCallback(callback)
            current = null
            link = null
            underlyingNetworks = null
            reportLocked(null)
            worker = thread
            thread = null
        }
        worker?.quitSafely()
    }

    private fun publishLocked() {
        val network = current
        if (network != null && link == null) return
        CoreHost.updateSystemState { it.withNetwork(link) }
        reportLocked(network?.let { rustNetwork(it.networkHandle, link) })
    }

    private fun reportLocked(next: RustNetwork?) {
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
