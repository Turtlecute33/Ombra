package app.ombra.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import app.ombra.R

/** Obscura web pages that Ombra opens in the browser. Kotlin never loads them itself. */
object ObscuraLinks {
    const val TERMS = "https://obscura.com/legal/"

    /** The upstream client, whose Rust core Ombra uses (GPL-3.0). */
    const val UPSTREAM_SOURCE = "https://github.com/Sovereign-Engineering/obscuravpn-client"

    /** Add time or manage payments. The number is in the fragment, which the browser does not send. */
    fun payment(accountNumber: String): String = "https://obscura.com/pay#account_id=$accountNumber"
}

object OmbraLinks {
    /** The source code of Ombra (GPL-3.0). */
    const val SOURCE = "https://github.com/Turtlecute33/Ombra"
}

/** Opens [url] in the browser; says so when no app can open it. */
fun openLink(context: Context, url: String) = openExternal(context, Intent(Intent.ACTION_VIEW, url.toUri()))

/** Starts a system screen or another app; says so when the device has none for [intent]. */
fun openExternal(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}

/** The system page of this app, where the user sets battery use to "Unrestricted". */
fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
