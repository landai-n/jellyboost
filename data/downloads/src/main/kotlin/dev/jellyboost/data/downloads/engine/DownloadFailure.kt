package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.network.toAppError
import dev.jellyboost.data.downloads.plan.NotDownloadableException
import dev.jellyboost.data.downloads.storage.StorageUnavailableException
import java.net.HttpURLConnection

/** Whether trying the same download again in a minute could plausibly work. */
internal enum class FailureKind {
    TRANSIENT,

    PERMANENT,
}

/**
 * Decides whether a download failure is worth another attempt. Without it every non-cancellation
 * exception would move the row straight to `DownloadStatus.ERROR` — and because the drain loop carries
 * on, it would do that to *every remaining row* within seconds: one proxy `502` or one server restart
 * emptying a forty-episode queue, under a message promising a retry nothing performs.
 *
 * The taxonomy is `:core:network`'s [AppError] (via [toAppError]), deliberately the same one
 * [DownloadErrorCopy] reads: what the user is *told* about a failure and what the queue *does* about it
 * must be two readings of one classification.
 *
 * - **Transient** — transport failures of every shape, the server statuses that mean "not now" (`408`,
 *   `429`, and all of `5xx`: a Jellyfin server restarting answers `502` through its proxy for exactly
 *   as long as it takes), and a storage volume not usable *right now* ([StorageUnavailableException]).
 * - **Permanent** — anything the user or the server has to change first: `401`/`403`, `404`/`410`, the
 *   remaining `4xx`, a row whose cached metadata is missing, and a row naming a folder, not a file.
 * - **Unknown is permanent.** An exception outside the taxonomy is not a *recognised* transient
 *   condition, and retrying something with no evidence it is recoverable only spends battery.
 */
internal object DownloadFailureClassifier {
    fun classify(error: Throwable): FailureKind =
        when {
            error is MissingMetadataException -> FailureKind.PERMANENT
            error is NotDownloadableException -> FailureKind.PERMANENT
            // Before the taxonomy: it *is* an IllegalStateException, which would otherwise land in
            // AppError.Unknown and read as permanent — emptying the queue over a transient mount.
            error is StorageUnavailableException -> FailureKind.TRANSIENT
            // Checked before the taxonomy: it is an IOException, which would otherwise read as a
            // transport failure and hide the status the server actually sent.
            error is DownloadHttpException -> forStatus(error.code)
            else ->
                when (val appError = error.toAppError()) {
                    is AppError.Network -> FailureKind.TRANSIENT
                    is AppError.ServerResolution -> FailureKind.TRANSIENT
                    is AppError.Server -> forStatus(appError.statusCode)
                    is AppError.Unauthorized -> FailureKind.PERMANENT
                    is AppError.NotFound -> FailureKind.PERMANENT
                    is AppError.Storage -> FailureKind.PERMANENT
                    is AppError.Unknown -> FailureKind.PERMANENT
                }
        }

    private fun forStatus(statusCode: Int?): FailureKind =
        when {
            statusCode == null -> FailureKind.PERMANENT
            statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT || statusCode == HTTP_TOO_MANY_REQUESTS ->
                FailureKind.TRANSIENT
            statusCode >= HttpURLConnection.HTTP_INTERNAL_ERROR -> FailureKind.TRANSIENT
            else -> FailureKind.PERMANENT
        }

    // 429 has no HttpURLConnection constant (the JDK's HTTP_* table predates RFC 6585).
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
