package app.ombra.ui.connect

import app.ombra.core.model.ConnectErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectErrorGateTest {
    @Test
    fun transientErrorsWaitAndFatalOnesShowAtOnce() {
        val gate = ConnectErrorGate(5_000)
        assertEquals(5_000, gate.waitFor(ConnectErrorCode.Other, now = 1_000))
        assertEquals(2_000, gate.waitFor(ConnectErrorCode.NoInternet, now = 4_000))
        assertEquals(0, gate.waitFor(ConnectErrorCode.NoRelay, now = 7_000))
        assertNull(gate.waitFor(null, now = 8_000))
        assertEquals(0, gate.waitFor(ConnectErrorCode.AccountExpired, now = 9_000))
    }

    @Test
    fun aBreakWithoutErrorRestartsTheWait() {
        val gate = ConnectErrorGate(5_000)
        gate.waitFor(ConnectErrorCode.Other, now = 0)
        gate.waitFor(null, now = 4_000)
        assertEquals(5_000, gate.waitFor(ConnectErrorCode.Other, now = 6_000))
    }

    @Test
    fun everyFatalCodeSkipsTheWait() {
        val fatal = ConnectErrorCode.entries.filter { it.isFatal }.toSet()
        val expected = setOf(
            ConnectErrorCode.AccountExpired,
            ConnectErrorCode.InvalidAccountId,
            ConnectErrorCode.NoSlotsLeft,
            ConnectErrorCode.NoLongerSupported,
            ConnectErrorCode.NotLoggedIn,
            ConnectErrorCode.UdpHandshakeFailed,
        )
        assertEquals(expected, fatal)
    }

    @Test
    fun gatedFlowShowsTransientErrorsAfterFiveSeconds() = runTest {
        val errors = MutableStateFlow<ConnectErrorCode?>(null)
        val seen = mutableListOf<ConnectErrorCode?>()
        backgroundScope.launch { errors.gated(now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()

        errors.value = ConnectErrorCode.Other
        advanceTimeBy(4_999)
        assertEquals(listOf<ConnectErrorCode?>(null, null), seen)

        advanceTimeBy(2)
        assertEquals(ConnectErrorCode.Other, seen.last())

        errors.value = null
        runCurrent()
        assertNull(seen.last())

        errors.value = ConnectErrorCode.NoSlotsLeft
        runCurrent()
        assertEquals(ConnectErrorCode.NoSlotsLeft, seen.last())
    }

    @Test
    fun anErrorThatHealsInTimeNeverShows() = runTest {
        val errors = MutableStateFlow<ConnectErrorCode?>(null)
        val seen = mutableListOf<ConnectErrorCode?>()
        backgroundScope.launch { errors.gated(now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()

        errors.value = ConnectErrorCode.NoInternet
        advanceTimeBy(3_000)
        errors.value = null
        advanceTimeBy(10_000)
        assertEquals(listOf<ConnectErrorCode?>(null, null, null), seen)
    }
}
