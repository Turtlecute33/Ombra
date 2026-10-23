package app.ombra.vpn

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Settings
import app.ombra.ipc.StateKind
import app.ombra.ipc.StatusSanitizer
import app.ombra.ipc.SystemState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * The JSON that IStateListener receives, per [StateKind]. Status leaves the process sanitized;
 * versioned values go out only when their version changes; relays go without their TLS
 * certificates, which the UI has no use for and which make the binder payload large.
 */
class StateFeed(
    private val status: StateFlow<Status?>,
    private val exits: StateFlow<CachedValue<ExitList>?>,
    private val relays: StateFlow<CachedValue<List<OneRelay>>?>,
    private val settings: StateFlow<Settings>,
    private val system: StateFlow<SystemState>,
) {
    /** One flow of JSON per kind; each emits the current value first, then every change. */
    fun changes(): Map<Int, Flow<String>> = mapOf(
        StateKind.STATUS to status.filterNotNull().distinctUntilChangedBy { it.version }.map(::statusJson),
        StateKind.EXITS to exits.filterNotNull().distinctUntilChangedBy { it.version }.map(::exitsJson),
        StateKind.RELAYS to relays.filterNotNull().distinctUntilChangedBy { it.version }.map(::relaysJson),
        StateKind.SETTINGS to settings.map(::settingsJson),
        StateKind.SYSTEM to system.map(::systemJson),
    )

    /** The current value of every kind that has one, for a new listener. */
    fun snapshot(): List<Pair<Int, String>> = buildList {
        status.value?.let { add(StateKind.STATUS to statusJson(it)) }
        exits.value?.let { add(StateKind.EXITS to exitsJson(it)) }
        relays.value?.let { add(StateKind.RELAYS to relaysJson(it)) }
        add(StateKind.SETTINGS to settingsJson(settings.value))
        add(StateKind.SYSTEM to systemJson(system.value))
    }

    private fun statusJson(status: Status): String = StatusSanitizer.sanitizedJson(status)

    private fun exitsJson(exits: CachedValue<ExitList>): String = IpcJson.encodeToString(EXITS, exits)

    private fun relaysJson(relays: CachedValue<List<OneRelay>>): String =
        IpcJson.encodeToString(RELAYS, relays.copy(value = relays.value.map { it.copy(tlsCert = "") }))

    private fun settingsJson(settings: Settings): String = IpcJson.encodeToString(Settings.serializer(), settings)

    private fun systemJson(system: SystemState): String = IpcJson.encodeToString(SystemState.serializer(), system)

    private companion object {
        val EXITS: KSerializer<CachedValue<ExitList>> = CachedValue.serializer(ExitList.serializer())
        val RELAYS: KSerializer<CachedValue<List<OneRelay>>> = CachedValue.serializer(ListSerializer(OneRelay.serializer()))
    }
}
