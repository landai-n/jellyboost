package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.SocketTimeoutException

class ApiErrorMappingTest {
    @Test
    fun `401 is an authentication failure, so the session layer can act on it`() {
        InvalidStatusException(HTTP_UNAUTHORIZED).toAppError().shouldBeInstanceOf<AppError.Unauthorized>()
    }

    @Test
    fun `403 is an authentication failure too, not a server fault`() {
        // The exact drift a duplicated mapper would produce: 401/403 must reach the session layer, and
        // `DownloadFailure` classifies Unauthorized as PERMANENT — a 403 reported as Server(403) reaches neither.
        InvalidStatusException(HTTP_FORBIDDEN).toAppError().shouldBeInstanceOf<AppError.Unauthorized>()
    }

    @Test
    fun `404 is a missing item, not a server fault`() {
        // `UserDataSyncer` abandons a pending row on NotFound and `DownloadFailure` stops retrying; both would
        // keep hammering a deleted item if this were Server(404).
        InvalidStatusException(HTTP_NOT_FOUND).toAppError().shouldBeInstanceOf<AppError.NotFound>()
    }

    @Test
    fun `every other status keeps its code, which is what the offline fallback reads`() {
        val error = InvalidStatusException(HTTP_UNAVAILABLE).toAppError()
        error.shouldBeInstanceOf<AppError.Server>().statusCode shouldBe HTTP_UNAVAILABLE
        InvalidStatusException(HTTP_INTERNAL_ERROR)
            .toAppError()
            .shouldBeInstanceOf<AppError.Server>()
            .statusCode shouldBe HTTP_INTERNAL_ERROR
    }

    @Test
    fun `a call that never completed is a network failure, whichever way it failed`() {
        SocketTimeoutException("slow").toAppError().shouldBeInstanceOf<AppError.Network>()
        IOException("socket closed").toAppError().shouldBeInstanceOf<AppError.Network>()
        TimeoutException("gave up").toAppError().shouldBeInstanceOf<AppError.Network>()
        // A TLS handshake the SDK would not make: an ApiClientException, not an IOException, so this pins that
        // the base-class arm is still there.
        SecureConnectionException("bad certificate").toAppError().shouldBeInstanceOf<AppError.Network>()
        InvalidContentException("not json").toAppError().shouldBeInstanceOf<AppError.Network>()
    }

    @Test
    fun `anything outside the taxonomy is Unknown, and keeps its cause`() {
        val cause = IllegalStateException("a bug, not a transport failure")
        cause.toAppError().shouldBeInstanceOf<AppError.Unknown>().cause shouldBe cause
    }

    @Test
    fun `runCatchingApi returns the value when nothing goes wrong`() =
        runTest {
            runCatchingApi { 42 } shouldBe AppResult.Success(42)
        }

    @Test
    fun `runCatchingApi folds a failure through the same mapping`() =
        runTest {
            val result = runCatchingApi { throw InvalidStatusException(HTTP_FORBIDDEN) }
            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Unauthorized>()
        }

    @Test
    fun `runCatchingApi never swallows cancellation`() =
        runTest {
            // The one thing a catch-all must not do: absorbing a cancellation leaves a dead scope rendering
            // an error nobody asked about.
            assertThrows<CancellationException> {
                runCatchingApi { throw CancellationException("scope died") }
            }
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_UNAVAILABLE = 503
    }
}
