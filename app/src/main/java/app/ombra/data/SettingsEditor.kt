package app.ombra.data

import app.ombra.ipc.IpcError
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Every settings change of the UI goes through here, one at a time and in order. [settings] shows
 * a change at once, before the :vpn process confirms it. Each patch starts from the confirmed
 * settings, so two quick changes to the same list (pins, apps) both land. Changes run in [scope]
 * (as long as the activity's ViewModelStore), so leaving a screen does not drop a change.
 */
class SettingsEditor(private val core: CoreSource, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val inFlight = MutableStateFlow<List<Change>>(emptyList())

    /** The settings of the core with the changes on their way applied on top. Null until the first push. */
    val settings: StateFlow<Settings?> = combine(core.settings, inFlight) { confirmed, changes ->
        confirmed?.let { changes.fold(it) { settings, change -> change.transform(settings) } }
    }.stateIn(scope, SharingStarted.Eagerly, core.settings.value)

    /**
     * Stores [transform] of the current settings and returns the stored settings. Throws
     * [CoreRequestException]; the change is then undone. A change whose patch is empty sends nothing.
     */
    suspend fun update(transform: (Settings) -> Settings): Settings {
        val change = Change(transform)
        inFlight.update { it + change }
        // The failure travels as a value: thrown inside async, it would also cancel [scope].
        return scope.async {
            try {
                Result.success(mutex.withLock { store(transform) })
            } catch (e: CoreRequestException) {
                Result.failure(e)
            } finally {
                inFlight.update { it - change }
            }
        }.await().getOrThrow()
    }

    private suspend fun store(transform: (Settings) -> Settings): Settings {
        val base = withTimeoutOrNull(FIRST_PUSH_WAIT) { core.settings.filterNotNull().first() }
            ?: throw CoreRequestException(IpcError.DISCONNECTED)
        val patch = transform(base).patchFrom(base)
        if (patch.isEmpty()) return base
        val stored = decode(Settings.serializer(), core.request(Request.UpdateSettings(patch)))
        // The SETTINGS push travels apart from this reply. Wait for it: the next change starts
        // from it, and a switch does not jump back for a moment.
        withTimeoutOrNull(PUSH_WAIT) { core.settings.first { it == stored } }
        return stored
    }

    /** Identity, not equality: two equal lambdas are two changes. */
    private class Change(val transform: (Settings) -> Settings)

    private companion object {
        val FIRST_PUSH_WAIT = 10.seconds
        val PUSH_WAIT = 2.seconds
    }
}
