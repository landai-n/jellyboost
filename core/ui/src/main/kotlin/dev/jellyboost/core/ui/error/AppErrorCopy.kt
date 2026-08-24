package dev.jellyboost.core.ui.error

import androidx.annotation.StringRes
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.text.UiText

/**
 * Only the branches whose wording genuinely differs per screen are slots; network, unauthorized and
 * storage are one sentence translated once.
 *
 * @param unknown always overridden: an unclassified failure can only be described by naming what
 *   was being done, which only the screen knows.
 */
data class AppErrorCopy(
    @StringRes val unknown: Int,
    @StringRes val notFound: Int = R.string.error_not_found_item,
    @StringRes val server: Int = R.string.error_server,
    @StringRes val serverWithCode: Int = R.string.error_server_with_code,
)

/**
 * [AppError.ServerResolution] shares the network copy on purpose: both are the same dead end with
 * the same first thing to try. The server-setup screen, which can say more, does not come here.
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
