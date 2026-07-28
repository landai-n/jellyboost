package dev.jellyfinnative.data

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.IOException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * Runs an SDK call and folds every failure into the domain [AppError] taxonomy.
 *
 * Coroutine cancellation is deliberately re-thrown: swallowing it into an [AppResult.Failure]
 * would leave a cancelled ViewModel scope rendering a bogus error state.
 */
internal inline fun <T> runCatchingApi(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (
        @Suppress("TooGenericExceptionCaught") throwable: Throwable,
    ) {
        AppResult.Failure(throwable.toAppError())
    }

/**
 * Maps an SDK/transport exception onto [AppError].
 *
 * The transport-failure cases (network, timeout, TLS) are the ones the delegating repository
 * treats as "fall back to offline" in M6, so they are kept distinct from server-side errors.
 */
internal fun Throwable.toAppError(): AppError =
    when (this) {
        is InvalidStatusException ->
            when (status) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> AppError.Unauthorized(this)
                HTTP_NOT_FOUND -> AppError.NotFound(id = "")
                else -> AppError.Server(statusCode = status, cause = this)
            }

        is TimeoutException, is SecureConnectionException, is IOException -> AppError.Network(this)
        else -> AppError.Unknown(this)
    }
