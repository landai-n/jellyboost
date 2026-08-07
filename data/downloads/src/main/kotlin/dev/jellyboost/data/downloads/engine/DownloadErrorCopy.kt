package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.AppError
import dev.jellyboost.data.downloads.plan.NotDownloadableException
import dev.jellyboost.data.downloads.storage.StorageUnavailableException
import dev.jellyboost.data.toAppError

/**
 * Turns a download failure into the sentence the Queue tab prints under the item.
 *
 * `DownloadEntity.errorMessage` is user-visible copy, not a log line: it is rendered verbatim as
 * *"Download failed: %s"*. Storing `throwable.message` there leaked SDK internals onto the screen —
 * the M7 device walk showed a queue row reading *"Download failed: Required value baseUrl is null.
 * Provide it by setting ApiClient.baseUrl."*, which tells the user nothing they can act on.
 *
 * The taxonomy is `:data`'s [AppError] (via [toAppError]) so that a download failure and a browse
 * failure describe the same underlying problem the same way; only the *copy* is download-specific,
 * because the remedy is ("the download will retry", not "pull to refresh").
 *
 * Copy lives in Kotlin rather than in `strings.xml` for one reason: the message is written to Room
 * at the moment of failure and read back days later, so it cannot be re-resolved against the
 * device's current locale anyway — a row failed in French would still read English after the user
 * switched the device to German. This is now the *only* place that trade is made: the browse-side
 * mappers this one used to cite have moved behind `AppError.toUiText` (audit H8), which keeps the
 * resource id in state and resolves it at draw time. Doing the same here needs the row to store a
 * key rather than a sentence, which is a schema migration, not a copy change.
 */
internal object DownloadErrorCopy {
    /** The message stored on the row for [error]. Never contains exception text. */
    fun forFailure(error: Throwable): String =
        when {
            error is MissingMetadataException -> MISSING_METADATA
            error is NotDownloadableException -> NOT_A_FILE
            // Before the taxonomy, like the classifier: an unusable volume is transient (the queue
            // retries it), so the copy must not read as a dead end. It only ever reaches the row
            // once the retry budget is spent — a volume that stayed gone for all five attempts.
            error is StorageUnavailableException -> STORAGE
            error is DownloadHttpException -> forStatus(error.code)
            else ->
                when (val appError = error.toAppError()) {
                    is AppError.Network -> NETWORK
                    is AppError.Unauthorized -> UNAUTHORIZED
                    is AppError.NotFound -> GONE
                    is AppError.Server -> forStatus(appError.statusCode)
                    is AppError.Storage -> STORAGE
                    is AppError.ServerResolution -> NETWORK
                    is AppError.Unknown -> UNKNOWN
                }
        }

    private fun forStatus(statusCode: Int?): String =
        when (statusCode) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> UNAUTHORIZED
            HTTP_NOT_FOUND -> GONE
            null -> UNKNOWN
            else -> "The server couldn't send this download (error $statusCode)."
        }

    private const val NETWORK = "Couldn't reach your server. The download will retry."

    private const val UNAUTHORIZED =
        "Your server refused this download. Sign in again, or check that your account may download."

    private const val GONE = "This item is no longer on the server."

    private const val STORAGE = "Couldn't write to the download folder. Check the device's storage."

    private const val MISSING_METADATA =
        "This item's details are missing on this device. Remove the download and add it again."

    /**
     * A show or a season that was queued as if it were a file — only reachable for rows created
     * before containers were expanded into their episodes (DECISIONS.md, 2026-07-29). It says what
     * to do about it, because the row itself can never succeed.
     */
    private const val NOT_A_FILE =
        "This is a show or a season, not a single video. Remove it and download the episodes."

    // Unknown failures are classified PERMANENT (DownloadFailure.kt), so this must not promise a
    // retry the queue will not attempt — that was the exact lie an unclassified NPE used to tell.
    private const val UNKNOWN = "Something went wrong. Try the download again."

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
}
