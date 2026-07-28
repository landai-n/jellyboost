package dev.jellyfinnative.core.network

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** HTTP status the server answers with when credentials are missing or rejected. */
private const val HTTP_UNAUTHORIZED = 401

/**
 * Runs [block] and folds every failure mode of the Jellyfin SDK into an [AppError].
 *
 * This is the single place transport exceptions are translated, so no repository ever has to
 * know about `ApiClientException` and no UI layer ever sees a status code.
 * [CancellationException] is deliberately rethrown — a cancelled coroutine is not a failure.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> apiCall(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: InvalidStatusException) {
        AppResult.Failure(
            if (error.status == HTTP_UNAUTHORIZED) {
                AppError.Unauthorized(error)
            } else {
                AppError.Server(statusCode = error.status, cause = error)
            },
        )
    } catch (error: TimeoutException) {
        AppResult.Failure(AppError.Network(error))
    } catch (error: IOException) {
        AppResult.Failure(AppError.Network(error))
    } catch (error: ApiClientException) {
        // Covers SecureConnectionException, InvalidContentException, MissingBaseUrlException…
        AppResult.Failure(AppError.Network(error))
    } catch (error: Exception) {
        AppResult.Failure(AppError.Unknown(error))
    }

/**
 * Runs [block] against local storage (Room, `SecureCredentialStore`) and folds failures into
 * [AppError.Storage]. Room raises unchecked `SQLiteException`s, hence the broad catch.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> storageCall(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppResult.Failure(AppError.Storage(error))
    }
