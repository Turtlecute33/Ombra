package app.ombra.ui.locations

import app.ombra.ui.components.GroupPosition
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationTreeTest {
    private val names = mapOf("ch" to "Switzerland", "de" to "Germany", "se" to "Sweden", "at" to "Austria")
    private val servers = listOf(
        exit("se-sto-002", "se", "sto", "Stockholm"),
        exit("de-fra-001", "de", "fra", "Frankfurt"),
        exit("se-sto-001", "se", "sto", "Stockholm"),
        exit("ch-zrh-001", "ch", "zrh", "Zürich"),
        exit("se-got-001", "se", "got", "Gothenburg"),
        exit("de-ber-001", "de", "ber", "Berlin"),
        exit("at-vie-001", "at", "vie", "Vienna"),
    )
    private val countries = locationTree(servers, { names.getValue(it) }, Collator.getInstance(Locale.ENGLISH))

    @Test
    fun countriesCitiesAndServersSortByNameAndId() {
        assertEquals(listOf("Austria", "Germany", "Sweden", "Switzerland"), countries.map { it.name })
        val sweden = countries.single { it.code == "se" }
        assertEquals(listOf("Gothenburg", "Stockholm"), sweden.cities.map { it.name })
        assertEquals(listOf("se-sto-001", "se-sto-002"), sweden.cities[1].servers.map { it.id })
        assertEquals(3, sweden.serverCount)
    }

    @Test
    fun withoutAQueryFastestComesFirstAndCountriesStartFolded() {
        val rows = locationRows(countries, Picker())
        assertEquals(FastestRow(selected = true), rows[0])
        assertEquals(HeaderRow(Header.Locations), rows[1])
        assertEquals(listOf("at", "de", "se", "ch"), rows.filterIsInstance<CountryRow>().map { it.code })
        assertTrue(rows.none { it is CityRow || it is ServerRow })
        assertTrue(rows.filterIsInstance<CountryRow>().all { it.expanded == false })
    }

    @Test
    fun theChoiceStartsUnfoldedAndAToggleFlipsAFold() {
        val chosen = locationRows(countries, Picker(choice = Choice.Server("se-sto-002")))
        assertEquals(listOf("se-sto-001", "se-sto-002"), chosen.filterIsInstance<ServerRow>().map { it.server.id })
        assertTrue(chosen.filterIsInstance<ServerRow>().single { it.selected }.server.id == "se-sto-002")
        assertEquals(listOf("got", "sto"), chosen.filterIsInstance<CityRow>().map { it.code })

        val closed = locationRows(countries, Picker(choice = Choice.Server("se-sto-002"), toggled = setOf(countryKey("se"))))
        assertTrue(closed.none { it is CityRow }, "the user folded the country of the choice")

        val opened = locationRows(countries, Picker(toggled = setOf(countryKey("de"), cityKey("de", "ber"))))
        assertEquals(listOf("ber", "fra"), opened.filterIsInstance<CityRow>().map { it.code })
        assertEquals(listOf("de-ber-001"), opened.filterIsInstance<ServerRow>().map { it.server.id })
    }

    @Test
    fun searchFindsCountriesCitiesAndCodesWithoutCaseOrAccents() {
        fun cities(query: String) = locationRows(countries, Picker(query = query)).filterIsInstance<CityRow>().map { it.code }

        assertEquals(listOf("zrh"), cities("zurich"))
        assertEquals(listOf("zrh"), cities("  ZÜRICH "))
        // A country that matches shows all its cities.
        assertEquals(listOf("got", "sto"), cities("swed"))
        // Country codes match from the start, city codes too.
        assertEquals(listOf("zrh"), cities("ch"))
        assertEquals(listOf("ber"), cities("ber"))
        assertEquals(emptyList(), cities("xyz"))
    }

    @Test
    fun aSearchHidesFastestAndPinsAndCannotFold() {
        val rows = locationRows(countries, Picker(query = "sto", pins = listOf(Choice.City("se", "sto"))))
        assertTrue(rows.none { it is FastestRow || it is HeaderRow })
        assertEquals(null, rows.filterIsInstance<CountryRow>().single().expanded)
        assertEquals(listOf("sto"), rows.filterIsInstance<CityRow>().map { it.code })
    }

    @Test
    fun pinsShowAboveTheTreeWithUniqueKeys() {
        val picker = Picker(pins = listOf(Choice.City("de", "fra"), Choice.City("xx", "gone")), toggled = setOf(countryKey("de")))
        val rows = locationRows(countries, picker)
        assertEquals(HeaderRow(Header.Pinned), rows[1])
        val pinned = rows[2] as CityRow
        assertEquals("fra" to true, pinned.code to pinned.pinnedSection)
        assertEquals(true, pinned.pinned)
        assertEquals(null, pinned.expanded, "a pin does not fold")
        assertEquals(HeaderRow(Header.Locations), rows[3], "a pin that is not in the list shows nothing")
        // Frankfurt shows twice (pinned and in the tree) under two keys.
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun entryPinsAreServers() {
        val picker = Picker(pins = listOf(Choice.Server("ch-zrh-001")), pinCities = false)
        val rows = locationRows(countries, picker)
        val pinned = rows[2] as ServerRow
        assertEquals("ch-zrh-001", pinned.server.id)
        assertEquals(true, pinned.pinned)
        val opened = locationRows(countries, picker.copy(toggled = setOf(countryKey("ch"))))
        assertTrue(opened.filterIsInstance<CityRow>().all { it.pinned == null }, "cities cannot be pinned on the entry side")
    }

    @Test
    fun theServerInUseMarksItsCityAndCountry() {
        val rows = locationRows(countries, Picker(inUseId = "de-fra-001", toggled = setOf(countryKey("de"))))
        assertEquals(listOf("de"), rows.filterIsInstance<CountryRow>().filter { it.inUse }.map { it.code })
        assertEquals(listOf("fra"), rows.filterIsInstance<CityRow>().filter { it.inUse }.map { it.code })
    }

    @Test
    fun rowsKnowTheirPlaceInTheGroup() {
        val rows = locationRows(countries, Picker()).filterIsInstance<CountryRow>()
        assertEquals(
            listOf(GroupPosition.First, GroupPosition.Middle, GroupPosition.Middle, GroupPosition.Last),
            rows.map { it.position },
        )
        val single = locationRows(countries, Picker(query = "vienna")).filterIsInstance<ItemRow>()
        assertEquals(listOf(GroupPosition.First, GroupPosition.Last), single.map { it.position })
    }

    @Test
    fun anEmptyListHasOnlyFastest() {
        assertEquals(listOf<LocationRow>(FastestRow(selected = false)), locationRows(emptyList(), Picker(choice = Choice.Country("se"))))
    }

    private fun exit(id: String, country: String, city: String, name: String) =
        Server(id = id, countryCode = country, cityCode = city, cityName = name, provider = "Mullvad VPN", datacenterId = 1, tier = 1)
}
