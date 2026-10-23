package app.ombra.ui.locations

import androidx.compose.runtime.Immutable
import app.ombra.core.model.ExitSelector
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.ipc.Settings
import app.ombra.ui.components.GroupPosition
import app.ombra.ui.nav.LocationSide
import java.text.Normalizer
import java.util.Locale

/** One exit or relay server, with what the picker shows of it. */
@Immutable
data class Server(
    val id: String,
    val countryCode: String,
    val cityCode: String,
    val cityName: String,
    /** Exit operator; null for relays. */
    val provider: String? = null,
    val datacenterId: Long? = null,
    val tier: Int? = null,
    /** Relay address; null for exits. */
    val address: String? = null,
)

fun OneExit.toServer() = Server(
    id = id,
    countryCode = countryCode,
    cityCode = cityCode,
    cityName = cityName,
    provider = providerName.ifBlank { null },
    datacenterId = datacenterId,
    tier = tier,
)

fun OneRelay.toServer() = Server(id, countryCode, cityCode, cityName, address = ipV4.ifBlank { null })

@Immutable
data class CityGroup(val countryCode: String, val code: String, val name: String, val servers: List<Server>)

@Immutable
data class CountryGroup(val code: String, val name: String, val cities: List<CityGroup>) {
    val serverCount: Int get() = cities.sumOf { it.servers.size }
}

/** Countries by name, cities by name, servers by id. [countryName] gives the name in the user's language. */
fun locationTree(servers: List<Server>, countryName: (String) -> String, order: Comparator<in String>): List<CountryGroup> =
    servers.groupBy { it.countryCode }
        .map { (code, inCountry) ->
            val cities = inCountry.groupBy { it.cityCode }
                .map { (cityCode, inCity) -> CityGroup(code, cityCode, inCity.first().cityName, inCity.sortedBy { it.id }) }
                .sortedWith(compareBy(order) { it.name })
            CountryGroup(code, countryName(code), cities)
        }
        .sortedWith(compareBy(order) { it.name })

/** What the user chose on one side, at the level they chose it. */
sealed interface Choice {
    data object Fastest : Choice

    data class Country(val countryCode: String) : Choice

    data class City(val countryCode: String, val cityCode: String) : Choice

    data class Server(val id: String) : Choice
}

fun ExitSelector.toChoice(): Choice = when (this) {
    ExitSelector.Any -> Choice.Fastest
    is ExitSelector.Country -> Choice.Country(countryCode)
    is ExitSelector.City -> Choice.City(countryCode, cityCode)
    is ExitSelector.Exit -> Choice.Server(id)
}

fun RelaySelector.toChoice(): Choice = when (this) {
    RelaySelector.Any -> Choice.Fastest
    is RelaySelector.Country -> Choice.Country(countryCode)
    is RelaySelector.City -> Choice.City(countryCode, cityCode)
    is RelaySelector.Relay -> Choice.Server(id)
}

fun Choice.toExitSelector(): ExitSelector = when (this) {
    Choice.Fastest -> ExitSelector.Any
    is Choice.Country -> ExitSelector.Country(countryCode)
    is Choice.City -> ExitSelector.City(countryCode, cityCode)
    is Choice.Server -> ExitSelector.Exit(id)
}

fun Choice.toRelaySelector(): RelaySelector = when (this) {
    Choice.Fastest -> RelaySelector.Any
    is Choice.Country -> RelaySelector.Country(countryCode)
    is Choice.City -> RelaySelector.City(countryCode, cityCode)
    is Choice.Server -> RelaySelector.Relay(id)
}

/** The choice of [side] in [settings]. */
fun Settings.choice(side: LocationSide): Choice = when (side) {
    LocationSide.Exit -> exit.toChoice()
    LocationSide.Entry -> relay.toChoice()
}

/** [settings] with [choice] for [side]. A change while connected makes the core reconnect at once. */
fun Settings.withChoice(side: LocationSide, choice: Choice): Settings = when (side) {
    LocationSide.Exit -> copy(exit = choice.toExitSelector())
    LocationSide.Entry -> copy(relay = choice.toRelaySelector())
}

/**
 * Pins in the order they were made. Exits pin cities (the upstream `PinnedLocation` shape, synced
 * to the core); relays pin single servers (`Settings.pinnedRelays`).
 */
fun Settings.pins(side: LocationSide): List<Choice> = when (side) {
    LocationSide.Exit -> pinnedExits.sortedBy { it.pinnedAt }.map { Choice.City(it.countryCode, it.cityCode) }
    LocationSide.Entry -> pinnedRelays.map { Choice.Server(it) }
}

/** Pins or unpins [choice]: a city on the exit side, a server on the entry side. [nowSeconds] stamps a new exit pin. */
fun Settings.togglePin(side: LocationSide, choice: Choice, nowSeconds: Long): Settings = when {
    side == LocationSide.Exit && choice is Choice.City -> copy(pinnedExits = pinnedExits.toggle(choice, nowSeconds))
    side == LocationSide.Entry && choice is Choice.Server ->
        copy(pinnedRelays = if (choice.id in pinnedRelays) pinnedRelays - choice.id else pinnedRelays + choice.id)
    else -> this
}

// Upstream compared the country code with itself here, so unpinning one city removed every pin with
// the same city code (obscura-ui LocationView.tsx:76-79). Both codes must match.
private fun List<PinnedLocation>.toggle(city: Choice.City, nowSeconds: Long): List<PinnedLocation> {
    val isCity = { pin: PinnedLocation -> pin.countryCode == city.countryCode && pin.cityCode == city.cityCode }
    return if (any(isCity)) filterNot(isCity) else this + PinnedLocation(city.countryCode, city.cityCode, nowSeconds)
}

enum class Header { Pinned, Locations }

/** One row of the picker. [key] is stable and unique, so the lazy list keeps state and animates. */
@Immutable
sealed interface LocationRow {
    val key: String
}

@Immutable
data class HeaderRow(val header: Header) : LocationRow {
    override val key: String get() = "h:$header"
}

/** A row on a rounded group surface. */
sealed interface ItemRow : LocationRow {
    val position: GroupPosition
    val selected: Boolean
}

@Immutable
data class FastestRow(override val selected: Boolean) : ItemRow {
    override val key: String get() = "fastest"
    override val position: GroupPosition get() = GroupPosition.Single
}

/** [expanded] is null when the row cannot fold (while searching, countries always show their cities). */
@Immutable
data class CountryRow(
    val code: String,
    val name: String,
    val cities: Int,
    val servers: Int,
    val expanded: Boolean?,
    override val selected: Boolean,
    val inUse: Boolean,
    override val position: GroupPosition,
) : ItemRow {
    override val key: String get() = countryKey(code)
}

/** [pinned] is null when cities cannot be pinned on this side; [pinnedSection] rows show their country. */
@Immutable
data class CityRow(
    val countryCode: String,
    val code: String,
    val name: String,
    val servers: Int,
    val expanded: Boolean?,
    override val selected: Boolean,
    val inUse: Boolean,
    val pinned: Boolean?,
    val pinnedSection: Boolean,
    override val position: GroupPosition,
) : ItemRow {
    override val key: String get() = (if (pinnedSection) "p" else "") + cityKey(countryCode, code)
}

@Immutable
data class ServerRow(
    val server: Server,
    override val selected: Boolean,
    val inUse: Boolean,
    val pinned: Boolean?,
    val pinnedSection: Boolean,
    override val position: GroupPosition,
) : ItemRow {
    override val key: String get() = (if (pinnedSection) "p" else "") + "s:${server.id}"
}

fun countryKey(code: String) = "c:$code"

fun cityKey(countryCode: String, cityCode: String) = "t:$countryCode/$cityCode"

/** Keys of the country and city that hold [choice]: they start unfolded, so the choice is in view. */
fun pathTo(choice: Choice, countries: List<CountryGroup>): Set<String> = when (choice) {
    Choice.Fastest, is Choice.Country -> emptySet()
    is Choice.City -> setOf(countryKey(choice.countryCode))
    is Choice.Server -> countries.asSequence().flatMap { it.cities }
        .firstOrNull { city -> city.servers.any { it.id == choice.id } }
        ?.let { setOf(countryKey(it.countryCode), cityKey(it.countryCode, it.code)) }
        .orEmpty()
}

/** Inputs of [locationRows] that change with user actions. */
@Immutable
data class Picker(
    val query: String = "",
    /** Rows whose fold state the user flipped; the default is folded, except the path to [choice]. */
    val toggled: Set<String> = emptySet(),
    val choice: Choice = Choice.Fastest,
    val pins: List<Choice> = emptyList(),
    /** True on the exit side, which pins cities; the entry side pins servers. */
    val pinCities: Boolean = true,
    /** Server of the live tunnel on this side. */
    val inUseId: String? = null,
)

/**
 * The rows of one side. Without a query: Fastest, the pins, then countries that unfold to cities
 * and cities that unfold to servers. With a query (case and accents ignored): the countries whose
 * name or code match with all their cities, and the cities whose name or code match.
 */
fun locationRows(countries: List<CountryGroup>, picker: Picker): List<LocationRow> = RowBuilder(countries, picker).rows()

private class RowBuilder(private val countries: List<CountryGroup>, private val picker: Picker) {
    private val query = searchKey(picker.query.trim())
    private val cities = countries.flatMap { it.cities }
    private val inUseCity = picker.inUseId?.let { id -> cities.firstOrNull { city -> city.servers.any { it.id == id } } }
    private val unfolded = pathTo(picker.choice, countries)

    fun rows(): List<LocationRow> = buildList {
        if (query.isEmpty()) {
            add(FastestRow(selected = picker.choice == Choice.Fastest))
            val pinned = picker.pins.flatMap(::pinnedRows)
            if (pinned.isNotEmpty()) {
                add(HeaderRow(Header.Pinned))
                addAll(pinned.positioned())
            }
        }
        val tree = countries.flatMap(::countryRows)
        if (tree.isNotEmpty()) {
            if (query.isEmpty()) add(HeaderRow(Header.Locations))
            addAll(tree.positioned())
        }
    }

    private fun isOpen(key: String): Boolean = (key in unfolded) != (key in picker.toggled)

    private fun countryRows(country: CountryGroup): List<ItemRow> {
        val countryMatches = query.isEmpty() || matches(country.name, country.code)
        val shown = if (countryMatches) country.cities else country.cities.filter { matches(it.name, it.code) }
        if (shown.isEmpty()) return emptyList()
        val open = query.isNotEmpty() || isOpen(countryKey(country.code))
        val row = CountryRow(
            code = country.code,
            name = country.name,
            cities = country.cities.size,
            servers = country.serverCount,
            expanded = if (query.isEmpty()) open else null,
            selected = picker.choice == Choice.Country(country.code),
            inUse = inUseCity?.countryCode == country.code,
            position = GroupPosition.Middle,
        )
        return if (open) listOf(row) + shown.flatMap { cityRows(it, pinnedSection = false) } else listOf(row)
    }

    private fun cityRows(city: CityGroup, pinnedSection: Boolean): List<ItemRow> {
        val open = !pinnedSection && isOpen(cityKey(city.countryCode, city.code))
        val row = CityRow(
            countryCode = city.countryCode,
            code = city.code,
            name = city.name,
            servers = city.servers.size,
            expanded = if (pinnedSection) null else open,
            selected = picker.choice == Choice.City(city.countryCode, city.code),
            inUse = inUseCity == city,
            pinned = if (picker.pinCities) Choice.City(city.countryCode, city.code) in picker.pins else null,
            pinnedSection = pinnedSection,
            position = GroupPosition.Middle,
        )
        return if (open) listOf(row) + city.servers.map { serverRow(it, pinnedSection = false) } else listOf(row)
    }

    private fun serverRow(server: Server, pinnedSection: Boolean) = ServerRow(
        server = server,
        selected = picker.choice == Choice.Server(server.id),
        inUse = server.id == picker.inUseId,
        pinned = if (picker.pinCities) null else Choice.Server(server.id) in picker.pins,
        pinnedSection = pinnedSection,
        position = GroupPosition.Middle,
    )

    /** Pins whose city or server is not in the list (any more) show nothing. */
    private fun pinnedRows(pin: Choice): List<ItemRow> = when (pin) {
        is Choice.City -> cities.firstOrNull { it.countryCode == pin.countryCode && it.code == pin.cityCode }
            ?.let { cityRows(it, pinnedSection = true) }.orEmpty()
        is Choice.Server -> cities.asSequence().flatMap { it.servers }.firstOrNull { it.id == pin.id }
            ?.let { listOf(serverRow(it, pinnedSection = true)) }.orEmpty()
        Choice.Fastest, is Choice.Country -> emptyList()
    }

    private fun matches(name: String, code: String): Boolean = query in searchKey(name) || searchKey(code).startsWith(query)
}

private fun List<ItemRow>.positioned(): List<ItemRow> = mapIndexed { index, row ->
    val position = GroupPosition.of(index, size)
    when (row) {
        is CountryRow -> row.copy(position = position)
        is CityRow -> row.copy(position = position)
        is ServerRow -> row.copy(position = position)
        is FastestRow -> row
    }
}

/** Lower case without accents, so "zurich" finds "Zürich". */
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")
