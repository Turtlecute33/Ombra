package app.ombra.vpn

import app.ombra.ipc.Settings
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun corruptFileGivesDefaults() {
        File(tmp.root, "settings.json").writeText("{not json")
        assertEquals(Settings(), SettingsStore(tmp.root).current)
    }

    @Test
    fun updateThatChangesNothingDoesNotWrite() {
        val store = SettingsStore(tmp.root)
        store.update { it }
        assertEquals(false, File(tmp.root, "settings.json").exists())

        store.update { it.copy(padding = true) }
        val file = File(tmp.root, "settings.json")
        file.setLastModified(1_000)
        store.update { it.copy(padding = true) }
        assertEquals(1_000, file.lastModified())
    }

    @Test
    fun failedWriteThrowsAndKeepsTheValue() {
        val store = SettingsStore(tmp.root)
        // A directory in the place of the temp file makes the write fail.
        File(tmp.root, "settings.json.tmp").mkdir()
        assertFailsWith<java.io.IOException> { store.update { it.copy(padding = true) } }
        assertEquals(Settings(), store.current)
    }
}
