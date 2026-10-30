package app.ombra.core.ffi

import java.util.concurrent.CompletableFuture

/**
 * Kotlin facade over [Native]. Holds the one handle of this process. Every call except
 * [initialize] fails with [IllegalStateException] before initialization, because Rust aborts the
 * whole process when it is called uninitialized.
 */
object RustFfi {
    @Volatile
    private var handle: Native.FfiHandle? = null

    val isInitialized: Boolean get() = handle != null

    /**
     * Loads the library (:vpn process only) and initializes Rust. Returns false when this process
     * is already initialized; Rust ignores the arguments of a second initialize anyway.
     * Rust aborts the process if the config dir is not usable, so create it first.
     */
    @Synchronized
    fun initialize(configDir: String, userAgent: String, wgSecretKey: ByteArray?, configKey: ByteArray?, persistLogs: Boolean): Boolean {
        if (handle != null) return false
        Native.loadLibrary()
        handle = Native.initialize(configDir, userAgent, wgSecretKey, configKey, persistLogs)
        return true
    }

    /**
     * Sends one ManagerCmd JSON. Throws [RuntimeException] synchronously when Rust cannot parse the
     * JSON; the future then never completes. Rust completes [future] on a tokio thread: never block
     * in its callbacks.
     */
    fun jsonFfi(json: String, future: CompletableFuture<String?>) = Native.jsonFfi(requireHandle(), json, future)

    /** See [Native.setNetworkInterface]. */
    fun setNetworkInterface(name: String, index: Int, networkHandle: Long, mtuHint: Int) {
        require(index >= 1) { "interface index must be >= 1" }
        require(mtuHint >= 0) { "mtuHint must be >= 0" }
        Native.setNetworkInterface(requireHandle(), name, index, networkHandle, mtuHint)
    }

    fun unsetNetworkInterface() = Native.unsetNetworkInterface(requireHandle())

    /** Tells Rust the device woke up, so a dead tunnel is detected after one lost probe. */
    fun wake() = Native.wake(requireHandle())

    fun flushAndStopPersistedLog() {
        requireHandle()
        Native.flushAndStopPersistedLog()
    }

    private fun requireHandle(): Native.FfiHandle = handle ?: throw IllegalStateException("Rust core is not initialized")
}
