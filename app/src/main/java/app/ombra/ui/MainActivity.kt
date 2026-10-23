package app.ombra.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import app.ombra.BuildConfig
import app.ombra.data.CoreClient
import app.ombra.data.TunnelControl
import app.ombra.data.ThemeMode
import app.ombra.data.UiPrefs
import app.ombra.data.demoCoreFor
import app.ombra.ui.components.SensitiveClip
import app.ombra.ui.theme.OmbraTheme
import app.ombra.vpn.OmbraVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val demo = demoCoreFor(intent)
                AppViewModel(demo ?: CoreClient(application), firstFramePrefs(), isDemo = demo != null)
            }
        }
    }
    private lateinit var tunnel: TunnelControl

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) enableStrictMode()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A VPN app has no reason to let other apps draw over it (tapjacking).
        window.setHideOverlayWindows(true)
        // A demo core also plays the tunnel; the real one needs the consent flow of this activity.
        tunnel = app.core as? TunnelControl ?: VpnConsent(this, app.prefs)
        // Demo data holds no secrets, and its screens must stay capturable for UI review.
        val secureWindow = if (app.isDemo) null else SecureWindow(window)

        setContent {
            val theme by app.prefs.theme.collectAsStateWithLifecycle()
            val dynamicColor by app.prefs.dynamicColor.collectAsStateWithLifecycle()
            val secureAll by app.prefs.secureAllScreens.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            DisposableEffect(dark) {
                // The app theme can differ from the system one: the bar icons follow the app.
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            LaunchedEffect(secureAll) { secureWindow?.secureAll = secureAll }
            OmbraTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                CompositionLocalProvider(LocalSecureWindow provides secureWindow) {
                    OmbraApp(app, tunnel)
                }
            }
        }
        if (savedInstanceState == null) onLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isConnectRequest(intent)) connectWhenStarted()
    }

    override fun onStart() {
        super.onStart()
        app.core.bind()
    }

    override fun onStop() {
        app.core.unbind()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only the focused app can read the clipboard: a secret copied before the app went to the
        // background is cleared now, if its minute is over.
        if (hasFocus) SensitiveClip.clearIfDue(this)
    }

    /**
     * The first frame needs the theme and the secure-screens choice, so the small prefs file is read
     * here on purpose (about 2 ms). StrictMode then reports only reads that nobody planned.
     */
    private fun firstFramePrefs(): UiPrefs {
        val policy = StrictMode.allowThreadDiskReads()
        try {
            return UiPrefs(application)
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /** Debug builds log disk and network work on the main thread, leaks and unsafe intents. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    /** The tile and the Connect shortcut send ACTION_CONNECT here when VPN consent is missing. */
    private fun onLaunch(intent: Intent?) {
        if (isConnectRequest(intent)) {
            connectWhenStarted()
        } else {
            lifecycleScope.launch { if (app.autoConnectOnLaunch()) withStarted { tunnel.connect() } }
        }
    }

    /**
     * ACTION_CONNECT counts only through [CONNECT_ALIAS], which other apps cannot start. This
     * activity is exported (launcher), so another app could otherwise connect Ombra and push
     * another VPN app aside.
     */
    private fun isConnectRequest(intent: Intent?): Boolean =
        intent?.action == OmbraVpnService.ACTION_CONNECT && intent.component?.className == CONNECT_ALIAS

    /** Consent dialogs need a started activity. */
    private fun connectWhenStarted() {
        lifecycleScope.launch { withStarted { tunnel.connect() } }
    }

    companion object {
        /** The not exported activity-alias of this activity (AndroidManifest.xml). */
        const val CONNECT_ALIAS = "app.ombra.ui.ConnectAlias"

        /** Opens this activity and connects, for the tile, the notification and the shortcuts. */
        fun connectIntent(context: Context): Intent =
            Intent().setComponent(ComponentName(context, CONNECT_ALIAS)).setAction(OmbraVpnService.ACTION_CONNECT)
    }
}
