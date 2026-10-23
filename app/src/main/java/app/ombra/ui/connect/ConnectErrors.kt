package app.ombra.ui.connect

import app.ombra.core.model.ConnectErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/** Errors that retries cannot fix: they show at once. Rust already waits ~5 s for the UDP handshake. */
val ConnectErrorCode.isFatal: Boolean
    get() = when (this) {
        ConnectErrorCode.AccountExpired,
        ConnectErrorCode.InvalidAccountId,
        ConnectErrorCode.NoSlotsLeft,
        ConnectErrorCode.NoLongerSupported,
        ConnectErrorCode.NotLoggedIn,
        ConnectErrorCode.UdpHandshakeFailed,
        -> true
        else -> false
    }

/**
 * Rust retries forever while connecting and reports the last failure; most failures heal within
 * a few seconds (a relay race lost, a network handover). The gate shows a transient error only
 * after errors have lasted [delayMs] without a break, fatal ones at once.
 */
class ConnectErrorGate(private val delayMs: Long = DEFAULT_DELAY_MS) {
    private var firstSeenAt: Long? = null

    /** Milliseconds until [error] may show: 0 = now, null = show nothing. */
    fun waitFor(error: ConnectErrorCode?, now: Long): Long? {
        if (error == null) {
            firstSeenAt = null
            return null
        }
        val since = firstSeenAt ?: now.also { firstSeenAt = it }
        return if (error.isFatal) 0 else (since + delayMs - now).coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 5_000L
    }
}

/**
 * The errors of this flow as the user should see them, through a [ConnectErrorGate] per
 * collection. [now] is a monotonic clock in milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<ConnectErrorCode?>.gated(
    now: () -> Long,
    delayMs: Long = ConnectErrorGate.DEFAULT_DELAY_MS,
): Flow<ConnectErrorCode?> = flow {
    val gate = ConnectErrorGate(delayMs)
    emitAll(
        distinctUntilChanged().transformLatest { error ->
            val wait = gate.waitFor(error, now())
            when {
                wait == null -> emit(null)
                wait > 0 -> {
                    emit(null)
                    delay(wait)
                    emit(error)
                }
                else -> emit(error)
            }
        },
    )
}

/** A one-tap fix offered next to an error. */
enum class ErrorFix {
    SwitchToStealth,
    UseTcpTls,
    UseFastestRelay,
    OpenAccount,
}

fun fixFor(error: ConnectErrorCode, state: ConnectUiState): ErrorFix? = when (error) {
    ConnectErrorCode.UdpHandshakeFailed -> ErrorFix.SwitchToStealth
    ConnectErrorCode.NoRelay -> when {
        state.relayChosen -> ErrorFix.UseFastestRelay
        state.protocol != Protocol.TcpTls -> ErrorFix.UseTcpTls
        else -> null
    }
    ConnectErrorCode.AccountExpired,
    ConnectErrorCode.InvalidAccountId,
    ConnectErrorCode.NoSlotsLeft,
    -> ErrorFix.OpenAccount
    else -> null
}
