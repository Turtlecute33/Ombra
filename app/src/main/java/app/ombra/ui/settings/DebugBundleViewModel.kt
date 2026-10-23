package app.ombra.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.createDebugBundle
import kotlinx.coroutines.launch

/** The steps of the debug bundle page. */
@Immutable
sealed interface BundleStep {
    /** The page explains what the bundle does; nothing ran yet. */
    data object Consent : BundleStep

    data object Creating : BundleStep

    /** The zip at [path] (in the app cache) is ready to share. */
    data class Ready(val path: String) : BundleStep

    data class Failed(val code: String) : BundleStep
}

/** Creates a debug bundle only after the user agreed on the consent page. */
class DebugBundleViewModel(private val core: CoreSource) : ViewModel() {
    var step by mutableStateOf<BundleStep>(BundleStep.Consent)
        private set

    /** The user agreed: the core probes the network and collects logs, which can take minutes. */
    fun create(feedback: String) {
        if (step == BundleStep.Creating) return
        step = BundleStep.Creating
        viewModelScope.launch {
            step = try {
                BundleStep.Ready(core.createDebugBundle(feedback.trim().ifEmpty { null }))
            } catch (e: CoreRequestException) {
                BundleStep.Failed(e.code)
            }
        }
    }
}
