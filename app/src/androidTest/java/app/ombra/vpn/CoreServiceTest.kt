package app.ombra.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.ombra.core.model.Status
import app.ombra.ipc.IOmbraCore
import app.ombra.ipc.IResultCallback
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.StateKind
import app.ombra.ipc.StateListener
import app.ombra.ipc.SystemState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The :vpn binder on a device, logged out: nothing here reaches the network. Never log in or
 * create an account here.
 */
class CoreServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun newListenerGetsSanitizedStatusSettingsAndSystem() = withCore { core ->
        val pushes = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        val listener = object : StateListener() {
            override fun onStateJson(kind: Int, json: String) {
                pushes.trySend(kind to json)
            }
        }
        core.addListener(listener)
        try {
            val latest = mutableMapOf<Int, String>()
            // STATUS can come after the snapshot when the :vpn process has just started.
            while (!latest.keys.containsAll(listOf(StateKind.STATUS, StateKind.SETTINGS, StateKind.SYSTEM))) {
                val (kind, json) = pushes.receive()
                latest[kind] = json
            }
            assertNull(IpcJson.decodeFromString(Status.serializer(), latest.getValue(StateKind.STATUS)).accountId)
            IpcJson.decodeFromString(SystemState.serializer(), latest.getValue(StateKind.SYSTEM))
        } finally {
            core.removeListener(listener)
        }
    }

    @Test
    fun requestsAnswerWhileLoggedOut() = withCore { core ->
        assertEquals(Answer("null", null), core.send(IpcJson.encodeToString(Request.serializer(), Request.RevealAccountNumber)))
        assertEquals(Answer("null", null), core.send(IpcJson.encodeToString(Request.serializer(), Request.EndNewAccountFlow)))
        assertEquals(Answer(null, IpcError.BAD_REQUEST), core.send("{"))
        val badNumber = IpcJson.encodeToString(Request.serializer(), Request.Login("1234"))
        assertEquals(Answer(null, IpcError.INVALID_ACCOUNT_NUMBER), core.send(badNumber))
    }

    @Test
    fun fileProviderSharesOnlyDebugArchives() {
        val authority = "${context.packageName}.files"
        val uri = FileProvider.getUriForFile(context, authority, File(context.cacheDir, "debug-archives/bundle.zip"))
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertFailsWith<IllegalArgumentException> {
            FileProvider.getUriForFile(context, authority, File(context.noBackupFilesDir, "settings.json"))
        }
    }

    private data class Answer(val okJson: String?, val errorCode: String?)

    private suspend fun IOmbraCore.send(requestJson: String): Answer {
        val answer = CompletableDeferred<Answer>()
        call(
            requestJson,
            object : IResultCallback.Stub() {
                override fun onResult(okJson: String?, errorCode: String?) {
                    answer.complete(Answer(okJson, errorCode))
                }
            },
        )
        return answer.await()
    }

    private fun withCore(block: suspend (IOmbraCore) -> Unit) = runBlocking {
        val bound = CompletableDeferred<IOmbraCore>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.complete(IOmbraCore.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent(context, CoreService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            withTimeout(TIMEOUT) { block(bound.await()) }
        } finally {
            context.unbindService(connection)
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
