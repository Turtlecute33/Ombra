package app.ombra.ui.components

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
        // The handler does not run when Android stops the process within the minute; the alarm
        // starts the process again. Inexact, so it needs no permission.
        val at = copiedAt ?: return
        appContext.alarms().set(AlarmManager.ELAPSED_REALTIME, dueAt, clearAlarm(appContext, at))
    }

    /**
     * Clears the clip when its minute is over and it is still Ombra's, or when Ombra cannot see
     * whose it is. Also call when the window gets the focus.
     */
    fun clearIfDue(context: Context) {
        val at = copiedAt ?: return
        if (SystemClock.elapsedRealtime() < dueAt) return
        clear(context, at)
        copiedAt = null
        // A late alarm must not clear a clip that the user copies after this.
        val appContext = context.applicationContext
        appContext.alarms().cancel(clearAlarm(appContext, at))
    }

    /** The alarm of [copy]: clears the clip copied at [at], also in a new process. */
    internal fun clearFromAlarm(context: Context, at: Long) {
        clear(context, at)
        if (copiedAt == at) {
            copiedAt = null
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun clear(context: Context, at: Long) {
        val clipboard = context.clipboard()
        // Null in the background (or when the clipboard is empty).
        val current = clipboard.primaryClipDescription
        if (current == null || current.timestamp == at) clipboard.clearPrimaryClip()
    }

    private fun clearAlarm(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ClipClearReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun Context.clipboard(): ClipboardManager = getSystemService(ClipboardManager::class.java)

    private fun Context.alarms(): AlarmManager = getSystemService(AlarmManager::class.java)

    internal const val EXTRA_AT = "at"
    private const val CLEAR_AFTER_MS = 60_000L
}

/** Target of the clear alarm of [SensitiveClip]. Not exported: only the alarm of this app sends it. */
class ClipClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = intent.getLongExtra(SensitiveClip.EXTRA_AT, 0L)
        if (at != 0L) SensitiveClip.clearFromAlarm(context, at)
    }
}
