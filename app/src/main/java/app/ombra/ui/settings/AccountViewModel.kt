package app.ombra.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.revealAccountNumber
import app.ombra.ipc.IpcError
import app.ombra.ipc.Request
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Account page. The account number stays in this ViewModel only while it shows: [hideNumber]
 * runs when the screen stops, and the ViewModel goes when the page closes.
 */
class AccountViewModel(
    private val core: CoreSource,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    val account: StateFlow<AccountSummary> = core.status
        .map { accountSummary(it?.account, nowSeconds()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), accountSummary(core.status.value?.account, nowSeconds()))

    /** The full account number while the user wants to see it. */
    var number by mutableStateOf<String?>(null)
        private set

    /** Code of the last failed request (see [app.ombra.ui.requestErrorText]). */
    var errorCode by mutableStateOf<String?>(null)
        private set

    /** Reveal, payment link, log out or delete is running. */
    var busy by mutableStateOf(false)
        private set

    var refreshing by mutableStateOf(false)
        private set

    /** The payment page is open in the browser; the account info is refreshed on return. */
    private var paymentOpen = false

    /**
     * The user confirmed the screen lock in this visit (DESIGN §10): one prompt before the first
     * reveal, copy, payment link or deletion. The visit ends when the screen stops ([endVisit]).
     */
    var unlocked = false
        private set

    fun onUnlocked() {
        unlocked = true
    }

    fun reveal() = launchRequest { number = requireNumber() }

    fun hideNumber() {
        number = null
    }

    /** The screen stopped (the app went to the background, or the payment page opened). */
    fun endVisit() {
        hideNumber()
        unlocked = false
    }

    /**
     * Gets the number for the payment link, then [open]s it. The number goes into the URL fragment:
     * the browser does not send it, but its history keeps it (the screen says so).
     */
    fun addTime(open: (String) -> Unit) = launchRequest {
        val account = requireNumber()
        paymentOpen = true
        open(account)
    }

    /** Call when the screen resumes: after the payment page, the account info may have changed. */
    fun onResumed() {
        if (!paymentOpen) return
        paymentOpen = false
        refresh()
    }

    /** Asks the Obscura API for the account info; only on user action or after the payment page. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                core.request(Request.RefreshAccount)
            } catch (e: CoreRequestException) {
                errorCode = e.code
            } finally {
                refreshing = false
            }
        }
    }

    /** The core disconnects first; onboarding follows with the next status. */
    fun logout() = launchRequest { core.request(Request.Logout) }

    /** Deletes the account on the server, then logs out. */
    fun deleteAccount() = launchRequest { core.request(Request.DeleteAccount) }

    fun errorShown() {
        errorCode = null
    }

    private suspend fun requireNumber(): String =
        number ?: core.revealAccountNumber() ?: throw CoreRequestException(IpcError.BAD_RESPONSE)

    private fun launchRequest(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        errorCode = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CoreRequestException) {
                errorCode = e.code
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
