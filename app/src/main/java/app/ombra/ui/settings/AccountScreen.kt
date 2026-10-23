package app.ombra.ui.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.core.AccountNumber
import app.ombra.data.CoreSource
import app.ombra.ui.ObscuraLinks
import app.ombra.ui.SecureContent
import app.ombra.ui.components.ConfirmDialog
import app.ombra.ui.components.Icons
import app.ombra.ui.components.ListRow
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.copySensitive
import app.ombra.ui.openLink
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing

/** Steps of the account deletion: two confirmations, the second one names what is lost. */
private enum class DeleteStep { None, First, Final }

/**
 * Account status, time and payment, the account number, log out and deletion. The window is
 * secure on this page: it can show the account number.
 */
@Composable
fun AccountScreen(core: CoreSource, onBack: () -> Unit) {
    SecureContent()
    val vm = viewModel { AccountViewModel(core) }
    val account by vm.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.new_account_clip_label)
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var deleteStep by rememberSaveable { mutableStateOf(DeleteStep.None) }
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    // The number leaves memory when the app goes to the background; the payment page counts too.
    LifecycleStartEffect(vm) { onStopOrDispose { vm.hideNumber() } }
    LifecycleResumeEffect(vm) {
        vm.onResumed()
        onPauseOrDispose {}
    }

    SettingsPage(stringResource(R.string.settings_account), onBack) {
        StatusCard(account, refreshing = vm.refreshing, onRefresh = vm::refresh)
        Column(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.l)) {
            Button(
                onClick = { vm.addTime { number -> openLink(context, ObscuraLinks.payment(number)) } },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.AddTime, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.account_add_time), modifier = Modifier.padding(start = Spacing.s))
            }
            Text(
                stringResource(R.string.account_add_time_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s, start = Spacing.xs, end = Spacing.xs),
            )
        }

        SectionHeader(stringResource(R.string.account_number))
        NumberCard(
            number = vm.number,
            busy = vm.busy,
            onReveal = vm::reveal,
            onHide = vm::hideNumber,
            onCopy = { number ->
                copySensitive(context, clipLabel, number)
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            },
        )
        GroupNote(stringResource(R.string.account_number_note))

        SectionHeader(stringResource(R.string.account_this_device))
        RowGroup {
            ListRow(
                title = stringResource(R.string.settings_logout),
                supporting = stringResource(R.string.settings_logout_detail),
                enabled = !vm.busy,
                onClick = { confirmLogout = true },
            )
            ListRow(
                title = stringResource(R.string.account_delete),
                supporting = stringResource(R.string.account_delete_detail),
                titleColor = MaterialTheme.colorScheme.error,
                enabled = !vm.busy,
                onClick = { deleteStep = DeleteStep.First },
            )
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logout),
            text = stringResource(R.string.settings_logout_confirm),
            confirmLabel = stringResource(R.string.settings_logout),
            onConfirm = {
                confirmLogout = false
                vm.logout()
            },
            onDismiss = { confirmLogout = false },
            destructive = true,
        )
    }
    when (deleteStep) {
        DeleteStep.None -> Unit
        DeleteStep.First -> ConfirmDialog(
            title = stringResource(R.string.account_delete_title),
            text = stringResource(R.string.account_delete_first),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = { deleteStep = DeleteStep.Final },
            onDismiss = { deleteStep = DeleteStep.None },
        )
        DeleteStep.Final -> ConfirmDialog(
            title = stringResource(R.string.account_delete_final_title),
            text = stringResource(R.string.account_delete_final),
            confirmLabel = stringResource(R.string.account_delete_action),
            onConfirm = {
                deleteStep = DeleteStep.None
                vm.deleteAccount()
            },
            onDismiss = { deleteStep = DeleteStep.None },
            destructive = true,
        )
    }
}

@Composable
private fun StatusCard(account: AccountSummary, refreshing: Boolean, onRefresh: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(
                    when {
                        account.state == AccountState.Unknown -> R.string.account_unknown
                        account.active -> R.string.account_active
                        else -> R.string.account_inactive
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = if (account.active) OmbraTheme.status.success else accountColor(account.state),
            )
            statusLines(account).forEachIndexed { index, line ->
                // With little time left, the line that says so stands out; the account is still active.
                val urgent = index == 0 && account.state == AccountState.ExpiresSoon
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.checkedAt?.let { checkedText(it) } ?: stringResource(R.string.account_not_checked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(stringResource(if (refreshing) R.string.account_checking else R.string.account_check))
                }
            }
        }
    }
}

@Composable
private fun statusLines(account: AccountSummary): List<String> = buildList {
    when (account.state) {
        AccountState.Active, AccountState.ExpiresSoon -> {
            account.timeLeft?.let { add(stringResource(R.string.account_time_left, timeLeftText(it), date(account.expiresAt))) }
        }
        AccountState.Renews -> add(stringResource(R.string.account_renews, date(account.renewsAt)))
        AccountState.Inactive -> add(stringResource(R.string.account_inactive_detail))
        AccountState.Unknown -> add(stringResource(R.string.account_unknown_detail))
        AccountState.ActiveNoDate -> Unit
    }
    account.funding?.let { add(stringResource(R.string.account_funding, stringResource(fundingName(it)))) }
    account.trialUntil?.let { add(stringResource(R.string.account_trial_until, date(it))) }
}

/** "Checked just now", "Checked 4 minutes ago", "Checked Oct 14". */
@Composable
private fun checkedText(atSeconds: Long): String {
    val now = System.currentTimeMillis()
    val at = atSeconds * 1000
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.account_checked_now)
    return stringResource(R.string.account_checked, DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS))
}

/** The account number: masked until the user reveals it, then copy and hide. */
@Composable
private fun NumberCard(number: String?, busy: Boolean, onReveal: () -> Unit, onHide: () -> Unit, onCopy: (String) -> Unit) {
    val masked = stringResource(R.string.account_number_masked)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
    ) {
        Column(Modifier.padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
            val groups = number?.let { AccountNumber.format(it).split(' ') } ?: List(GROUPS) { MASKED_GROUP }
            // Three groups on the first line, two on the second: it fits at large font sizes.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = if (number == null) Modifier.semantics { contentDescription = masked } else Modifier,
            ) {
                groups.chunked(GROUPS_PER_LINE).forEach { line ->
                    Text(
                        line.joinToString(" "),
                        style = OmbraTextStyles.monoLarge,
                        color = if (number == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.size(Spacing.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (number == null) {
                    FilledTonalButton(onClick = onReveal, enabled = !busy) {
                        Icon(Icons.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.account_reveal), modifier = Modifier.padding(start = Spacing.s))
                    }
                } else {
                    OutlinedButton(onClick = onHide) {
                        Icon(Icons.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.account_hide), modifier = Modifier.padding(start = Spacing.s))
                    }
                    FilledTonalButton(onClick = { onCopy(number) }) {
                        Icon(Icons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = Spacing.s))
                    }
                }
            }
        }
    }
}

private const val GROUPS = AccountNumber.LENGTH / 4
private const val GROUPS_PER_LINE = 3
private const val MASKED_GROUP = "••••"
