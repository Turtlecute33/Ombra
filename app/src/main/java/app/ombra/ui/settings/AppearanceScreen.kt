package app.ombra.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ombra.R
import app.ombra.data.CoreSource
import app.ombra.data.SettingsEditor
import app.ombra.data.ThemeMode
import app.ombra.data.UiPrefs
import app.ombra.ui.components.RequestErrorMessage
import app.ombra.ui.components.RowGroup
import app.ombra.ui.components.SectionHeader
import app.ombra.ui.components.SwitchRow
import app.ombra.ui.theme.Spacing

/** Theme and colours (UI preferences), plus what other people can see of the screen and notification. */
@Composable
fun AppearanceScreen(core: CoreSource, editor: SettingsEditor, prefs: UiPrefs, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(core, editor) }
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val dynamicColor by prefs.dynamicColor.collectAsStateWithLifecycle()
    val secureAll by prefs.secureAllScreens.collectAsStateWithLifecycle()
    RequestErrorMessage(vm.errorCode, vm::errorShown)

    SettingsPage(stringResource(R.string.settings_appearance), onBack) {
        RowGroup {
            ThemeRow(theme, prefs::setTheme)
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                supporting = stringResource(R.string.settings_dynamic_color_detail),
                checked = dynamicColor,
                onCheckedChange = prefs::setDynamicColor,
            )
        }

        SectionHeader(stringResource(R.string.settings_privacy))
        RowGroup {
            SwitchRow(
                title = stringResource(R.string.settings_secure_all),
                supporting = stringResource(R.string.settings_secure_all_detail),
                checked = secureAll,
                onCheckedChange = prefs::setSecureAllScreens,
            )
            SwitchRow(
                title = stringResource(R.string.appearance_hide_details),
                supporting = stringResource(R.string.appearance_hide_details_detail),
                checked = state.settings.hideNotificationDetails,
                onCheckedChange = { on -> vm.update { it.copy(hideNotificationDetails = on) } },
            )
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Spacing.s))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) { Text(stringResource(option.label)) }
            }
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Dark -> R.string.settings_theme_dark
        ThemeMode.Light -> R.string.settings_theme_light
    }
