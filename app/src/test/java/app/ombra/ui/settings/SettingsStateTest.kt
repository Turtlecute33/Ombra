package app.ombra.ui.settings

import app.ombra.core.model.VpnStatus
import app.ombra.ipc.Settings
import app.ombra.ipc.SystemState
import app.ombra.ui.connect.connected
import app.ombra.ui.connect.status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStateTest {
    @Test
    fun beforeTheFirstPushesTheDefaultsShow() {
        val state = settingsUiState(settings = null, system = null, status = null, nowSeconds = 0)
        assertEquals(Settings(), state.settings)
        assertEquals(SystemState(), state.system)
        assertEquals(AccountSummary.Unknown, state.account)
        assertNull(state.tunnel)
        assertNull(state.mullvadExit)
    }

    @Test
    fun blockListsWorkOnlyOnMullvadExits() {
        val vpn = connected()
        val mullvad = vpn.copy(exit = vpn.exit.copy(providerName = MULLVAD_PROVIDER_NAME))
        assertEquals(true, settingsUiState(null, null, status(mullvad), 0).mullvadExit)
        val other = vpn.copy(exit = vpn.exit.copy(providerName = "Example Hosting"))
        assertEquals(false, settingsUiState(null, null, status(other), 0).mullvadExit)
        assertNull(settingsUiState(null, null, status(VpnStatus.Disconnected), 0).mullvadExit, "unknown while disconnected")
    }

    @Test
    fun theLiveTunnelShowsItsKeys() {
        val tunnel = settingsUiState(null, null, status(connected()), 0).tunnel
        assertEquals("client", tunnel?.clientPublicKey)
        assertEquals("exit", tunnel?.exitPublicKey)
    }
}
