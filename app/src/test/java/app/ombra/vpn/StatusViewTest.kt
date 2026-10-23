package app.ombra.vpn

import app.ombra.R
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.vpn.StatusView.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusViewTest {
    private val connected = VpnStatus.Connected(
        exit = OneExit(id = "e1", countryCode = "de", cityCode = "ber", cityName = "Berlin"),
        relay = OneRelay(id = "r1", countryCode = "nl", cityCode = "ams", cityName = "Amsterdam"),
        clientPublicKey = "client",
        exitPublicKey = "exit",
    )

    private fun status(vpn: VpnStatus) = Status(version = "v1", vpnStatus = vpn)

    private fun connecting(error: ConnectErrorCode? = null, reconnecting: Boolean = false) =
        status(VpnStatus.Connecting(connectError = error, reconnecting = reconnecting))

    private fun view(status: Status?, wanted: Boolean = true, hideDetails: Boolean = true, alwaysOn: Boolean = false) =
        StatusView.of(status, wanted, hideDetails, alwaysOn)

    @Test
    fun connectedHidesTheExitCityByDefault() {
        val view = view(status(connected))
        assertEquals(StatusView(Phase.Protected, canDisconnect = true), view)
        assertEquals(R.string.state_protected, view.titleRes)
        assertNull(view.problemRes)
    }

    @Test
    fun connectedShowsTheExitCityWhenAllowed() {
        assertEquals("Berlin", view(status(connected), hideDetails = false).exitCity)
    }

    @Test
    fun alwaysOnHidesDisconnect() {
        assertFalse(view(status(connected), alwaysOn = true).canDisconnect)
        assertFalse(view(connecting(), alwaysOn = true).canDisconnect)
    }

    @Test
    fun connectingWithoutErrorOrWithTransientError() {
        assertEquals(StatusView(Phase.Connecting, canDisconnect = true), view(connecting()))
        assertEquals(StatusView(Phase.Connecting, canDisconnect = true), view(connecting(ConnectErrorCode.ApiUnreachable)))
        assertEquals(StatusView(Phase.Reconnecting, canDisconnect = true), view(connecting(ConnectErrorCode.Other, reconnecting = true)))
    }

    @Test
    fun noInternetIsShownWhileConnecting() {
        val view = view(connecting(ConnectErrorCode.NoInternet, reconnecting = true))
        assertEquals(Phase.Reconnecting, view.phase)
        assertEquals(R.string.state_reconnecting, view.titleRes)
        assertEquals(R.string.notification_waiting_for_network, view.problemRes)
    }

    @Test
    fun errorsThatNeedTheUserFailTheConnect() {
        val texts = mapOf(
            ConnectErrorCode.UdpHandshakeFailed to R.string.notification_problem_udp_handshake,
            ConnectErrorCode.AccountExpired to R.string.notification_problem_account_expired,
            ConnectErrorCode.NoSlotsLeft to R.string.notification_problem_no_slots,
            ConnectErrorCode.InvalidAccountId to R.string.notification_problem_login,
            ConnectErrorCode.NotLoggedIn to R.string.notification_problem_login,
            ConnectErrorCode.NoLongerSupported to R.string.notification_problem_update,
        )
        texts.forEach { (error, text) ->
            val view = view(connecting(error, reconnecting = true))
            assertEquals(Phase.Failed, view.phase, error.wire)
            assertEquals(R.string.state_cannot_connect, view.titleRes)
            assertEquals(error, view.problem)
            assertEquals(text, view.problemRes, error.wire)
            assertTrue(view.canDisconnect)
        }
    }

    @Test
    fun disconnectedIsConnectingWhileWanted() {
        assertEquals(StatusView(Phase.Connecting, canDisconnect = true), view(status(VpnStatus.Disconnected)))
        assertEquals(StatusView(Phase.Connecting, canDisconnect = true), view(null))
    }

    @Test
    fun disconnectedAndNotWantedIsNotProtected() {
        val view = view(status(VpnStatus.Disconnected), wanted = false)
        assertEquals(StatusView(Phase.NotProtected), view)
        assertFalse(view.tunnelInUse)
        assertEquals(R.string.state_not_protected, view.titleRes)
        assertEquals(StatusView(Phase.NotProtected), view(null, wanted = false))
    }

    @Test
    fun statusChangesTheViewDoesNotShowKeepItEqual() {
        // The notification and the tile re-render only when the view changes.
        val before = view(status(connected))
        val after = view(status(connected).copy(version = "v2", autoConnect = true))
        assertEquals(before, after)
    }
}
