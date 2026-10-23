package app.ombra.vpn

import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.ffi.RustFfi
import app.ombra.core.model.CoreCall
import app.ombra.ipc.IpcError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

/** [CoreChannel] over the JNI bridge of this process (:vpn only). */
object CoreBridge : CoreChannel {
    override suspend fun <R> call(call: CoreCall<R>): R {
        val json = raw(call.encode(), call.timeout)
        return try {
            call.decode(json)
        } catch (e: SerializationException) {
            throw ErrorCodeException(IpcError.BAD_RESPONSE)
        } catch (e: IllegalArgumentException) {
            throw ErrorCodeException(IpcError.BAD_RESPONSE)
        }
    }

    /**
     * Sends [json] and waits for the Ok payload. [timeout] null waits forever (long-polls). A
     * timeout does not cancel the Rust task; its late result is dropped.
     */
    override suspend fun raw(json: String, timeout: Duration?): String {
        val future = CompletableFuture<String?>()
        try {
            RustFfi.jsonFfi(json, future)
        } catch (e: IllegalStateException) {
            throw ErrorCodeException(IpcError.CORE_NOT_READY)
        } catch (e: RuntimeException) {
            // Rust could not parse the command; it never completes the future.
            throw ErrorCodeException(IpcError.BAD_COMMAND)
        }
        // Rust completes the future on a tokio worker; await() only resumes the coroutine, so
        // nothing runs on that thread. Default is the dispatcher we continue on.
        val result = withContext(Dispatchers.Default) {
            try {
                if (timeout == null) future.await() else withTimeout(timeout) { future.await() }
            } catch (e: TimeoutCancellationException) {
                throw ErrorCodeException(IpcError.TIMEOUT)
            }
        }
        // Rust completes with null only when it could not create the Java string.
        return result ?: throw ErrorCodeException("other")
    }
}
