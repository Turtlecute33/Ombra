package app.ombra.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import app.ombra.R
import app.ombra.ui.requestErrorText

/** A screen above a tab: a top bar with a back button, then [content]. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable (PaddingValues) -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = stringResource(R.string.action_back)) }
                },
                scrollBehavior = scroll,
            )
        },
        content = content,
    )
}

/** The snackbar of the main shell, for short messages about a request. */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar host") }

/** Shows the text of a failed request (see [requestErrorText]) once, then calls [onShown]. */
@Composable
fun RequestErrorMessage(code: String?, onShown: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val text = code?.let { stringResource(requestErrorText(it)) } ?: return
    LaunchedEffect(text) {
        snackbar.showSnackbar(text)
        onShown()
    }
}
