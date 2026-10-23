package app.ombra.vpn

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Settings
import app.ombra.ipc.StateKind
import app.ombra.ipc.SystemState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFeedTest {
    private val number = "55550123456780554337"
    private val status = MutableStateFlow<Status?>(null)
    private val exits = MutableStateFlow<CachedValue<ExitList>?>(null)
    private val relays = MutableStateFlow<CachedValue<List<OneRelay>>?>(null)
    private val settings = MutableStateFlow(Settings())
    private val system = MutableStateFlow(SystemState())
    private val feed = StateFeed(status, exits, relays, settings, system)

    private val relay = OneRelay("r1", "NL", "ams", "Amsterdam", ipV4 = "192.0.2.1", ports = listOf(443), tlsCert = "MIIBcert")
    private fun exitList(version: String, vararg ids: String) =
        CachedValue(version, 1_700_000_000, ExitList(ids.map { OneExit(it, "DE", "ber", "Berlin") }))

    @Test
    fun snapshotHasSettingsAndSystemAlwaysAndOtherKindsOnceKnown() {
        assertEquals(listOf(StateKind.SETTINGS, StateKind.SYSTEM), feed.snapshot().map { it.first })

        status.value = Status(version = "v1", accountId = number)
        exits.value = exitList("e1", "x1")
        relays.value = CachedValue("r1", 1_700_000_000, listOf(relay))
        assertEquals(
            listOf(StateKind.STATUS, StateKind.EXITS, StateKind.RELAYS, StateKind.SETTINGS, StateKind.SYSTEM),
            feed.snapshot().map { it.first },
        )
    }

    @Test
    fun statusLeavesSanitized() {
        status.value = Status(version = "v1", accountId = number)
        val json = feed.snapshot().first { it.first == StateKind.STATUS }.second
        assertFalse(number in json)
        assertTrue(IpcJson.decodeFromString(Status.serializer(), json).isLoggedIn)
    }

    @Test
    fun relaysLeaveWithoutCertificates() {
        relays.value = CachedValue("r1", 1_700_000_000, listOf(relay))
        val json = feed.snapshot().first { it.first == StateKind.RELAYS }.second
        val sent = IpcJson.decodeFromString(CachedValue.serializer(ListSerializer(OneRelay.serializer())), json)
        assertEquals(listOf(relay.copy(tlsCert = "")), sent.value)
    }

    @Test
    fun versionedKindsArePushedOnlyForANewVersion() = runTest {
        val statusPushes = mutableListOf<String>()
        val exitPushes = mutableListOf<String>()
        val changes = feed.changes()
        backgroundScope.launch { changes.getValue(StateKind.STATUS).toList(statusPushes) }
        backgroundScope.launch { changes.getValue(StateKind.EXITS).toList(exitPushes) }
        runCurrent()
        assertEquals(0, statusPushes.size)

        status.value = Status(version = "v1")
        exits.value = exitList("e1", "x1")
        runCurrent()
        status.value = Status(version = "v1", autoConnect = true)
        exits.value = exitList("e1", "x1", "x2")
        runCurrent()
        assertEquals(1, statusPushes.size)
        assertEquals(1, exitPushes.size)

        status.value = Status(version = "v2")
        exits.value = exitList("e2", "x1", "x2")
        runCurrent()
        assertEquals(2, statusPushes.size)
        assertEquals(2, exitPushes.size)
    }

    @Test
    fun settingsAndSystemArePushedOnChange() = runTest {
        val settingsPushes = mutableListOf<String>()
        val systemPushes = mutableListOf<String>()
        val changes = feed.changes()
        backgroundScope.launch { changes.getValue(StateKind.SETTINGS).toList(settingsPushes) }
        backgroundScope.launch { changes.getValue(StateKind.SYSTEM).toList(systemPushes) }
        runCurrent()

        settings.value = Settings(lanAccess = true)
        system.value = SystemState(lockdown = true)
        runCurrent()

        assertEquals(Settings(lanAccess = true), IpcJson.decodeFromString(Settings.serializer(), settingsPushes.last()))
        assertEquals(SystemState(lockdown = true), IpcJson.decodeFromString(SystemState.serializer(), systemPushes.last()))
        assertEquals(2, settingsPushes.size)
        assertEquals(2, systemPushes.size)
    }
}
