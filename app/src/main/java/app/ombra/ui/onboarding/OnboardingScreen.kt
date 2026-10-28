package app.ombra.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.ui.ObscuraLinks
import app.ombra.ui.SecureContent
import app.ombra.ui.components.ConfirmDialog
import app.ombra.ui.components.Icons
import app.ombra.ui.components.Notice
import app.ombra.ui.components.NoticeTone
import app.ombra.ui.components.OmbraMark
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.components.copySensitive
import app.ombra.ui.nav.sharedAxis
import app.ombra.ui.openLink
import app.ombra.ui.requestErrorText
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.OmbraTheme
import app.ombra.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Welcome, log in, create an account. The whole flow runs with FLAG_SECURE. */
@Composable
fun OnboardingScreen(core: CoreSource) {
    SecureContent()
    val vm = viewModel { OnboardingViewModel(core) }
    val backward = sharedAxis<Scene<OnboardingStep>>(forward = false)
    NavDisplay(
        backStack = vm.steps,
        onBack = vm::back,
        transitionSpec = sharedAxis(forward = true),
        popTransitionSpec = backward,
        predictivePopTransitionSpec = { backward() },
        entryProvider = entryProvider {
            entry<OnboardingStep.Welcome> { Welcome(onLogin = { vm.open(OnboardingStep.Login) }, onCreate = { vm.open(OnboardingStep.Create) }) }
            entry<OnboardingStep.Login> { Login(vm) }
            entry<OnboardingStep.Create> { Create(vm) }
            entry<OnboardingStep.NewAccount> { NewAccount(vm) }
        },
    )
}

@Composable
private fun Page(onBack: (() -> Unit)?, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.xs), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = stringResource(R.string.action_back)) }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Spacing.maxContentWidth), content = content)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = bottom,
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Spacing.m))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = Spacing.s), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.l))
    }
}

/** A full-width main button that shows a loading indicator while [busy]. */
@Composable
private fun MainButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().widthIn(max = Spacing.maxContentWidth).heightIn(min = 56.dp),
    ) {
        if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary) else Text(text)
    }
}

@Composable
private fun Welcome(onLogin: () -> Unit, onCreate: () -> Unit) {
    Page(
        onBack = null,
        bottom = {
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().widthIn(max = Spacing.maxContentWidth).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.welcome_login))
            }
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().widthIn(max = Spacing.maxContentWidth).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.welcome_create))
            }
        },
    ) {
        Spacer(Modifier.height(Spacing.xl))
        OmbraMark(Modifier.align(Alignment.CenterHorizontally), size = 112.dp)
        Spacer(Modifier.height(Spacing.xl))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xxl))
        Point(Icons.Key, stringResource(R.string.welcome_point_account))
        Point(Icons.Relay, stringResource(R.string.welcome_point_hops))
        Point(Icons.Shield, stringResource(R.string.welcome_point_tracking))
        Spacer(Modifier.height(Spacing.xl))
        Text(
            stringResource(R.string.welcome_independent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Login(vm: OnboardingViewModel) {
    val digits = vm.accountInput.text.toString()
    val check = AccountNumberInput.check(digits)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Page(
        onBack = vm::back,
        bottom = { MainButton(stringResource(R.string.login_action), check == AccountNumberInput.Check.Valid, vm.busy, vm::login) },
    ) {
        Title(stringResource(R.string.login_title))
        Body(stringResource(R.string.login_body))
        Spacer(Modifier.height(Spacing.xl))
        AccountNumberField(
            state = vm.accountInput,
            label = stringResource(R.string.login_field),
            isError = check == AccountNumberInput.Check.Invalid,
            onDone = vm::login,
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (check) {
                    AccountNumberInput.Check.Incomplete -> stringResource(R.string.login_progress, digits.length)
                    AccountNumberInput.Check.Invalid -> stringResource(R.string.login_typo)
                    AccountNumberInput.Check.Valid -> stringResource(R.string.login_valid)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (check) {
                    AccountNumberInput.Check.Incomplete -> MaterialTheme.colorScheme.onSurfaceVariant
                    AccountNumberInput.Check.Invalid -> MaterialTheme.colorScheme.error
                    AccountNumberInput.Check.Valid -> OmbraTheme.status.success
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                    if (text != null) vm.accountInput.setTextAndPlaceCursorAtEnd(AccountNumberInput.sanitize(text))
                }
            }) {
                Icon(Icons.Paste, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_paste), modifier = Modifier.padding(start = Spacing.s))
            }
        }
        vm.errorCode?.let { code ->
            Spacer(Modifier.height(Spacing.l))
            Notice(stringResource(requestErrorText(code)), NoticeTone.Error)
        }
    }
}

@Composable
private fun Create(vm: OnboardingViewModel) {
    val context = LocalContext.current
    Page(
        onBack = vm::back,
        bottom = {
            MainButton(
                stringResource(if (vm.trial == Trial.Available) R.string.create_action_trial else R.string.create_action),
                enabled = vm.trial != Trial.Checking,
                busy = vm.busy,
                onClick = vm::create,
            )
            Text(
                stringResource(R.string.create_terms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { openLink(context, ObscuraLinks.TERMS) }) {
                Text(stringResource(R.string.create_terms_link))
            }
        },
    ) {
        Title(stringResource(R.string.create_title))
        Point(Icons.Key, stringResource(R.string.create_point_number))
        Point(Icons.Warning, stringResource(R.string.create_point_secret))
        Spacer(Modifier.height(Spacing.l))
        TrialRow(vm.trial)
        Spacer(Modifier.height(Spacing.s))
        SwitchRow(
            title = stringResource(R.string.create_referral),
            supporting = stringResource(R.string.create_referral_detail),
            checked = vm.useReferral,
            onCheckedChange = { vm.useReferral = it },
        )
        vm.errorCode?.let { code ->
            Spacer(Modifier.height(Spacing.l))
            Notice(stringResource(requestErrorText(code)), NoticeTone.Error)
        }
    }
}

@Composable
private fun TrialRow(trial: Trial?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            if (trial == Trial.Checking || trial == null) {
                LoadingIndicator(Modifier.size(24.dp))
            } else {
                Icon(
                    if (trial == Trial.Available) Icons.Check else Icons.Info,
                    contentDescription = null,
                    tint = if (trial == Trial.Available) OmbraTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    when (trial) {
                        Trial.Checking, null -> R.string.create_trial_checking
                        Trial.Available -> R.string.create_trial_available
                        Trial.Unavailable -> R.string.create_trial_unavailable
                        Trial.Unknown -> R.string.create_trial_unknown
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Spacing.l),
            )
        }
    }
}

@Composable
private fun NewAccount(vm: OnboardingViewModel) {
    var saved by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val number = vm.newNumber
    LifecycleResumeEffect(vm) {
        vm.onResumed()
        onPauseOrDispose {}
    }
    Page(
        onBack = null,
        bottom = {
            MainButton(stringResource(R.string.new_account_done), enabled = saved, busy = vm.busy, onClick = vm::finish)
            if (vm.accountActive != true && number != null) {
                OutlinedButton(
                    onClick = {
                        vm.onPaymentOpened()
                        openLink(context, ObscuraLinks.payment(number))
                    },
                    enabled = saved,
                    modifier = Modifier.fillMaxWidth().widthIn(max = Spacing.maxContentWidth).heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.new_account_add_time)) }
                Text(
                    stringResource(R.string.account_add_time_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
    ) {
        Title(stringResource(R.string.new_account_title))
        Body(stringResource(R.string.new_account_body))
        Spacer(Modifier.height(Spacing.xl))
        NumberCard(number, busy = vm.busy, onReveal = vm::reveal)
        vm.accountActive?.let { active ->
            Spacer(Modifier.height(Spacing.l))
            Notice(
                stringResource(if (active) R.string.new_account_active else R.string.new_account_inactive),
                NoticeTone.Info,
            )
        }
        vm.errorCode?.let { code ->
            Spacer(Modifier.height(Spacing.l))
            Notice(stringResource(requestErrorText(code)), NoticeTone.Error)
        }
        Spacer(Modifier.height(Spacing.l))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = saved, enabled = number != null, role = Role.Checkbox, onValueChange = { saved = it })
                .padding(vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = saved, onCheckedChange = null, enabled = number != null)
            Text(stringResource(R.string.new_account_saved), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.m))
        }
        TextButton(onClick = { confirmLeave = true }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.new_account_other))
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.new_account_other_title),
            text = stringResource(R.string.new_account_other_confirm),
            confirmLabel = stringResource(R.string.new_account_other_action),
            onConfirm = {
                confirmLeave = false
                vm.useOtherAccount()
            },
            onDismiss = { confirmLeave = false },
            destructive = true,
        )
    }
}

/** The new number, or a masked one with a reveal button after a process death. */
@Composable
private fun NumberCard(number: String?, busy: Boolean, onReveal: () -> Unit) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.new_account_clip_label)
    val groups = number?.let(AccountNumberInput::groups) ?: List(AccountNumberInput.GROUPS) { MASKED_GROUP }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
            groups.chunked(GROUPS_PER_LINE).forEach { line ->
                Text(
                    line.joinToString(" "),
                    style = OmbraTextStyles.monoLarge,
                    color = if (number == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.m))
            if (number == null) {
                FilledTonalButton(onClick = onReveal, enabled = !busy) { Text(stringResource(R.string.new_account_reveal)) }
            } else {
                FilledTonalButton(onClick = {
                    copySensitive(context, clipLabel, number)
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

/** Three groups on the first line, two on the second: the number fits a phone at large font sizes. */
private const val GROUPS_PER_LINE = 3

private const val MASKED_GROUP = "••••"
