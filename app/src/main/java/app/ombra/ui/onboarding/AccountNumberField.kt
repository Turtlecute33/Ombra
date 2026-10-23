package app.ombra.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ombra.ui.theme.OmbraTextStyles
import app.ombra.ui.theme.Spacing

/**
 * The 20-digit account number as 5 groups of 4. One invisible text field takes the input, so
 * typing, deleting, pasting and password-manager autofill work as usual; the groups are drawn
 * under it. The caret stays at the end. It takes the focus when it appears: it is the only input
 * of its screen. The groups run left to right in every language, like the digits of a number.
 */
@Composable
fun AccountNumberField(
    state: TextFieldState,
    label: String,
    isError: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val digits = state.text.toString()
    val active = AccountNumberInput.activeGroup(digits)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                AccountNumberInput.groups(digits).forEachIndexed { index, group ->
                    DigitGroup(
                        digits = group,
                        highlighted = focused && index == active,
                        isError = isError,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            BasicTextField(
                state = state,
                inputTransformation = AccountDigits,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                onKeyboardAction = KeyboardActionHandler { onDone() },
                lineLimits = TextFieldLineLimits.SingleLine,
                interactionSource = interaction,
                textStyle = OmbraTextStyles.mono.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                modifier = Modifier
                    .matchParentSize()
                    .focusRequester(focus)
                    .semantics {
                        contentDescription = label
                        contentType = ContentType.Password
                    },
            )
        }
    }
}

/** Digits only, at most 20: pasted text with spaces or dashes becomes a clean number. */
private object AccountDigits : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val clean = AccountNumberInput.sanitize(asCharSequence())
        if (clean != asCharSequence().toString()) replace(0, length, clean)
        placeCursorAtEnd()
    }
}

@Composable
private fun DigitGroup(digits: String, highlighted: Boolean, isError: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(
        when {
            isError -> colors.error
            highlighted -> colors.primary
            else -> colors.outlineVariant
        },
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    Surface(
        modifier = modifier
            .height(56.dp)
            .border(if (highlighted || isError) 2.dp else 1.dp, border, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = Spacing.s)) {
            BasicText(
                text = groupText(digits, colors.onSurface, colors.outline),
                style = OmbraTextStyles.monoLarge,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 22.sp),
            )
        }
    }
}

/** Typed digits, then dots for the ones still missing. */
private fun groupText(digits: String, typed: Color, missing: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = typed)) { append(digits) }
    withStyle(SpanStyle(color = missing)) { append("·".repeat(AccountNumberInput.GROUP_SIZE - digits.length)) }
}
