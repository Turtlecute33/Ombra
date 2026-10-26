package app.ombra.ui.connect

import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.RelaySelector
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.VpnStatus
import app.ombra.data.SettingsEditor
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.SystemState
import app.ombra.ui.FakeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeCore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ConnectViewModel {
        val vm = ConnectViewModel(core, SettingsEditor(core, backgroundScope), now = { testScheduler.currentTime })
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun connectShowsConnectingUntilTheCoreAnswers() = runTest(dispatcher) {
        core.status.value = status(VpnStatus.Disconnected)
        val vm = viewModel()
        runCurrent()
        assertEquals(ConnectPhase.Disconnected, vm.state.value.phase)

        vm.onConnectRequested()
        runCurrent()
        assertEquals(ConnectPhase.Connecting, vm.state.value.phase)

        core.status.value = status(connected())
        runCurrent()
        assertEquals(ConnectPhase.Connected, vm.state.value.phase)

        // The pending connect is gone: a later disconnect from elsewhere shows as disconnected.
        core.status.value = status(VpnStatus.Disconnected)
        runCurrent()
        assertEquals(ConnectPhase.Disconnected, vm.state.value.phase)
    }

    @Test
    fun aFailedStartOrSilenceEndsTheOptimisticPhase() = runTest(dispatcher) {
        core.status.value = status(VpnStatus.Disconnected)
        val vm = viewModel()
        vm.onConnectRequested()
        runCurrent()
        vm.onStartFailed()
        runCurrent()
        assertEquals(ConnectPhase.Disconnected, vm.state.value.phase)

        vm.onConnectRequested()
        advanceTimeBy(20_001)
        assertEquals(ConnectPhase.Disconnected, vm.state.value.phase)
    }

    @Test
    fun disconnectShowsDisconnectingUntilDone() = runTest(dispatcher) {
        core.status.value = status(connected())
        val vm = viewModel()
        vm.onDisconnectRequested()
        runCurrent()
        assertEquals(ConnectPhase.Disconnecting, vm.state.value.phase)

        core.status.value = status(VpnStatus.Disconnected)
        runCurrent()
        assertEquals(ConnectPhase.Disconnected, vm.state.value.phase)
    }

    @Test
    fun transientErrorsShowAfterFiveSeconds() = runTest(dispatcher) {
        core.status.value = status(connecting(ConnectErrorCode.ApiUnreachable))
        val vm = viewModel()
        advanceTimeBy(4_000)
        assertNull(vm.state.value.error)
        advanceTimeBy(1_001)
        assertEquals(ConnectErrorCode.ApiUnreachable, vm.state.value.error)

        core.status.value = status(connected())
        runCurrent()
        assertNull(vm.state.value.error)
    }

    @Test
    fun fatalErrorsShowAtOnce() = runTest(dispatcher) {
        core.status.value = status(connecting(ConnectErrorCode.UdpHandshakeFailed))
        val vm = viewModel()
        runCurrent()
        assertEquals(ConnectErrorCode.UdpHandshakeFailed, vm.state.value.error)
    }

    @Test
    fun fixesChangeTheSettings() = runTest(dispatcher) {
        core.settings.value = Settings(mode = TunnelMode.WireguardUdp, relay = RelaySelector.Relay("r"))
        val vm = viewModel()
        vm.apply(ErrorFix.SwitchToStealth)
        runCurrent()
        assertEquals(TunnelMode.Quic, core.settings.value?.mode)
        vm.apply(ErrorFix.UseFastestRelay)
        runCurrent()
        assertEquals(RelaySelector.Any, core.settings.value?.relay)
    }

    @Test
    fun statsPollOnlyWhileConnectedAndCollected() = runTest(dispatcher) {
        core.status.value = status(connected())
        core.traffic += TrafficStats(connectedMs = 1_000, connId = "a", rxBytes = 0, txBytes = 0, latestLatencyMs = 31)
        core.traffic += TrafficStats(connectedMs = 2_000, connId = "a", rxBytes = 1_250_000, txBytes = 125_000, latestLatencyMs = 30)
        val vm = ConnectViewModel(core, SettingsEditor(core, backgroundScope), now = { testScheduler.currentTime })
        runCurrent()
        assertTrue(core.requests.none { it == Request.TrafficStats }, "no polling without a collector")

        val collector = backgroundScope.launch { vm.stats.collect {} }
        runCurrent()
        assertEquals(31, vm.stats.value?.latencyMs)
        advanceTimeBy(1_001)
        assertEquals(1_250_000, vm.stats.value?.downBytesPerSecond)
        assertEquals(125_000, vm.stats.value?.upBytesPerSecond)

        collector.cancel()
        val polls = core.requests.count { it == Request.TrafficStats }
        advanceTimeBy(5_000)
        assertEquals(polls, core.requests.count { it == Request.TrafficStats })
    }

    @Test
    fun statsStopWhenTheTunnelGoesDown() = runTest(dispatcher) {
        core.status.value = status(connected())
        core.traffic += TrafficStats(connectedMs = 1_000, connId = "a")
        val vm = ConnectViewModel(core, SettingsEditor(core, backgroundScope), now = { testScheduler.currentTime })
        backgroundScope.launch { vm.stats.collect {} }
        runCurrent()
        assertEquals(1_000, vm.stats.value?.sessionMs)

        core.status.value = status(VpnStatus.Disconnected)
        runCurrent()
        assertNull(vm.stats.value)
        val polls = core.requests.count { it == Request.TrafficStats }
        advanceTimeBy(5_000)
        assertEquals(polls, core.requests.count { it == Request.TrafficStats })
    }
}
