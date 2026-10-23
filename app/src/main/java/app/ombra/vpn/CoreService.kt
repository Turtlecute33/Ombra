package app.ombra.vpn

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import app.ombra.ipc.IOmbraCore
import app.ombra.ipc.IResultCallback
import app.ombra.ipc.IStateListener
import app.ombra.ipc.StatePush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bound service of the :vpn process: runs app-level requests and pushes state to listeners.
 * Not exported, and every entry point also checks that the caller is this app. Binder threads only
 * hand work over; outgoing calls are oneway and leave from [CoreHost.ipcDispatcher].
 */
class CoreService : Service() {
    private val pushScope = CoroutineScope(SupervisorJob() + CoreHost.ipcDispatcher)
    private val listeners = RemoteCallbackList<IStateListener>()

    override fun onCreate() {
        super.onCreate()
        CoreHost.init(applicationContext)
        CoreHost.feed.changes().forEach { (kind, values) ->
            pushScope.launch { values.collect { broadcast(kind, it) } }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        pushScope.cancel()
        listeners.kill()
        super.onDestroy()
    }

    private val binder = object : IOmbraCore.Stub() {
        override fun call(requestJson: String?, cb: IResultCallback?) {
            if (!callerAllowed() || cb == null) return
            // Process scope: a request that changes state finishes even if this service is destroyed meanwhile.
            CoreHost.scope.launch {
                val reply = CoreHost.requests.handle(requestJson.orEmpty())
                withContext(CoreHost.ipcDispatcher) { deliver(cb, reply) }
            }
        }

        override fun addListener(l: IStateListener?) {
            if (!callerAllowed() || l == null) return
            pushScope.launch {
                listeners.register(l)
                CoreHost.refreshSystemState()
                pushSnapshot(l)
            }
        }

        override fun removeListener(l: IStateListener?) {
            if (!callerAllowed() || l == null) return
            pushScope.launch { listeners.unregister(l) }
        }
    }

    /** Must run on the binder thread: the calling identity is per thread. */
    private fun callerAllowed(): Boolean {
        val allowed = Binder.getCallingUid() == Process.myUid()
        if (!allowed) Log.w(TAG, "binder call from another app refused")
        return allowed
    }

    private fun deliver(cb: IResultCallback, reply: Reply) {
        try {
            cb.onResult(reply.okJson, reply.errorCode)
        } catch (e: RemoteException) {
            Log.w(TAG, "caller went away before the result")
        }
    }

    private fun pushSnapshot(listener: IStateListener) {
        CoreHost.feed.snapshot().forEach { (kind, json) -> encode(kind, json)?.let { send(listener, kind, it) } }
    }

    private fun broadcast(kind: Int, json: String) {
        val push = encode(kind, json) ?: return
        val count = listeners.beginBroadcast()
        try {
            for (i in 0 until count) send(listeners.getBroadcastItem(i), kind, push)
        } finally {
            listeners.finishBroadcast()
        }
    }

    /** Null, with a log line, when [json] is too large to send even compressed. */
    private fun encode(kind: Int, json: String): StatePush? = StatePush.of(json).also {
        if (it == null) Log.e(TAG, "state push of kind $kind dropped: ${json.length / 1024} Ki chars of JSON")
    }

    /**
     * A failed oneway call does not unregister [listener]: binder also reports a full async buffer of
     * a live process as DeadObjectException, and RemoteCallbackList drops a dead listener by itself.
     */
    private fun send(listener: IStateListener, kind: Int, push: StatePush) {
        try {
            push.sendTo(listener, kind)
        } catch (e: RemoteException) {
            Log.w(TAG, "state push of kind $kind failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "OmbraCoreService"
    }
}
