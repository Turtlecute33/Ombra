package app.ombra.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock

/**
 * Copies a secret: marked sensitive (keyboards and the clipboard preview hide it) and cleared after
 * a minute, unless something else was copied in the meantime.
 */
fun copySensitive(context: Context, label: String, text: String) = SensitiveClip.copy(context, label, text)

/**
 * The secret on the clipboard, if Ombra put one there. Android lets only the focused app read the
 * clipboard, so Ombra cannot see in the background whether the clip is still its own: a clear that
 * falls due then clears the clip anyway, also a clip that the user copied in another app within
 * the minute. Writing to the clipboard needs no focus.
 */
object SensitiveClip {
    private val main = Handler(Looper.getMainLooper())

    /** [ClipDescription.getTimestamp] of the clip that Ombra copied; null when nothing waits. */
    private var copiedAt: Long? = null
    private var dueAt = 0L

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.clipboard()
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        copiedAt = clipboard.primaryClipDescription?.timestamp
        dueAt = SystemClock.elapsedRealtime() + CLEAR_AFTER_MS
        val appContext = context.applicationContext
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ clearIfDue(appContext) }, CLEAR_AFTER_MS)
    }

    /**
     * Clears the clip when its minute is over and it is still Ombra's, or when Ombra cannot see
     * whose it is. Also call when the window gets the focus.
     */
    fun clearIfDue(context: Context) {
        val at = copiedAt ?: return
        if (SystemClock.elapsedRealtime() < dueAt) return
        val clipboard = context.clipboard()
        // Null in the background (or when the clipboard is empty).
        val current = clipboard.primaryClipDescription
        if (current == null || current.timestamp == at) clipboard.clearPrimaryClip()
        copiedAt = null
    }

    private fun Context.clipboard(): ClipboardManager = getSystemService(ClipboardManager::class.java)

    private const val CLEAR_AFTER_MS = 60_000L
}
