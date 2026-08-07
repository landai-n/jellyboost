package dev.jellyboost.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.appErrorOrNull
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.resolve
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * What this screen calls the two branches it does not share.
 *
 * It asked about a *library*, so a missing thing is a missing library, and an unclassified failure
 * happened while loading one. Everything else comes from `:core:ui`.
 */
internal val LibraryErrorCopy =
    AppErrorCopy(
        unknown = R.string.library_error_unknown,
        notFound = CoreUiR.string.error_not_found_library,
    )

/** Turns the domain failure taxonomy into copy a user can act on. */
@Composable
internal fun AppError.toMessage(): String = toUiText(LibraryErrorCopy).resolve()

/**
 * Copy for a Paging failure.
 *
 * `LoadState.Error` is typed on `Throwable`, so the repository wraps the domain error on its way
 * out (see `AppErrorException`); anything that is not ours falls back to the generic message.
 */
@Composable
internal fun Throwable.toPagingMessage(): String =
    appErrorOrNull()?.toMessage() ?: stringResource(R.string.library_error_unknown)
