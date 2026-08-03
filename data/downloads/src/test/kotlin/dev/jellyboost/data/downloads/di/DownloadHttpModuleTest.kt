package dev.jellyboost.data.downloads.di

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the one property of the download client a code review cannot see at a glance: its timeouts.
 *
 * `readTimeout(0)` was audit DL-01 — a half-open TCP connection blocked the copy loop's `read`
 * forever, and the worker held the process-wide drain lease (with a live foreground notification)
 * until the process died. OkHttp's read timeout bounds the *silence between two bytes*, not the
 * call's total duration, so a bounded value costs a healthy hour-long transfer nothing.
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
