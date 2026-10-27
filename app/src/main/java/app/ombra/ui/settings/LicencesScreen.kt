package app.ombra.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ombra.R
import app.ombra.ui.components.EmptyState
import app.ombra.ui.components.Icons
import app.ombra.ui.components.LoadingState
import app.ombra.ui.components.NavRow
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SubScreen
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/** Each part of Ombra with its licence; a row opens the licence text. */
@Composable
fun LicencesScreen(onBack: () -> Unit, onOpen: (Licence) -> Unit) {
    val context = LocalContext.current
    // The Rust crate files are assets that a build can lack: show them once hasLicence finds them.
    val components by produceState(
        COMPONENTS.filter { it.licence != Licence.RustCrates && it.licence != Licence.RustNotices },
    ) {
        value = COMPONENTS.filter { context.hasLicence(it.licence) }
    }
    SettingsPage(stringResource(R.string.about_licences), onBack) {
        GroupNote(stringResource(R.string.licences_intro), Modifier.padding(bottom = Spacing.m))
        RowGroup {
            components.forEach { component ->
                NavRow(
                    title = component.name,
                    supporting = stringResource(R.string.licences_row, stringResource(component.detail), stringResource(component.licence.title)),
                    onClick = { onOpen(component.licence) },
                )
            }
        }
    }
}

/** The full text of one licence, as a lazy list of paragraphs. */
@Composable
fun LicenceTextScreen(licence: Licence, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<LicenceLoad>(LicenceLoad.Loading, licence) {
        value = context.licenceParagraphs(licence)?.let(LicenceLoad::Loaded) ?: LicenceLoad.Missing
    }
    SubScreen(title = stringResource(licence.title), onBack = onBack) { padding ->
        when (val load = text) {
            LicenceLoad.Loading -> LoadingState(Modifier.padding(padding))
            LicenceLoad.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Document, title = stringResource(R.string.licences_missing))
            }
            is LicenceLoad.Loaded -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + Spacing.xl,
                ),
            ) {
                items(load.paragraphs, contentType = { "paragraph" }) { paragraph ->
                    Text(
                        paragraph,
                        style = if (licence.reflowed) MaterialTheme.typography.bodyMedium else OmbraTextStyles.mono,
                        modifier = Modifier.padding(bottom = Spacing.m),
                    )
                }
            }
        }
    }
}

private sealed interface LicenceLoad {
    data object Loading : LicenceLoad

    data object Missing : LicenceLoad

    data class Loaded(val paragraphs: List<String>) : LicenceLoad
}
