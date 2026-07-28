package dev.jellyfinnative.data.downloads.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit tests for [FileDownloader] — the HTTP Range resume engine the milestone's definition of done
 * is measured against ("a 2 GB movie resumes from byte offset after app kill").
 *
 * No server is involved: an OkHttp `Interceptor` answers every call with a canned response, which
 * makes the four cases that matter (`200`, `206`, `416`, everything else) directly expressible and
 * lets the whole thing run on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDownloaderTest {
    @TempDir
    lateinit var tempDir: Path

    private val requests = mutableListOf<Request>()

    private val apiClient =
        mockk<ApiClient>(relaxed = true) {
            every { clientInfo } returns ClientInfo(name = "jellyfin-native", version = "0.1.0")
            every { deviceInfo } returns DeviceInfo(id = "device-1", name = "test tablet")
            every { accessToken } returns "token"
        }

    // ---- fresh downloads --------------------------------------------------------------------

    @Test
    fun `a fresh download writes the whole body and sends no Range header`() =
        runTest {
            val downloader = downloader(respondWith = ok("hello world".toByteArray()))
            val target = file("movie.mkv")

            val written = downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { _, _ -> }

            written shouldBe 11L
            target.readText() shouldBe "hello world"
            requests.single().header("Range").shouldBeNull()
        }

    @Test
    fun `every request carries the Jellyfin authorization header`() =
        runTest {
            val downloader = downloader(respondWith = ok("x".toByteArray()))

            downloader.download(URL, file("movie.mkv"), UnconfinedTestDispatcher(testScheduler)) { _, _ -> }

            // Built by the SDK's own `AuthorizationHeaderBuilder`, so the field order and the
            // URL-encoded device name are the server's expectations, not ours.
            requests.single().header("Authorization")!! shouldBe
                """MediaBrowser Client="jellyfin-native", Version="0.1.0", DeviceId="device-1", """ +
                """Device="test+tablet", Token="token""""
        }

    // ---- resume -------------------------------------------------------------------------------

    @Test
    fun `a partial file is resumed from its byte offset`() =
        runTest {
            val target = file("movie.mkv", content = "hello ")
            val downloader = downloader(respondWith = partial("world".toByteArray(), from = 6, total = 11))

            val written = downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { _, _ -> }

            // The bytes already on disk are the bookmark; nothing else has to survive the kill.
            requests.single().header("Range") shouldBe "bytes=6-"
            written shouldBe 11L
            target.readText() shouldBe "hello world"
        }

    @Test
    fun `progress is reported as the file's total, not this run's bytes`() =
        runTest {
            val target = file("movie.mkv", content = "a".repeat(100))
            val downloader = downloader(respondWith = partial(ByteArray(50), from = 100, total = 150))
            val samples = mutableListOf<Pair<Long, Long>>()

            downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { bytes, total ->
                samples += bytes to total
            }

            // A resumed transfer must not restart the progress bar at zero.
            samples.last() shouldBe (150L to 150L)
        }

    @Test
    fun `a server that ignores Range restarts the file instead of appending a second copy`() =
        runTest {
            val target = file("movie.mkv", content = "stale bytes")
            // Some proxies answer 200 to a ranged request; appending would silently corrupt the file.
            val downloader = downloader(respondWith = ok("fresh".toByteArray()))

            val written = downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { _, _ -> }

            written shouldBe 5L
            target.readText() shouldBe "fresh"
        }

    @Test
    fun `an already-complete file transfers nothing`() =
        runTest {
            val target = file("movie.mkv", content = "hello world")
            val downloader = downloader(respondWith = rangeNotSatisfiable())
            val samples = mutableListOf<Pair<Long, Long>>()

            val written =
                downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { bytes, total ->
                    samples += bytes to total
                }

            written shouldBe 11L
            target.readText() shouldBe "hello world"
            // Reported as complete, so the queue closes the file out instead of retrying forever.
            samples shouldContainExactly listOf(11L to 11L)
        }

    // ---- progress -----------------------------------------------------------------------------

    @Test
    fun `progress is reported once per 64 KB buffer`() =
        runTest {
            val bytes = ByteArray(FileDownloader.BUFFER_BYTES * 3)
            val downloader = downloader(respondWith = ok(bytes))
            var callbacks = 0

            downloader.download(URL, file("movie.mkv"), UnconfinedTestDispatcher(testScheduler)) { _, _ ->
                callbacks++
            }

            callbacks shouldBe 3
        }

    @Test
    fun `an undeclared body length reports a total of zero rather than a wrong one`() =
        runTest {
            val downloader = downloader(respondWith = chunked("hello".toByteArray()))
            var lastTotal = -1L

            downloader.download(URL, file("movie.mkv"), UnconfinedTestDispatcher(testScheduler)) { _, total ->
                lastTotal = total
            }

            lastTotal shouldBe 0L
        }

    // ---- failures -----------------------------------------------------------------------------

    @Test
    fun `an error response is raised with its status code`() =
        runTest {
            val downloader = downloader(respondWith = error(code = 403))

            val thrown =
                shouldThrow<DownloadHttpException> {
                    downloader.download(URL, file("movie.mkv"), UnconfinedTestDispatcher(testScheduler)) { _, _ -> }
                }

            // The queue reads this code to decide whether to fall back to the video stream.
            thrown.code shouldBe 403
        }

    @Test
    fun `cancelling leaves the partial file on disk`() =
        runTest {
            val target = file("movie.mkv")
            val downloader = downloader(respondWith = ok(ByteArray(FileDownloader.BUFFER_BYTES * 4)))

            var job: Job? = null
            job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    downloader.download(URL, target, UnconfinedTestDispatcher(testScheduler)) { bytes, _ ->
                        if (bytes >= FileDownloader.BUFFER_BYTES) job?.cancel()
                    }
                }
            job.join()

            // Nothing in the engine ever deletes a file — that is exactly what makes it resumable.
            target.exists() shouldBe true
            target.length() shouldBe FileDownloader.BUFFER_BYTES.toLong()
        }

    // ---- helpers ------------------------------------------------------------------------------

    private fun downloader(respondWith: (Request) -> Response): FileDownloader {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        requests += chain.request()
                        respondWith(chain.request())
                    },
                ).build()
        return FileDownloader(client, apiClient)
    }

    private fun file(
        name: String,
        content: String? = null,
    ): File {
        val target = File(tempDir.toFile(), name)
        if (content != null) target.writeText(content)
        return target
    }

    private fun ok(body: ByteArray): (Request) -> Response = { request -> response(request, 200, body) }

    private fun partial(
        body: ByteArray,
        from: Long,
        total: Long,
    ): (Request) -> Response =
        { request ->
            response(request, 206, body, headers = mapOf("Content-Range" to "bytes $from-${total - 1}/$total"))
        }

    private fun rangeNotSatisfiable(): (Request) -> Response =
        { request ->
            response(request, 416, ByteArray(0))
        }

    private fun error(code: Int): (Request) -> Response = { request -> response(request, code, ByteArray(0)) }

    /** A body whose length the client cannot know in advance, as a chunked response would be. */
    private fun chunked(body: ByteArray): (Request) -> Response =
        { request ->
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(UnknownLengthBody(body))
                .build()
        }

    private fun response(
        request: Request,
        code: Int,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("response $code")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(body.toResponseBody(OCTET_STREAM))
            .build()

    private companion object {
        const val URL = "https://server.example/Items/1/Download"
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

/** A response body that refuses to declare its length, like a chunked transfer. */
private class UnknownLengthBody(
    private val bytes: ByteArray,
) : okhttp3.ResponseBody() {
    override fun contentLength(): Long = -1L

    override fun contentType(): okhttp3.MediaType? = null

    override fun source(): okio.BufferedSource = okio.Buffer().write(bytes)
}
