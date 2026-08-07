package dev.jellyboost.data

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Runs an SDK call and folds every failure into the domain [AppError] taxonomy.
 *
 * Coroutine cancellation is deliberately re-thrown: swallowing it into an [AppResult.Failure]
 * would leave a cancelled ViewModel scope rendering a bogus error state.
 *
 * Public rather than module-internal since M7: `:data:downloads` issues one SDK call of its own
 * (the full-fields re-fetch behind an enqueue) and must fold its failures into the *same* taxonomy,
 * not a parallel one.
 */
inline fun <T> runCatchingApi(block: () -> T): AppResult<T> =
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
fun Throwable.toAppError(): AppError =
    when (this) {
        is InvalidStatusException ->
            when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> AppError.Unauthorized(this)
                HttpURLConnection.HTTP_NOT_FOUND -> AppError.NotFound(id = "")
                else -> AppError.Server(statusCode = status, cause = this)
            }

        is TimeoutException, is SecureConnectionException, is IOException -> AppError.Network(this)
        else -> {
            // An exception outside the known taxonomy is always worth a log line: it is either a
            // transport type this mapper should learn about or a real bug (e.g. a response the
            // SDK's serializers reject), and the Unknown error state hides it from the UI.
            Timber.e(this, "API error outside the known taxonomy")
            AppError.Unknown(this)
        }
    }
