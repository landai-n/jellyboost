package dev.jellyboost.core.common

/**
 * Result type used across repository boundaries.
 *
 * Deliberately not `kotlin.Result`: we want an exhaustive, typed error channel ([AppError])
 * rather than an arbitrary [Throwable], and we want it usable from Compose state holders.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success
}

/** Returns the wrapped value, or `null` when this is an [AppResult.Failure]. */
fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> null
    }

/** Maps the success value, leaving a failure untouched. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }
