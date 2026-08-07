package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.network.jellyfinAuthorizationHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/**
 * An HTTP response the download cannot use, carrying the status code.
 *
 * The code is not decoration: `403` on the media file is how a server tells this client that the
 * user's `enableContentDownloading` policy is off, and the queue answers it by re-planning that one
 * file onto the static video stream instead of failing the item.
 */
class DownloadHttpException(
    val code: Int,
    url: String,
) : IOException("Unexpected response $code for $url")

/** Reported every [FileDownloader.BUFFER_BYTES]; the caller decides how often it reaches Room. */
fun interface ProgressCallback {
    /**
     * @param bytesDownloaded total bytes of the file present locally, *including* whatever a
     *   previous run had already written — not the bytes of this run.
     * @param bytesTotal the file's full size, or `0` when the server did not say.
     */
    suspend fun onProgress(
        bytesDownloaded: Long,
        bytesTotal: Long,
    )
}

/**
 * Sees every byte of the response body as it is written, in order and without gaps.
 *
 * The one consumer is [MkvClusterScanner], which reads a transcode's media timestamps out of the
 * stream so its finished size can be projected while it is still arriving. It is a *tap*, not a
 * transform: the bytes have already been written to disk when this is called, nothing here can
 * change them, and an implementation must not block — it runs inside the copy loop.
 */
fun interface MediaChunkSink {
    /** [length] bytes of the body, starting at [offset] in [buffer]. The array is reused. */
    fun onChunk(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )
}

/**
 * Downloads one file over OkHttp, resuming from whatever is already on disk.
 *
 * This is the engine the plan points at jellyfin-android's `downloads/FileDownloader.kt` for, and
 * the reason the plan chose OkHttp over the system `DownloadManager` in the first place: an
 * `Authorization` header, byte-level progress, and an HTTP `Range` request that picks up exactly
 * where a killed process left off.
 *
 * ### How resume works
 * The partial file *is* the bookmark. Its length is the resume offset, so no separate state has to
 * survive a process death and no state can disagree with the bytes on disk:
 *
 * - the request goes out as `Range: bytes=<existing length>-`;
 * - `206 Partial Content` → the server honoured it, and the body is appended from that offset;
 * - `200 OK` → the server ignored `Range` (some proxies do), so the file is truncated and rewritten
 *   from zero rather than silently corrupted by appending a second copy;
 * - `416 Range Not Satisfiable` → the file was already complete; nothing is transferred.
 *
 * ### The one thing that cannot be resumed
 * A **transcode** is not a file; it is an encode the server runs while it answers. A second run
 * produces different bytes at the same offset, so appending its body to the first run's prefix
 * splices two encodes into one container: a file that still opens, still has `Cues` ending exactly
 * at its last byte, and would earn a `SeekHead` pointing into the middle of the wrong encode
 * (docs/notes/audit-2026-07.md, MKV-10). The server usually says so itself by ignoring `Range` and
 * answering `200` — but that is the server being careful, not this client. So a transcoded
 * download never asks to resume: see [download]'s `transcoded` parameter.
 *
 * ### Cancellation
 * Cancelling the coroutine cancels the OkHttp call and stops the write loop, leaving the partial
 * file in place — that is precisely what makes it resumable. Nothing here ever deletes a file.
 *
 * The call is cancelled during the **body** as well as while awaiting the headers: the copy loop's
 * blocking `read` cannot be reached by coroutine cancellation on its own, so a watcher coroutine
 * holds the [Call] and cancels it the moment the download's job is — which fails the blocked read
 * immediately instead of waiting out the socket (audit DL-01).
 */
@Singleton
class FileDownloader
    @Inject
    constructor(
        @DownloadHttpClient private val callFactory: Call.Factory,
        private val apiClient: ApiClient,
    ) {
        /**
         * Fetches [url] into [target], appending to it when it already holds part of the file.
         *
         * @param chunkSink an optional tap on the body as it is written; see [MediaChunkSink]. It
         *   is deliberately declared before [onProgress] so the callback stays a trailing lambda at
         *   every call site.
         * @param transcoded whether [url] is a live transcode rather than a file the server already
         *   holds. One is un-resumable by nature, so no `Range` is asked for and a `206` answered
         *   anyway is treated exactly like a `200`: truncate and start over. Half of a discarded
         *   encode costs bandwidth; splicing two encodes together costs the download
         *   (docs/notes/audit-2026-07.md, MKV-10).
         * @return the total number of bytes the file holds once this call returns.
         * @throws IOException on any transport or HTTP failure the caller should treat as a
         *   download failure.
         */
        @Suppress("LongParameterList")
        suspend fun download(
            url: String,
            target: File,
            dispatcher: CoroutineDispatcher,
            chunkSink: MediaChunkSink? = null,
            transcoded: Boolean = false,
            onProgress: ProgressCallback,
        ): Long =
            withContext(dispatcher) {
                target.parentFile?.mkdirs()
                val existing = if (target.exists()) target.length() else 0L
                val resumeFrom = if (transcoded) 0L else existing

                val call = newCall(url, resumeFrom)
                val response = awaitUsableResponse(call, url)
                cancellingCall(call) {
                    response.use {
                        when (response.code) {
                            HTTP_RANGE_NOT_SATISFIABLE -> {
                                // Already complete. Report the final size so the caller can close
                                // the file out instead of retrying it forever.
                                onProgress.onProgress(existing, existing)
                                existing
                            }

                            HttpURLConnection.HTTP_PARTIAL ->
                                writeBody(response, target, resumeFrom, chunkSink, onProgress)

                            HttpURLConnection.HTTP_OK -> writeBody(response, target, 0L, chunkSink, onProgress)

                            else -> throw DownloadHttpException(response.code, url)
                        }
                    }
                }
            }

        private fun newCall(
            url: String,
            rangeStart: Long,
        ): Call {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", authorizationHeader())
                    .apply { if (rangeStart > 0L) header("Range", "bytes=$rangeStart-") }
                    .build()
            return callFactory.newCall(request)
        }

        private suspend fun awaitUsableResponse(
            call: Call,
            url: String,
        ): Response {
            val response = call.await()

            // 416 is a success for our purposes (the file is already whole), so it is checked by
            // the caller rather than rejected here.
            if (!response.isSuccessful && response.code != HTTP_RANGE_NOT_SATISFIABLE) {
                val code = response.code
                response.close()
                throw DownloadHttpException(code, url)
            }
            return response
        }

        /**
         * Runs [block] with a watcher that cancels [call] if this coroutine is cancelled first.
         *
         * The `await()` continuation's own `invokeOnCancellation` has already resumed by the time
         * the body is being read, so without this nothing holds the call during `writeBody` — a
         * pause, a stop or a delete could only wait for the socket to fail on its own, which a
         * half-open connection never does (audit DL-01). The blocked `read` then throws an
         * `IOException` that [copy] converts back into the cancellation it really is.
         */
        private suspend fun <T> cancellingCall(
            call: Call,
            block: suspend () -> T,
        ): T =
            coroutineScope {
                val finished = AtomicBoolean(false)
                val watcher =
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            // Cancelled by the `finally` below when the body simply ended; only a
                            // cancellation arriving *while the transfer is live* touches the call.
                            if (!finished.get()) call.cancel()
                        }
                    }
                try {
                    block()
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }

        /**
         * Streams the body into [target].
         *
         * @param appendFrom where in the file the body belongs. Seeking rather than opening in
         *   append mode is what makes the `200`-after-`Range` case safe: position 0 plus
         *   [RandomAccessFile.setLength] truncates whatever a partial run had left behind.
         *
         * A [chunkSink] is only wired up when [appendFrom] is `0`. A sink reads the stream from its
         * beginning — a resumed body starts in the middle of a container it never saw the head of,
         * and feeding it that would produce a confident wrong answer instead of no answer. In
         * practice a transcode always lands here: it never asks to resume, so its body is always the
         * whole file and this method always rewrites from zero.
         */
        private suspend fun writeBody(
            response: Response,
            target: File,
            appendFrom: Long,
            chunkSink: MediaChunkSink?,
            onProgress: ProgressCallback,
        ): Long {
            // OkHttp 5 always hands back a body; an empty one simply declares length 0.
            val body = response.body
            val declaredLength = body.contentLength()
            // A chunked response declares -1; the callback contract says 0 means "unknown", which
            // keeps a progress bar at zero rather than showing it complete.
            val expectedTotal = if (declaredLength >= 0L) appendFrom + declaredLength else 0L

            return RandomAccessFile(target, "rw").use { output ->
                if (appendFrom == 0L) output.setLength(0L)
                output.seek(appendFrom)
                val sink = chunkSink?.takeIf { appendFrom == 0L }
                body.byteStream().use { input -> copy(input, output, appendFrom, expectedTotal, sink, onProgress) }
            }
        }

        /**
         * The copy loop.
         *
         * OkHttp's stream hands back one okio segment (8 KB) per read, so the callback is driven by
         * *bytes accumulated* rather than by reads — otherwise the plan's "every 64 KB" would
         * silently be "every 8 KB" and the throttle above it would do eight times the work.
         */
        @Suppress("LongParameterList")
        private suspend fun copy(
            input: InputStream,
            output: RandomAccessFile,
            appendFrom: Long,
            expectedTotal: Long,
            chunkSink: MediaChunkSink?,
            onProgress: ProgressCallback,
        ): Long {
            val buffer = ByteArray(BUFFER_BYTES)
            var written = appendFrom
            var sinceReport = 0

            while (true) {
                coroutineContext.ensureActive()
                val read =
                    try {
                        input.read(buffer)
                    } catch (error: IOException) {
                        // A cancelled coroutine cancels the OkHttp call (see [cancellingCall]),
                        // and the blocked read then fails with an IOException. The caller asked
                        // for a cancellation, not a failure — let the cancellation win.
                        coroutineContext.ensureActive()
                        throw error
                    }
                if (read == -1) break
                output.write(buffer, 0, read)
                // After the write, never before: the tap must not be able to affect the file.
                chunkSink?.onChunk(buffer, 0, read)
                written += read
                sinceReport += read
                if (sinceReport >= BUFFER_BYTES) {
                    sinceReport = 0
                    onProgress.onProgress(written, expectedTotal)
                }
            }

            // The tail: a file that ended mid-window would otherwise never report its last bytes,
            // leaving the row a few kilobytes short of complete forever.
            if (sinceReport > 0) onProgress.onProgress(written, expectedTotal)
            return written
        }

        /**
         * No same-origin guard around [jellyfinAuthorizationHeader] here (unlike
         * `JellyfinAuthInterceptor`): [url] is always one `DownloadUrlFactory` built from this same
         * [ApiClient]'s own base URL, never a caller-supplied or redirect-followed one, so there is
         * no other origin the header could leak to.
         */
        private fun authorizationHeader(): String = jellyfinAuthorizationHeader(apiClient)

        companion object {
            /**
             * Read granularity, and therefore progress granularity — the plan's "callback per
             * 64 KB". Large enough that the loop is not syscall-bound on a fast LAN, small enough
             * that a paused download stops within milliseconds.
             */
            const val BUFFER_BYTES = 64 * 1024

            // No HttpURLConnection constant for 416 (the JDK's HTTP_* table predates WebDAV/Range).
            private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        }
    }

/**
 * Suspends on an OkHttp call, cancelling it if the coroutine is cancelled.
 *
 * `enqueue` rather than `execute` so the cancellation actually reaches the socket: a blocking
 * `execute` on a dispatcher thread would keep reading until the server gave up.
 */
internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )
        continuation.invokeOnCancellation { cancel() }
    }
