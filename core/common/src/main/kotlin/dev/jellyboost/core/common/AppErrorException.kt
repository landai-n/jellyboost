package dev.jellyboost.core.common

/**
 * Carries an [AppError] across an API that can only report a [Throwable]. The one such API is Paging 3, whose
 * `LoadResult.Error` is typed on `Throwable`; wrapping keeps the domain taxonomy intact for the screens.
 */
class AppErrorException(
    val error: AppError,
) : Exception(error.toString(), (error as? AppError.Network)?.cause)

fun Throwable.appErrorOrNull(): AppError? = (this as? AppErrorException)?.error
