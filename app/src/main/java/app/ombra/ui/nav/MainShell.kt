package app.ombra.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.ombra.BuildConfig
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.TunnelControl
import app.ombra.data.UiPrefs
import app.ombra.ui.components.Icons
import app.ombra.ui.components.LocalSnackbar
import app.ombra.ui.connect.ConnectScreen
import app.ombra.ui.locations.LocationsScreen
import app.ombra.ui.settings.AboutScreen
import app.ombra.ui.settings.AccountScreen
import app.ombra.ui.settings.AdvancedScreen
import app.ombra.ui.settings.AppearanceScreen
import app.ombra.ui.settings.ConnectionScreen
import app.ombra.ui.settings.ConsoleScreen
import app.ombra.ui.settings.DebugBundleScreen
import app.ombra.ui.settings.DnsScreen
import app.ombra.ui.settings.LicenceTextScreen
import app.ombra.ui.settings.LicencesScreen
import app.ombra.ui.settings.SettingsScreen
import app.ombra.ui.settings.SplitTunnelScreen

/**
 * The logged-in app: Connect, Locations and Settings behind a bottom bar. Tabs fade through;
 * pushed screens slide. A `viewModel()` inside a screen lives as long as its back stack entry.
 */
@Composable
fun MainShell(core: CoreSource, editor: SettingsEditor, tunnel: TunnelControl, prefs: UiPrefs) {
    val nav = rememberNavigator()
    val snackbar = remember { SnackbarHostState() }

    val tabs = fadeThrough<Scene<Route>>()
    val forward = sharedAxis<Scene<*>>(forward = true)
    val backward = sharedAxis<Scene<*>>(forward = false)
    val pushed = NavDisplay.transitionSpec(forward) +
        NavDisplay.popTransitionSpec(backward) +
        NavDisplay.predictivePopTransitionSpec { _ -> backward() }

    val currentTab = nav.current.tab
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        // The bottom bar takes the navigation bar inset; the screens start below the status bar, so
        // scrolled content never runs under it.
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                // Above the bottom bar; without it (pushed screens), above the system navigation bar.
                SnackbarHost(snackbar, if (currentTab == null) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
            },
            bottomBar = {
                AnimatedVisibility(currentTab != null, enter = expandVertically(), exit = shrinkVertically()) {
                    BottomBar(selected = currentTab, onSelect = { tab -> if (tab != currentTab) nav.select(tab.route) })
                }
            },
        ) { padding ->
            NavDisplay(
                backStack = nav.backStack,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                onBack = nav::back,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = tabs,
                popTransitionSpec = tabs,
                predictivePopTransitionSpec = { tabs() },
                entryProvider = entryProvider {
                    entry<Route.Connect> {
                        ConnectScreen(
                            core = core,
                            editor = editor,
                            prefs = prefs,
                            tunnel = tunnel,
                            onChooseEntry = { nav.select(Route.Locations(LocationSide.Entry)) },
                            onChooseExit = { nav.select(Route.Locations(LocationSide.Exit)) },
                            onOpenAccount = {
                                nav.select(Route.Settings)
                                nav.push(Route.SettingsPage(Page.Account))
                            },
                            contentPadding = padding,
                        )
                    }
                    entry<Route.Locations> { route ->
                        LocationsScreen(core = core, editor = editor, initialSide = route.side, contentPadding = padding, onChosen = nav::back)
                    }
                    entry<Route.Settings> {
                        SettingsScreen(core = core, editor = editor, prefs = prefs, contentPadding = padding, onOpen = { nav.push(Route.SettingsPage(it)) })
                    }
                    entry<Route.SettingsPage>(metadata = pushed) { route ->
                        val open = { page: Page -> nav.push(Route.SettingsPage(page)) }
                        when (route.page) {
                            Page.Account -> AccountScreen(core, onBack = nav::back)
                            Page.Connection -> ConnectionScreen(core, editor, onBack = nav::back)
                            Page.Dns -> DnsScreen(core, editor, onBack = nav::back)
                            Page.SplitTunnel -> SplitTunnelScreen(core, editor, onBack = nav::back)
                            Page.Advanced -> AdvancedScreen(
                                core,
                                editor,
                                onBack = nav::back,
                                onDebugBundle = { open(Page.DebugBundle) },
                                onConsole = { nav.push(Route.Console) },
                            )
                            Page.DebugBundle -> DebugBundleScreen(core, onBack = nav::back)
                            Page.Appearance -> AppearanceScreen(core, editor, prefs, onBack = nav::back)
                            Page.About -> AboutScreen(onBack = nav::back, onLicences = { open(Page.Licences) })
                            Page.Licences -> LicencesScreen(onBack = nav::back, onOpen = { nav.push(Route.LicenceText(it)) })
                        }
                    }
                    entry<Route.LicenceText>(metadata = pushed) { route -> LicenceTextScreen(route.licence, onBack = nav::back) }
                    // Release builds have no console; R8 then drops the screen.
                    if (BuildConfig.DEBUG) {
                        entry<Route.Console>(metadata = pushed) { ConsoleScreen(core = core, onBack = nav::back) }
                    }
                },
            )
        }
    }
}

private val Tab.route: Route
    get() = when (this) {
        Tab.Connect -> Route.Connect
        Tab.Locations -> Route.Locations()
        Tab.Settings -> Route.Settings
    }

@Composable
private fun BottomBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    ShortNavigationBar {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            ShortNavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(isSelected), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

private fun Tab.icon(selected: Boolean): ImageVector = when (this) {
    Tab.Connect -> if (selected) Icons.ShieldFilled else Icons.Shield
    Tab.Locations -> if (selected) Icons.PlaceFilled else Icons.Place
    Tab.Settings -> if (selected) Icons.SettingsFilled else Icons.Settings
}

private val Tab.label: Int
    get() = when (this) {
        Tab.Connect -> R.string.nav_connect
        Tab.Locations -> R.string.nav_locations
        Tab.Settings -> R.string.nav_settings
    }
