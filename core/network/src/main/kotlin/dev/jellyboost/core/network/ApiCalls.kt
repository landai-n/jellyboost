package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.cancellation.CancellationException

/**
 * **The** place SDK transport exceptions are translated into [AppError]; a second copy would drift apart on
 * exactly the codes that matter.
 *
 * [CancellationException] is deliberately rethrown — swallowing it into an [AppResult.Failure] leaves a dead
 * ViewModel scope rendering a bogus error.
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
 * The single source of truth for what a status code means to this app.
 *
 * - **401 and 403 → [AppError.Unauthorized].** A 403 is the shape a revoked token or a policy change takes
 *   on a Jellyfin server; calling it [AppError.Server] would keep it from ever reaching sign-out.
 * - **404 → [AppError.NotFound].** The item is gone, not the server, so retrying is pointless.
 *   [AppError.NotFound.id] is empty on purpose: this mapper sees an exception, not a request.
 * - **everything else → [AppError.Server]** carrying the code, which the delegating repository reads to tell
 *   "the server is down" (502/503/504) from "the server said no".
 *
 * Transport failures (network, timeout, TLS) stay distinct from server-side errors because they are the ones
 * the delegating repository treats as "fall back to offline".
 */
fun Throwable.toAppError(): AppError =
    when (this) {
        is InvalidStatusException ->
            when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> AppError.Unauthorized(this)
                HttpURLConnection.HTTP_NOT_FOUND -> AppError.NotFound(id = "")
                else -> AppError.Server(statusCode = status, cause = this)
            }

        is TimeoutException, is IOException -> AppError.Network(this)
        // Everything else the SDK raises for a call that never completed: SecureConnectionException,
        // InvalidContentException, MissingBaseUrlException…
        is ApiClientException -> AppError.Network(this)
        else -> {
            // Always worth a log line: either a transport type this mapper should learn about or a real bug,
            // and the Unknown error state hides it from the UI.
            Timber.e(this, "API error outside the known taxonomy")
            AppError.Unknown(this)
        }
    }

/** Room raises unchecked `SQLiteException`s, hence the broad catch. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> storageCall(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppResult.Failure(AppError.Storage(error))
    }
