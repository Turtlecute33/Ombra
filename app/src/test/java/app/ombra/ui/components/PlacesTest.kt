package app.ombra.ui.components

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacesTest {
    @Test
    fun flagsUseRegionalIndicators() {
        assertEquals("🇸🇪", flagEmoji("se"))
        assertEquals("🇬🇧", flagEmoji("uk"))
        assertEquals("", flagEmoji("xyz"))
        assertEquals("", flagEmoji("1a"))
    }

    @Test
    fun countryNamesAreLocalised() {
        assertEquals("Sweden", countryName("se", Locale.ENGLISH))
        assertEquals("Schweden", countryName("se", Locale.GERMAN))
        assertEquals("United Kingdom", countryName("uk", Locale.ENGLISH))
        assertEquals("XYZ", countryName("xyz", Locale.ENGLISH))
    }
}
