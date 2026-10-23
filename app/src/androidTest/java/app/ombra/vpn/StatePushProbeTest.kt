package app.ombra.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.ipc.IOmbraCore
import app.ombra.ipc.IpcJson
import app.ombra.ipc.StateKind
import app.ombra.ipc.StateListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * 2000 exits and 300 relays reach the UI process from another process ([StatePushProbe], debug
 * builds only). As one String the exit list alone would need about 1.1 MB in the Parcel, more than
 * the whole binder buffer of the receiver.
 */
class StatePushProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeListsArriveWholeAndInOrder() = runBlocking {
        val pushes = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        val listener = object : StateListener() {
            override fun onStateJson(kind: Int, json: String) {
                pushes.trySend(kind to json)
            }
        }
        val probe = CompletableDeferred<IOmbraCore>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                probe.complete(IOmbraCore.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent(context, StatePushProbe::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val received = withTimeout(TIMEOUT) {
                probe.await().addListener(listener)
                List(4) { pushes.receive() }.toMap()
            }
            // Compressed and plain pushes keep the order in which they were sent.
            assertEquals(listOf(StateKind.EXITS, StateKind.RELAYS, StateKind.SETTINGS, StateKind.SYSTEM), received.keys.toList())
            assertEquals(StatePushProbe.EXITS, IpcJson.decodeFromString(EXITS, received.getValue(StateKind.EXITS)))
            assertEquals(
                StatePushProbe.RELAYS.value.map { it.copy(tlsCert = "") },
                IpcJson.decodeFromString(RELAYS, received.getValue(StateKind.RELAYS)).value,
            )
        } finally {
            context.unbindService(connection)
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val EXITS = CachedValue.serializer(ExitList.serializer())
        val RELAYS = CachedValue.serializer(ListSerializer(OneRelay.serializer()))
    }
}
