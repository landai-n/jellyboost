package dev.jellyboost.core.ui.component

import androidx.compose.runtime.Immutable
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * One type rather than a `label` composable plus a `labelText` string, which a caller could spell
 * two different ways.
 *
 * @param text becomes the field node's `contentDescription` — an editable node's name, its value
 *   staying the text it holds (see [fieldNodeSemantics]).
 * @param caption the drawn spelling, muted for the screen reader so the caption and the field are
 *   not two stops saying the same word, the first spelled out letter by letter.
 */
@Immutable
data class FieldLabel(
    val text: String,
    val caption: String? = null,
) {
    companion object {
        /**
         * Spoken in sentence case, drawn uppercased. The locale-less `uppercase()` is `Locale.ROOT`'s,
         * which is what a caption drawn from an already-localized resource wants.
         */
        fun eyebrow(text: String): FieldLabel = FieldLabel(text = text, caption = text.uppercase())
    }
}

/**
 * Three states rather than `enabled`/`readOnly`/`isError`/`errorMessage`, two of whose combinations
 * were traps: an error with no message announces "invalid" and nothing else, and `enabled = false`
 * destroys the node a screen-reader user is standing on at the moment they press the button.
 */
@Immutable
sealed interface FieldState {
    data object Editable : FieldState

    /** `readOnly`, not disabled: the field keeps its focus, name and value and refuses keystrokes. */
    data object InFlight : FieldState

    /**
     * [message] draws nothing but is attached as `error(…)` semantics, so TalkBack says *what* is
     * wrong. Pass the same sentence the screen shows.
     */
    data class Error(
        val message: String,
    ) : FieldState
}

internal val FieldState.isReadOnly: Boolean get() = this == FieldState.InFlight

internal val FieldState.isError: Boolean get() = this is FieldState.Error

internal val FieldState.errorMessage: String? get() = (this as? FieldState.Error)?.message

/**
 * Marking a node as a secret ([androidx.compose.ui.semantics.password]) and masking its characters
 * (a [VisualTransformation]) are two mechanisms; as one choice a field can no longer be masked
 * without being announced as a password, or announced as one while showing its characters.
 */
@Immutable
sealed interface FieldContent {
    /** @param autofill `null` leaves the field out of autofill entirely — right for a search box. */
    data class Plain(
        val autofill: ContentType? = null,
    ) : FieldContent

    /**
     * @param revealed shows the characters on *screen* only; the node stays marked as a password, so
     *   a screen reader never reads a revealed value out loud.
     */
    data class Password(
        val revealed: Boolean = false,
    ) : FieldContent
}

internal val FieldContent.isSecret: Boolean get() = this is FieldContent.Password

/** A password's answer is not a parameter: there is exactly one honest one. */
internal val FieldContent.autofillContentType: ContentType?
    get() =
        when (this) {
            is FieldContent.Plain -> autofill
            is FieldContent.Password -> ContentType.Password
        }

internal val FieldContent.visualTransformation: VisualTransformation
    get() =
        when (this) {
            is FieldContent.Plain -> VisualTransformation.None
            is FieldContent.Password ->
                if (revealed) VisualTransformation.None else PasswordVisualTransformation()
        }
