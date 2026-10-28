package app.ombra.vpn

import app.ombra.core.AccountNumber
import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.model.ConnectErrorCode
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.ManagerCmd
import app.ombra.core.model.ManagerCmdErrorCode
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.IpcError
import app.ombra.ipc.Settings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CoreControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val core = FakeCore()
    private val number = "55550123456780554337"
    private val stored = Settings(lanAccess = true, padding = true, exit = ExitSelector.Country("DE"))

    private val store by lazy { SettingsStore(tmp.root).apply { update { stored } } }
    private val wanted by lazy { VpnWanted(File(tmp.root, "vpn_wanted")) }

    private fun TestScope.controller() = CoreController(core, store, wanted, backgroundScope, testScheduler.timeSource)

    private fun patch(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

    private fun allSettingsCommands(settings: Settings = stored) = SettingsSync.all(settings).map { it.command }

    // ---- start and long-polls -----------------------------------------------------------------

    @Test
    fun startAppliesTheSettingsAndRunsOneLongPollPerValue() = runTest {
        val controller = controller()
        controller.start()
        controller.start()
        runCurrent()

        assertEquals(allSettingsCommands(), core.sent)
        assertEquals(1, core.openPolls(ManagerCmd.GetStatus::class))
        assertEquals(1, core.openPolls(ManagerCmd.GetExitList::class))
        assertEquals(1, core.openPolls(ManagerCmd.GetRelayList::class))
    }

    @Test
    fun statusLoopFollowsEachNewVersion() = runTest {
        val controller = controller()
        controller.start()
        core.setStatus()
        runCurrent()
        assertEquals("v1", controller.status.value?.version)

        core.setStatus(VpnStatus.Connecting(TunnelArgs()))
        runCurrent()
        assertEquals("v2", controller.status.value?.version)
        assertEquals(3, core.pollCalls(ManagerCmd.GetStatus::class))
        assertEquals(1, core.openPolls(ManagerCmd.GetStatus::class))
    }

    @Test
    fun relayLoopStopsWhenTheCoreHasNoRelayList() = runTest {
        core.failures[ManagerCmd.GetRelayList::class] = IpcError.BAD_COMMAND
        controller().start()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, core.pollCalls(ManagerCmd.GetRelayList::class))
    }

    @Test
    fun failedPollIsRetried() = runTest {
        core.failures[ManagerCmd.GetExitList::class] = ManagerCmdErrorCode.Other.wire
        controller().start()
        runCurrent()
        core.failures.clear()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(2, core.pollCalls(ManagerCmd.GetExitList::class))
        assertEquals(1, core.openPolls(ManagerCmd.GetExitList::class))
    }

    // ---- settings ------------------------------------------------------------------------------

    @Test
    fun updateSettingsStoresAndSendsOnlyWhatChanged() = runTest {
        val after = controller().updateSettings(patch { put("useSystemDns", true) })

        assertTrue(after.useSystemDns)
        assertEquals(listOf<ManagerCmd>(ManagerCmd.SetUseSystemDns(true)), core.sent)
        assertEquals(after, SettingsStore(tmp.root).current)
    }

    @Test
    fun unchangedPatchSendsNothing() = runTest {
        controller().updateSettings(patch { put("lanAccess", true) })
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun badPatchChangesNothing() = runTest {
        assertFailsWith<SerializationException> { controller().updateSettings(patch { put("exit", "nowhere") }) }
        assertEquals(emptyList(), core.sent)
        assertEquals(stored, SettingsStore(tmp.root).current)
    }

    @Test
    fun splitTunnelChangeRebuildsTheTunOnlyWhileTheTunnelIsUsed() = runTest {
        val controller = controller()
        controller.start()
        core.setStatus(VpnStatus.Connecting(TunnelArgs()))
        runCurrent()
        core.sent.clear()

        controller.updateSettings(patch { put("splitMode", "exclude") })
        advanceTimeBy(CoreController.SPLIT_TUN_REFRESH_DELAY)
        runCurrent()
        assertEquals(listOf<ManagerCmd>(ManagerCmd.RefreshOsNetworkConfig), core.sent)

        core.setStatus(VpnStatus.Disconnected)
        runCurrent()
        core.sent.clear()
        controller.updateSettings(patch { put("splitMode", "include") })
        advanceTimeBy(CoreController.SPLIT_TUN_REFRESH_DELAY)
        runCurrent()
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun quickSplitTunnelChangesGiveOneTunRebuildAfterTheLast() = runTest {
        val controller = controller()
        controller.start()
        core.setStatus(VpnStatus.Connecting(TunnelArgs()))
        runCurrent()
        core.sent.clear()

        controller.updateSettings(patch { put("splitMode", "exclude") })
        for (i in 1..5) {
            advanceTimeBy(1.seconds)
            controller.updateSettings(patch { put("splitApps", buildJsonArray { (1..i).forEach { add("org.example.app$it") } }) })
        }
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(emptyList(), core.sent, "no rebuild while the changes come")

        advanceTimeBy(CoreController.SPLIT_TUN_REFRESH_DELAY)
        runCurrent()
        assertEquals(listOf<ManagerCmd>(ManagerCmd.RefreshOsNetworkConfig), core.sent)
        assertEquals(5, store.current.splitApps.size, "each change is stored at once")
    }

    @Test
    fun customDnsChangeRebuildsTheTunAtOnceAndTakesThePendingRebuild() = runTest {
        val controller = controller()
        controller.start()
        core.setStatus(VpnStatus.Connecting(TunnelArgs()))
        runCurrent()
        core.sent.clear()

        controller.updateSettings(patch { put("splitMode", "exclude") })
        controller.updateSettings(patch { put("customDns", buildJsonArray { add("192.0.2.53") }) })
        assertEquals(listOf<ManagerCmd>(ManagerCmd.RefreshOsNetworkConfig), core.sent)

        advanceTimeBy(CoreController.SPLIT_TUN_REFRESH_DELAY)
        runCurrent()
        assertEquals(listOf<ManagerCmd>(ManagerCmd.RefreshOsNetworkConfig), core.sent, "the DNS rebuild has the split change")
    }

    // ---- account -------------------------------------------------------------------------------

    @Test
    fun loginChecksTheNumberLocallyFirst() = runTest {
        val error = assertFailsWith<ErrorCodeException> { controller().login("5555 0123 4567 8055 4338") }
        assertEquals(IpcError.INVALID_ACCOUNT_NUMBER, error.errorCode)
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun loginReappliesTheSettingsThenRefreshesAccountAndLists() = runTest {
        val controller = controller()
        controller.login("5555 0123 4567 8055 4337")

        assertEquals(
            listOf(ManagerCmd.SetInNewAccountFlow(false), ManagerCmd.Login(number, validate = true)) + allSettingsCommands(),
            core.sent,
        )
        runCurrent()
        assertEquals(1, core.count(ManagerCmd.ApiGetAccountInfo::class))
        assertEquals(1, core.count(ManagerCmd.RefreshExitList::class))
        assertEquals(1, core.count(ManagerCmd.RefreshRelayList::class))
    }

    @Test
    fun failedLoginStillReappliesButRefreshesNothing() = runTest {
        core.failures[ManagerCmd.Login::class] = "apiInvalidAccountId"
        val error = assertFailsWith<ErrorCodeException> { controller().login(number) }
        assertEquals("apiInvalidAccountId", error.errorCode)
        runCurrent()
        assertEquals(allSettingsCommands(), core.sent.drop(2))
        assertEquals(0, core.count(ManagerCmd.ApiGetAccountInfo::class))
    }

    @Test
    fun loginReconnectsATunnelThatWaitsForTheLogin() = runTest {
        // Always-on after a logout: Rust tries to connect without an account.
        core.setStatus(VpnStatus.Connecting(TunnelArgs(), connectError = ConnectErrorCode.NotLoggedIn), accountId = null)
        wanted.value = true
        val controller = controller()
        controller.start()
        runCurrent()
        core.sent.clear()

        controller.login(number)

        // Rust resets tunnel_active at the account change; connect again with the restored args.
        assertEquals(
            listOf(ManagerCmd.SetInNewAccountFlow(false), ManagerCmd.Login(number, validate = true)) +
                allSettingsCommands() + ManagerCmd.SetTunnelArgs(null, true),
            core.sent,
        )
        assertTrue(wanted.value)
    }

    @Test
    fun loginWithAStaleVpnWantedClearsIt() = runTest {
        core.setStatus(VpnStatus.Disconnected, accountId = null)
        wanted.value = true
        val controller = controller()
        controller.start()
        runCurrent()
        core.sent.clear()

        controller.login(number)

        // No connect: nothing waits for the login.
        assertEquals(
            listOf(ManagerCmd.SetInNewAccountFlow(false), ManagerCmd.Login(number, validate = true)) + allSettingsCommands(),
            core.sent,
        )
        assertFalse(wanted.value)
    }

    @Test
    fun failedReconnectAfterLoginClearsVpnWanted() = runTest {
        core.setStatus(VpnStatus.Connecting(TunnelArgs(), connectError = ConnectErrorCode.NotLoggedIn), accountId = null)
        wanted.value = true
        val controller = controller()
        controller.start()
        runCurrent()
        core.failures[ManagerCmd.SetTunnelArgs::class] = ManagerCmdErrorCode.Other.wire

        controller.login(number)

        assertFalse(wanted.value)
    }

    @Test
    fun createAccountLogsInWithANewValidNumber() = runTest {
        val created = controller().createAccount(referral = false)

        assertTrue(AccountNumber.isValid(created))
        assertEquals(
            listOf(ManagerCmd.SetInNewAccountFlow(true), ManagerCmd.Login(created, validate = true)),
            core.sent.take(2),
        )
        assertEquals(allSettingsCommands(), core.sent.drop(2))
    }

    @Test
    fun failedCreateAccountEndsTheNewAccountFlow() = runTest {
        core.failures[ManagerCmd.Login::class] = "apiSignupLimitExceeded"
        val error = assertFailsWith<ErrorCodeException> { controller().createAccount(referral = true) }
        assertEquals("apiSignupLimitExceeded", error.errorCode)
        assertEquals(ManagerCmd.SetInNewAccountFlow(false), core.sent.last())
        assertEquals(0, core.count(ManagerCmd.ApiUseReferralCode::class))
    }

    @Test
    fun createAccountWithReferralSendsTheCodeAfterTheLogin() = runTest {
        val created = controller().createAccount(referral = true)
        runCurrent()
        val referral = core.sent.indexOf(ManagerCmd.ApiUseReferralCode(CoreController.REFERRAL_CODE))
        assertTrue(referral > core.sent.indexOf(ManagerCmd.Login(created, validate = true)))
        assertEquals(1, core.count(ManagerCmd.ApiUseReferralCode::class))
    }

    @Test
    fun aRefusedReferralDoesNotFailTheNewAccount() = runTest {
        core.failures[ManagerCmd.ApiUseReferralCode::class] = "apiError"
        val created = controller().createAccount(referral = true)
        runCurrent()
        assertTrue(AccountNumber.isValid(created))
        assertEquals(1, core.count(ManagerCmd.ApiUseReferralCode::class))
    }

    @Test
    fun logoutClearsVpnWantedAndReappliesTheSettings() = runTest {
        wanted.value = true
        controller().logout()
        assertFalse(wanted.value)
        assertEquals(listOf<ManagerCmd>(ManagerCmd.Logout) + allSettingsCommands(), core.sent)
    }

    @Test
    fun deleteAccountLogsOutOnlyAfterTheServerDeletedIt() = runTest {
        controller().deleteAccount()
        assertEquals(listOf(ManagerCmd.ApiDeleteAccount, ManagerCmd.Logout), core.sent.take(2))

        core.sent.clear()
        core.failures[ManagerCmd.ApiDeleteAccount::class] = "apiUnreachable"
        assertFailsWith<ErrorCodeException> { controller().deleteAccount() }
        assertEquals(listOf<ManagerCmd>(ManagerCmd.ApiDeleteAccount), core.sent)
    }

    @Test
    fun refreshListsToleratesACoreWithoutRelayList() = runTest {
        core.failures[ManagerCmd.RefreshRelayList::class] = IpcError.BAD_COMMAND
        controller().refreshLists()
        assertEquals(1, core.count(ManagerCmd.RefreshExitList::class))
    }

    @Test
    fun accountNumberComesFromTheRawStatus() = runTest {
        core.setStatus(accountId = number)
        val controller = controller()
        assertEquals(number, controller.accountNumber())

        controller.start()
        runCurrent()
        core.setStatus(accountId = null)
        runCurrent()
        assertNull(controller.accountNumber())
    }

    // ---- account info refresh ------------------------------------------------------------------

    @Test
    fun accountInfoRefreshesAtStartWhenLoggedInAndEvery12Hours() = runTest {
        core.setStatus(accountId = number)
        controller().start()
        runCurrent()
        assertEquals(1, core.count(ManagerCmd.ApiGetAccountInfo::class))
        assertEquals(1, core.count(ManagerCmd.RefreshExitList::class))

        advanceTimeBy(CoreController.ACCOUNT_REFRESH_INTERVAL)
        runCurrent()
        assertEquals(2, core.count(ManagerCmd.ApiGetAccountInfo::class))
    }

    @Test
    fun noAccountRefreshWhileLoggedOut() = runTest {
        core.setStatus(accountId = null)
        controller().start()
        advanceTimeBy(25.hours)
        runCurrent()
        assertEquals(0, core.count(ManagerCmd.ApiGetAccountInfo::class))
    }

    @Test
    fun expiredAccountRefreshesTheInfoAtMostOncePerGap() = runTest {
        core.setStatus(accountId = number)
        controller().start()
        runCurrent()
        assertEquals(1, core.count(ManagerCmd.ApiGetAccountInfo::class))

        val expired = VpnStatus.Connecting(TunnelArgs(), connectError = ConnectErrorCode.AccountExpired)
        advanceTimeBy(CoreController.EXPIRED_REFRESH_GAP + 1.seconds)
        core.setStatus(expired)
        runCurrent()
        assertEquals(2, core.count(ManagerCmd.ApiGetAccountInfo::class))

        advanceTimeBy(10.seconds)
        core.setStatus(expired.copy(reconnecting = true))
        runCurrent()
        assertEquals(2, core.count(ManagerCmd.ApiGetAccountInfo::class))

        advanceTimeBy(CoreController.EXPIRED_REFRESH_GAP)
        core.setStatus(expired)
        runCurrent()
        assertEquals(3, core.count(ManagerCmd.ApiGetAccountInfo::class))
    }

    @Test
    fun otherConnectErrorsDoNotRefreshTheAccount() = runTest {
        core.setStatus(accountId = null)
        controller().start()
        core.setStatus(VpnStatus.Connecting(TunnelArgs(), connectError = ConnectErrorCode.NoInternet))
        runCurrent()
        assertEquals(0, core.count(ManagerCmd.ApiGetAccountInfo::class))
    }
}
