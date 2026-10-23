package app.ombra.ui.locations

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ombra.core.model.VpnStatus
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ui.components.countryName
import app.ombra.ui.nav.LocationSide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator

/** Why a side shows no locations. */
enum class ListStatus {
    /** The core has no list yet (not loaded, or a core without the relay list). */
    Missing,

    /** The list came, without any server. */
    Empty,

    /** The search finds nothing. */
    NoMatch,
    Ready,
}

@Immutable
data class LocationsUiState(
    val side: LocationSide,
    val rows: List<LocationRow> = emptyList(),
    val status: ListStatus = ListStatus.Missing,
)

/**
 * The exit and entry relay picker. A choice is stored in the settings at once; while connected,
 * the core then reconnects with it. Nothing here calls the network unless the user asks to load
 * the lists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationsViewModel(
    private val core: CoreSource,
    private val editor: SettingsEditor,
    initialSide: LocationSide,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Builds the tree and the rows, off the main thread. */
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val sideState = MutableStateFlow(initialSide)
    private val queryState = MutableStateFlow("")
    private val toggled = MutableStateFlow(LocationSide.entries.associateWith { emptySet<String>() })

    /** The search text, for the text field (a snapshot state, so typing stays in step). */
    var query by mutableStateOf("")
        private set

    var refreshing by mutableStateOf(false)
        private set

    /** Code of the last failed request (see [app.ombra.ui.requestErrorText]). */
    var errorCode by mutableStateOf<String?>(null)
        private set

    private val exitTree = core.exits
        .map { list -> list?.value?.exits?.map { it.toServer() } }
        .distinctUntilChanged()
        .map { servers -> servers?.let(::tree) }

    private val relayTree = core.relays
        .map { list -> list?.value?.map { it.toServer() } }
        .distinctUntilChanged()
        .map { servers -> servers?.let(::tree) }

    val state: StateFlow<LocationsUiState> = sideState
        .flatMapLatest(::sideUiState)
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LocationsUiState(initialSide))

    fun selectSide(side: LocationSide) {
        sideState.value = side
    }

    fun updateQuery(text: String) {
        query = text
        queryState.value = text
    }

    fun toggleFold(key: String) = toggled.update { all ->
        val side = sideState.value
        val keys = all.getValue(side)
        all + (side to if (key in keys) keys - key else keys + key)
    }

    /** Stores [choice] for the current side; [onDone] runs once the core has it. */
    fun choose(choice: Choice, onDone: () -> Unit) {
        val side = sideState.value
        launchRequest {
            editor.update { it.withChoice(side, choice) }
            onDone()
        }
    }

    fun togglePin(choice: Choice) {
        val side = sideState.value
        launchRequest { editor.update { it.togglePin(side, choice, nowSeconds()) } }
    }

    /** Asks the Obscura API for new lists; only on user action. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        launchRequest {
            try {
                core.request(Request.RefreshLists)
            } finally {
                refreshing = false
            }
        }
    }

    fun errorShown() {
        errorCode = null
    }

    private fun sideUiState(side: LocationSide): Flow<LocationsUiState> {
        val inUseId = core.status.map { status ->
            (status?.vpnStatus as? VpnStatus.Connected)?.let { if (side == LocationSide.Exit) it.exit.id else it.relay.id }
        }
        val picker = combine(queryState, toggled, editor.settings, inUseId) { query, toggled, settings, inUse ->
            val chosen = settings ?: Settings()
            Picker(
                query = query,
                toggled = toggled.getValue(side),
                choice = chosen.choice(side),
                pins = chosen.pins(side),
                pinCities = side == LocationSide.Exit,
                inUseId = inUse,
            )
        }
        val tree = if (side == LocationSide.Exit) exitTree else relayTree
        return combine(tree, picker) { countries, current -> uiState(side, countries, current) }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            errorCode = null
            try {
                block()
            } catch (e: CoreRequestException) {
                errorCode = e.code
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun tree(servers: List<Server>): List<CountryGroup> =
            locationTree(servers, { countryName(it) }, Collator.getInstance())

        fun uiState(side: LocationSide, countries: List<CountryGroup>?, picker: Picker): LocationsUiState {
            val rows = locationRows(countries.orEmpty(), picker)
            val status = when {
                countries == null -> ListStatus.Missing
                countries.isEmpty() -> ListStatus.Empty
                picker.query.isNotBlank() && rows.isEmpty() -> ListStatus.NoMatch
                else -> ListStatus.Ready
            }
            return LocationsUiState(side, rows, status)
        }
    }
}
