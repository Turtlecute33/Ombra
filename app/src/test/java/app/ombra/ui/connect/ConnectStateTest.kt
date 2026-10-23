package app.ombra.ui.connect

import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TransportKind
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.Settings
import app.ombra.ui.components.Hop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectStateTest {
    private fun state(
        vpn: VpnStatus?,
        settings: Settings = Settings(),
        pending: PendingAction? = null,
        error: ConnectErrorCode? = null,
        active: Boolean? = true,
    ) = connectUiState(vpn?.let { status(it, active) }, settings, listOf(stockholm), listOf(amsterdam), pending, error)

    @Test
    fun phaseFollowsTheCoreStatus() {
        assertEquals(ConnectPhase.Loading, state(null).phase)
        assertEquals(ConnectPhase.Disconnected, state(VpnStatus.Disconnected).phase)
        assertEquals(ConnectPhase.Connecting, state(connecting()).phase)
        assertEquals(ConnectPhase.Reconnecting, state(connecting(reconnecting = true)).phase)
        assertEquals(ConnectPhase.Connected, state(connected()).phase)
    }

    @Test
    fun pendingActionsShowAtOnce() {
        assertEquals(ConnectPhase.Connecting, state(VpnStatus.Disconnected, pending = PendingAction.Connect).phase)
        assertEquals(ConnectPhase.Disconnecting, state(connected(), pending = PendingAction.Disconnect).phase)
        assertEquals(ConnectPhase.Disconnecting, state(connecting(), pending = PendingAction.Disconnect).phase)
        // A finished disconnect wins over a stale pending action.
        assertEquals(ConnectPhase.Disconnected, state(VpnStatus.Disconnected, pending = PendingAction.Disconnect).phase)
    }

    @Test
    fun pendingActionIsDoneWhenTheCoreCatchesUp() {
        assertFalse(PendingAction.Connect.isDone(VpnStatus.Disconnected))
        assertTrue(PendingAction.Connect.isDone(connecting()))
        assertTrue(PendingAction.Connect.isDone(connected()))
        assertFalse(PendingAction.Disconnect.isDone(connected()))
        assertTrue(PendingAction.Disconnect.isDone(VpnStatus.Disconnected))
    }

    @Test
    fun connectedRouteShowsTheRealHops() {
        val s = state(connected(TransportKind.WireguardUdp))
        assertEquals(Hop("nl", "Amsterdam"), s.entry)
        assertEquals(Hop("se", "Stockholm", "Mullvad"), s.exit)
        assertEquals(Protocol.Speed, s.protocol)
    }

    @Test
    fun idleRouteShowsTheChoiceFromSettings() {
        val automatic = state(VpnStatus.Disconnected)
        assertTrue(automatic.entry.isAutomatic)
        assertTrue(automatic.exit.isAutomatic)
        assertEquals(Protocol.Stealth, automatic.protocol)

        val chosen = state(
            VpnStatus.Disconnected,
            Settings(
                exit = ExitSelector.City("se", "sto"),
                relay = RelaySelector.Country("de"),
                mode = TunnelMode.TcpTls,
            ),
        )
        assertEquals(Hop("se", "Stockholm"), chosen.exit)
        assertEquals(Hop("de"), chosen.entry)
        assertEquals(Protocol.TcpTls, chosen.protocol)
        assertTrue(chosen.relayChosen)
    }

    @Test
    fun serversAreFoundByIdAndUnknownOnesKeepTheirId() {
        val known = state(VpnStatus.Disconnected, Settings(exit = ExitSelector.Exit("se-sto-001")))
        assertEquals(Hop("se", "Stockholm", "se-sto-001"), known.exit)
        val unknown = state(VpnStatus.Disconnected, Settings(relay = RelaySelector.Relay("gone")))
        assertEquals(Hop(detail = "gone"), unknown.entry)
        assertFalse(unknown.entry.isAutomatic)
    }

    @Test
    fun connectingRouteUsesTheTunnelArgsOfTheAttempt() {
        val args = TunnelArgs(exit = ExitSelector.Country("ch"), mode = TunnelMode.WireguardUdp, udpMtu = 1280)
        val s = state(connecting(args = args), Settings(exit = ExitSelector.Any))
        assertEquals(Hop("ch"), s.exit)
        assertEquals(Protocol.Speed, s.protocol)
    }

    @Test
    fun accountAndErrorPassThrough() {
        assertTrue(state(VpnStatus.Disconnected, active = false).accountInactive)
        assertFalse(state(VpnStatus.Disconnected, active = null).accountInactive)
        assertEquals(ConnectErrorCode.NoRelay, state(connecting(ConnectErrorCode.NoRelay), error = ConnectErrorCode.NoRelay).error)
        assertNull(state(connecting(ConnectErrorCode.NoRelay)).error)
    }

    @Test
    fun fixesDependOnTheContext() {
        val idle = state(VpnStatus.Disconnected)
        assertEquals(ErrorFix.SwitchToStealth, fixFor(ConnectErrorCode.UdpHandshakeFailed, idle))
        assertEquals(ErrorFix.UseTcpTls, fixFor(ConnectErrorCode.NoRelay, idle))
        val pinned = state(VpnStatus.Disconnected, Settings(relay = RelaySelector.Relay("relay-nl-1")))
        assertEquals(ErrorFix.UseFastestRelay, fixFor(ConnectErrorCode.NoRelay, pinned))
        val tcp = state(VpnStatus.Disconnected, Settings(mode = TunnelMode.TcpTls))
        assertNull(fixFor(ConnectErrorCode.NoRelay, tcp))
        assertEquals(ErrorFix.OpenAccount, fixFor(ConnectErrorCode.AccountExpired, idle))
        assertNull(fixFor(ConnectErrorCode.Other, idle))
    }

    @Test
    fun alwaysOnBlocksOnlyDisconnectAndCancel() {
        assertTrue(state(connected()).copy(alwaysOn = true).disconnectBlocked)
        assertTrue(state(connecting()).copy(alwaysOn = true).disconnectBlocked)
        assertTrue(state(connecting(reconnecting = true)).copy(alwaysOn = true).disconnectBlocked)
        assertFalse(state(VpnStatus.Disconnected).copy(alwaysOn = true).disconnectBlocked, "connect stays possible")
        assertFalse(state(null).copy(alwaysOn = true).disconnectBlocked)
        assertFalse(state(connected()).disconnectBlocked)
    }

    @Test
    fun detailsLinkOnlyToAWebPageOfTheExitOperator() {
        val vpn = connected()
        val details = connectionDetails(vpn.copy(exit = vpn.exit.copy(providerUrl = "https://mullvad.net/servers")))
        assertEquals("https://mullvad.net/servers", details.providerUrl)
        assertNull(connectionDetails(vpn.copy(exit = vpn.exit.copy(providerUrl = "javascript:alert(1)"))).providerUrl)
        assertNull(connectionDetails(vpn.copy(exit = vpn.exit.copy(providerUrl = "http://example.com"))).providerUrl)
        assertNull(connectionDetails(vpn).relayAddress, "no address in the fixture")
    }
}
