package dev.jellyfinnative.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.common.AppError

/** Turns the domain failure taxonomy into copy a user can act on. */
@Composable
internal fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> stringResource(R.string.search_error_network)
        is AppError.ServerResolution -> stringResource(R.string.search_error_network)
        is AppError.Unauthorized -> stringResource(R.string.search_error_unauthorized)
        is AppError.NotFound -> stringResource(R.string.search_error_not_found)
        is AppError.Server ->
            statusCode
                ?.let { stringResource(R.string.search_error_server_with_code, it) }
                ?: stringResource(R.string.search_error_server)

        is AppError.Storage -> stringResource(R.string.search_error_storage)
        is AppError.Unknown -> stringResource(R.string.search_error_unknown)
    }
