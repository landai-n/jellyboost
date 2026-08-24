package dev.jellyboost.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.appErrorOrNull

@Composable
internal fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> stringResource(R.string.music_error_network)
        is AppError.ServerResolution -> stringResource(R.string.music_error_network)
        is AppError.Unauthorized -> stringResource(R.string.music_error_unauthorized)
        is AppError.NotFound -> stringResource(R.string.music_error_not_found)
        is AppError.Server ->
            statusCode
                ?.let { stringResource(R.string.music_error_server_with_code, it) }
                ?: stringResource(R.string.music_error_server)

        is AppError.Storage -> stringResource(R.string.music_error_storage)
        is AppError.Unknown -> stringResource(R.string.music_error_unknown)
    }

/**
 * `LoadState.Error` is typed on `Throwable`, so the repository wraps the domain error on the way out
 * (`AppErrorException`); anything not ours falls back to the generic message.
 */
@Composable
internal fun Throwable.toPagingMessage(): String =
    appErrorOrNull()?.toMessage() ?: stringResource(R.string.music_error_unknown)
