package app.ombra.ui.settings

import app.ombra.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class SplitTunnelTest {
    private val apps = listOf(
        InstalledApp("org.example.bank", "Bank", isSystem = false),
        InstalledApp("com.android.camera", "Camera", isSystem = true),
        InstalledApp("org.example.cafe", "Café", isSystem = false),
        InstalledApp("org.example.maps", "Maps", isSystem = false),
    )

    @Test
    fun chosenAppsComeFirstAndGoneOnesStay() {
        val rows = appRows(apps, selected = setOf("org.example.maps", "org.example.gone"), query = "", showSystem = false)
        assertEquals(
            listOf(
                AppRow("org.example.maps", "Maps", true),
                AppRow("org.example.gone", null, true),
                AppRow("org.example.bank", "Bank", false),
                AppRow("org.example.cafe", "Café", false),
            ),
            rows,
        )
    }

    @Test
    fun systemAppsShowOnRequestOrWhenChosen() {
        assertEquals(false, appRows(apps, emptySet(), "", showSystem = false).any { it.packageName == "com.android.camera" })
        assertEquals(true, appRows(apps, emptySet(), "", showSystem = true).any { it.packageName == "com.android.camera" })
        assertEquals(true, appRows(apps, setOf("com.android.camera"), "", showSystem = false).first().selected)
    }

    @Test
    fun searchMatchesTheNameWithoutAccentsOrThePackage() {
        assertEquals(listOf("org.example.cafe"), appRows(apps, emptySet(), "CAFE", showSystem = false).map { it.packageName })
        assertEquals(listOf("org.example.maps"), appRows(apps, emptySet(), "example.ma", showSystem = false).map { it.packageName })
        assertEquals(listOf("org.example.gone"), appRows(apps, setOf("org.example.gone"), "gone", showSystem = false).map { it.packageName })
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theListLoadsOnceAndFollowsTheChoice() = runTest(dispatcher) {
        val selected = MutableStateFlow(emptySet<String>())
        var loads = 0
        val vm = SplitTunnelViewModel(selected, loadApps = { loads++; apps }, compute = dispatcher)
        backgroundScope.launch { vm.rows.collect {} }
        assertNull(vm.rows.value, "loading")
        advanceUntilIdle()
        assertEquals(3, vm.rows.value?.size)

        selected.value = setOf("org.example.cafe")
        vm.updateQuery("caf")
        runCurrent()
        assertEquals(listOf(AppRow("org.example.cafe", "Café", true)), vm.rows.value)
        assertEquals("caf", vm.query)

        vm.updateQuery("")
        vm.showSystemApps(true)
        runCurrent()
        assertEquals(4, vm.rows.value?.size)
        assertEquals(1, loads)
    }
}
