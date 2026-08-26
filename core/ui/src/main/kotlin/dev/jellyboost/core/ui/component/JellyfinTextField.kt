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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

private val FieldFill = Color.White.copy(alpha = 0.04f)

/**
 * The app's only focus affordance, so it owes 3:1 (WCAG 1.4.11, and 2.4.7 while focused). 0.22
 * measured 1.97:1 on `#101010`; 0.42 is 4.09:1 there and 3.99:1 on `#202020`.
 */
private val FieldActiveBorder = Color.White.copy(alpha = 0.42f)

private val FieldPlaceholder = Color.White.copy(alpha = 0.48f)

private val FieldPadding = 14.dp

/**
 * Also the height of the trailing slot's touch target, which is why it may never fall below
 * [Dimens.MinTouchTarget]: the target lays out inside the well rather than inflating it, so a
 * field with a trailing button (password reveal, search clear) is exactly as tall as one without.
 * `FieldGeometryTest` holds the floor.
 */
internal val FieldMinHeight = 50.dp

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
 * [BasicTextField], not M3's `TextField`: Material floats its label inside the well and reserves
 * 56dp for that animation, where this field draws a caption above a 14dp-padded box. Only the
 * colours and the cursor are borrowed.
 *
 * The a11y guarantees rest on [FieldLabel], [FieldState] and [FieldContent] rather than on four
 * pairs of parameters a caller had to keep in agreement by hand; each type documents its own.
 */
@Suppress(
    // One Material text field plus the label/error/password wiring its value types resolve. The decomposition wanted
    // here is `FieldLabel`/`FieldState`/`FieldContent` growing to absorb the remaining correlated parameters — which
    // shortens this by changing its API, not by moving lines out of it. Tracked, not line-shaved.
    "LongMethod",
)
@Composable
fun JellyfinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: FieldLabel? = null,
    state: FieldState = FieldState.Editable,
    content: FieldContent = FieldContent.Plain(),
    singleLine: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    val borderColor =
        when {
            state.isError -> MaterialTheme.colorScheme.error
            focused || value.isNotEmpty() -> FieldActiveBorder
            else -> GlassDefaults.Hairline
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        val caption = label?.caption
        if (caption != null) {
            Text(
                text = caption,
                style = JellyfinTypeExtras.Eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Muted: the field node carries the name, so this would be a second stop saying
                // the same word, spelled out letter by letter.
                modifier = Modifier.clearAndSetSemantics {},
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
                    .fieldNodeSemantics(
                        labelText = label?.text,
                        password = content.isSecret,
                        errorMessage = state.errorMessage,
                        autofillContentType = content.autofillContentType,
                    ),
            readOnly = state.isReadOnly,
            textStyle = FieldTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            visualTransformation = content.visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                // Vertical padding belongs to the text, not to the well: [FieldMinHeight] reaches
                // this row undiminished (`BasicTextField` propagates min constraints into the
                // decoration box), so the trailing touch target has the field's full height to sit
                // in. Padding the whole row instead left it [FieldPadding] * 2 short of
                // [Dimens.MinTouchTarget], and the 48dp button then pushed the field past every
                // field beside it.
                Row(
                    modifier = Modifier.padding(horizontal = FieldPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            content = leadingIcon,
                        )
                        Spacer(modifier = Modifier.width(Dimens.SpaceSmall))
                    }
                    Box(modifier = Modifier.weight(1f).padding(vertical = FieldPadding)) {
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
                        // Fits inside the well rather than stretching it, which holds only while
                        // [FieldMinHeight] >= [Dimens.MinTouchTarget]; `requiredSize` keeps the
                        // frame 48dp even where a caller bounds the field's height below that.
                        // M3's `IconButton` declares a 40dp state layer and carries the click and
                        // the semantics on that node, and a `Box` hands its children `min = 0`:
                        // both are needed for the 48dp floor to reach the button rather than stop
                        // at the frame drawn around it.
                        Box(
                            modifier = Modifier.requiredSize(Dimens.MinTouchTarget),
                            contentAlignment = Alignment.Center,
                            propagateMinConstraints = true,
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                                content = trailingIcon,
                            )
                        }
                    }
                }
            },
        )

        if (supportingText != null) {
            CompositionLocalProvider(
                LocalTextStyle provides FieldSupportingStyle,
                LocalContentColor provides
                    if (state.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                content = supportingText,
            )
        }
    }
}

/**
 * Must sit on the field's own node: the caption above the well and the sentence below it are
 * separate nodes with no association to it, so without this the field announces only its value and
 * "edit box".
 *
 * @param errorMessage already filtered by the caller: `null` when the field is not in error.
 */
private fun Modifier.fieldNodeSemantics(
    labelText: String?,
    password: Boolean,
    errorMessage: String?,
    autofillContentType: ContentType?,
): Modifier =
    this
        .then(autofillContentType?.let { Modifier.contentType(it) } ?: Modifier)
        .semantics {
            labelText?.let { contentDescription = it }
            if (password) password()
            errorMessage?.let { error(it) }
        }

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
                label = FieldLabel.eyebrow("Server address"),
                placeholder = { Text(text = "http://192.168.1.10:8096") },
            )
            JellyfinTextField(
                value = "claude",
                onValueChange = {},
                label = FieldLabel.eyebrow("Username"),
                state = FieldState.InFlight,
            )
            JellyfinTextField(
                value = "•••••",
                onValueChange = {},
                label = FieldLabel.eyebrow("Password"),
                content = FieldContent.Password(),
            )
            JellyfinTextField(
                value = "nope",
                onValueChange = {},
                label = FieldLabel.eyebrow("Server address"),
                state = FieldState.Error("That server did not answer."),
                supportingText = { Text(text = "That server did not answer.") },
            )
        }
    }
}
