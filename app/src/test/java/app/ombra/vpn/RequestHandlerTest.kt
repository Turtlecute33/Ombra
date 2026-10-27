package app.ombra.vpn

import app.ombra.core.AccountNumber
import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.model.BundleInfo
import app.ombra.core.model.FreeTrialStatus
import app.ombra.core.model.ManagerCmd
import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.TunnelArgs
import app.ombra.core.model.TunnelMode
import app.ombra.core.model.parseJsonString
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestHandlerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val core = FakeCore()
    private val number = "55550123456780554337"

    private fun TestScope.handler(
        allowRawCommands: Boolean = false,
        settingsDir: File = tmp.root,
        deleteWgKey: () -> Unit = {},
        forgetLogs: () -> Unit = {},
    ): RequestHandler {
        val controller = CoreController(
            core,
            SettingsStore(settingsDir),
            VpnWanted(File(tmp.root, "vpn_wanted")),
            backgroundScope,
            testScheduler.timeSource,
        )
        val bundles = DebugBundles(File(tmp.root, "cache"), BundleInfo("1.0", 37), core)
        return RequestHandler(controller, core, bundles, allowRawCommands, deleteWgKey, forgetLogs)
    }

    private suspend fun RequestHandler.send(request: Request): Reply = handle(IpcJson.encodeToString(Request.serializer(), request))

    @Test
    fun undecodableRequestIsBadRequest() = runTest {
        assertEquals(Reply.error(IpcError.BAD_REQUEST), handler().handle("{"))
        assertEquals(Reply.error(IpcError.BAD_REQUEST), handler().handle("""{"type":"fromTheFuture"}"""))
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun loginWithATypoSendsNothing() = runTest {
        assertEquals(Reply.error(IpcError.INVALID_ACCOUNT_NUMBER), handler().send(Request.Login("12345")))
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun loginAnswersNull() = runTest {
        assertEquals(Reply.ok("null"), handler().send(Request.Login(number)))
        assertTrue(ManagerCmd.Login(number, validate = true) in core.sent)
    }

    @Test
    fun createAccountAnswersTheNewNumber() = runTest {
        val reply = handler().send(Request.CreateAccount)
        val created = parseJsonString(reply.okJson!!)!!
        assertTrue(AccountNumber.isValid(created))
        assertTrue(ManagerCmd.Login(created, validate = true) in core.sent)
    }

    @Test
    fun endNewAccountFlowTurnsTheFlagOff() = runTest {
        assertEquals(Reply.ok("null"), handler().send(Request.EndNewAccountFlow))
        assertEquals(listOf<ManagerCmd>(ManagerCmd.SetInNewAccountFlow(false)), core.sent)
    }

    @Test
    fun revealAccountNumberAnswersTheNumberOrNull() = runTest {
        core.setStatus(accountId = number)
        assertEquals(number, parseJsonString(handler().send(Request.RevealAccountNumber).okJson!!))
        core.setStatus(accountId = null)
        assertEquals(Reply.ok("null"), handler().send(Request.RevealAccountNumber))
    }

    @Test
    fun serviceStatusAndTrafficStatsAreEncodedForIpc() = runTest {
        val status = IpcJson.decodeFromString(ServiceStatus.serializer(), handler().send(Request.ServiceStatus).okJson!!)
        assertEquals(FreeTrialStatus(available = true), status.freeTrial)
        val stats = IpcJson.decodeFromString(TrafficStats.serializer(), handler().send(Request.TrafficStats).okJson!!)
        assertEquals(core.trafficStats, stats)
    }

    @Test
    fun updateSettingsAnswersTheNewSettings() = runTest {
        val reply = handler().send(Request.UpdateSettings(buildJsonObject { put("lanAccess", true) }))
        assertEquals(Settings(lanAccess = true), IpcJson.decodeFromString(Settings.serializer(), reply.okJson!!))
    }

    @Test
    fun switchToStealthReachesRust() = runTest {
        // The patch of "Switch to Stealth" (ConnectViewModel) after udpHandshakeFailed in Speed mode.
        val handler = handler()
        val speed = Settings(mode = TunnelMode.WireguardUdp, udpMtu = 1400)
        handler.send(Request.UpdateSettings(speed.patchFrom(Settings())))
        core.sent.clear()

        val reply = handler.send(Request.UpdateSettings(speed.copy(mode = TunnelMode.Quic).patchFrom(speed)))
        assertEquals(TunnelMode.Quic, IpcJson.decodeFromString(Settings.serializer(), reply.okJson!!).mode)
        // active = null: Rust leaves the Speed retry wait and connects again in QUIC mode.
        assertEquals(listOf<ManagerCmd>(ManagerCmd.SetTunnelArgs(TunnelArgs(mode = TunnelMode.Quic), active = null)), core.sent)
    }

    @Test
    fun updateSettingsWithAWrongTypeIsBadRequest() = runTest {
        val reply = handler().send(Request.UpdateSettings(buildJsonObject { put("lanAccess", "maybe") }))
        assertEquals(Reply.error(IpcError.BAD_REQUEST), reply)
    }

    @Test
    fun unwritableSettingsAreConfigSaveError() = runTest {
        val reply = handler(settingsDir = File(tmp.root, "missing")).send(Request.UpdateSettings(buildJsonObject { put("lanAccess", true) }))
        assertEquals(Reply.error("configSaveError"), reply)
        assertEquals(emptyList(), core.sent)
    }

    @Test
    fun simpleRequestsSendTheirCoreCommand() = runTest {
        assertEquals(Reply.ok("null"), handler().send(Request.RotateWgKey))
        assertEquals(Reply.ok("null"), handler().send(Request.RefreshAccount))
        assertEquals(Reply.ok("null"), handler().send(Request.RefreshLists))
        assertEquals(Reply.ok("null"), handler().send(Request.Logout))
        assertEquals(Reply.ok("null"), handler().send(Request.DeleteAccount))
        for (command in listOf(
            ManagerCmd.RotateWgKey,
            ManagerCmd.ApiGetAccountInfo,
            ManagerCmd.RefreshExitList(60_000),
            ManagerCmd.RefreshRelayList(60_000),
            ManagerCmd.Logout,
            ManagerCmd.ApiDeleteAccount,
        )) {
            assertTrue(command in core.sent, "$command was not sent")
        }
    }

    @Test
    fun aLogoutThatWorkedDeletesTheDebugBundleAndTheStoredKey() = runTest {
        var keyDeletes = 0
        val bundle = File(tmp.root, "cache/${DebugBundles.DIR}/bundle.zip").apply {
            parentFile!!.mkdirs()
            writeText("logs")
        }
        core.failures[ManagerCmd.Logout::class] = "notLoggedIn"
        assertEquals(Reply.error("notLoggedIn"), handler(deleteWgKey = { keyDeletes++ }).send(Request.Logout))
        assertTrue(bundle.exists())
        assertEquals(0, keyDeletes)

        core.failures.remove(ManagerCmd.Logout::class)
        assertEquals(Reply.ok("null"), handler(deleteWgKey = { keyDeletes++ }).send(Request.Logout))
        assertFalse(bundle.exists())
        assertEquals(1, keyDeletes)
    }

    @Test
    fun aLogoutOrDeletionThatWorkedForgetsTheRustLogs() = runTest {
        var logForgets = 0
        core.failures[ManagerCmd.Logout::class] = "notLoggedIn"
        core.failures[ManagerCmd.ApiDeleteAccount::class] = "notLoggedIn"
        assertEquals(Reply.error("notLoggedIn"), handler(forgetLogs = { logForgets++ }).send(Request.Logout))
        assertEquals(Reply.error("notLoggedIn"), handler(forgetLogs = { logForgets++ }).send(Request.DeleteAccount))
        assertEquals(0, logForgets)

        core.failures.clear()
        assertEquals(Reply.ok("null"), handler(forgetLogs = { logForgets++ }).send(Request.Logout))
        assertEquals(1, logForgets)
        assertEquals(Reply.ok("null"), handler(forgetLogs = { logForgets++ }).send(Request.DeleteAccount))
        assertEquals(2, logForgets)
    }

    @Test
    fun coreErrorCodesReachTheCaller() = runTest {
        core.failures[ManagerCmd.ApiGetAccountInfo::class] = "notLoggedIn"
        assertEquals(Reply.error("notLoggedIn"), handler().send(Request.RefreshAccount))
        core.failures[ManagerCmd.GetTrafficStats::class] = IpcError.TIMEOUT
        assertEquals(Reply.error(IpcError.TIMEOUT), handler().send(Request.TrafficStats))
    }

    @Test
    fun rawCommandOnlyInDebugBuilds() = runTest {
        assertEquals(Reply.error(IpcError.NOT_ALLOWED), handler().send(Request.RawCommand("""{"ping":{}}""")))
        assertEquals(emptyList(), core.rawSent)

        assertEquals(Reply.ok("null"), handler(allowRawCommands = true).send(Request.RawCommand("""{"ping":{}}""")))
        assertEquals(listOf("""{"ping":{}}"""), core.rawSent)
    }

    @Test
    fun debugBundleReplacesOlderBundles() = runTest {
        val cache = File(tmp.root, "cache")
        val old = File(cache, "${DebugBundles.DIR}/old.zip").apply {
            parentFile!!.mkdirs()
            writeText("old logs")
        }

        val reply = handler().send(Request.CreateDebugBundle("slow at night"))

        assertEquals(core.debugBundlePath, parseJsonString(reply.okJson!!))
        assertFalse(old.exists())
        assertEquals(
            listOf<ManagerCmd>(ManagerCmd.CreateDebugBundle("slow at night", BundleInfo("1.0", 37), cache.absolutePath)),
            core.sent,
        )
    }

    @Test
    fun errorCodeMapping() {
        assertEquals("apiUnreachable", RequestHandler.errorCode(ErrorCodeException("apiUnreachable")))
        assertEquals(IpcError.CORE_NOT_READY, RequestHandler.errorCode(ErrorCodeException(IpcError.CORE_NOT_READY)))
        assertEquals(IpcError.BAD_REQUEST, RequestHandler.errorCode(SerializationException("bad value")))
        assertEquals("configSaveError", RequestHandler.errorCode(IOException("disk full")))
        assertEquals("other", RequestHandler.errorCode(IllegalStateException("bug")))
    }
}
