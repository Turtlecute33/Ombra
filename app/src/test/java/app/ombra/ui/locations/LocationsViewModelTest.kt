package app.ombra.ui.locations

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.OneExit
import app.ombra.data.SettingsEditor
import app.ombra.ipc.IpcError
import app.ombra.ipc.Request
import app.ombra.ui.FakeCore
import app.ombra.ui.connect.connected
import app.ombra.ui.connect.status
import app.ombra.ui.nav.LocationSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LocationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeCore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(side: LocationSide = LocationSide.Exit): LocationsViewModel {
        val vm = LocationsViewModel(core, SettingsEditor(core, backgroundScope), side, nowSeconds = { 42 }, compute = dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun noListIsNotAnEmptyList() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(ListStatus.Missing, vm.state.value.status)
        assertEquals(listOf<LocationRow>(FastestRow(selected = true)), vm.state.value.rows, "Fastest works without a list")

        core.exits.value = CachedValue("v1", 0, ExitList())
        runCurrent()
        assertEquals(ListStatus.Empty, vm.state.value.status)
    }

    @Test
    fun theSidesShowTheirOwnList() = runTest(dispatcher) {
        core.exits.value = CachedValue("v1", 0, ExitList(listOf(exit("se-sto-001"))))
        val vm = viewModel()
        runCurrent()
        assertEquals(ListStatus.Ready, vm.state.value.status)

        vm.selectSide(LocationSide.Entry)
        runCurrent()
        assertEquals(LocationSide.Entry, vm.state.value.side)
        assertEquals(ListStatus.Missing, vm.state.value.status, "a core without the relay list")
    }

    @Test
    fun aSearchWithoutResultsSaysSo() = runTest(dispatcher) {
        core.exits.value = CachedValue("v1", 0, ExitList(listOf(exit("se-sto-001"))))
        val vm = viewModel()
        vm.updateQuery("tokyo")
        runCurrent()
        assertEquals(ListStatus.NoMatch, vm.state.value.status)
        assertEquals("tokyo", vm.query)
    }

    @Test
    fun aChoiceIsStoredThenTheScreenCloses() = runTest(dispatcher) {
        core.exits.value = CachedValue("v1", 0, ExitList(listOf(exit("se-sto-001"))))
        val vm = viewModel()
        var done = false
        vm.choose(Choice.City("se", "sto")) { done = true }
        advanceUntilIdle()
        assertEquals(ExitSelector.City("se", "sto"), core.settings.value?.exit)
        assertTrue(core.requests.single() is Request.UpdateSettings)
        assertTrue(done)
        val city = vm.state.value.rows.filterIsInstance<CityRow>().single()
        assertTrue(city.selected)
    }

    @Test
    fun aFailedChoiceStaysOnTheScreenWithItsError() = runTest(dispatcher) {
        core.settings.value = null
        val vm = viewModel()
        var done = false
        vm.choose(Choice.Fastest) { done = true }
        // The editor waits up to 10 s for the first settings push, in its background scope, which
        // advanceUntilIdle does not run.
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(IpcError.DISCONNECTED, vm.errorCode)
        assertEquals(false, done)
        vm.errorShown()
        assertNull(vm.errorCode)
    }

    @Test
    fun pinsAreStampedWithTheTime() = runTest(dispatcher) {
        val vm = viewModel()
        vm.togglePin(Choice.City("se", "sto"))
        advanceUntilIdle()
        assertEquals(42L, core.settings.value?.pinnedExits?.single()?.pinnedAt)
    }

    @Test
    fun theConnectedServerIsMarked() = runTest(dispatcher) {
        core.exits.value = CachedValue("v1", 0, ExitList(listOf(exit("se-sto-001"))))
        core.status.value = status(connected())
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.state.value.rows.filterIsInstance<CountryRow>().single().inUse)
    }

    @Test
    fun loadingTheListsIsOneRequestAtATime() = runTest(dispatcher) {
        val vm = viewModel()
        vm.refresh()
        vm.refresh()
        assertTrue(vm.refreshing)
        advanceUntilIdle()
        assertEquals(listOf<Request>(Request.RefreshLists), core.requests)
        assertEquals(false, vm.refreshing)
    }

    // The fixture's connected exit is se-sto-001 in Stockholm.
    private fun exit(id: String) = OneExit(id = id, countryCode = "se", cityCode = "sto", cityName = "Stockholm", providerName = "Mullvad VPN")
}
