package dev.jellyboost.core.ui.error

import androidx.annotation.StringRes
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.text.UiText

/**
 * The copy a screen supplies for the branches of [AppError] where its wording genuinely differs.
 *
 * Every other branch — network, unauthorized, storage — says the same thing whichever screen asked,
 * so it is not a slot: "can't reach your server" is one sentence, translated once, in `:core:ui`.
 * Before audit H8 it was five sentences in five files, three of them Kotlin literals, and they had
 * already drifted.
 *
 * @param unknown always overridden. An unclassified failure can only be described by naming what
 *   was being done — "loading this library", "starting playback" — and only the screen knows that.
 * @param notFound defaults to the item wording; a screen that asked about a *library* overrides it.
 * @param server the server answered and refused. The player overrides both this and
 *   [serverWithCode] because "could not start playback" is a different failure from "returned an
 *   error" and the remedy differs.
 */
data class AppErrorCopy(
    @StringRes val unknown: Int,
    @StringRes val notFound: Int = R.string.error_not_found_item,
    @StringRes val server: Int = R.string.error_server,
    @StringRes val serverWithCode: Int = R.string.error_server_with_code,
)

/**
 * Turns the domain failure taxonomy into copy a user can act on.
 *
 * Returns a [UiText] rather than a `String` so the ViewModel that decides *which* failure this is
 * does not also have to decide what language to say it in — see [UiText].
 *
 * [AppError.ServerResolution] shares the network copy on purpose: to the user, "the address you
 * typed answers nothing usable" and "your server is unreachable" are the same dead end with the
 * same first thing to try. The server-setup screen, which can say more, does not come through here.
 */
fun AppError.toUiText(copy: AppErrorCopy): UiText =
    when (this) {
        is AppError.Network -> UiText.res(R.string.error_network)
        is AppError.ServerResolution -> UiText.res(R.string.error_network)
        is AppError.Unauthorized -> UiText.res(R.string.error_unauthorized)
        is AppError.NotFound -> UiText.res(copy.notFound)
        is AppError.Server ->
            statusCode
                ?.let { UiText.res(copy.serverWithCode, it) }
                ?: UiText.res(copy.server)

        is AppError.Storage -> UiText.res(R.string.error_storage)
        is AppError.Unknown -> UiText.res(copy.unknown)
    }
