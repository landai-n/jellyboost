package dev.jellyboost.core.ui.component

import androidx.compose.runtime.Immutable
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * What a [JellyfinTextField] is called, in the two spellings a screen has for it.
 *
 * The field used to take these as two independent parameters — a `label` composable and a
 * `labelText` string — which every caller had to keep saying the same thing by hand, and which
 * nothing checked. That is the whole of the CR-2 fix resting on call-site discipline
 * (docs/notes/audit-2026-08-06-quality.md, CPX-8): a field given a caption and no [text] is
 * exactly as unlabelled to a screen reader as it was before the accessibility audit, and a field
 * whose two spellings drift apart says one word and draws another.
 *
 * @param text the field's name, as a *person* hears it. It becomes the field node's
 *   `contentDescription`, which is what an editable node uses as its name (its value stays the
 *   text it holds) — see [fieldNodeSemantics].
 * @param caption the same name as it is *drawn*, above the well, in the tracked-out eyebrow style.
 *   `null` for a field whose name is never drawn — a search box named by its placeholder, a
 *   dialog's single input. When it is drawn it is muted for the screen reader, so the caption and
 *   the field are not two stops saying the same word, the first of them spelled out letter by
 *   letter (accessibility audit 2026-08-05, CR-2/F16).
 */
@Immutable
data class FieldLabel(
    val text: String,
    val caption: String? = null,
) {
    companion object {
        /**
         * The form the mocks' labelled fields take: the name spoken in sentence case, drawn
         * uppercased. `uppercase()` with no locale is `Locale.ROOT`'s, which is what a caption
         * drawn from an already-localized resource wants.
         */
        fun eyebrow(text: String): FieldLabel = FieldLabel(text = text, caption = text.uppercase())
    }
}

/**
 * What the field is *doing*, as the three states a screen can put it in.
 *
 * This replaces four booleans-and-a-string that had to agree — `enabled`, `readOnly`, `isError`,
 * `errorMessage`. Two of those pairs were traps rather than combinations:
 *
 * - `isError = true` with no `errorMessage` is a field that announces "invalid" and nothing else,
 *   which is worse for a screen-reader user than saying nothing at all (audit CR-2). [Error]
 *   carries its sentence, so there is no way to raise one without the other.
 * - `enabled = false` for an in-flight request destroys the node the user is standing on at the
 *   exact moment they pressed the button, and a screen reader dropped mid-form has nowhere to land
 *   (audit F17). [InFlight] is `readOnly`: the field keeps its focus, its name and its value and
 *   refuses to be typed into. There is no disabled state, because no screen in this app wants one
 *   and its only use here was that mistake.
 */
@Immutable
sealed interface FieldState {
    /** The ordinary state: the field takes keystrokes. */
    data object Editable : FieldState

    /**
     * A request is in flight over what the field holds.
     *
     * The screens guard the edit in their state holders as well — belt and braces, and the guard
     * is the part a JVM test can hold still (DECISIONS.md, "an in-flight auth field stays
     * enabled"). `readOnly` says the same thing to the platform, so the IME does not offer a
     * keyboard for a field whose contents cannot move.
     */
    data object InFlight : FieldState

    /**
     * Something is wrong with what the field holds, and [message] is what.
     *
     * The message draws nothing — the supporting text and the screens' own error blocks own the
     * visuals — but it is attached to the field node as `error(…)` semantics, so TalkBack says
     * *what* is wrong rather than only that something is. Pass the same sentence the screen shows.
     */
    data class Error(
        val message: String,
    ) : FieldState
}

/** `true` while the field refuses keystrokes but keeps its node — see [FieldState.InFlight]. */
internal val FieldState.isReadOnly: Boolean get() = this == FieldState.InFlight

/** `true` while the field draws its error border and colours its supporting text. */
internal val FieldState.isError: Boolean get() = this is FieldState.Error

/** The sentence for the node's `error(…)` semantics, or `null` when nothing is wrong. */
internal val FieldState.errorMessage: String? get() = (this as? FieldState.Error)?.message

/**
 * What the field *holds*, which is what decides both how it is masked and how it is spoken.
 *
 * Marking a node as holding a secret ([androidx.compose.ui.semantics.password]) and masking the
 * characters on screen (a [VisualTransformation]) are two different mechanisms, and they were two
 * independent parameters: a field could be masked without being announced as a password, or
 * announced as one while showing its characters. Both are wrong, and both were one keystroke away
 * (audit F5 / CPX-8). Here they are the same choice made once.
 */
@Immutable
sealed interface FieldContent {
    /**
     * Ordinary text: never masked, never announced as a secret.
     *
     * @param autofill what the platform's autofill service should offer here — a username, an
     *   email. `null` (the default) leaves the field out of autofill entirely, which is right for
     *   a search box and wrong for a credential.
     */
    data class Plain(
        val autofill: ContentType? = null,
    ) : FieldContent

    /**
     * A secret. Always announced as one, and masked unless the caller's reveal toggle says
     * otherwise.
     *
     * @param revealed the eye button's state. It shows the characters on *screen*; the node stays
     *   marked as holding a password either way, so a screen reader keeps speaking it the way the
     *   platform speaks passwords rather than reading a revealed value out loud.
     */
    data class Password(
        val revealed: Boolean = false,
    ) : FieldContent
}

/** `true` when the node must be marked as holding a secret. */
internal val FieldContent.isSecret: Boolean get() = this is FieldContent.Password

/**
 * What autofill is offered here.
 *
 * A [FieldContent.Password] is not asked: there is exactly one honest answer for a field that
 * announces itself as a password, and making it a parameter only allows the dishonest ones.
 */
internal val FieldContent.autofillContentType: ContentType?
    get() =
        when (this) {
            is FieldContent.Plain -> autofill
            is FieldContent.Password -> ContentType.Password
        }

/** How the characters are drawn — the half of "is this a secret" the eye can see. */
internal val FieldContent.visualTransformation: VisualTransformation
    get() =
        when (this) {
            is FieldContent.Plain -> VisualTransformation.None
            is FieldContent.Password ->
                if (revealed) VisualTransformation.None else PasswordVisualTransformation()
        }
