package app.ombra.core.ffi

import androidx.annotation.Keep

/**
 * Error of a core command. Rust builds it through the (String) constructor and completes the
 * command future with it; [errorCode] is a ManagerCmdErrorCode wire value. Kotlin also throws it
 * with the app-level codes in [app.ombra.ipc.IpcError].
 */
@Keep
class ErrorCodeException(val errorCode: String) : Exception(errorCode)
