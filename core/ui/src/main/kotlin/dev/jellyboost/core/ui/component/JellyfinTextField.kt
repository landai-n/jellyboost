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

/** Fill of the field's well — barely there, so the border does the work of drawing the box. */
private val FieldFill = Color.White.copy(alpha = 0.04f)

/**
 * The border once the field has the user's attention (focused) or their data (non-empty).
 *
 * This is the app's only focus affordance, which makes it a UI component boundary owing 3:1 under
 * WCAG 1.4.11 (and, on the focused path, 2.4.7). At 0.22 it was 1.97:1 on `#101010` — a border you
 * had to already know was there. 0.42 gives 4.09:1 on the
 * background and 3.99:1 on `#202020`, and stays clearly a step under the [FieldFill]'s text.
 */
private val FieldActiveBorder = Color.White.copy(alpha = 0.42f)

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
 * ### Three values, not nineteen parameters
 * The field's accessibility guarantees rest on three values rather than four pairs of parameters a
 * caller would otherwise have to keep in agreement by hand — `label`/`labelText`,
 * `isError`/`errorMessage`, `password`/`visualTransformation`, `enabled`/`readOnly` — none of them
 * checked by anything. Each pair is one value that cannot be half-passed: [FieldLabel], [FieldState],
 * [FieldContent]. What each of them guarantees is documented on the type rather than repeated here.
 *
 * The placeholder and the icons stay `@Composable` lambdas: they are decoration, they carry no
 * semantics of their own, and one of them (the trailing icon) is a real control the caller owns.
 *
 * @param label the field's name, drawn above the well as a caption and spoken by the field node
 *   itself. `null` for a field with no name at all, which every call site in this app avoids.
 * @param leadingIcon drawn before the well's content, in the muted [MaterialTheme.colorScheme]
 *   `onSurfaceVariant` tint — added for `:feature:search`'s field, which wants a search glyph the
 *   way every `OutlinedTextField` call site it replaces already had one. `null` (the default)
 *   leaves every existing caller's layout untouched.
 * @param supportingText drawn below in [MaterialTheme] error colour while [state] is a
 *   [FieldState.Error], muted otherwise.
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
            // The caption is the shared eyebrow style: the mocks' field labels and their section
            // eyebrows are the same tracked-out 11dp caption, and callers uppercase the text.
            Text(
                text = caption,
                style = JellyfinTypeExtras.Eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Muted for the screen reader, because the field itself carries the name: the
                // caption and the field would otherwise be two stops saying the same word, the
                // first of them spelled out letter by letter.
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
                    ).padding(FieldPadding),
            readOnly = state.isReadOnly,
            textStyle = FieldTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            visualTransformation = content.visualTransformation,
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
                        // The slot usually holds a 48dp `IconButton` (password reveal, search
                        // clear), and letting that measure normally inflated the whole field past
                        // [FieldMinHeight] — the password field stood visibly taller than the
                        // username field one line above it.
                        // `requiredSize` reports the row's own height back to the layout while the
                        // 48dp touch target draws and hit-tests centred over it, the same
                        // visual-inside-a-bigger-invisible-frame trade the chip and pill
                        // components already make; nothing in the field's modifier chain clips,
                        // so the overflowing target stays tappable.
                        Box(
                            modifier = Modifier.requiredSize(Dimens.MinTouchTarget),
                            contentAlignment = Alignment.Center,
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
 * Everything a screen reader needs from the field itself.
 *
 * On the field's own node, because that is the node a screen reader lands on: the caption above the
 * well and the sentence below it are separate nodes with no association to it, which is what
 * prevents a field from announcing only its value and the word "edit box" — no name, no
 * failure.
 *
 * @param errorMessage already filtered by the caller: pass `null` when the field is not in error.
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
