package app.ombra.vpn

import app.ombra.ipc.Settings
import app.ombra.ipc.SplitMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageMonitorTest {
    private val only = Settings(splitMode = SplitMode.Include, splitApps = setOf("org.chat"))

    @Test
    fun aNewInstallOfAListedAppRebuildsTheTun() {
        assertTrue(PackageMonitor.needsTunRebuild("org.chat", replacing = false, only))
        assertTrue(PackageMonitor.needsTunRebuild("org.chat", replacing = false, only.copy(splitMode = SplitMode.Exclude)))
    }

    @Test
    fun updatesOtherAppsAndSplitTunnelOffChangeNothing() {
        assertFalse(PackageMonitor.needsTunRebuild("org.chat", replacing = true, only))
        assertFalse(PackageMonitor.needsTunRebuild("org.mail", replacing = false, only))
        assertFalse(PackageMonitor.needsTunRebuild("org.chat", replacing = false, only.copy(splitMode = SplitMode.Off)))
        assertFalse(PackageMonitor.needsTunRebuild(null, replacing = false, only))
    }
}
