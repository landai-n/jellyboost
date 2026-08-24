package dev.jellyboost.core.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/**
 * State holding a resolved `String` is already in the build's language, and a Kotlin literal is
 * invisible to `MissingTranslation` — [Res] carries the id and resolves at draw time instead.
 *
 * [Raw] is the only escape hatch: text already worded outside the app (an ExoPlayer or Cast error)
 * has no resource to point at and must not be invented one.
 */
@Immutable
sealed interface UiText {
    /** [args] are values, not text: anything that itself needs translating must be its own resource. */
    @Immutable
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    @Immutable
    data class Raw(
        val value: String,
    ) : UiText

    companion object {
        fun res(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Res(id, args.toList())
    }
}

/** The spread is `stringResource`'s own vararg contract, over a handful of values. */
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
 * Prefer the `@Composable` overload on screens: this reads the *context's* configuration, the same
 * thing only when the caller holds a configuration-aware context.
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
