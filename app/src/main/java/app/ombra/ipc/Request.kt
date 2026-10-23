package app.ombra.ipc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * App-level requests of [IOmbraCore.call] (JSON with a `type` discriminator). The Ok payload of
 * each is noted below; `null` means the JSON literal null. Connect and disconnect are not here:
 * they go through OmbraVpnService intents so that the foreground service exists.
 */
@Serializable
sealed interface Request {
    /**
     * Ok: null. [accountNumber] is checked locally (length + Verhoeff) before any network call
     * (error `invalidAccountNumber`). Ends a new-account flow. Account info and lists follow as pushes.
     */
    @Serializable
    @SerialName("login")
    data class Login(val accountNumber: String) : Request {
        override fun toString(): String = "Login(***)"
    }

    /**
     * Ok: the new account number as a JSON string. Show it once with a "write it down" step.
     * Logs in and turns on Status.inNewAccountFlow; [EndNewAccountFlow] turns it off. Free trial
     * availability comes from [ServiceStatus].
     */
    @Serializable
    @SerialName("createAccount")
    data object CreateAccount : Request

    /** Ok: null. Ends the flow that [CreateAccount] started, for example when the number is saved. */
    @Serializable
    @SerialName("endNewAccountFlow")
    data object EndNewAccountFlow : Request

    /** Ok: null. Disconnects. */
    @Serializable
    @SerialName("logout")
    data object Logout : Request

    /** Ok: null. Deletes the account on the server, then logs out. */
    @Serializable
    @SerialName("deleteAccount")
    data object DeleteAccount : Request

    /** Ok: null. The new account info arrives with the next STATUS push. */
    @Serializable
    @SerialName("refreshAccount")
    data object RefreshAccount : Request

    /** Ok: [app.ombra.core.model.ServiceStatus] (free trial availability). No login needed. */
    @Serializable
    @SerialName("serviceStatus")
    data object ServiceStatus : Request

    /** Ok: the account number as a JSON string, or null when logged out. Only on explicit user action. */
    @Serializable
    @SerialName("revealAccountNumber")
    data object RevealAccountNumber : Request

    /**
     * Ok: the new [Settings]. [patch] holds only the changed top-level keys (see [Settings.patchFrom]).
     * Errors: `badRequest` for a value of the wrong type, `configSaveError` when it cannot be stored.
     */
    @Serializable
    @SerialName("updateSettings")
    data class UpdateSettings(val patch: JsonObject) : Request

    /** Ok: [app.ombra.core.model.TrafficStats]. */
    @Serializable
    @SerialName("trafficStats")
    data object TrafficStats : Request

    /** Ok: null. The new key is used from the next connect. */
    @Serializable
    @SerialName("rotateWgKey")
    data object RotateWgKey : Request

    /** Ok: null. Refreshes the exit and relay lists; new lists arrive as EXITS / RELAYS pushes. */
    @Serializable
    @SerialName("refreshLists")
    data object RefreshLists : Request

    /**
     * Ok: absolute path of the zip as a JSON string; share it through the FileProvider with authority
     * `${applicationId}.files`. Send only after the consent screen: the bundle probes the network and
     * holds logs. Older bundles are deleted first.
     */
    @Serializable
    @SerialName("createDebugBundle")
    data class CreateDebugBundle(val feedback: String? = null) : Request

    /** Debug builds only. Ok: the raw Rust result (not sanitized). */
    @Serializable
    @SerialName("rawCommand")
    data class RawCommand(val json: String) : Request
}
