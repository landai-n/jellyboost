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
 * An HTTP response the download cannot use. The code is not decoration: `403` on the media file is
 * how a server tells this client the user's `enableContentDownloading` policy is off, and the queue
 * answers it by re-planning that one file onto the static video stream rather than failing the item.
 */
internal class DownloadHttpException(
    val code: Int,
    url: String,
) : IOException("Unexpected response $code for $url")

/** Reported every [FileDownloader.BUFFER_BYTES]; the caller decides how often it reaches Room. */
internal fun interface ProgressCallback {
    /**
     * @param bytesDownloaded bytes of the file present locally, *including* what a previous run wrote.
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
 * A *tap*, not a transform: the bytes have already been written to disk when this is called, and an
 * implementation must not block — it runs inside the copy loop. Chunks are whatever the copy loop
 * wrote, so nothing may assume a size; "in order and without gaps" is the whole contract, and it is
 * what lets an element straddling two chunks be carried forward.
 */
internal fun interface MediaChunkSink {
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
 * The partial file *is* the bookmark: its length is the resume offset, so no separate state has to
 * survive a process death and none can disagree with the bytes on disk. The request goes out as
 * `Range: bytes=<existing length>-`; `206` means the server honoured it and the body is appended,
 * `200` means it ignored `Range` (some proxies do) so the file is truncated and rewritten from zero
 * rather than corrupted by a second copy, and `416` means the file was already complete.
 *
 * A **transcode** is not a file but an encode the server runs while it answers: a second run produces
 * different bytes at the same offset, so appending splices two encodes into one container — a file
 * that still opens, still has `Cues` ending at its last byte, and would earn a `SeekHead` pointing
 * into the middle of the wrong encode. So a transcoded download never asks to resume; see [download]'s
 * `transcoded` parameter.
 *
 * Cancelling the coroutine cancels the call and stops the write loop, leaving the partial file in
 * place — nothing here ever deletes a file. The call is cancelled during the **body** as well: the
 * copy loop's blocking `read` is out of coroutine cancellation's reach, so a watcher coroutine holds
 * the [Call] and cancels it, failing the read at once instead of waiting out the socket.
 */
@Singleton
internal class FileDownloader
    @Inject
    constructor(
        @DownloadHttpClient private val callFactory: Call.Factory,
        private val apiClient: ApiClient,
    ) {
        /**
         * Fetches [url] into [target], appending to it when it already holds part of the file.
         *
         * @param transcoded whether [url] is a live transcode rather than a file the server already
         *   holds. One is un-resumable by nature, so no `Range` is asked for and a `206` answered
         *   anyway is treated exactly like a `200`: truncate and start over. Half of a discarded encode
         *   costs bandwidth; splicing two encodes together costs the download.
         * @return the total number of bytes the file holds once this call returns.
         * @throws IOException on any transport or HTTP failure the caller should treat as a failure.
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
                // `use` outside `cancellingCall`, not inside it: `cancellingCall` opens a
                // `coroutineScope`, and a scope entered in an already-cancelled coroutine throws before
                // it ever runs the block — leaving the response, and the connection under it, open.
                // Out here it is closed on every path, with no suspension point between the two lines.
                response.use {
                    cancellingCall(call) {
                        when (response.code) {
                            HTTP_RANGE_NOT_SATISFIABLE -> {
                                // Already complete. Report the final size so the caller can close the
                                // file out instead of retrying it forever.
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
         * Runs [block] with a watcher that cancels [call] if this coroutine is cancelled first. The
         * `await()` continuation's own `invokeOnCancellation` has already resumed by the time the body
         * is read, so without this a pause could only wait for a half-open socket to fail on its own.
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
                            // Only a cancellation arriving *while the transfer is live* touches the call.
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
         * @param appendFrom where in the file the body belongs. Seeking rather than opening in append
         *   mode is what makes the `200`-after-`Range` case safe: position 0 plus
         *   [RandomAccessFile.setLength] truncates whatever a partial run had left behind.
         *
         * A [chunkSink] is wired up only when [appendFrom] is `0`: a resumed body starts in the middle
         * of a container the sink never saw the head of, and feeding it that would produce a confident
         * wrong answer instead of no answer.
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
         * The copy loop. The buffer is *filled* before it is written: OkHttp's stream hands back one
         * 8 KB okio segment per read however large the array offered, and [RandomAccessFile] is
         * unbuffered, so writing each read straight through would turn one 64 KB write into eight —
         * ~15,300 syscalls a second on a gigabit LAN. The write, the tap and the progress callback
         * happen once per full buffer; cancellation is still checked once per *read*.
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
            // Bytes held in `buffer` that are not on disk yet — always < BUFFER_BYTES at loop top.
            var filled = 0

            suspend fun flush() {
                if (filled == 0) return
                output.write(buffer, 0, filled)
                // After the write, never before: the tap must not be able to affect the file.
                chunkSink?.onChunk(buffer, 0, filled)
                written += filled
                filled = 0
                onProgress.onProgress(written, expectedTotal)
            }

            while (true) {
                coroutineContext.ensureActive()
                val read =
                    try {
                        input.read(buffer, filled, buffer.size - filled)
                    } catch (error: IOException) {
                        // A cancelled coroutine cancels the OkHttp call (see [cancellingCall]) and the
                        // blocked read then fails; the caller asked for a cancellation, not a failure.
                        coroutineContext.ensureActive()
                        throw error
                    }
                if (read == -1) break
                filled += read
                if (filled == buffer.size) flush()
            }

            // The tail: a body that ended mid-buffer would otherwise never have its last bytes written.
            flush()
            return written
        }

        /**
         * No same-origin guard around [jellyfinAuthorizationHeader] (unlike `JellyfinAuthInterceptor`):
         * [url] is always one `DownloadUrlFactory` built from this same [ApiClient]'s own base URL,
         * never a caller-supplied or redirect-followed one, so there is no other origin to leak to.
         */
        private fun authorizationHeader(): String = jellyfinAuthorizationHeader(apiClient)

        companion object {
            /**
             * Read granularity, and therefore progress granularity. Large enough that the loop is not
             * syscall-bound on a fast LAN, small enough that a paused download stops within milliseconds.
             */
            const val BUFFER_BYTES = 64 * 1024

            // No HttpURLConnection constant for 416 (the JDK's HTTP_* table predates WebDAV/Range).
            private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        }
    }

/**
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
