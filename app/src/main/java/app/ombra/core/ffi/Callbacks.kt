package app.ombra.core.ffi

import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicReference

/**
 * Static Rust -> Java callbacks (class name and signatures are a JNI contract). Rust calls them
 * on tokio threads. A Java exception left pending there is unsafe, so every entry point catches
 * Throwable and returns the failure value Rust expects.
 */
object Callbacks {
    private const val TAG = "OmbraCallbacks"

    /** Owned by OmbraVpnService while it exists. */
    fun interface NetworkConfigHandler {
        /** Builds the TUN from Rust's OsNetworkConfig JSON and returns the detached fd, or -1. */
        fun setNetworkConfig(json: String): Int
    }

    /** Owned by CoreHost for the life of the process. */
    interface HostHandler {
        /** JSON array of recent process exit reasons, or null. */
        fun processExitReasons(): String?

        /** Encrypts and stores the WireGuard secret key; true on success. */
        fun storeWgSecretKey(secret: ByteArray): Boolean
    }

    private val networkConfigHandler = AtomicReference<NetworkConfigHandler?>(null)
    private val hostHandler = AtomicReference<HostHandler?>(null)

    fun setNetworkConfigHandler(handler: NetworkConfigHandler) = networkConfigHandler.set(handler)

    /** Clears the handler only if it is still [handler], so a newer service instance stays registered. */
    fun clearNetworkConfigHandler(handler: NetworkConfigHandler) {
        networkConfigHandler.compareAndSet(handler, null)
    }

    fun setHostHandler(handler: HostHandler) = hostHandler.set(handler)

    @Keep
    @JvmStatic
    fun ffiSetNetworkConfig(json: String): Int = try {
        networkConfigHandler.get()?.setNetworkConfig(json) ?: run {
            Log.w(TAG, "network config without a VPN service")
            -1
        }
    } catch (t: Throwable) {
        Log.e(TAG, "ffiSetNetworkConfig failed: ${t.javaClass.name}")
        -1
    }

    @Keep
    @JvmStatic
    fun ffiGetProcessExitReasons(): String? = try {
        hostHandler.get()?.processExitReasons()
    } catch (t: Throwable) {
        Log.e(TAG, "ffiGetProcessExitReasons failed: ${t.javaClass.name}")
        null
    }

    @Keep
    @JvmStatic
    fun ffiStoreWgSecretKey(secret: ByteArray): Boolean = try {
        hostHandler.get()?.storeWgSecretKey(secret) ?: false
    } catch (t: Throwable) {
        Log.e(TAG, "ffiStoreWgSecretKey failed: ${t.javaClass.name}")
        false
    }
}
