package dev.jellyboost.data.downloads.engine

import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.plan.NotDownloadableException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Unit tests for [DownloadErrorCopy].
 *
 * This is the string a user reads under a failed download, so the tests assert on the *copy* rather
 * than on a code — a mapping that silently starts printing an exception message again is exactly
 * the regression this guards against.
 */
class DownloadErrorCopyTest {
    @Test
    fun `an SDK precondition failure never reaches the user verbatim`() {
        val copy =
            DownloadErrorCopy.forFailure(
                IllegalStateException("Required value baseUrl is null. Provide it by setting ApiClient.baseUrl."),
            )

        copy shouldNotContain "baseUrl"
        copy shouldNotContain "ApiClient"
        copy shouldBe "Something went wrong. Try the download again."
    }

    @Test
    fun `a transport failure says the download will retry`() {
        DownloadErrorCopy.forFailure(IOException("connection reset")) shouldBe
            "Couldn't reach your server. The download will retry."
        DownloadErrorCopy.forFailure(SocketTimeoutException("read timed out")) shouldBe
            "Couldn't reach your server. The download will retry."
    }

    @Test
    fun `a refused download points at the account, not at the network`() {
        // 403 on the media file is the download-policy case the queue already retries on the video
        // stream; by the time it is stored, the fallback failed too.
        DownloadErrorCopy.forFailure(DownloadHttpException(code = 403, url = "https://server/x")) shouldBe
            "Your server refused this download. Sign in again, or check that your account may download."
        DownloadErrorCopy.forFailure(DownloadHttpException(code = 401, url = "https://server/x")) shouldBe
            "Your server refused this download. Sign in again, or check that your account may download."
    }

    @Test
    fun `a missing item says so plainly`() {
        DownloadErrorCopy.forFailure(DownloadHttpException(code = 404, url = "https://server/x")) shouldBe
            "This item is no longer on the server."
    }

    @Test
    fun `a server error keeps its status code, which is all it can offer`() {
        DownloadErrorCopy.forFailure(DownloadHttpException(code = 502, url = "https://server/x")) shouldBe
            "The server couldn't send this download (error 502)."
    }

    @Test
    fun `a row for a folder explains itself instead of quoting a 400`() {
        // The alternative is "The server couldn't send this download (error 400)" under a row
        // keyed on a season id — such rows still exist on devices, and a status code tells the user
        // nothing they can act on.
        val copy = DownloadErrorCopy.forFailure(NotDownloadableException(uuid(11)))

        copy shouldBe "This is a show or a season, not a single video. Remove it and download the episodes."
        copy shouldNotContain "400"
    }

    @Test
    fun `an item whose cached metadata is gone tells the user what to do about it`() {
        DownloadErrorCopy.forFailure(MissingMetadataException(uuid(1))) shouldBe
            "This item's details are missing on this device. Remove the download and add it again."
    }
}
