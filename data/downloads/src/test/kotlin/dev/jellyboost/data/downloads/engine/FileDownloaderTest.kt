package dev.jellyboost.data.downloads.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
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
            every { clientInfo } returns ClientInfo(name = "Jellyboost", version = "0.1.0")
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
                """MediaBrowser Client="Jellyboost", Version="0.1.0", DeviceId="device-1", """ +
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

    // ---- the transfer that cannot be resumed ---------------------------------------------------

    @Test
    fun `a transcode never asks the server to resume it`() =
        runTest {
            val target = file("movie.mkv", content = "half of one encode")
            val downloader = downloader(respondWith = ok("a whole other encode".toByteArray()))

            downloader.download(
                URL,
                target,
                UnconfinedTestDispatcher(testScheduler),
                transcoded = true,
            ) { _, _ -> }

            // The partial file is a bookmark into an encode that no longer exists anywhere.
            requests.single().header("Range").shouldBeNull()
        }

    @Test
    fun `a transcode the server answers 206 for is still restarted from zero`() =
        runTest {
            val target = file("movie.mkv", content = "half of one encode")
            // A server that honours `Range` on a live transcode hands back the *second* encode's
            // bytes labelled as a continuation of the first. Appending them makes a file that still
            // opens, still ends in Cues, and would earn a SeekHead pointing into the wrong encode
            // (docs/notes/audit-2026-07.md, MKV-10).
            val downloader =
                downloader(respondWith = partial("a whole other encode".toByteArray(), from = 18, total = 38))

            val written =
                downloader.download(
                    URL,
                    target,
                    UnconfinedTestDispatcher(testScheduler),
                    transcoded = true,
                ) { _, _ -> }

            written shouldBe 20L
            target.readText() shouldBe "a whole other encode"
        }

    @Test
    fun `a restarted transcode is fed to the chunk sink from its first byte`() =
        runTest {
            val target = file("movie.mkv", content = "half of one encode")
            val body = "a whole other encode".toByteArray()
            val downloader = downloader(respondWith = partial(body, from = 18, total = 38))
            val seen = mutableListOf<Byte>()

            downloader.download(
                URL,
                target,
                UnconfinedTestDispatcher(testScheduler),
                chunkSink = { buffer, offset, length -> seen += buffer.copyOfRange(offset, offset + length).toList() },
                transcoded = true,
            ) { _, _ -> }

            // The scanner is only wired up for a body that starts at byte zero. Before the restart
            // was made unconditional, a 206 here silently switched the size projection off.
            seen.toByteArray().contentEquals(body) shouldBe true
        }

    @Test
    fun `a file the server already holds is still resumed`() =
        runTest {
            val target = file("movie.mkv", content = "hello ")
            val downloader = downloader(respondWith = partial("world".toByteArray(), from = 6, total = 11))

            downloader.download(
                URL,
                target,
                UnconfinedTestDispatcher(testScheduler),
                transcoded = false,
            ) { _, _ -> }

            // The restart is for transcodes only; an ORIGINAL download is the same bytes every time
            // and resuming one is the whole point of the engine.
            requests.single().header("Range") shouldBe "bytes=6-"
            target.readText() shouldBe "hello world"
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
    fun `the copy loop fills its buffer before writing, so a chunk is a whole buffer or the tail`() =
        runTest {
            // okio hands back one 8 KB segment per read however large the array offered is, and
            // `RandomAccessFile` is unbuffered — each read used to become its own `pwrite`, eight
            // per 64 KB (audit 2026-08-08, PERF-12). The tap sees exactly what was written, so the
            // chunk lengths are the write sizes.
            val bytes = ByteArray(FileDownloader.BUFFER_BYTES * 2 + TAIL_BYTES) { (it % 251).toByte() }
            val downloader = downloader(respondWith = ok(bytes))
            val target = file("movie.mkv")
            val chunkLengths = mutableListOf<Int>()

            downloader.download(
                URL,
                target,
                UnconfinedTestDispatcher(testScheduler),
                chunkSink = { _, _, length -> chunkLengths += length },
            ) { _, _ -> }

            chunkLengths shouldContainExactly
                listOf(FileDownloader.BUFFER_BYTES, FileDownloader.BUFFER_BYTES, TAIL_BYTES)
            // And the batching is byte-exact: a body that does not divide by the buffer must land
            // whole, tail included.
            target.readBytes().contentEquals(bytes) shouldBe true
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
    fun `cancelling while a body read is blocked cancels the OkHttp call itself`() =
        runTest {
            // The DL-01 wedge: once the headers have arrived, the `await()` continuation has
            // resumed and its `invokeOnCancellation` can no longer reach the call. A half-open
            // socket then blocks `input.read()` forever, coroutine cancellation cannot interrupt
            // it, and the drain lease is held for the rest of the process. The fix holds the call
            // for the whole body, so cancelling the coroutine fails the blocked read immediately.
            // Without it this test times out rather than merely failing.
            val calls = mutableListOf<Call>()
            val firstBytesServed = CompletableDeferred<Unit>()
            val body = BlockingBody(firstBytesServed) { calls.single().isCanceled() }
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body)
                                .build()
                        },
                    ).build()
            val factory = Call.Factory { request -> client.newCall(request).also { calls += it } }
            val downloader = FileDownloader(factory, apiClient)
            val target = file("movie.mkv")

            val job =
                launch(Dispatchers.IO) {
                    downloader.download(URL, target, Dispatchers.IO) { _, _ -> }
                }
            withContext(Dispatchers.Default) { firstBytesServed.await() }

            job.cancelAndJoin()

            calls.single().isCanceled() shouldBe true
            // The bytes that made it stay: a cancelled transfer is a resumable one.
            target.exists() shouldBe true
        }

    @Test
    fun `cancelling leaves the partial file on disk`() =
        runTest {
            val target = file("movie.mkv")
            val downloader = downloader(respondWith = ok(ByteArray(FileDownloader.BUFFER_BYTES * 4)))

            // The callback parks the copy loop after the first 64 KB window, so the cancel below
            // always lands mid-body. Cancelling through a `var job` from inside the callback was
            // racy: the loop runs on OkHttp's dispatcher thread, which could stream the whole
            // four-window body before the test thread had even assigned the var.
            val firstWindowWritten = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.IO) {
                    downloader.download(URL, target, Dispatchers.IO) { bytes, _ ->
                        if (bytes >= FileDownloader.BUFFER_BYTES) {
                            firstWindowWritten.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
            withContext(Dispatchers.Default) { firstWindowWritten.await() }

            job.cancelAndJoin()

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

        /** A body remainder that is deliberately not a multiple of the copy buffer. */
        const val TAIL_BYTES = 1_000

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

/**
 * A body that serves one chunk and then behaves like a half-open TCP connection: the next read
 * blocks indefinitely, unblocking (with the `IOException` a torn-down socket raises) only once the
 * call has been cancelled — which is exactly what OkHttp's `Call.cancel()` does to a blocked read.
 */
private class BlockingBody(
    private val firstBytesServed: CompletableDeferred<Unit>,
    private val cancelled: () -> Boolean,
) : okhttp3.ResponseBody() {
    override fun contentLength(): Long = -1L

    override fun contentType(): okhttp3.MediaType? = null

    override fun source(): okio.BufferedSource {
        val raw =
            object : okio.Source {
                private var served = false

                override fun read(
                    sink: okio.Buffer,
                    byteCount: Long,
                ): Long {
                    if (!served) {
                        served = true
                        val chunk = ByteArray(1024)
                        sink.write(chunk)
                        return chunk.size.toLong()
                    }
                    firstBytesServed.complete(Unit)
                    while (!cancelled()) Thread.sleep(POLL_MILLIS)
                    throw java.io.IOException("socket closed by cancel")
                }

                override fun timeout(): okio.Timeout = okio.Timeout.NONE

                override fun close() = Unit
            }
        return raw.buffer()
    }

    private companion object {
        const val POLL_MILLIS = 5L
    }
}
