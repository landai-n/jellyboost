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

/**
 * The border once the field has the user's attention (focused) or their data (non-empty).
 *
 * This is the app's only focus affordance, which makes it a UI component boundary owing 3:1 under
 * WCAG 1.4.11 (and, on the focused path, 2.4.7). At 0.22 it was 1.97:1 on `#101010` — a border you
 * had to already know was there (accessibility audit 2026-08-05). 0.42 gives 4.09:1 on the
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
 * The parameter list deliberately mirrors the `OutlinedTextField` call sites in `:feature:auth` so
 * that swapping one for the other is a rename — the label and placeholder stay `@Composable`
 * lambdas for the same reason, even though every current caller passes a bare `Text`.
 *
 * @param label drawn above the field as a caption, not floated into it.
 * @param labelText the same words as [label], in the case a *person* reads them — the caption is
 *   uppercased by its callers, and "USERNAME" spelled at a screen-reader user is not a label
 *   (accessibility audit 2026-08-05, CR-2/F16). It becomes the field node's `contentDescription`,
 *   which is what an editable node uses as its name (its value stays the text it holds), and the
 *   visible caption is then muted with `clearAndSetSemantics` so the pair is announced once. A
 *   field with no [labelText] is exactly as unlabelled as it was before — but every call site in
 *   the app passes one.
 * @param leadingIcon drawn before the well's content, in the muted [MaterialTheme.colorScheme]
 *   `onSurfaceVariant` tint — added for `:feature:search`'s field (2026 refresh, Phase 5 sweep),
 *   which wants a search glyph the way every `OutlinedTextField` call site it replaces already had
 *   one. `null` (the default) leaves every existing caller's layout untouched.
 * @param supportingText drawn below in [MaterialTheme] error colour when [isError], muted otherwise.
 * @param errorMessage what went wrong, in words. Draws nothing — [supportingText] and the screens'
 *   own error blocks own the visuals — but while [isError] it is attached to the field node as
 *   `error(…)` semantics, so TalkBack says *what* is wrong rather than only that something is.
 *   Pass the same sentence the screen shows.
 * @param readOnly the field keeps its focus, its name and its value and refuses to be typed into.
 *   This is what an in-flight request wants, not `enabled = false`: disabling destroys the node the
 *   user is standing on at the exact moment they pressed the button, and a screen reader dropped
 *   mid-form has nowhere to land (accessibility audit 2026-08-05, F17). The auth screens guard the
 *   edit in their state holders as well — belt and braces, and the guard is the part a JVM test can
 *   hold still (DECISIONS.md, "an in-flight auth field stays enabled").
 * @param password marks the field node as holding a secret, so a screen reader speaks it the way
 *   the platform speaks passwords instead of reading the value out loud (audit F5). Independent of
 *   [visualTransformation], which is what hides the characters on screen.
 * @param autofillContentType what the platform's autofill service should offer here — a username,
 *   a password. `null` (the default) leaves the field out of autofill entirely, which is right for
 *   a search box and wrong for a credential.
 */
@Composable
fun JellyfinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    labelText: String? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    errorMessage: String? = null,
    password: Boolean = false,
    autofillContentType: ContentType? = null,
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
            Box(
                // Muted for the screen reader when the field itself carries the name: the caption
                // and the field would otherwise be two stops saying the same word, the first of
                // them spelled out letter by letter.
                modifier = if (labelText != null) Modifier.clearAndSetSemantics {} else Modifier,
            ) {
                CompositionLocalProvider(
                    LocalTextStyle provides JellyfinTypeExtras.Eyebrow,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    content = label,
                )
            }
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
                        labelText = labelText,
                        password = password,
                        errorMessage = errorMessage.takeIf { isError },
                        autofillContentType = autofillContentType,
                    ).padding(FieldPadding),
            enabled = enabled,
            readOnly = readOnly,
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

/**
 * Everything a screen reader needs from the field itself.
 *
 * On the field's own node, because that is the node a screen reader lands on: the caption above the
 * well and the sentence below it are separate nodes with no association to it, which is why a field
 * in this app used to announce its value, the word "edit box", and nothing else — no name, no
 * failure (accessibility audit 2026-08-05, CR-2).
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
                labelText = "Server address",
                placeholder = { Text(text = "http://192.168.1.10:8096") },
            )
            JellyfinTextField(
                value = "claude",
                onValueChange = {},
                label = { Text(text = "USERNAME") },
                labelText = "Username",
            )
            JellyfinTextField(
                value = "nope",
                onValueChange = {},
                isError = true,
                labelText = "Server address",
                supportingText = { Text(text = "That server did not answer.") },
                errorMessage = "That server did not answer.",
            )
        }
    }
}
