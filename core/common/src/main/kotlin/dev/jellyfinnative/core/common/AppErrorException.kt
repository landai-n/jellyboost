package dev.jellyfinnative.core.common

/**
 * Carries an [AppError] across an API that can only report a [Throwable].
 *
 * The one such API in the app is Paging 3: `PagingSource.LoadResult.Error` and `LoadState.Error`
 * are typed on `Throwable`, so a paged repository call cannot hand back an [AppResult.Failure].
 * Wrapping keeps the domain taxonomy intact — screens unwrap with [appErrorOrNull] and reuse the
 * same error copy as their non-paged siblings.
 */
class AppErrorException(
    val error: AppError,
) : Exception(error.toString(), (error as? AppError.Network)?.cause)

/** The [AppError] behind a paging failure, or `null` when the failure came from somewhere else. */
fun Throwable.appErrorOrNull(): AppError? = (this as? AppErrorException)?.error
