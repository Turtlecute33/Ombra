package app.ombra.ui

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * FLAG_SECURE (no screenshots, no recording, blank in Recents) while at least one screen asks for
 * it with [SecureContent], or everywhere when [secureAll] is on.
 */
class SecureWindow(private val window: Window) {
    private var requests = 0

    var secureAll: Boolean = false
        set(value) {
            field = value
            update()
        }

    fun acquire() {
        requests++
        update()
    }

    fun release() {
        requests--
        update()
    }

    private fun update() {
        if (secureAll || requests > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

val LocalSecureWindow = staticCompositionLocalOf<SecureWindow?> { null }

/** Keeps the window secure while this is in the composition (account numbers, login). */
@Composable
fun SecureContent() {
    val secure = LocalSecureWindow.current ?: return
    DisposableEffect(secure) {
        secure.acquire()
        onDispose { secure.release() }
    }
}
