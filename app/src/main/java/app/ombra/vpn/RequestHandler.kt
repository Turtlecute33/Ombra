package app.ombra.vpn

import android.util.Log
import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.model.CoreCommands
import app.ombra.core.model.ManagerCmdErrorCode
import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.jsonString
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/** The answer to one IOmbraCore.call: [okJson] on success, else [errorCode]. */
data class Reply(val okJson: String?, val errorCode: String?) {
    companion object {
        fun ok(json: String) = Reply(json, null)

        fun error(code: String) = Reply(null, code)
    }
}

/** Runs app-level [Request]s (see Request.kt for each Ok payload) and turns every failure into an error code. */
class RequestHandler(
    private val controller: CoreController,
    private val core: CoreChannel,
    private val debugBundles: DebugBundles,
    private val allowRawCommands: Boolean,
    private val deleteWgKey: () -> Unit = {},
    private val forgetLogs: () -> Unit = {},
) {
    /**
     * After a logout: the last debug bundle and the persisted Rust logs hold logs of the account's
     * use, and the key was the account's.
     */
    private fun forgetAccountFiles() {
        debugBundles.deleteAll()
        deleteWgKey()
        forgetLogs()
    }

    suspend fun handle(requestJson: String): Reply {
        val request = try {
            IpcJson.decodeFromString(Request.serializer(), requestJson)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            return Reply.error(IpcError.BAD_REQUEST)
        }
        return try {
            Reply.ok(execute(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reply.error(errorCode(e))
        }
    }

    private suspend fun execute(request: Request): String = when (request) {
        is Request.Login -> controller.login(request.accountNumber).let { JSON_NULL }
        is Request.CreateAccount -> jsonString(controller.createAccount(request.referral))
        Request.EndNewAccountFlow -> controller.endNewAccountFlow().let { JSON_NULL }
        Request.Logout -> controller.logout().also { forgetAccountFiles() }.let { JSON_NULL }
        Request.DeleteAccount -> controller.deleteAccount().also { forgetAccountFiles() }.let { JSON_NULL }
        Request.RefreshAccount -> controller.refreshAccount().let { JSON_NULL }
        Request.ServiceStatus -> IpcJson.encodeToString(ServiceStatus.serializer(), core.call(CoreCommands.apiServiceStatus()))
        Request.RevealAccountNumber -> jsonString(controller.accountNumber())
        is Request.UpdateSettings -> IpcJson.encodeToString(Settings.serializer(), controller.updateSettings(request.patch))
        Request.TrafficStats -> IpcJson.encodeToString(TrafficStats.serializer(), core.call(CoreCommands.getTrafficStats()))
        Request.RotateWgKey -> core.call(CoreCommands.rotateWgKey()).let { JSON_NULL }
        Request.RefreshLists -> controller.refreshLists().let { JSON_NULL }
        is Request.CreateDebugBundle -> jsonString(debugBundles.create(request.feedback))
        is Request.RawCommand ->
            if (allowRawCommands) core.raw(request.json, RAW_TIMEOUT) else throw ErrorCodeException(IpcError.NOT_ALLOWED)
    }

    companion object {
        private const val TAG = "OmbraRequests"
        private const val JSON_NULL = "null"
        private val RAW_TIMEOUT = 2.minutes

        /** The IPC error code of a failed request: a Rust ManagerCmdErrorCode or an [IpcError] code. */
        fun errorCode(e: Exception): String = when (e) {
            is ErrorCodeException -> e.errorCode
            // A patch value of the wrong type.
            is SerializationException -> IpcError.BAD_REQUEST
            // settings.json could not be written; nothing was sent to Rust.
            is IOException -> ManagerCmdErrorCode.ConfigSaveError.wire
            else -> {
                Log.e(TAG, "request failed: ${e.javaClass.name}")
                ManagerCmdErrorCode.Other.wire
            }
        }
    }
}
