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
 * Runs an SDK call and folds every failure mode of the Jellyfin SDK into an [AppError].
 *
 * **The** place transport exceptions are translated (audit DUP-1). It used to be three places —
 * an `internal apiCall` here, a public `runCatchingApi` in `:data`, and a verbatim copy of the
 * former inside `PlaybackInfoResolver` that existed only because `apiCall` could not be seen from
 * `:player` — and the three had drifted apart on exactly the codes that matter: a 403 was a server
 * fault in two of them and an authentication failure in the third. One function, public from the
 * module every caller already depends on, is what stops that happening again.
 *
 * [CancellationException] is deliberately rethrown — a cancelled coroutine is not a failure, and
 * swallowing it into an [AppResult.Failure] leaves a dead ViewModel scope rendering a bogus error.
 *
 * `inline` and non-`suspend` so it wraps suspending and blocking blocks alike; every caller is
 * inside a coroutine anyway.
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
 * Maps an SDK/transport exception onto [AppError]. The single source of truth for what a status
 * code means to this app.
 *
 * ### The status answers, and why they are these
 * - **401 and 403 → [AppError.Unauthorized].** `DelegatingJellyfinRepository` documents "401/403
 *   surfaced so the session layer can re-authenticate", and a 403 is precisely the shape a revoked
 *   token or a policy change takes on a Jellyfin server. Calling it [AppError.Server] instead —
 *   which two of the three pre-DUP-1 copies did — meant a 403 from `/PlaybackInfo` was reported as
 *   "the server is broken" and never reached sign-out.
 * - **404 → [AppError.NotFound].** The item is gone, not the server. `DownloadFailure` classifies
 *   it PERMANENT (no retry is going to make it exist) and `UserDataSyncer` abandons the row.
 *   [AppError.NotFound.id] is empty here on purpose: this mapper sees an exception, not a request,
 *   and inventing an id would be worse than admitting it does not know one.
 * - **everything else → [AppError.Server]** carrying the code, which is what the delegating
 *   repository reads to tell "the server is down" (502/503/504) from "the server said no".
 *
 * The transport-failure cases (network, timeout, TLS) are kept distinct from server-side errors
 * because they are the ones the delegating repository treats as "fall back to offline".
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
        // Everything else the SDK raises for a call that never completed: SecureConnectionException
        // (a TLS handshake it would not make), InvalidContentException, MissingBaseUrlException…
        is ApiClientException -> AppError.Network(this)
        else -> {
            // An exception outside the known taxonomy is always worth a log line: it is either a
            // transport type this mapper should learn about or a real bug (e.g. a response the
            // SDK's serializers reject), and the Unknown error state hides it from the UI.
            Timber.e(this, "API error outside the known taxonomy")
            AppError.Unknown(this)
        }
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
