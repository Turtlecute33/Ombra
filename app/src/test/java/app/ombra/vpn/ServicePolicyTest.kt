package app.ombra.vpn

import android.net.VpnService
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.vpn.ServicePolicy.Start
import app.ombra.vpn.ServicePolicy.TileAction
import app.ombra.vpn.StatusView.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicePolicyTest {
    private fun status(vpn: VpnStatus) = Status(version = "v1", vpnStatus = vpn)

    @Test
    fun everyStartButDisconnectEntersTheForeground() {
        assertTrue(ServicePolicy.needsForeground(OmbraVpnService.ACTION_CONNECT))
        assertTrue(ServicePolicy.needsForeground(VpnService.SERVICE_INTERFACE))
        assertTrue(ServicePolicy.needsForeground(null))
        assertFalse(ServicePolicy.needsForeground(OmbraVpnService.ACTION_DISCONNECT))
    }

    @Test
    fun connectUsesTheSettingsArgs() {
        assertEquals(Start.Connect(useStoredArgs = false), ServicePolicy.start(OmbraVpnService.ACTION_CONNECT, inForeground = true, wanted = false))
    }

    @Test
    fun alwaysOnStartKeepsTheStoredArgs() {
        assertEquals(Start.Connect(useStoredArgs = true), ServicePolicy.start(VpnService.SERVICE_INTERFACE, inForeground = true, wanted = false))
    }

    @Test
    fun stickyRestartReconnectsOnlyWhenWanted() {
        // PackageReplacedReceiver's restore start uses this branch too (OmbraVpnService.restoreIntent has no action).
        assertEquals(Start.Connect(useStoredArgs = true), ServicePolicy.start(null, inForeground = true, wanted = true))
        assertEquals(Start.Stop, ServicePolicy.start(null, inForeground = true, wanted = false))
    }

    @Test
    fun connectWithoutForegroundNeedsConsent() {
        assertEquals(Start.NeedConsent, ServicePolicy.start(OmbraVpnService.ACTION_CONNECT, inForeground = false, wanted = false))
        assertEquals(Start.NeedConsent, ServicePolicy.start(VpnService.SERVICE_INTERFACE, inForeground = false, wanted = true))
        assertEquals(Start.NeedConsent, ServicePolicy.start(null, inForeground = false, wanted = true))
    }

    @Test
    fun disconnectNeedsNoForeground() {
        assertEquals(Start.Disconnect, ServicePolicy.start(OmbraVpnService.ACTION_DISCONNECT, inForeground = false, wanted = true))
    }

    @Test
    fun unknownActionsAreIgnored() {
        assertEquals(Start.Ignore, ServicePolicy.start("org.example.OTHER", inForeground = true, wanted = true))
    }

    @Test
    fun stopsOnlyWhenDisconnectedAndNotWanted() {
        assertTrue(ServicePolicy.shouldStop(status(VpnStatus.Disconnected), wanted = false))
        assertFalse(ServicePolicy.shouldStop(status(VpnStatus.Disconnected), wanted = true))
        assertFalse(ServicePolicy.shouldStop(status(VpnStatus.Connecting()), wanted = false))
        // Before the first status Rust's state is not known yet.
        assertFalse(ServicePolicy.shouldStop(null, wanted = false))
    }

    @Test
    fun tileConnectsDisconnectsOrOpensTheApp() {
        assertEquals(TileAction.Connect, ServicePolicy.tileAction(StatusView(Phase.NotProtected)))
        assertEquals(TileAction.Disconnect, ServicePolicy.tileAction(StatusView(Phase.Protected, canDisconnect = true)))
        assertEquals(TileAction.Disconnect, ServicePolicy.tileAction(StatusView(Phase.Failed, canDisconnect = true)))
        // Always-on: the system owns the tunnel.
        assertEquals(TileAction.OpenApp, ServicePolicy.tileAction(StatusView(Phase.Connecting, canDisconnect = false)))
    }
}
