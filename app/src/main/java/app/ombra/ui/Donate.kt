package app.ombra.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import app.ombra.R
import app.ombra.ui.components.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small donation card when the user opens the app from the launcher: first 7 days after the install, then
 * at most once each 90 days (365 days after a tap on Donate). No notification and no network. The only data
 * is the time of the next card, in a private prefs file (no backup, DESIGN §10, §8).
 */
@Composable
fun DonatePrompt() {
    val activity = LocalActivity.current ?: return
    var show by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Not for the tile or a Connect shortcut: the user came to do a task.
        if (activity.intent?.action == Intent.ACTION_MAIN && withContext(Dispatchers.IO) { Donate.claim(activity) }) show = true
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        icon = { Icon(Icons.Beer, null) },
        title = { Text(stringResource(R.string.donate_title)) },
        text = { Text(stringResource(R.string.donate_text)) },
        confirmButton = {
            Button(onClick = {
                show = false
                Donate.snooze(activity, Donate.AFTER_DONATE_DAYS)
                openLink(activity, OmbraLinks.DONATE)
            }) { Text(stringResource(R.string.donate_action)) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.donate_later)) } },
    )
}

private object Donate {
    const val AFTER_DONATE_DAYS = 365L
    private const val FIRST_DAYS = 7L
    private const val LATER_DAYS = 90L
    private const val DAY_MS = 86_400_000L
    private const val NEXT = "next"

    private fun prefs(context: Context) = context.getSharedPreferences("donate", Context.MODE_PRIVATE)

    /** True when the card is due. It then sets the next card at once, so that Back or a closed app also waits 90 days. */
    fun claim(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(now)
        if (now < prefs(context).getLong(NEXT, installed + FIRST_DAYS * DAY_MS)) return false
        snooze(context, LATER_DAYS)
        return true
    }

    fun snooze(context: Context, days: Long) =
        prefs(context).edit { putLong(NEXT, System.currentTimeMillis() + days * DAY_MS) }
}
