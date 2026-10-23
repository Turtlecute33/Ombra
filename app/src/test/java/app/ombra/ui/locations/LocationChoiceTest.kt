package app.ombra.ui.locations

import app.ombra.core.model.ExitSelector
import app.ombra.core.model.PinnedLocation
import app.ombra.core.model.RelaySelector
import app.ombra.ipc.Settings
import app.ombra.ui.nav.LocationSide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationChoiceTest {
    private val choices = listOf(Choice.Fastest, Choice.Country("se"), Choice.City("se", "sto"), Choice.Server("se-sto-001"))

    @Test
    fun choicesBuildTheSelectorOfEachLevel() {
        assertEquals(
            listOf(ExitSelector.Any, ExitSelector.Country("se"), ExitSelector.City("se", "sto"), ExitSelector.Exit("se-sto-001")),
            choices.map { it.toExitSelector() },
        )
        assertEquals(
            listOf(RelaySelector.Any, RelaySelector.Country("se"), RelaySelector.City("se", "sto"), RelaySelector.Relay("se-sto-001")),
            choices.map { it.toRelaySelector() },
        )
        assertEquals(choices, choices.map { it.toExitSelector().toChoice() })
        assertEquals(choices, choices.map { it.toRelaySelector().toChoice() })
    }

    @Test
    fun aChoiceChangesOnlyItsSide() {
        val base = Settings(relay = RelaySelector.Relay("r1"))
        val exit = base.withChoice(LocationSide.Exit, Choice.City("ch", "zrh"))
        assertEquals(ExitSelector.City("ch", "zrh"), exit.exit)
        assertEquals(RelaySelector.Relay("r1"), exit.relay)
        assertEquals(Choice.City("ch", "zrh"), exit.choice(LocationSide.Exit))

        val entry = base.withChoice(LocationSide.Entry, Choice.Fastest)
        assertEquals(RelaySelector.Any, entry.relay)
        assertEquals(ExitSelector.Any, entry.exit)
    }

    @Test
    fun theSettingsPatchHoldsTheRustSelector() {
        val base = Settings()
        assertEquals(
            json("""{"exit":{"city":{"country_code":"ch","city_code":"zrh"}}}"""),
            base.withChoice(LocationSide.Exit, Choice.City("ch", "zrh")).patchFrom(base),
        )
        assertEquals(
            json("""{"relay":{"relay":{"id":"de-fra-r1"}}}"""),
            base.withChoice(LocationSide.Entry, Choice.Server("de-fra-r1")).patchFrom(base),
        )
        assertEquals(
            json("""{"exit":{"country":{"country_code":"se"}}}"""),
            base.withChoice(LocationSide.Exit, Choice.Country("se")).patchFrom(base),
        )
    }

    @Test
    fun exitPinsAreCitiesWithTheirTime() {
        val pinned = Settings().togglePin(LocationSide.Exit, Choice.City("se", "sto"), nowSeconds = 100)
        assertEquals(listOf(PinnedLocation("se", "sto", 100)), pinned.pinnedExits)
        // Only cities pin on the exit side.
        assertEquals(pinned, pinned.togglePin(LocationSide.Exit, Choice.Server("se-sto-001"), nowSeconds = 200))
        assertEquals(emptyList(), pinned.togglePin(LocationSide.Exit, Choice.City("se", "sto"), nowSeconds = 300).pinnedExits)
    }

    @Test
    fun unpinningACityKeepsTheSameCityCodeInAnotherCountry() {
        // Upstream removed every pin with the same city code (LocationView.tsx:76-79).
        val settings = Settings(pinnedExits = listOf(PinnedLocation("us", "par", 1), PinnedLocation("fr", "par", 2)))
        val unpinned = settings.togglePin(LocationSide.Exit, Choice.City("fr", "par"), nowSeconds = 3)
        assertEquals(listOf(PinnedLocation("us", "par", 1)), unpinned.pinnedExits)
    }

    @Test
    fun pinsListInTheOrderTheyWereMade() {
        val settings = Settings(pinnedExits = listOf(PinnedLocation("se", "sto", 20), PinnedLocation("ch", "zrh", 10)))
        assertEquals(listOf(Choice.City("ch", "zrh"), Choice.City("se", "sto")), settings.pins(LocationSide.Exit))
    }

    @Test
    fun entryPinsAreRelayIds() {
        val pinned = Settings().togglePin(LocationSide.Entry, Choice.Server("r1"), nowSeconds = 0)
            .togglePin(LocationSide.Entry, Choice.Server("r2"), nowSeconds = 0)
        assertEquals(listOf("r1", "r2"), pinned.pinnedRelays)
        assertEquals(listOf(Choice.Server("r1"), Choice.Server("r2")), pinned.pins(LocationSide.Entry))
        assertEquals(listOf("r2"), pinned.togglePin(LocationSide.Entry, Choice.Server("r1"), nowSeconds = 0).pinnedRelays)
        assertEquals(pinned, pinned.togglePin(LocationSide.Entry, Choice.City("de", "fra"), nowSeconds = 0))
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
