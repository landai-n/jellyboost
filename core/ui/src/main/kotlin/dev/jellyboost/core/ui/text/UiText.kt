package dev.jellyboost.core.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/**
 * A sentence a ViewModel can decide on but only a composition can read.
 *
 * A ViewModel has no `Context` and no locale, so state that holds a `String` has already been
 * resolved — in whatever language the *build* was written in. That is exactly how home, detail and
 * playback ended up showing English error copy on 68 translated locales (audit H8): a Kotlin
 * literal is invisible to `MissingTranslation`, so the gate had nothing to catch. A [Res] carries
 * the resource id instead and is resolved at draw time, which puts the decision in the ViewModel
 * (where it is unit-testable) and the language on the device (where it belongs).
 *
 * [Raw] is the deliberate escape hatch, and the only one: text that arrives already worded from
 * outside the app — an ExoPlayer or Cast error string — has no resource to point at and must not be
 * invented one.
 */
@Immutable
sealed interface UiText {
    /**
     * A string resource, optionally formatted.
     *
     * [args] are the `%1$s`/`%1$d` positions, in order. They are values, not text: a status code, a
     * count, an item title. Anything that itself needs translating must be a resource of its own.
     */
    @Immutable
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** Wording that came from outside the app and cannot be translated. */
    @Immutable
    data class Raw(
        val value: String,
    ) : UiText

    companion object {
        /** [Res] without the list ceremony: `UiText.res(R.string.error_server_with_code, 502)`. */
        fun res(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Res(id, args.toList())
    }
}

/**
 * Resolves against the composition's configuration.
 *
 * The spread is `stringResource`'s own vararg contract, hence the suppression: the array it copies
 * holds at most a handful of values and is built once, for a state view the user is already reading.
 */
@Suppress("SpreadOperator")
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Res ->
            if (args.isEmpty()) {
                stringResource(id)
            } else {
                stringResource(id, *args.toTypedArray())
            }
    }

/**
 * Resolves outside a composition — a notification, a `MediaSession` error, a test.
 *
 * Prefer the `@Composable` overload on screens: this one reads the *context's* configuration, which
 * is only the same thing when the caller already holds a configuration-aware context.
 */
@Suppress("SpreadOperator") // As above: `Context.getString` is varargs.
fun UiText.resolve(context: Context): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Res ->
            if (args.isEmpty()) {
                context.getString(id)
            } else {
                context.getString(id, *args.toTypedArray())
            }
    }
