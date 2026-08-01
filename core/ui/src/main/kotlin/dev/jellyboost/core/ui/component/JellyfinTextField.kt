package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/** Fill of the field's well — barely there, so the border does the work of drawing the box. */
private val FieldFill = Color.White.copy(alpha = 0.04f)

/** The border once the field has the user's attention (focused) or their data (non-empty). */
private val FieldActiveBorder = Color.White.copy(alpha = 0.22f)

private val FieldPlaceholder = Color.White.copy(alpha = 0.48f)

private val FieldPadding = 14.dp

/** Floor on the well's height, so an empty field is not shorter than one holding a line of text. */
private val FieldMinHeight = 50.dp

private val FieldTextStyle =
    TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 20.sp,
    )

private val FieldSupportingStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

/**
 * The refresh's text field: a filled well with a hairline that brightens as the field fills.
 *
 * Built on [BasicTextField] rather than M3's `TextField` for the label. Material's filled field
 * floats its label *inside* the well and reserves 56dp of height for the animation; the mocks put a
 * small tracked-out caption above the box and give the box itself 14dp of padding. Those are
 * different components wearing the same name, so this one owns its layout and only borrows
 * Material's colours and text cursor.
 *
 * The parameter list deliberately mirrors the `OutlinedTextField` call sites in `:feature:auth` so
 * that swapping one for the other is a rename — the label and placeholder stay `@Composable`
 * lambdas for the same reason, even though every current caller passes a bare `Text`.
 *
 * @param label drawn above the field as a caption, not floated into it.
 * @param leadingIcon drawn before the well's content, in the muted [MaterialTheme.colorScheme]
 *   `onSurfaceVariant` tint — added for `:feature:search`'s field (2026 refresh, Phase 5 sweep),
 *   which wants a search glyph the way every `OutlinedTextField` call site it replaces already had
 *   one. `null` (the default) leaves every existing caller's layout untouched.
 * @param supportingText drawn below in [MaterialTheme] error colour when [isError], muted otherwise.
 */
@Composable
fun JellyfinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    val borderColor =
        when {
            isError -> MaterialTheme.colorScheme.error
            focused || value.isNotEmpty() -> FieldActiveBorder
            else -> GlassDefaults.Hairline
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        if (label != null) {
            // The caption is the shared eyebrow style: the mocks' field labels and their section
            // eyebrows are the same tracked-out 11dp caption, and callers uppercase the text.
            CompositionLocalProvider(
                LocalTextStyle provides JellyfinTypeExtras.Eyebrow,
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                content = label,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = FieldMinHeight)
                    .background(color = FieldFill, shape = shape)
                    .border(width = GlassDefaults.HairlineWidth, color = borderColor, shape = shape)
                    .padding(FieldPadding),
            enabled = enabled,
            textStyle = FieldTextStyle.copy(color = contentColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            content = leadingIcon,
                        )
                        Spacer(modifier = Modifier.width(Dimens.SpaceSmall))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            CompositionLocalProvider(
                                LocalTextStyle provides FieldTextStyle,
                                LocalContentColor provides FieldPlaceholder,
                                content = placeholder,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingIcon != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            content = trailingIcon,
                        )
                    }
                }
            },
        )

        if (supportingText != null) {
            CompositionLocalProvider(
                LocalTextStyle provides FieldSupportingStyle,
                LocalContentColor provides
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                content = supportingText,
            )
        }
    }
}

/** A disabled field still shows what it holds, just without claiming to be editable. */
private const val DISABLED_CONTENT_ALPHA = 0.5f

@Preview(name = "JellyfinTextField", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun JellyfinTextFieldPreview() {
    JellyfinTheme {
        Column(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        ) {
            JellyfinTextField(
                value = "",
                onValueChange = {},
                label = { Text(text = "SERVER ADDRESS") },
                placeholder = { Text(text = "http://192.168.1.10:8096") },
            )
            JellyfinTextField(
                value = "claude",
                onValueChange = {},
                label = { Text(text = "USERNAME") },
            )
            JellyfinTextField(
                value = "nope",
                onValueChange = {},
                isError = true,
                supportingText = { Text(text = "That server did not answer.") },
            )
        }
    }
}
