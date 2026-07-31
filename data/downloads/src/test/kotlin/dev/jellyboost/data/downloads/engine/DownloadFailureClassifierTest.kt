package dev.jellyboost.data.downloads.engine

import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.plan.NotDownloadableException
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [DownloadFailureClassifier].
 *
 * This is the table that decides whether a queue survives a server restart. It is asserted as a
 * table rather than one case per test because the property worth protecting is the *shape* of the
 * split — a new transport exception quietly landing on the permanent side is exactly the regression
 * that made one `502` empty a forty-episode queue.
 */
class DownloadFailureClassifierTest {
    @Test
    fun `transport failures are transient, whatever shape they arrive in`() {
        val transient =
            listOf<Throwable>(
                IOException("connection reset"),
                SocketTimeoutException("read timed out"),
                ConnectException("connection refused"),
                UnknownHostException("jellyfin.local"),
                TimeoutException("the server took too long"),
                // A TLS handshake that failed once can succeed on the next attempt (a proxy coming
                // up, a clock settling); five bounded tries is a cheap price for the ones that can.
                SecureConnectionException("handshake failed", IOException("bad record")),
            )

        transient.forEach { error ->
            withClue(error::class.simpleName.orEmpty()) {
                DownloadFailureClassifier.classify(error) shouldBe FailureKind.TRANSIENT
            }
        }
    }

    @Test
    fun `the server statuses that mean not now are transient`() {
        // 502 is what a Jellyfin server behind a reverse proxy answers for the whole of a restart —
        // the exact blip the retry policy exists for.
        val transient = listOf(408, 429, 500, 502, 503, 504)

        transient.forEach { code ->
            withClue("HTTP $code") {
                DownloadFailureClassifier.classify(httpError(code)) shouldBe FailureKind.TRANSIENT
                DownloadFailureClassifier.classify(sdkStatus(code)) shouldBe FailureKind.TRANSIENT
            }
        }
    }

    @Test
    fun `the server statuses that need the user or the server to change are permanent`() {
        // Retrying any of these on a 30-second backoff would spend an afternoon on an answer that
        // is not going to move.
        val permanent = listOf(400, 401, 403, 404, 410, 422)

        permanent.forEach { code ->
            withClue("HTTP $code") {
                DownloadFailureClassifier.classify(httpError(code)) shouldBe FailureKind.PERMANENT
                DownloadFailureClassifier.classify(sdkStatus(code)) shouldBe FailureKind.PERMANENT
            }
        }
    }

    @Test
    fun `a status carried by an HTTP exception outranks the fact that it is an IOException`() {
        // `DownloadHttpException` extends IOException, so a classifier that checked transport first
        // would read every server refusal as a network blip and retry a 403 five times.
        DownloadFailureClassifier.classify(DownloadHttpException(code = 403, url = "https://server/x")) shouldBe
            FailureKind.PERMANENT
    }

    @Test
    fun `a row that can never succeed is permanent, however many times it is tried`() {
        DownloadFailureClassifier.classify(MissingMetadataException(uuid(1))) shouldBe FailureKind.PERMANENT
        DownloadFailureClassifier.classify(NotDownloadableException(uuid(11))) shouldBe FailureKind.PERMANENT
    }

    @Test
    fun `an exception outside the taxonomy is permanent`() {
        // Not caution for its own sake: `toAppError` already logs an unrecognised throwable so the
        // mapper can learn about it, and retrying something with no evidence of being recoverable
        // only spends battery. The remedy for a misclassified one is to teach the mapper.
        DownloadFailureClassifier.classify(IllegalArgumentException("nonsense")) shouldBe FailureKind.PERMANENT
        DownloadFailureClassifier.classify(IllegalStateException("Could not create the download directory")) shouldBe
            FailureKind.PERMANENT
    }

    private fun httpError(code: Int) = DownloadHttpException(code = code, url = "https://server/x")

    private fun sdkStatus(code: Int) = InvalidStatusException(code)
}
