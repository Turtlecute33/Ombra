package app.ombra.ui

import app.ombra.core.model.Status
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.Settings
import app.ombra.ui.connect.connected
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppStageTest {
    private val loggedOut = Status(version = "v")
    private val loggedIn = loggedOut.copy(loggedIn = true)
    private val autoConnect = Settings(autoConnectOnLaunch = true)

    @Test
    fun onboardingLastsUntilTheNewNumberIsConfirmed() {
        assertEquals(AppStage.Loading, stageOf(null))
        assertEquals(AppStage.Onboarding, stageOf(loggedOut))
        assertEquals(AppStage.Onboarding, stageOf(loggedIn.copy(inNewAccountFlow = true)))
        assertEquals(AppStage.Main, stageOf(loggedIn))
    }

    @Test
    fun autoConnectNeedsTheSettingAnAccountAndADownTunnel() {
        assertTrue(shouldAutoConnect(loggedIn, autoConnect))
        assertFalse(shouldAutoConnect(loggedIn, Settings()))
        assertFalse(shouldAutoConnect(loggedOut, autoConnect))
        assertFalse(shouldAutoConnect(loggedIn.copy(inNewAccountFlow = true), autoConnect))
        assertFalse(shouldAutoConnect(loggedIn.copy(vpnStatus = connected()), autoConnect))
        assertFalse(shouldAutoConnect(loggedIn.copy(vpnStatus = VpnStatus.Connecting()), autoConnect))
    }

    @Test
    fun launchWaitsForTheFirstStatus() = runTest {
        val core = FakeCore().apply { settings.value = autoConnect }
        val decision = async { core.shouldAutoConnectOnLaunch(10.seconds) }
        advanceTimeBy(3_000)
        core.status.value = loggedIn
        runCurrent()
        assertTrue(decision.await())
    }

    @Test
    fun aSilentCoreMeansNoAutoConnect() = runTest {
        val core = FakeCore().apply { settings.value = autoConnect }
        assertFalse(core.shouldAutoConnectOnLaunch(10.seconds))
    }
}
