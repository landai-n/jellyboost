package dev.jellyfinnative.data.downloads.model

import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.JellyfinItem

/**
 * One row of the Downloads screen — a download joined to the item it belongs to.
 *
 * [item] can be `null`: the download row and the cached item row are written together at enqueue
 * time, but a wiped cache or an unreadable blob must degrade to a row with a title rather than to
 * an invisible download whose files nobody can delete. That is why [title] and [seriesName] are
 * denormalised onto the download row in the first place.
 */
data class DownloadItem(
    val itemId: String,
    val title: String,
    val seriesName: String?,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    /** Bytes this item actually occupies on disk, summed over its files. */
    val bytesOnDisk: Long,
    val queuePosition: Int,
    /** The download quality stamped on this row at enqueue time (schema v5). */
    val quality: DownloadQuality = DownloadQuality.ORIGINAL,
    val errorMessage: String? = null,
    val item: JellyfinItem? = null,
) {
    /** Transfer progress in `0f..1f`; `0f` while the total size is unknown. */
    val progress: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)

    /**
     * What the *Downloaded* tab groups by: the show an episode belongs to, or `null` for a film.
     *
     * `null` rather than falling back to [title]: a film has nothing to be grouped *with*, and a
     * heading is only worth its line when it says something the rows underneath do not.
     */
    val seriesKey: String? get() = seriesName?.takeIf { it.isNotBlank() }

    /**
     * `true` when [bytesTotal] is an upper bound rather than an exact figure.
     *
     * For [DownloadQuality.ORIGINAL] the server already reported the real file size
     * (`mediaSources[0].size`). For a transcoded step `DownloadEnqueuer.expectedBytes` can only
     * estimate from the source's runtime and bitrate cap — a correct ceiling, but one the encoder
     * routinely undershoots on easy content, so it must never be presented as an exact figure
     * (DECISIONS.md, 2026-07-29).
     */
    val isSizeCapped: Boolean get() = quality.isTranscoded
}

/**
 * The storage header on the Downloads screen.
 *
 * [usedBytes] is walked from the filesystem rather than summed from Room on purpose: it is the
 * number the user can verify with a file manager, and a mismatch with Room is exactly the kind of
 * orphaned-file bug this screen should make visible instead of hiding.
 */
data class StorageUsage(
    val usedBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val rootPath: String? = null,
)
