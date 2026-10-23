package app.ombra.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import app.ombra.ui.settings.Licence

/**
 * The back stack of the main shell: Connect at the bottom, at most one other tab above it, then
 * pushed screens. Back from a tab returns to Connect; back on Connect is the system's (NavDisplay
 * handles back only while there is a screen to pop). Saved with the activity state; it starts
 * fresh each time the main shell opens (after a login).
 */
class Navigator(routes: List<Route> = listOf(Route.Connect)) {
    private val stack = mutableStateListOf<Route>().apply { addAll(routes) }

    val backStack: List<Route> get() = stack

    val current: Route get() = stack.last()

    /** Opens a tab; the screens above the current tab are dropped. */
    fun select(route: Route) {
        stack.removeRange(1, stack.size)
        if (route != Route.Connect) stack += route
    }

    /** Opens [route] above the current screen. */
    fun push(route: Route) {
        if (current != route) stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        val Saver: Saver<Navigator, List<String>> = Saver(
            save = { navigator -> navigator.stack.map(::encode) },
            restore = { saved -> Navigator(saved.mapNotNull(::decode).ifEmpty { listOf(Route.Connect) }) },
        )

        private fun encode(route: Route): String = when (route) {
            Route.Connect -> "connect"
            is Route.Locations -> "locations:${route.side.name}"
            Route.Settings -> "settings"
            is Route.SettingsPage -> "page:${route.page.name}"
            is Route.LicenceText -> "licence:${route.licence.name}"
            Route.Console -> "console"
        }

        /** Null for a route that this version does not know; the stack then drops it. */
        private fun decode(text: String): Route? {
            val argument = text.substringAfter(':')
            return when {
                text == "connect" -> Route.Connect
                text.startsWith("locations:") ->
                    Route.Locations(LocationSide.entries.firstOrNull { it.name == argument } ?: LocationSide.Exit)
                text == "settings" -> Route.Settings
                text.startsWith("page:") -> Page.entries.firstOrNull { it.name == argument }?.let(Route::SettingsPage)
                text.startsWith("licence:") -> Licence.entries.firstOrNull { it.name == argument }?.let(Route::LicenceText)
                text == "console" -> Route.Console
                else -> null
            }
        }
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.Saver) { Navigator() }
