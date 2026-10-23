package app.ombra.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * "The user wants the VPN on", kept as the existence of [file] (in noBackupFilesDir) so that it
 * survives process death. Read on a sticky or always-on restart of OmbraVpnService and after an
 * app update. Only this object writes the file, so [flow] and the file agree.
 */
class VpnWanted(private val file: File) {
    private val state = MutableStateFlow(file.exists())

    val flow: StateFlow<Boolean> = state.asStateFlow()

    var value: Boolean
        get() = state.value

        @Synchronized
        set(value) {
            if (value) file.createNewFile() else file.delete()
            state.value = value
        }

    companion object {
        const val FILE_NAME = "vpn_wanted"

        /** Reads the flag in [dir] without an instance, for a component that only checks it once. */
        fun isSet(dir: File): Boolean = File(dir, FILE_NAME).exists()
    }
}
