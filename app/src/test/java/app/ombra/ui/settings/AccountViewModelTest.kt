package app.ombra.ui.settings

import app.ombra.core.AccountNumber
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.IpcError
import app.ombra.ipc.Request
import app.ombra.ui.FakeCore
import app.ombra.ui.ObscuraLinks
import app.ombra.ui.connect.status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeCore()
    private val number = AccountNumber.generate()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        core.status.value = status(VpnStatus.Disconnected)
        core.answers[Request.RevealAccountNumber] = "\"$number\""
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theNumberShowsOnlyWhileRevealed() = runTest(dispatcher) {
        val vm = AccountViewModel(core)
        assertNull(vm.number)
        vm.reveal()
        advanceUntilIdle()
        assertEquals(number, vm.number)
        vm.hideNumber()
        assertNull(vm.number)
    }

    @Test
    fun addTimeRevealsTheNumberOpensThePageAndRefreshesOnReturn() = runTest(dispatcher) {
        val vm = AccountViewModel(core)
        val opened = mutableListOf<String>()
        vm.onResumed()
        advanceUntilIdle()
        assertEquals(emptyList(), core.requests, "no refresh without the payment page")

        vm.addTime { opened += ObscuraLinks.payment(it) }
        advanceUntilIdle()
        assertEquals(listOf("https://obscura.com/pay#account_id=$number"), opened)
        assertEquals(listOf<Request>(Request.RevealAccountNumber), core.requests)
        assertNull(vm.number, "the number for the link does not show on the screen")

        vm.onResumed()
        vm.onResumed()
        advanceUntilIdle()
        assertEquals(listOf(Request.RevealAccountNumber, Request.RefreshAccount), core.requests)
    }

    @Test
    fun noNumberNoPage() = runTest(dispatcher) {
        core.failures[Request.RevealAccountNumber] = IpcError.DISCONNECTED
        val vm = AccountViewModel(core)
        val opened = mutableListOf<String>()
        vm.addTime { opened += it }
        advanceUntilIdle()
        assertEquals(emptyList(), opened)
        assertEquals(IpcError.DISCONNECTED, vm.errorCode)
        assertEquals(false, vm.busy)
    }

    @Test
    fun deleteAndLogOutGoToTheCore() = runTest(dispatcher) {
        val vm = AccountViewModel(core)
        vm.deleteAccount()
        advanceUntilIdle()
        vm.logout()
        advanceUntilIdle()
        assertEquals(listOf<Request>(Request.DeleteAccount, Request.Logout), core.requests)
    }
}
