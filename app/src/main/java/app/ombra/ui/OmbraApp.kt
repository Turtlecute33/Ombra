package app.ombra.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ombra.R
import app.ombra.data.TunnelControl
import app.ombra.ui.components.EmptyState
import app.ombra.ui.components.Icons
import app.ombra.ui.components.LoadingState
import app.ombra.ui.nav.MainShell
import app.ombra.ui.nav.fadeThrough
import app.ombra.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.delay

/** The whole UI: onboarding until there is a confirmed account, then the main shell. */
@Composable
fun OmbraApp(app: AppViewModel, tunnel: TunnelControl) {
    val stage by app.stage.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(stage, transitionSpec = fadeThrough(), label = "stage") { target ->
            when (target) {
                AppStage.Loading -> Startup()
                AppStage.Onboarding -> OnboardingScreen(app.core)
                AppStage.Main -> MainShell(app.core, app.editor, tunnel, app.prefs)
            }
        }
    }
}

/** The core usually answers within a few hundred milliseconds; say so when it does not. */
@Composable
private fun Startup() {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_START_MS)
        slow = true
    }
    if (slow) {
        EmptyState(
            icon = Icons.Warning,
            title = stringResource(R.string.startup_slow),
            message = stringResource(R.string.startup_slow_detail),
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        LoadingState()
    }
}

private const val SLOW_START_MS = 8_000L
