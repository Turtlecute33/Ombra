package app.ombra.ui.connect

import androidx.compose.runtime.Immutable
import app.ombra.core.model.TrafficStats
import java.util.Locale

/** Live figures of the Connect screen. Rates are null until two samples of one session exist. */
@Immutable
data class LiveStats(
    val downBytesPerSecond: Long? = null,
    val upBytesPerSecond: Long? = null,
    val latencyMs: Int? = null,
    val sessionMs: Long = 0,
)

/**
 * Turns 1 Hz traffic samples into rates. Rust counts bytes per session ([TrafficStats.connId]) and
 * its connected time pauses while reconnecting, so the rate uses that time, not the poll interval.
 */
class TrafficMeter {
    private var last: TrafficStats? = null

    fun next(sample: TrafficStats): LiveStats {
        val previous = last
        last = sample
        val elapsedMs = previous?.let { sample.connectedMs - it.connectedMs } ?: 0
        val comparable = previous != null &&
            previous.connId == sample.connId &&
            elapsedMs > 0 &&
            sample.rxBytes >= previous.rxBytes &&
            sample.txBytes >= previous.txBytes
        return LiveStats(
            downBytesPerSecond = if (comparable) (sample.rxBytes - previous.rxBytes) * 1000 / elapsedMs else null,
            upBytesPerSecond = if (comparable) (sample.txBytes - previous.txBytes) * 1000 / elapsedMs else null,
            latencyMs = sample.latestLatencyMs.takeIf { it > 0 },
            sessionMs = sample.connectedMs,
        )
    }
}

enum class RateUnit { Bits, Kilobits, Megabits, Gigabits }

/** A rate as a number with three significant digits and its unit, for example 84.2 Mbit/s. */
@Immutable
data class FormattedRate(val value: String, val unit: RateUnit)

fun formatRate(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): FormattedRate {
    val bits = bytesPerSecond.coerceAtLeast(0) * 8.0
    val unit = when {
        bits < 1e3 -> return FormattedRate(bits.toLong().toString(), RateUnit.Bits)
        bits < 999.5e3 -> RateUnit.Kilobits
        bits < 999.5e6 -> RateUnit.Megabits
        else -> RateUnit.Gigabits
    }
    val scaled = bits / when (unit) {
        RateUnit.Kilobits -> 1e3
        RateUnit.Megabits -> 1e6
        else -> 1e9
    }
    val decimals = when {
        scaled < 9.995 -> 2
        scaled < 99.95 -> 1
        else -> 0
    }
    return FormattedRate(String.format(locale, "%.${decimals}f", scaled), unit)
}

/** Session length as m:ss or h:mm:ss. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}
