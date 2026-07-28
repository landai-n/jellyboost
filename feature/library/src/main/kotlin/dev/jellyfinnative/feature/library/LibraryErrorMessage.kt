package dev.jellyfinnative.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.appErrorOrNull

/** Turns the domain failure taxonomy into copy a user can act on. */
@Composable
internal fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> stringResource(R.string.library_error_network)
        is AppError.ServerResolution -> stringResource(R.string.library_error_network)
        is AppError.Unauthorized -> stringResource(R.string.library_error_unauthorized)
        is AppError.NotFound -> stringResource(R.string.library_error_not_found)
        is AppError.Server ->
            statusCode
                ?.let { stringResource(R.string.library_error_server_with_code, it) }
                ?: stringResource(R.string.library_error_server)

        is AppError.Storage -> stringResource(R.string.library_error_storage)
        is AppError.Unknown -> stringResource(R.string.library_error_unknown)
    }

/**
 * Copy for a Paging failure.
 *
 * `LoadState.Error` is typed on `Throwable`, so the repository wraps the domain error on its way
 * out (see `AppErrorException`); anything that is not ours falls back to the generic message.
 */
@Composable
internal fun Throwable.toPagingMessage(): String =
    appErrorOrNull()?.toMessage() ?: stringResource(R.string.library_error_unknown)
