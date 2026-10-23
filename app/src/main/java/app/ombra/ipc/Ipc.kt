package app.ombra.ipc

import kotlinx.serialization.json.Json

/** JSON for main <-> :vpn payloads (Request, Settings, pushed state). */
val IpcJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** `kind` of [IStateListener.onState]. */
object StateKind {
    /** Sanitized [app.ombra.core.model.Status]. */
    const val STATUS = 1

    /** `CachedValue<ExitList>`, pushed when its version changes. */
    const val EXITS = 2

    /** `CachedValue<List<OneRelay>>`, pushed when its version changes. */
    const val RELAYS = 3

    /** [Settings]. */
    const val SETTINGS = 4

    /** [SystemState]: Android-side facts the UI shows (kill switch, Private DNS, battery). */
    const val SYSTEM = 5
}

/**
 * App-level error codes of [IResultCallback.onResult], next to the Rust ManagerCmdErrorCode values
 * (for example `apiUnreachable`, `notLoggedIn`).
 */
object IpcError {
    /** The core did not answer in time. */
    const val TIMEOUT = "timeout"

    /** Rust rejected the command JSON (for example a command that this core build lacks). */
    const val BAD_COMMAND = "badCommand"

    /** The Rust core is not loaded or failed to initialize. */
    const val CORE_NOT_READY = "coreNotReady"

    /** The request JSON did not decode. */
    const val BAD_REQUEST = "badRequest"

    /** Local check of the account number failed; nothing was sent. */
    const val INVALID_ACCOUNT_NUMBER = "invalidAccountNumber"

    /** The request is not allowed in this build (for example RawCommand in release). */
    const val NOT_ALLOWED = "notAllowed"

    /** The :vpn process went away before it answered. */
    const val DISCONNECTED = "coreDisconnected"

    /** The core answered with a payload that did not decode. */
    const val BAD_RESPONSE = "badResponse"
}
