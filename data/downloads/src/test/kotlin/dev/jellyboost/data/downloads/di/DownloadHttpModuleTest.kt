package dev.jellyboost.data.downloads.di

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the download client's timeouts. `readTimeout(0)` is the wedge: a half-open TCP connection
 * blocks the copy loop's `read` forever, and the worker holds the process-wide drain lease until the
 * process dies. OkHttp's read timeout bounds the *silence between two bytes*, not the call's duration.
 */
class DownloadHttpModuleTest {
    @Test
    fun `the download client bounds the silence between two bytes`() {
        val client = DownloadHttpModule.provideDownloadHttpClient()

        client.readTimeoutMillis shouldBeGreaterThan 0
        client.readTimeoutMillis shouldBe (DownloadHttpModule.READ_TIMEOUT_SECONDS * 1_000L).toInt()
    }

    @Test
    fun `the download client bounds writes too`() {
        val client = DownloadHttpModule.provideDownloadHttpClient()

        client.writeTimeoutMillis shouldBeGreaterThan 0
    }

    @Test
    fun `redirects are still followed — the download endpoint can hand off to a storage backend`() {
        val client = DownloadHttpModule.provideDownloadHttpClient()

        client.followRedirects shouldBe true
        client.followSslRedirects shouldBe true
    }
}
