package app.ombra.ui.nav

import android.os.Process
import androidx.compose.runtime.saveable.SaverScope
import app.ombra.ui.settings.Licence
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigatorTest {
    @Test
    fun aTabSitsAboveConnectAndBackReturnsToConnect() {
        val nav = Navigator()
        nav.select(Route.Locations(LocationSide.Entry))
        nav.select(Route.Settings)
        assertEquals(listOf(Route.Connect, Route.Settings), nav.backStack)

        nav.back()
        assertEquals(listOf<Route>(Route.Connect), nav.backStack)
        nav.back()
        assertEquals(listOf<Route>(Route.Connect), nav.backStack, "back on Connect is the system's")
    }

    @Test
    fun pushedScreensStackOnceAndGoWithTheirTab() {
        val nav = Navigator()
        nav.select(Route.Settings)
        nav.push(Route.Console)
        nav.push(Route.Console)
        assertEquals(listOf(Route.Connect, Route.Settings, Route.Console), nav.backStack)

        nav.select(Route.Connect)
        assertEquals(listOf<Route>(Route.Connect), nav.backStack)
    }

    @Test
    fun theBackStackSurvivesSaving() {
        val nav = Navigator()
        nav.select(Route.Locations(LocationSide.Entry))
        val saved = with(Navigator.Saver) { SaverScope { true }.save(nav) }
        assertEquals(nav.backStack.toList(), Navigator.Saver.restore(checkNotNull(saved))?.backStack?.toList())
    }

    @Test
    fun settingsPagesSurviveSavingAndUnknownRoutesAreDropped() {
        val nav = Navigator()
        nav.select(Route.Settings)
        nav.push(Route.SettingsPage(Page.About))
        nav.push(Route.SettingsPage(Page.Licences))
        nav.push(Route.LicenceText(Licence.Gpl3))
        val saved = checkNotNull(with(Navigator.Saver) { SaverScope { true }.save(nav) })
        assertEquals(nav.backStack.toList(), Navigator.Saver.restore(saved)?.backStack?.toList())
        assertEquals(nav.backStack.map { it.tab }, listOf(Tab.Connect, Tab.Settings, null, null, null))

        val pid = Process.myPid().toString()
        val stale = listOf(pid, "connect", "settings", "page:Gone", "licence:Gone", "elsewhere")
        assertEquals(listOf(Route.Connect, Route.Settings), Navigator.Saver.restore(stale)?.backStack?.toList())

        // Saved by an earlier process: the app opens on home (UI-UX-10).
        val earlierProcess = listOf("$pid-earlier", "connect", "settings")
        assertEquals(listOf(Route.Connect), Navigator.Saver.restore(earlierProcess)?.backStack?.toList())
    }
}
