package app.ombra.vpn

import app.ombra.core.model.CoreCall
import kotlin.time.Duration

/**
 * The Rust command channel: [CoreBridge] in the app, a fake in JVM tests. Every failure is an
 * [app.ombra.core.ffi.ErrorCodeException] with a Rust ManagerCmdErrorCode or an
 * [app.ombra.ipc.IpcError] code.
 */
interface CoreChannel {
    /** Sends [call] and decodes its Ok payload, within the timeout of the call. */
    suspend fun <R> call(call: CoreCall<R>): R

    /** Sends raw ManagerCmd JSON and returns the Ok payload. [timeout] null waits forever. */
    suspend fun raw(json: String, timeout: Duration?): String
}
