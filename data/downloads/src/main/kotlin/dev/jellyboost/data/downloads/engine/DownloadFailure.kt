package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.network.toAppError
import dev.jellyboost.data.downloads.plan.NotDownloadableException
import dev.jellyboost.data.downloads.storage.StorageUnavailableException
import java.net.HttpURLConnection

/** Whether trying the same download again in a minute could plausibly work. */
internal enum class FailureKind {
    /** The server or the link was momentarily unavailable; the same request may well succeed next. */
    TRANSIENT,

    /** Nothing about waiting changes the answer — the item is gone, refused, or unusable. */
    PERMANENT,
}

/**
 * Decides whether a download failure is worth another attempt.
 *
 * Until this existed every non-cancellation exception moved the row straight to
 * `DownloadStatus.ERROR`, and because the drain loop carried on it did that to *every remaining
 * row* within seconds: one proxy 502 or one server restart emptied a forty-episode queue into the
 * failed state, under a message that promised a retry nothing performed
 * (docs/notes/audit-2026-07.md, STAB-01).
 *
 * The taxonomy is `:core:network`'s [AppError] (via [toAppError]), deliberately the same one
 * [DownloadErrorCopy] reads: what the user is *told* about a failure and what the queue *does*
 * about it must be two readings of one classification, or the copy starts lying again the moment a
 * new transport exception appears.
 *
 * ### Where the line falls
 * - **Transient** — transport failures of every shape (no route, reset, TLS handshake, read
 *   timeout), the server statuses that mean "not now" (`408`, `429`, and all of `5xx` — a
 *   Jellyfin server restarting answers `502` through its proxy for exactly as long as it takes),
 *   and a storage volume that is not usable *right now* ([StorageUnavailableException] — an
 *   ejected card, an MTP session, the window after boot before the card mounts).
 * - **Permanent** — anything the user or the server has to change first: `401`/`403` (signed out,
 *   or the account may not download), `404`/`410` (gone), the remaining `4xx`, a row whose cached
 *   metadata is missing, and a row that names a folder rather than a file.
 * - **Unknown is permanent.** An exception outside the taxonomy is, by definition, not a
 *   *recognised* transient condition; [toAppError] already logs it so the mapper can learn about
 *   it, and retrying something we have no evidence is recoverable only spends battery.
 */
internal object DownloadFailureClassifier {
    fun classify(error: Throwable): FailureKind =
        when {
            error is MissingMetadataException -> FailureKind.PERMANENT
            error is NotDownloadableException -> FailureKind.PERMANENT
            // Before the taxonomy: it *is* an IllegalStateException, which would otherwise land in
            // AppError.Unknown and read as permanent — the DL-10 queue-emptying failure mode.
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
