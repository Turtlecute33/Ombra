package app.ombra.ui.components

import java.util.Locale

/** The flag emoji of an ISO 3166 alpha-2 code ("se" -> 🇸🇪); empty for anything else. */
fun flagEmoji(countryCode: String): String {
    val code = isoRegion(countryCode) ?: return ""
    return code.map { Character.toChars(REGIONAL_INDICATOR_A + (it - 'A')).concatToString() }.joinToString("")
}

/** Country name in the user's language; the upper-case code when the platform does not know it. */
fun countryName(countryCode: String, locale: Locale = Locale.getDefault()): String {
    val code = isoRegion(countryCode) ?: return countryCode.uppercase(Locale.ROOT)
    return Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifEmpty { code }
}

/** Obscura uses lower-case ISO codes; "uk" is a common alias of "gb". */
private fun isoRegion(countryCode: String): String? {
    val code = countryCode.uppercase(Locale.ROOT).let { if (it == "UK") "GB" else it }
    return code.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6
