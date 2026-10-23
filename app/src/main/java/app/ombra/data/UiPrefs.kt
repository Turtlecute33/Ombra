package app.ombra.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Dark, Light }

/**
 * Preferences of the UI process only (appearance, one-time prompts and hints). Nothing secret: the
 * VPN settings live in :vpn (SettingsStore), the account number in the Rust config. The app has no
 * backup at all (allowBackup off, data extraction rules exclude everything).
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val themeState = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
    )
    private val dynamicColorState = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    private val secureAllScreensState = MutableStateFlow(prefs.getBoolean(KEY_SECURE_ALL, false))
    private val batteryHintDismissedState = MutableStateFlow(prefs.getBoolean(KEY_BATTERY_HINT_DISMISSED, false))

    val theme: StateFlow<ThemeMode> = themeState.asStateFlow()

    /** Wallpaper colours instead of the brand scheme. */
    val dynamicColor: StateFlow<Boolean> = dynamicColorState.asStateFlow()

    /** FLAG_SECURE on every screen, not only on account screens. */
    val secureAllScreens: StateFlow<Boolean> = secureAllScreensState.asStateFlow()

    /** The hint about battery restrictions shows on Connect until the user acts on it once. */
    val batteryHintDismissed: StateFlow<Boolean> = batteryHintDismissedState.asStateFlow()

    /** The notification permission is asked once, at the first connect. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, value) }

    fun setTheme(mode: ThemeMode) {
        themeState.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    fun setDynamicColor(enabled: Boolean) {
        dynamicColorState.value = enabled
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    fun setSecureAllScreens(enabled: Boolean) {
        secureAllScreensState.value = enabled
        prefs.edit { putBoolean(KEY_SECURE_ALL, enabled) }
    }

    fun dismissBatteryHint() {
        batteryHintDismissedState.value = true
        prefs.edit { putBoolean(KEY_BATTERY_HINT_DISMISSED, true) }
    }

    private companion object {
        const val FILE = "ui"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamicColor"
        const val KEY_SECURE_ALL = "secureAllScreens"
        const val KEY_NOTIFICATIONS_ASKED = "notificationsAsked"
        const val KEY_BATTERY_HINT_DISMISSED = "batteryHintDismissed"
    }
}
