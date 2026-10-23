package app.ombra.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.ombra.R
import app.ombra.core.model.TunnelMode
import app.ombra.data.ThemeMode
import app.ombra.ui.components.SubScreen
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/** A Settings page: a top bar with a back button, then content that scrolls. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SubScreen(title = title, onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(bottom = Spacing.xl),
            content = content,
        )
    }
}

/** A short explanation under a group of rows. */
@Composable
fun GroupNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.screen + Spacing.l, end = Spacing.screen + Spacing.l, top = Spacing.s),
    )
}

@StringRes
fun protocolName(mode: TunnelMode): Int = when (mode) {
    TunnelMode.Quic -> R.string.protocol_stealth
    TunnelMode.WireguardUdp -> R.string.protocol_speed
    TunnelMode.TcpTls -> R.string.protocol_tcp_tls
}

@StringRes
fun themeSummary(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.summary_theme_system
    ThemeMode.Dark -> R.string.summary_theme_dark
    ThemeMode.Light -> R.string.summary_theme_light
}

@Composable
fun accountColor(state: AccountState): Color = when (state) {
    AccountState.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    AccountState.Renews, AccountState.Active, AccountState.ActiveNoDate -> OmbraTheme.status.success
    AccountState.ExpiresSoon, AccountState.Inactive -> MaterialTheme.colorScheme.error
}

/** The account in one line: "Active · 23 days left", "Renews on 4 Nov 2026", "No time left". */
@Composable
fun accountLine(account: AccountSummary): String = when (account.state) {
    AccountState.Unknown -> stringResource(R.string.account_unknown)
    AccountState.Inactive -> stringResource(R.string.account_inactive)
    AccountState.ActiveNoDate -> stringResource(R.string.account_active)
    AccountState.Renews -> stringResource(R.string.account_renews, date(account.renewsAt))
    AccountState.Active, AccountState.ExpiresSoon ->
        stringResource(R.string.account_active_left, account.timeLeft?.let { timeLeftText(it) }.orEmpty())
}

@Composable
fun timeLeftText(left: TimeLeft): String = when (left) {
    is TimeLeft.Days -> pluralStringResource(R.plurals.time_left_days, left.days, left.days)
    is TimeLeft.Hours -> pluralStringResource(R.plurals.time_left_hours, left.hours, left.hours)
    TimeLeft.UnderAnHour -> stringResource(R.string.time_left_under_hour)
}

@StringRes
fun fundingName(funding: Funding): Int = when (funding) {
    Funding.FreeTrial -> R.string.funding_trial
    Funding.TopUp -> R.string.funding_top_up
    Funding.Subscription -> R.string.funding_subscription
    Funding.GooglePlay -> R.string.funding_google_play
    Funding.AppStore -> R.string.funding_app_store
}

/** A date in the user's format; [seconds] is Unix time. */
fun date(seconds: Long?): String =
    seconds?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }.orEmpty()
