package app.ombra.ui.connect

import app.ombra.core.model.TrafficStats
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrafficTest {
    private fun sample(ms: Long, rx: Long, tx: Long, conn: String = "a", latency: Int = 20) =
        TrafficStats(connectedMs = ms, connId = conn, txBytes = tx, rxBytes = rx, latestLatencyMs = latency)

    @Test
    fun ratesComeFromDeltasOfOneSession() {
        val meter = TrafficMeter()
        val first = meter.next(sample(1_000, rx = 0, tx = 0))
        assertNull(first.downBytesPerSecond)
        assertEquals(20, first.latencyMs)

        val second = meter.next(sample(3_000, rx = 2_000_000, tx = 500_000))
        assertEquals(1_000_000, second.downBytesPerSecond)
        assertEquals(250_000, second.upBytesPerSecond)
        assertEquals(3_000, second.sessionMs)
    }

    @Test
    fun aNewSessionOrAPausedClockGivesNoRate() {
        val meter = TrafficMeter()
        meter.next(sample(5_000, rx = 9_000, tx = 9_000))
        assertNull(meter.next(sample(1_000, rx = 100, tx = 100, conn = "b")).downBytesPerSecond)
        // Reconnecting: Rust's connected time stands still.
        assertNull(meter.next(sample(1_000, rx = 200, tx = 200, conn = "b")).downBytesPerSecond)
        assertEquals(100, meter.next(sample(2_000, rx = 300, tx = 300, conn = "b")).downBytesPerSecond)
    }

    @Test
    fun zeroLatencyMeansUnknown() {
        assertNull(TrafficMeter().next(sample(1_000, 0, 0, latency = 0)).latencyMs)
    }

    @Test
    fun ratesUseBitsWithThreeSignificantDigits() {
        val us = Locale.US
        assertEquals(FormattedRate("0", RateUnit.Bits), formatRate(0, us))
        assertEquals(FormattedRate("800", RateUnit.Bits), formatRate(100, us))
        assertEquals(FormattedRate("1.00", RateUnit.Kilobits), formatRate(125, us))
        assertEquals(FormattedRate("84.2", RateUnit.Megabits), formatRate(10_525_000, us))
        assertEquals(FormattedRate("842", RateUnit.Megabits), formatRate(105_250_000, us))
        assertEquals(FormattedRate("1.00", RateUnit.Gigabits), formatRate(124_990_000, us))
        assertEquals(FormattedRate("8,42", RateUnit.Megabits), formatRate(1_052_500, Locale.GERMANY))
    }

    @Test
    fun durationsDropTheHourWhenZero() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:42", formatDuration(42_900))
        assertEquals("12:03", formatDuration(723_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
