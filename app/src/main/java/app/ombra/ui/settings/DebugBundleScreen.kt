package app.ombra.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.ui.components.Icons
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.requestErrorText
import app.ombra.ui.theme.Spacing
import java.io.File

/**
 * The consent page of the debug bundle. It says what the bundle does before anything runs: it
 * probes the network and collects logs. The zip stays on the phone until the user shares it.
 */
@Composable
fun DebugBundleScreen(core: CoreSource, onBack: () -> Unit) {
    val vm = viewModel { DebugBundleViewModel(core) }
    val context = LocalContext.current
    var feedback by rememberSaveable { mutableStateOf("") }
    val step = vm.step

    SettingsPage(stringResource(R.string.bundle_title), onBack) {
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(R.string.bundle_intro), style = MaterialTheme.typography.bodyLarge)
            BUNDLE_FACTS.forEach { (icon, text) -> Fact(icon, stringResource(text)) }
            OutlinedTextField(
                value = feedback,
                onValueChange = { feedback = it },
                label = { Text(stringResource(R.string.bundle_feedback)) },
                placeholder = { Text(stringResource(R.string.bundle_feedback_hint)) },
                enabled = step is BundleStep.Consent || step is BundleStep.Failed,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                when (step) {
                    BundleStep.Consent -> CreateButton(R.string.bundle_create) { vm.create(feedback) }
                    BundleStep.Creating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(40.dp))
                        Text(stringResource(R.string.bundle_creating), modifier = Modifier.padding(start = Spacing.m))
                    }
                    is BundleStep.Ready -> {
                        Notice(stringResource(R.string.bundle_ready), NoticeTone.Info)
                        CreateButton(R.string.bundle_share) { share(context, step.path) }
                    }
                    is BundleStep.Failed -> {
                        Notice(stringResource(requestErrorText(step.code)), NoticeTone.Error)
                        CreateButton(R.string.action_retry) { vm.create(feedback) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.m))
    }
}

@Composable
private fun CreateButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(label)) }
}

/** Shares the zip through the FileProvider (authority `<applicationId>.files`) with a read grant. */
private fun share(context: Context, path: String) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    } catch (e: IllegalArgumentException) {
        // No provider for this path (a build without the :vpn provider).
        Toast.makeText(context, R.string.bundle_share_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser passes the grant on only for URIs in the clip data.
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.bundle_share)))
}

private val BUNDLE_FACTS = listOf(
    Icons.Globe to R.string.bundle_fact_probes,
    Icons.Document to R.string.bundle_fact_logs,
    Icons.VerifiedUser to R.string.bundle_fact_private,
    Icons.Share to R.string.bundle_fact_share,
)
