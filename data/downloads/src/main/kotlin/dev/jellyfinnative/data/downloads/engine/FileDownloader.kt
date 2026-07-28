package dev.jellyfinnative.data.downloads.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
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
 * ### Cancellation
 * Cancelling the coroutine cancels the OkHttp call and stops the write loop, leaving the partial
 * file in place — that is precisely what makes it resumable. Nothing here ever deletes a file.
 */
@Singleton
class FileDownloader
    @Inject
    constructor(
        private val callFactory: Call.Factory,
        private val apiClient: ApiClient,
    ) {
        /**
         * Fetches [url] into [target], appending to it when it already holds part of the file.
         *
         * @return the total number of bytes the file holds once this call returns.
         * @throws IOException on any transport or HTTP failure the caller should treat as a
         *   download failure.
         */
        suspend fun download(
            url: String,
            target: File,
            dispatcher: CoroutineDispatcher,
            onProgress: ProgressCallback,
        ): Long =
            withContext(dispatcher) {
                target.parentFile?.mkdirs()
                val existing = if (target.exists()) target.length() else 0L

                val response = execute(url, existing)
                response.use {
                    when (response.code) {
                        HTTP_RANGE_NOT_SATISFIABLE -> {
                            // Already complete. Report the final size so the caller can close the
                            // file out instead of retrying it forever.
                            onProgress.onProgress(existing, existing)
                            return@withContext existing
                        }

                        HTTP_PARTIAL -> writeBody(response, target, appendFrom = existing, onProgress)

                        HTTP_OK -> writeBody(response, target, appendFrom = 0L, onProgress)

                        else -> throw DownloadHttpException(response.code, url)
                    }
                }
            }

        private suspend fun execute(
            url: String,
            rangeStart: Long,
        ): Response {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", authorizationHeader())
                    .apply { if (rangeStart > 0L) header("Range", "bytes=$rangeStart-") }
                    .build()

            val response = callFactory.newCall(request).await()

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
         * Streams the body into [target].
         *
         * @param appendFrom where in the file the body belongs. Seeking rather than opening in
         *   append mode is what makes the `200`-after-`Range` case safe: position 0 plus
         *   [RandomAccessFile.setLength] truncates whatever a partial run had left behind.
         */
        private suspend fun writeBody(
            response: Response,
            target: File,
            appendFrom: Long,
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
                body.byteStream().use { input -> copy(input, output, appendFrom, expectedTotal, onProgress) }
            }
        }

        /**
         * The copy loop.
         *
         * OkHttp's stream hands back one okio segment (8 KB) per read, so the callback is driven by
         * *bytes accumulated* rather than by reads — otherwise the plan's "every 64 KB" would
         * silently be "every 8 KB" and the throttle above it would do eight times the work.
         */
        private suspend fun copy(
            input: InputStream,
            output: RandomAccessFile,
            appendFrom: Long,
            expectedTotal: Long,
            onProgress: ProgressCallback,
        ): Long {
            val buffer = ByteArray(BUFFER_BYTES)
            var written = appendFrom
            var sinceReport = 0

            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
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

        private fun authorizationHeader(): String =
            AuthorizationHeaderBuilder.buildHeader(
                clientName = apiClient.clientInfo.name,
                clientVersion = apiClient.clientInfo.version,
                deviceId = apiClient.deviceInfo.id,
                deviceName = apiClient.deviceInfo.name,
                accessToken = apiClient.accessToken,
            )

        companion object {
            /**
             * Read granularity, and therefore progress granularity — the plan's "callback per
             * 64 KB". Large enough that the loop is not syscall-bound on a fast LAN, small enough
             * that a paused download stops within milliseconds.
             */
            const val BUFFER_BYTES = 64 * 1024

            private const val HTTP_OK = 200
            private const val HTTP_PARTIAL = 206
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
