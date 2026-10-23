package app.ombra.ipc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One state push as it crosses the binder (docs/DESIGN.md section 6). All oneway calls into the UI
 * process share its async binder buffer (about 512 KiB), and a String takes two bytes per char in a
 * Parcel. So short JSON goes as a String ([IStateListener.onState]) and longer JSON, in practice the
 * exit and relay lists, goes gzip-compressed ([IStateListener.onStateGz]).
 */
sealed interface StatePush {
    /** Makes the oneway call. */
    fun sendTo(listener: IStateListener, kind: Int)

    class Plain(val json: String) : StatePush {
        override fun sendTo(listener: IStateListener, kind: Int) = listener.onState(kind, json)
    }

    class Gzip(val gzJson: ByteArray) : StatePush {
        override fun sendTo(listener: IStateListener, kind: Int) = listener.onStateGz(kind, gzJson)
    }

    companion object {
        /** Longest JSON that goes as a String: 32 KiB in the Parcel. */
        const val MAX_PLAIN_CHARS = 16 * 1024

        /** Largest compressed JSON that goes at all; 2000 exits need about a third of it. */
        const val MAX_GZIP_BYTES = 128 * 1024

        /** The wire form of [json], or null when even the compressed JSON is above [MAX_GZIP_BYTES]. */
        fun of(json: String): StatePush? {
            if (json.length <= MAX_PLAIN_CHARS) return Plain(json)
            val gzJson = gzip(json)
            return if (gzJson.size <= MAX_GZIP_BYTES) Gzip(gzJson) else null
        }

        fun gzip(json: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json.encodeToByteArray()) }
            return out.toByteArray()
        }

        /** Throws [IOException] when [gzJson] is not complete gzip data. */
        fun gunzip(gzJson: ByteArray): String = GZIPInputStream(gzJson.inputStream()).use { it.readBytes() }.decodeToString()
    }
}

/**
 * The receiving end of [StatePush]: both forms arrive as JSON in [onStateJson]. That runs on the
 * binder thread that delivers the push, never on the main thread. Binder delivers the oneway calls
 * to one object one at a time and in order, so a compressed push cannot overtake a plain one.
 */
abstract class StateListener : IStateListener.Stub() {
    abstract fun onStateJson(kind: Int, json: String)

    final override fun onState(kind: Int, json: String?) {
        if (json != null) onStateJson(kind, json)
    }

    final override fun onStateGz(kind: Int, gzJson: ByteArray?) {
        if (gzJson == null) return
        val json = try {
            StatePush.gunzip(gzJson)
        } catch (e: IOException) {
            Log.e(TAG, "bad compressed state push of kind $kind")
            return
        }
        onStateJson(kind, json)
    }

    private companion object {
        const val TAG = "OmbraStateListener"
    }
}
