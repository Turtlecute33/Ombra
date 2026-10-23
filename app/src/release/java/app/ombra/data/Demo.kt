package app.ombra.data

import android.content.Intent

/** Release builds have no demo mode: the UI always talks to the core. */
@Suppress("UNUSED_PARAMETER")
fun demoCoreFor(intent: Intent?): CoreSource? = null
