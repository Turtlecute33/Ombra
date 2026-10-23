package app.ombra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ombra.BuildConfig
import app.ombra.R
import app.ombra.ui.ObscuraLinks
import app.ombra.ui.OmbraLinks
import app.ombra.ui.components.Icons
import app.ombra.ui.components.ListRow
import app.ombra.ui.components.NavRow
import app.ombra.ui.components.OmbraMark
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.Sheet
import app.ombra.ui.openLink
import app.ombra.ui.theme.Spacing

/** Version, who sees what, credits, licences and the speed test tips. */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicences: () -> Unit) {
    val context = LocalContext.current
    var showTips by rememberSaveable { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.settings_about), onBack) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OmbraMark(size = 72.dp)
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(stringResource(R.string.about_who_sees))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        ) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Party(Icons.Relay, stringResource(R.string.about_party_relay), stringResource(R.string.about_party_relay_detail))
                Party(Icons.Globe, stringResource(R.string.about_party_exit), stringResource(R.string.about_party_exit_detail))
                Party(Icons.Device, stringResource(R.string.about_party_ombra), stringResource(R.string.about_party_ombra_detail))
            }
        }
        GroupNote(stringResource(R.string.about_who_sees_note))

        SectionHeader(stringResource(R.string.about_credits))
        RowGroup {
            ListRow(
                title = stringResource(R.string.about_upstream),
                supporting = stringResource(R.string.about_upstream_detail),
                onClick = { openLink(context, ObscuraLinks.UPSTREAM_SOURCE) },
            ) { LinkIcon() }
            ListRow(
                title = stringResource(R.string.about_source),
                supporting = stringResource(R.string.about_source_detail),
                onClick = { openLink(context, OmbraLinks.SOURCE) },
            ) { LinkIcon() }
            NavRow(stringResource(R.string.about_licences), onLicences, supporting = stringResource(R.string.about_licences_detail))
        }
        GroupNote(stringResource(R.string.about_trademarks))

        SectionHeader(stringResource(R.string.about_help))
        RowGroup {
            NavRow(stringResource(R.string.tips_title), { showTips = true }, supporting = stringResource(R.string.tips_summary))
            ListRow(
                title = stringResource(R.string.about_terms),
                supporting = stringResource(R.string.about_terms_detail),
                onClick = { openLink(context, ObscuraLinks.TERMS) },
            ) { LinkIcon() }
        }
    }

    if (showTips) SpeedTips(onDismiss = { showTips = false })
}

@Composable
private fun Party(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Column(Modifier.padding(start = Spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkIcon() {
    Icon(Icons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How to compare protocols fairly (docs/research/perf-plan.md section 5, short). */
@Composable
private fun SpeedTips(onDismiss: () -> Unit) {
    Sheet(title = stringResource(R.string.tips_title), onDismiss = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            stringArrayResource(R.array.tips).forEach { tip ->
                Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(tip, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}
