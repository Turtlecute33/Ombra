package app.ombra.ui.settings

import android.content.Context
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.ombra.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Where the text of a licence is packaged. */
sealed interface LicenceSource {
    data class Raw(@RawRes val id: Int) : LicenceSource

    data class Asset(val path: String) : LicenceSource
}

/**
 * A licence text that ships in the APK. Prose texts are [reflowed]: their hard line breaks would
 * leave ragged lines on a phone. A list keeps its lines.
 */
enum class Licence(@StringRes val title: Int, val source: LicenceSource, val reflowed: Boolean = true) {
    /** A copy of the repository LICENSE: Ombra and the Obscura core. */
    Gpl3(R.string.licence_gpl, LicenceSource.Raw(R.raw.licence_gpl_3_0)),
    Apache2(R.string.licence_apache, LicenceSource.Raw(R.raw.licence_apache_2_0)),
    Manrope(R.string.licence_ofl, LicenceSource.Asset("licenses/fonts/Manrope-OFL.txt")),
    JetBrainsMono(R.string.licence_ofl, LicenceSource.Asset("licenses/fonts/JetBrainsMono-OFL.txt")),

    /** Written by the Rust build; absent from builds without it. */
    RustCrates(R.string.licence_rust_crates, LicenceSource.Asset("licenses/rust-crates.txt"), reflowed = false),

    /** Made by scripts/rust-notices.zsh: the notices and texts that MIT, BSD, ISC and other crates ask for. */
    RustNotices(R.string.licence_rust_notices, LicenceSource.Asset("licenses/rust-notices.txt"), reflowed = false),
}

/** A part of Ombra and its licence. [name] is a proper name and stays untranslated. */
@Immutable
data class Component(val name: String, @StringRes val detail: Int, val licence: Licence)

val COMPONENTS: List<Component> = listOf(
    Component("Ombra", R.string.component_ombra, Licence.Gpl3),
    Component("Obscura VPN client", R.string.component_obscura, Licence.Gpl3),
    Component("Rust crates", R.string.component_rust_crates, Licence.RustCrates),
    Component("Rust crate notices", R.string.component_rust_notices, Licence.RustNotices),
    Component("Jetpack Compose", R.string.component_compose, Licence.Apache2),
    Component("AndroidX", R.string.component_androidx, Licence.Apache2),
    Component("Kotlin", R.string.component_kotlin, Licence.Apache2),
    Component("Material Symbols", R.string.component_symbols, Licence.Apache2),
    Component("Manrope", R.string.component_manrope, Licence.Manrope),
    Component("JetBrains Mono", R.string.component_jetbrains_mono, Licence.JetBrainsMono),
)

/** True when the APK has the text of [licence]. */
suspend fun Context.hasLicence(licence: Licence): Boolean = withContext(Dispatchers.IO) {
    when (val source = licence.source) {
        is LicenceSource.Raw -> true
        is LicenceSource.Asset -> {
            val directory = source.path.substringBeforeLast('/')
            assets.list(directory).orEmpty().contains(source.path.substringAfterLast('/'))
        }
    }
}

/** The text of [licence] in paragraphs, or null when the APK does not have it. */
suspend fun Context.licenceParagraphs(licence: Licence): List<String>? = withContext(Dispatchers.IO) {
    val text = try {
        when (val source = licence.source) {
            is LicenceSource.Raw -> resources.openRawResource(source.id)
            is LicenceSource.Asset -> assets.open(source.path)
        }.bufferedReader().use { it.readText() }
    } catch (e: FileNotFoundException) {
        return@withContext null
    }
    paragraphs(text, licence.reflowed)
}

/**
 * The paragraphs of a plain text (split at blank lines); a lazy list of them scrolls smoothly where
 * one huge text would not. With [reflow], the lines of a paragraph join into one.
 */
fun paragraphs(text: String, reflow: Boolean): List<String> =
    text.replace("\r\n", "\n").split(BLANK_LINES)
        .map { paragraph ->
            if (reflow) paragraph.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ") else paragraph.trimEnd()
        }
        .filter { it.isNotBlank() }

private val BLANK_LINES = Regex("\n[ \t]*\n+")
