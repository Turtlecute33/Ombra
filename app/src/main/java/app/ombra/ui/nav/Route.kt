package app.ombra.ui.nav

import app.ombra.ui.settings.Licence

/** Screens of the main shell. The back stack lives in [Navigator]. */
sealed interface Route {
    data object Connect : Route

    /** [side] is the list that opens first. */
    data class Locations(val side: LocationSide = LocationSide.Exit) : Route

    data object Settings : Route

    /** A page above the Settings tab. */
    data class SettingsPage(val page: Page) : Route

    /** The text of one licence, above the licence list. */
    data class LicenceText(val licence: Licence) : Route

    /** Debug builds only: raw commands to the Rust core. */
    data object Console : Route
}

enum class LocationSide { Exit, Entry }

/** The pages of Settings. */
enum class Page { Account, Connection, Dns, SplitTunnel, Advanced, DebugBundle, Appearance, About, Licences }

/** The bottom navigation destinations, in display order. */
enum class Tab { Connect, Locations, Settings }

val Route.tab: Tab?
    get() = when (this) {
        Route.Connect -> Tab.Connect
        is Route.Locations -> Tab.Locations
        Route.Settings -> Tab.Settings
        is Route.SettingsPage, is Route.LicenceText, Route.Console -> null
    }
