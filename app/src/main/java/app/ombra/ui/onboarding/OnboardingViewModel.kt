package app.ombra.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.core.model.Status
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.createAccount
import app.ombra.data.revealAccountNumber
import app.ombra.data.serviceStatus
import app.ombra.ipc.Request
import kotlinx.coroutines.launch

sealed interface OnboardingStep {
    data object Welcome : OnboardingStep

    data object Login : OnboardingStep

    data object Create : OnboardingStep

    /** The new number with the "write it down" step. */
    data object NewAccount : OnboardingStep
}

/** Free trial availability, asked from the Obscura API when the user opens "Create an account". */
enum class Trial { Checking, Available, Unavailable, Unknown }

/**
 * Login and account creation. Nothing here calls the network on its own: the API is reached only
 * after the user taps Log in, opens Create, or taps Create account. The core keeps
 * [Status.inNewAccountFlow] on from account creation until [finish], so the "write it down" step
 * comes back after a process death.
 */
class OnboardingViewModel(private val core: CoreSource) : ViewModel() {
    val steps = mutableStateListOf<OnboardingStep>(OnboardingStep.Welcome)

    /** The typed account number. Memory only, like every number in this class. */
    val accountInput = TextFieldState()

    var busy by mutableStateOf(false)
        private set

    /** Code of the last failed request (see [app.ombra.ui.requestErrorText]). */
    var errorCode by mutableStateOf<String?>(null)
        private set

    var trial by mutableStateOf<Trial?>(null)
        private set

    /** Create screen switch: also link the new account to the referral code of the Ombra developer (DESIGN §10). */
    var useReferral by mutableStateOf(true)

    /** The new account number; null after a process death until the user reveals it again. */
    var newNumber by mutableStateOf<String?>(null)
        private set

    /** Account info of the new account: true once a trial or a payment is active, null before it is known. */
    var accountActive by mutableStateOf<Boolean?>(null)
        private set

    /** The payment page is open in the browser; the account info is refreshed on return. */
    private var paymentOpen = false

    init {
        viewModelScope.launch { core.status.collect(::onStatus) }
    }

    fun open(step: OnboardingStep) {
        errorCode = null
        steps += step
        if (step == OnboardingStep.Create && trial == null) checkTrial()
    }

    fun back() {
        if (steps.size > 1 && !busy) {
            steps.removeAt(steps.lastIndex)
            errorCode = null
        }
    }

    /** Sends nothing unless the number passes the local check. */
    fun login() {
        val digits = AccountNumberInput.sanitize(accountInput.text)
        if (busy || AccountNumberInput.check(digits) != AccountNumberInput.Check.Valid) return
        launchRequest { core.request(Request.Login(digits)) }
    }

    fun create() {
        if (busy) return
        launchRequest {
            newNumber = core.createAccount(useReferral)
            show(OnboardingStep.NewAccount)
        }
    }

    fun reveal() = launchRequest { newNumber = core.revealAccountNumber() }

    /** Call when the payment page opens; the account info is refreshed when the user comes back. */
    fun onPaymentOpened() {
        paymentOpen = true
    }

    /** Call when the screen resumes: after the payment page, the account info may have changed. */
    fun onResumed() {
        if (!paymentOpen) return
        paymentOpen = false
        viewModelScope.launch {
            try {
                core.request(Request.RefreshAccount)
            } catch (e: CoreRequestException) {
                // The periodic refresh in :vpn catches up later.
            }
        }
    }

    /** The number is saved: the main app opens with the next status. */
    fun finish() = launchRequest { core.request(Request.EndNewAccountFlow) }

    /** Leaves the new account (it stays valid) to log in with another number. */
    fun useOtherAccount() = launchRequest {
        core.request(Request.Logout)
        core.request(Request.EndNewAccountFlow)
    }

    private fun onStatus(status: Status?) {
        if (status == null) return
        accountActive = status.account?.accountInfo?.active
        when {
            // While creating, create() moves on by itself once it has the number.
            status.isLoggedIn && status.inNewAccountFlow -> if (!busy) show(OnboardingStep.NewAccount)
            // Logged in and done: the main app takes over; start fresh for the next logout.
            status.isLoggedIn -> reset()
            steps.last() == OnboardingStep.NewAccount -> reset()
        }
    }

    private fun checkTrial() {
        trial = Trial.Checking
        viewModelScope.launch {
            trial = try {
                if (core.serviceStatus().freeTrial.available) Trial.Available else Trial.Unavailable
            } catch (e: CoreRequestException) {
                Trial.Unknown
            }
        }
    }

    private fun show(step: OnboardingStep) {
        if (steps.size == 1 && steps[0] == step) return
        steps.clear()
        steps += step
    }

    private fun reset() {
        show(OnboardingStep.Welcome)
        accountInput.clearText()
        newNumber = null
        errorCode = null
        trial = null
        paymentOpen = false
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            errorCode = null
            try {
                block()
            } catch (e: CoreRequestException) {
                errorCode = e.code
            } finally {
                busy = false
            }
        }
    }
}
