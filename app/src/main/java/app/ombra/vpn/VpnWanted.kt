package app.ombra.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

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
            try {
                if (value) file.createNewFile() else file.delete()
            } catch (e: IOException) {
                // For example a full disk. Do not crash :vpn. The VPN stays on in this process;
                // only the restore after a process death is lost.
                Log.w(TAG, "cannot create the marker file: ${e.javaClass.simpleName}")
            }
            state.value = value
        }

    companion object {
        const val FILE_NAME = "vpn_wanted"
        private const val TAG = "OmbraVpnWanted"

        /** Reads the flag in [dir] without an instance, for a component that only checks it once. */
        fun isSet(dir: File): Boolean = File(dir, FILE_NAME).exists()
    }
}
