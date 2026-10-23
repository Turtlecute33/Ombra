package app.ombra.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.data.InstalledApp
import app.ombra.ui.locations.searchKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the app picker. [label] is null for a chosen app that is not installed (any more). */
@Immutable
data class AppRow(val packageName: String, val label: String?, val selected: Boolean)

/**
 * The rows of the picker. Chosen apps come first, so the list shows at a glance what the split
 * tunnel affects; a chosen app that is gone stays until the user removes it. System apps show only
 * with [showSystem] (or when chosen). [query] matches the label or the package name, case and
 * accents ignored.
 */
fun appRows(installed: List<InstalledApp>, selected: Set<String>, query: String, showSystem: Boolean): List<AppRow> {
    val key = searchKey(query.trim())
    val matches = { label: String?, packageName: String ->
        key.isEmpty() || (label != null && key in searchKey(label)) || key in packageName.lowercase()
    }
    val known = installed.associateBy { it.packageName }
    val chosen = installed.filter { it.packageName in selected }.map { AppRow(it.packageName, it.label, true) } +
        selected.filter { it !in known }.sorted().map { AppRow(it, null, true) }
    val others = installed.filter { it.packageName !in selected && (showSystem || !it.isSystem) }
        .map { AppRow(it.packageName, it.label, false) }
    return (chosen + others).filter { matches(it.label, it.packageName) }
}

/**
 * The app list of split tunnelling. It loads once, off the main thread; [loadApps] reads the
 * PackageManager. The choice itself lives in the settings ([selected]).
 */
class SplitTunnelViewModel(
    selected: Flow<Set<String>>,
    loadApps: suspend () -> List<InstalledApp>,
    /** Filters the list off the main thread. */
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val queryState = MutableStateFlow("")
    private val showSystemState = MutableStateFlow(false)

    /** The search text, for the text field (a snapshot state, so typing stays in step). */
    var query by mutableStateOf("")
        private set

    var showSystem by mutableStateOf(false)
        private set

    /** Null while the app list loads. */
    val rows: StateFlow<List<AppRow>?> = combine(installed, selected, queryState, showSystemState) { apps, chosen, query, system ->
        apps?.let { appRows(it, chosen, query, system) }
    }.flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { installed.value = loadApps() }
    }

    fun updateQuery(text: String) {
        query = text
        queryState.value = text
    }

    fun showSystemApps(show: Boolean) {
        showSystem = show
        showSystemState.value = show
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
