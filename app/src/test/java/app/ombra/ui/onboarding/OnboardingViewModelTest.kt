package app.ombra.ui.onboarding

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.ombra.core.AccountNumber
import app.ombra.core.model.FreeTrialStatus
import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.Status
import app.ombra.core.model.jsonString
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ui.FakeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

// The core is a fake: nothing here reaches Obscura.
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeCore()
    private val number = AccountNumber.generate()
    private val loggedOut = Status(version = "v")
    private val newAccount = loggedOut.copy(loggedIn = true, inNewAccountFlow = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(status: Status = loggedOut): OnboardingViewModel {
        core.status.value = status
        return OnboardingViewModel(core).also { runCurrent() }
    }

    private fun OnboardingViewModel.type(text: String) = accountInput.setTextAndPlaceCursorAtEnd(text)

    @Test
    fun loginSendsNothingUntilTheNumberPassesTheLocalCheck() = runTest(dispatcher) {
        val vm = viewModel()
        vm.type(number.dropLast(1))
        vm.login()
        val typo = number.dropLast(1) + (number.last() - '0' + 1) % 10
        vm.type(typo)
        vm.login()
        runCurrent()
        assertEquals(emptyList(), core.requests)

        vm.type(AccountNumber.format(number, " - "))
        vm.login()
        runCurrent()
        assertEquals(listOf<Request>(Request.Login(number)), core.requests)
    }

    @Test
    fun aFailedLoginShowsItsCode() = runTest(dispatcher) {
        core.failures[Request.Login(number)] = "apiUnreachable"
        val vm = viewModel()
        vm.type(number)
        vm.login()
        runCurrent()
        assertEquals("apiUnreachable", vm.errorCode)
    }

    @Test
    fun theTypedNumberGoesOnceLoggedIn() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(OnboardingStep.Login)
        vm.type(number)
        core.status.value = loggedOut.copy(loggedIn = true)
        runCurrent()
        assertEquals("", vm.accountInput.text.toString())
        assertEquals(listOf<OnboardingStep>(OnboardingStep.Welcome), vm.steps)
    }

    @Test
    fun theTrialIsCheckedOnceWhenCreateOpens() = runTest(dispatcher) {
        core.answers[Request.ServiceStatus] =
            IpcJson.encodeToString(ServiceStatus.serializer(), ServiceStatus(FreeTrialStatus(available = true)))
        val vm = viewModel()
        assertNull(vm.trial)
        vm.open(OnboardingStep.Create)
        runCurrent()
        assertEquals(Trial.Available, vm.trial)

        vm.back()
        vm.open(OnboardingStep.Create)
        runCurrent()
        assertEquals(1, core.requests.count { it == Request.ServiceStatus })
    }

    @Test
    fun aFailedTrialCheckStillAllowsCreating() = runTest(dispatcher) {
        core.failures[Request.ServiceStatus] = "apiUnreachable"
        val vm = viewModel()
        vm.open(OnboardingStep.Create)
        runCurrent()
        assertEquals(Trial.Unknown, vm.trial)
    }

    @Test
    fun createShowsTheNewNumber() = runTest(dispatcher) {
        core.answers[Request.CreateAccount(referral = true)] = jsonString(number)
        val vm = viewModel()
        vm.open(OnboardingStep.Create)
        vm.create()
        core.status.value = newAccount
        runCurrent()
        assertEquals(listOf<OnboardingStep>(OnboardingStep.NewAccount), vm.steps)
        assertEquals(number, vm.newNumber)
    }

    @Test
    fun theReferralSwitchGoesIntoTheCreateRequest() = runTest(dispatcher) {
        core.answers[Request.CreateAccount(referral = false)] = jsonString(number)
        val vm = viewModel()
        vm.open(OnboardingStep.Create)
        vm.useReferral = false
        vm.create()
        runCurrent()
        assertTrue(Request.CreateAccount(referral = false) in core.requests)
        assertEquals(number, vm.newNumber)
    }

    @Test
    fun afterAProcessDeathTheNumberComesBackOnlyOnRequest() = runTest(dispatcher) {
        core.answers[Request.RevealAccountNumber] = jsonString(number)
        val vm = viewModel(newAccount)
        assertEquals(listOf<OnboardingStep>(OnboardingStep.NewAccount), vm.steps)
        assertNull(vm.newNumber)
        assertEquals(emptyList(), core.requests)

        vm.reveal()
        runCurrent()
        assertEquals(number, vm.newNumber)
    }

    @Test
    fun returningFromThePaymentPageRefreshesTheAccount() = runTest(dispatcher) {
        val vm = viewModel(newAccount)
        vm.onResumed()
        runCurrent()
        assertEquals(emptyList(), core.requests)

        vm.onPaymentOpened()
        vm.onResumed()
        vm.onResumed()
        runCurrent()
        assertEquals(listOf<Request>(Request.RefreshAccount), core.requests)
    }

    @Test
    fun doneEndsTheFlowAndLeavingLogsOutFirst() = runTest(dispatcher) {
        val vm = viewModel(newAccount)
        vm.finish()
        runCurrent()
        assertEquals(listOf<Request>(Request.EndNewAccountFlow), core.requests)

        core.requests.clear()
        vm.useOtherAccount()
        runCurrent()
        assertEquals(listOf(Request.Logout, Request.EndNewAccountFlow), core.requests)
    }
}
