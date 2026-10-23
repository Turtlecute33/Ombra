package app.ombra.data

import app.ombra.core.model.PinnedLocation
import app.ombra.ipc.IpcError
import app.ombra.ipc.Request
import app.ombra.ui.FakeCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEditorTest {
    private val core = FakeCore()

    /** Holds UpdateSettings until [release] completes, so a test can look at the state in between. */
    private class SlowCore(private val core: FakeCore, val release: CompletableDeferred<Unit>) : CoreSource by core {
        override suspend fun request(request: Request): String {
            release.await()
            return core.request(request)
        }
    }

    private fun TestScope.editor(source: CoreSource = core): SettingsEditor {
        val editor = SettingsEditor(source, backgroundScope)
        backgroundScope.launch { editor.settings.collect {} }
        return editor
    }

    @Test
    fun aChangeSendsOnlyTheChangedKeys() = runTest {
        val stored = editor().update { it.copy(lanAccess = true) }
        assertEquals(true, stored.lanAccess)
        assertEquals(listOf<Request>(Request.UpdateSettings(json("""{"lanAccess":true}"""))), core.requests)
    }

    @Test
    fun aChangeShowsBeforeTheCoreConfirmsIt() = runTest {
        val release = CompletableDeferred<Unit>()
        val editor = editor(SlowCore(core, release))
        val change = async { editor.update { it.copy(padding = true) } }
        runCurrent()
        assertEquals(true, editor.settings.value?.padding)
        assertEquals(false, core.settings.value?.padding)

        release.complete(Unit)
        change.await()
        runCurrent()
        assertEquals(true, core.settings.value?.padding)
        assertEquals(true, editor.settings.value?.padding)
    }

    @Test
    fun twoQuickChangesToOneListBothLand() = runTest {
        val editor = editor()
        val first = async { editor.update { it.copy(pinnedExits = it.pinnedExits + PinnedLocation("se", "sto", 1)) } }
        val second = async { editor.update { it.copy(pinnedExits = it.pinnedExits + PinnedLocation("ch", "zrh", 2)) } }
        first.await()
        second.await()
        assertEquals(listOf("sto", "zrh"), core.settings.value?.pinnedExits?.map { it.cityCode })
    }

    @Test
    fun aFailedChangeIsUndone() = runTest {
        val editor = editor()
        core.failures[Request.UpdateSettings(json("""{"lanAccess":true}"""))] = SAVE_ERROR
        val error = assertFailsWith<CoreRequestException> { editor.update { it.copy(lanAccess = true) } }
        assertEquals(SAVE_ERROR, error.code)
        runCurrent()
        assertEquals(false, editor.settings.value?.lanAccess)
        assertTrue(backgroundScope.isActive, "a failure does not cancel the scope")
    }

    @Test
    fun aChangeWithoutEffectSendsNothing() = runTest {
        editor().update { it.copy() }
        assertEquals(emptyList(), core.requests)
    }

    @Test
    fun withoutSettingsFromTheCoreAChangeFails() = runTest {
        core.settings.value = null
        val editor = editor()
        val change = async { runCatching { editor.update { it.copy(lanAccess = true) } } }
        advanceUntilIdle()
        assertEquals(IpcError.DISCONNECTED, (change.await().exceptionOrNull() as CoreRequestException).code)
        assertEquals(null, editor.settings.value)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private companion object {
        /** The error of :vpn when settings.json cannot be written. */
        const val SAVE_ERROR = "configSaveError"
    }
}
