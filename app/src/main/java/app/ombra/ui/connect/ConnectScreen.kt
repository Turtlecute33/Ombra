package app.ombra.ui.connect

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.StartProblem
import app.ombra.data.TunnelControl
import app.ombra.data.UiPrefs
import app.ombra.ui.appDetailsIntent
import app.ombra.ui.components.ConnectControl
import app.ombra.ui.components.ConnectionDetailsSheet
import app.ombra.ui.components.ControlLook
import app.ombra.ui.components.LiveStatsCard
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.components.OmbraMark
import app.ombra.ui.components.RouteCard
import app.ombra.ui.components.StatusHeader
import app.ombra.ui.components.StatusTone
import app.ombra.ui.connectErrorText
import app.ombra.ui.fixLabel
import app.ombra.ui.openExternal
import app.ombra.ui.startProblemText
import app.ombra.ui.theme.Spacing

@Composable
fun ConnectScreen(
    core: CoreSource,
    editor: SettingsEditor,
    prefs: UiPrefs,
    tunnel: TunnelControl,
    onChooseEntry: () -> Unit,
    onChooseExit: () -> Unit,
    onOpenAccount: () -> Unit,
    contentPadding: PaddingValues,
) {
    val vm = viewModel { ConnectViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Read only inside the AnimatedVisibility content below: the 1 Hz updates then recompose the
    // stats card alone (measured: no recomposition of the screen, route or control while connected).
    val stats by vm.stats.collectAsStateWithLifecycle()
    val startProblem by tunnel.problem.collectAsStateWithLifecycle()
    val system by core.system.collectAsStateWithLifecycle()
    val batteryHintDismissed by prefs.batteryHintDismissed.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(startProblem) { if (startProblem != null) vm.onStartFailed() }

    val connect = {
        vm.onConnectRequested()
        tunnel.connect()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = Spacing.maxContentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            Spacer(Modifier.height(Spacing.l))
            Header(state, lockdown = system?.lockdown == true)
            Spacer(Modifier.height(Spacing.s))
            ConnectControl(
                look = lookOf(state),
                actionLabel = stringResource(actionLabel(state)),
                stateLabel = stringResource(titleOf(state)),
                enabled = state.phase != ConnectPhase.Loading && state.phase != ConnectPhase.Disconnecting,
                onClick = {
                    if (state.phase == ConnectPhase.Disconnected) {
                        connect()
                    } else {
                        vm.onDisconnectRequested()
                        tunnel.disconnect()
                    }
                },
            )
            // The control itself announces the action.
            Text(
                stringResource(actionLabel(state)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.height(Spacing.l))
            Messages(
                state = state,
                startProblem = startProblem,
                onFix = { fix -> if (fix == ErrorFix.OpenAccount) onOpenAccount() else vm.apply(fix) },
                onRetry = connect,
                onOpenVpnSettings = { openExternal(context, Intent(AndroidSettings.ACTION_VPN_SETTINGS)) },
                onOpenAccount = onOpenAccount,
            )
            if (system?.backgroundRestricted == true && !batteryHintDismissed) {
                Notice(
                    text = stringResource(R.string.battery_hint),
                    tone = NoticeTone.Info,
                    actionLabel = stringResource(R.string.battery_hint_action),
                    onAction = {
                        prefs.dismissBatteryHint()
                        openExternal(context, appDetailsIntent(context))
                    },
                    onDismiss = prefs::dismissBatteryHint,
                )
                Spacer(Modifier.height(Spacing.m))
            }
            RouteCard(
                entry = state.entry,
                exit = state.exit,
                protected = state.phase == ConnectPhase.Connected,
                onEntryClick = onChooseEntry,
                onExitClick = onChooseExit,
            )
            AnimatedVisibility(
                visible = state.phase == ConnectPhase.Connected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.m))
                    LiveStatsCard(stats, stringResource(protocolLabel(state.protocol)), onDetails = { showDetails = true })
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    val details = state.details
    if (showDetails && details != null) ConnectionDetailsSheet(details, onDismiss = { showDetails = false })
}

@Composable
private fun Brand() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        OmbraMark(size = 28.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Header(state: ConnectUiState, lockdown: Boolean) {
    val error = state.error
    when {
        error?.isFatal == true -> StatusHeader(
            title = stringResource(R.string.status_problem),
            subtitle = stringResource(R.string.status_problem_blocked),
            tone = StatusTone.Negative,
        )
        // Rust still retries: the attempt goes on, the notice below says what fails.
        error != null -> StatusHeader(
            title = stringResource(titleOf(state)),
            subtitle = stringResource(R.string.status_problem_retrying),
            tone = StatusTone.Neutral,
        )
        else -> StatusHeader(
            title = stringResource(titleOf(state)),
            subtitle = stringResource(detailOf(state.phase, lockdown)),
            tone = if (state.phase == ConnectPhase.Connected) StatusTone.Positive else StatusTone.Neutral,
        )
    }
}

@Composable
private fun Messages(
    state: ConnectUiState,
    startProblem: StartProblem?,
    onFix: (ErrorFix) -> Unit,
    onRetry: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val error = state.error
    val showAccount = state.accountInactive && error == null && state.phase == ConnectPhase.Disconnected
    when {
        startProblem != null -> Notice(
            text = stringResource(startProblemText(startProblem)),
            tone = NoticeTone.Error,
            actionLabel = stringResource(
                if (startProblem == StartProblem.OtherAlwaysOnVpn) R.string.start_open_vpn_settings else R.string.action_retry,
            ),
            onAction = if (startProblem == StartProblem.OtherAlwaysOnVpn) onOpenVpnSettings else onRetry,
        )
        error != null -> {
            val fix = fixFor(error, state)
            Notice(
                text = stringResource(connectErrorText(error, state)),
                tone = NoticeTone.Error,
                actionLabel = fix?.let { stringResource(fixLabel(it)) },
                onAction = { fix?.let(onFix) },
            )
        }
        showAccount -> Notice(
            text = stringResource(R.string.account_inactive_notice),
            tone = NoticeTone.Info,
            actionLabel = stringResource(R.string.fix_open_account),
            onAction = onOpenAccount,
        )
        else -> return
    }
    Spacer(Modifier.height(Spacing.m))
}

private fun lookOf(state: ConnectUiState): ControlLook = when (state.phase) {
    ConnectPhase.Loading -> ControlLook.Unavailable
    ConnectPhase.Disconnected -> ControlLook.Ready
    ConnectPhase.Connected -> ControlLook.Protected
    ConnectPhase.Connecting, ConnectPhase.Reconnecting -> if (state.error?.isFatal == true) ControlLook.Problem else ControlLook.Working
    ConnectPhase.Disconnecting -> ControlLook.Working
}

private fun actionLabel(state: ConnectUiState): Int = when (state.phase) {
    ConnectPhase.Disconnected -> R.string.action_connect
    ConnectPhase.Connecting, ConnectPhase.Reconnecting -> R.string.action_cancel
    ConnectPhase.Connected -> R.string.action_disconnect
    ConnectPhase.Loading, ConnectPhase.Disconnecting -> R.string.control_wait
}

private fun titleOf(state: ConnectUiState): Int = when (state.phase) {
    ConnectPhase.Loading -> R.string.status_loading
    ConnectPhase.Disconnected -> R.string.status_off
    ConnectPhase.Connecting -> R.string.status_connecting
    ConnectPhase.Reconnecting -> R.string.status_reconnecting
    ConnectPhase.Connected -> R.string.status_on
    ConnectPhase.Disconnecting -> R.string.status_disconnecting
}

/** [lockdown]: "Block connections without VPN" is on, so while off Android blocks all traffic. */
private fun detailOf(phase: ConnectPhase, lockdown: Boolean): Int = when (phase) {
    ConnectPhase.Loading -> R.string.status_loading_detail
    ConnectPhase.Disconnected -> if (lockdown) R.string.status_off_lockdown_detail else R.string.status_off_detail
    ConnectPhase.Connecting -> R.string.status_connecting_detail
    ConnectPhase.Reconnecting -> R.string.status_reconnecting_detail
    ConnectPhase.Connected -> R.string.status_on_detail
    ConnectPhase.Disconnecting -> R.string.status_disconnecting_detail
}

private fun protocolLabel(protocol: Protocol): Int = when (protocol) {
    Protocol.Stealth -> R.string.protocol_stealth
    Protocol.Speed -> R.string.protocol_speed
    Protocol.TcpTls -> R.string.protocol_tcp_tls
}
