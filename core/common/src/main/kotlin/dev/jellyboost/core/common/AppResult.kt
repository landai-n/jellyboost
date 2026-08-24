package dev.jellyboost.core.common

/**
 * Deliberately not `kotlin.Result`: an exhaustive, typed error channel ([AppError]) rather than an arbitrary
 * [Throwable], usable from Compose state holders.
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

fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> null
    }

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }
