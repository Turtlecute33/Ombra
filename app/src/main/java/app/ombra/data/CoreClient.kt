package app.ombra.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.ipc.IOmbraCore
import app.ombra.ipc.IResultCallback
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.StateKind
import app.ombra.ipc.StateListener
import app.ombra.ipc.SystemState
import app.ombra.vpn.CoreService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A failed [CoreClient.request]: a Rust ManagerCmdErrorCode or an [IpcError] code. */
class CoreRequestException(val code: String) : Exception(code)

/**
 * Main-process side of the IPC: binds CoreService in :vpn, mirrors its pushed state and sends
 * [Request]s. The main process never loads the native core.
 */
class CoreClient(context: Context) : CoreSource {
    private val appContext = context.applicationContext
    private val core = MutableStateFlow<IOmbraCore?>(null)
    private val pending = ConcurrentHashMap.newKeySet<PendingCall>()
    private var bound = false

    private val statusState = MutableStateFlow<Status?>(null)
    private val exitsState = MutableStateFlow<CachedValue<ExitList>?>(null)
    private val relaysState = MutableStateFlow<CachedValue<List<OneRelay>>?>(null)
    private val settingsState = MutableStateFlow<Settings?>(null)
    private val systemState = MutableStateFlow<SystemState?>(null)

    override val status: StateFlow<Status?> = statusState.asStateFlow()
    override val exits: StateFlow<CachedValue<ExitList>?> = exitsState.asStateFlow()
    override val relays: StateFlow<CachedValue<List<OneRelay>>?> = relaysState.asStateFlow()
    override val settings: StateFlow<Settings?> = settingsState.asStateFlow()
    override val system: StateFlow<SystemState?> = systemState.asStateFlow()

    private val exitsSerializer: KSerializer<CachedValue<ExitList>> = CachedValue.serializer(ExitList.serializer())
    private val relaysSerializer: KSerializer<CachedValue<List<OneRelay>>> =
        CachedValue.serializer(ListSerializer(OneRelay.serializer()))

    private val listener = object : StateListener() {
        override fun onStateJson(kind: Int, json: String) {
            try {
                when (kind) {
                    StateKind.STATUS -> statusState.value = IpcJson.decodeFromString(Status.serializer(), json)
                    StateKind.EXITS -> exitsState.value = IpcJson.decodeFromString(exitsSerializer, json)
                    StateKind.RELAYS -> relaysState.value = IpcJson.decodeFromString(relaysSerializer, json)
                    StateKind.SETTINGS -> settingsState.value = IpcJson.decodeFromString(Settings.serializer(), json)
                    StateKind.SYSTEM -> systemState.value = IpcJson.decodeFromString(SystemState.serializer(), json)
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "bad state push of kind $kind")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = IOmbraCore.Stub.asInterface(binder)
            try {
                remote.addListener(listener)
            } catch (e: RemoteException) {
                return
            }
            core.value = remote
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // :vpn died, and its tunnel with it: show Loading until the new process pushes a status.
            statusState.value = null
            lost()
        }

        override fun onBindingDied(name: ComponentName) {
            lost()
            // The binding is dead for good: bind again so a restarted :vpn process is picked up.
            unbind()
            bind()
        }
    }

    /** Starts the :vpn process if needed. */
    override fun bind() {
        if (bound) return
        bound = appContext.bindService(Intent(appContext, CoreService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.e(TAG, "cannot bind CoreService")
    }

    override fun unbind() {
        if (!bound) return
        bound = false
        try {
            core.value?.removeListener(listener)
        } catch (e: RemoteException) {
            // The process is gone already.
        }
        appContext.unbindService(connection)
        lost()
    }

    /** See [Request] for each Ok payload. Waits up to [BIND_TIMEOUT] for the binder. */
    override suspend fun request(request: Request): String {
        val remote = try {
            withTimeout(BIND_TIMEOUT) { core.filterNotNull().first() }
        } catch (e: TimeoutCancellationException) {
            throw CoreRequestException(IpcError.DISCONNECTED)
        }
        val json = IpcJson.encodeToString(Request.serializer(), request)
        return try {
            withTimeout(REQUEST_TIMEOUT) {
                suspendCancellableCoroutine { cont ->
                    val call = PendingCall(cont)
                    pending += call
                    cont.invokeOnCancellation { pending -= call }
                    try {
                        remote.call(json, call)
                    } catch (e: RemoteException) {
                        call.fail(IpcError.DISCONNECTED)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw CoreRequestException(IpcError.TIMEOUT)
        }
    }

    private fun lost() {
        core.value = null
        pending.toList().forEach { it.fail(IpcError.DISCONNECTED) }
    }

    /** One reply slot; a oneway reply and a disconnect can race, so it resumes at most once. */
    private inner class PendingCall(private val cont: CancellableContinuation<String>) : IResultCallback.Stub() {
        private val done = AtomicBoolean(false)

        override fun onResult(okJson: String?, errorCode: String?) {
            if (!done.compareAndSet(false, true)) return
            pending -= this
            if (errorCode != null) cont.resumeWithException(CoreRequestException(errorCode)) else cont.resume(okJson ?: "null")
        }

        fun fail(code: String) = onResult(null, code)
    }

    private companion object {
        const val TAG = "OmbraCoreClient"
        val BIND_TIMEOUT = 10.seconds

        /** Longer than the slowest core command (API calls with proof-of-work). */
        val REQUEST_TIMEOUT = 3.minutes
    }
}
