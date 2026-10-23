package app.ombra.vpn

import android.util.Log
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** [Settings] as one JSON file in noBackupFilesDir, written atomically. :vpn process only. */
class SettingsStore(dir: File) {
    private val file = File(dir, FILE_NAME)
    private val state = MutableStateFlow(load())

    val settings: StateFlow<Settings> = state.asStateFlow()

    val current: Settings get() = state.value

    /** Applies [patch] (see [Settings.applyPatch]), persists, publishes. Throws on a bad patch. */
    fun update(patch: JsonObject): Settings = update { it.applyPatch(patch) }

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(state.value).normalized()
        if (next != state.value) {
            write(next)
            state.value = next
        }
        return next
    }

    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return try {
            IpcJson.decodeFromString(Settings.serializer(), file.readText()).normalized()
        } catch (e: Exception) {
            // A broken file must not keep the VPN process from starting; fall back to defaults.
            Log.e(TAG, "settings file unreadable, using defaults: ${e.javaClass.simpleName}")
            Settings()
        }
    }

    private fun write(settings: Settings) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
            it.write(IpcJson.encodeToString(Settings.serializer(), settings).toByteArray())
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val TAG = "OmbraSettings"
        const val FILE_NAME = "settings.json"
    }
}
