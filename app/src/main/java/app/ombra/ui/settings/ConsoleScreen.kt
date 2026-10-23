package app.ombra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.ombra.R
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.ipc.Request
import app.ombra.ui.SecureContent
import app.ombra.ui.components.Icons
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Debug builds only: sends raw JSON commands to the Rust core (the :vpn process refuses them in
 * release). Raw results are not sanitized and can hold the account number, so the window is secure.
 */
@Composable
fun ConsoleScreen(core: CoreSource, onBack: () -> Unit) {
    SecureContent()
    val command = rememberTextFieldState(SAMPLE_COMMAND)
    var output by rememberSaveable { mutableStateOf("") }
    var running by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorFormat = stringResource(R.string.console_error)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.console_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            OutlinedTextField(
                state = command,
                label = { Text(stringResource(R.string.console_command)) },
                textStyle = OmbraTextStyles.mono,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        output = try {
                            core.request(Request.RawCommand(command.text.toString()))
                        } catch (e: CoreRequestException) {
                            errorFormat.format(e.code)
                        }
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.console_send)) }
            SelectionContainer {
                Text(output, style = OmbraTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val SAMPLE_COMMAND = """{"getTrafficStats":{}}"""
