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
    /**
     * What the finished file is now expected to weigh, against [bytesTotal]'s "will not exceed"
     * (schema v6); `null` when the ceiling is still the best answer available.
     */
    val projectedBytes: Long? = null,
    /** `true` when [bytesTotal] is the size the file will be, not an upper bound (schema v6). */
    val sizeIsExact: Boolean = false,
    val errorMessage: String? = null,
    val item: JellyfinItem? = null,
) {
    /**
     * The denominator to show and to divide by: the projection when there is one, the ceiling
     * otherwise.
     *
     * The projection is clamped into `[bytesDownloaded, bytesTotal]` here as well as where it is
     * written, because the two arrive from Room in the same row but not necessarily from the same
     * instant — a progress write and a projection write can interleave, and a denominator below the
     * numerator would draw a progress bar past its own end.
     */
    val displayTotalBytes: Long
        get() {
            val ceiling = maxOf(bytesTotal, bytesDownloaded)
            return projectedBytes?.coerceIn(bytesDownloaded, ceiling) ?: bytesTotal
        }

    /** Transfer progress in `0f..1f`; `0f` while the total size is unknown. */
    val progress: Float
        get() =
            displayTotalBytes.takeIf { it > 0L }?.let {
                (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f)
            } ?: 0f

    /**
     * What the *Downloaded* tab groups by: the show an episode belongs to, or `null` for a film.
     *
     * `null` rather than falling back to [title]: a film has nothing to be grouped *with*, and a
     * heading is only worth its line when it says something the rows underneath do not.
     */
    val seriesKey: String? get() = seriesName?.takeIf { it.isNotBlank() }

    /**
     * How much the size on this row can be trusted, which is what decides how it is worded.
     *
     * The three cases are three genuinely different things, and collapsing them is what made a
     * finished 232 MB episode spend its whole download claiming 552 MB:
     * - [SizeCertainty.EXACT] — the server reported the size ([DownloadQuality.ORIGINAL]) or the
     *   transcode is a video stream copy, whose output is arithmetic. Say the number plainly.
     * - [SizeCertainty.APPROXIMATE] — a projection exists: measured from the media time the stream
     *   has actually delivered, or seeded from finished episodes of the same show at the same
     *   quality. It is the app's best guess and it will move, so it is hedged rather than promised.
     * - [SizeCertainty.CEILING] — nothing but the enqueue-time bound, `runtime × min(cap, source
     *   bitrate)`. The encoder routinely undershoots it on easy content (DECISIONS.md,
     *   2026-07-29), so it can only honestly be stated as a limit.
     */
    val sizeCertainty: SizeCertainty
        get() =
            when {
                !quality.isTranscoded || sizeIsExact -> SizeCertainty.EXACT
                projectedBytes != null -> SizeCertainty.APPROXIMATE
                else -> SizeCertainty.CEILING
            }

    /**
     * Whether the queue row offers *Pause*.
     *
     * Only an [DownloadQuality.ORIGINAL] download does. `/Videos/{id}/stream.mkv?static=false`
     * ignores an HTTP `Range` header — the server cannot seek into a file it has not finished
     * producing — so a paused transcode does not resume, it **restarts from zero**
     * (docs/features/download-quality.md, *"No resume"*). A pause button that silently throws away
     * however many hundred megabytes have arrived is not a pause, and offering it is worse than not
     * offering it: *Cancel* already says what it does, and says it honestly.
     *
     * Resume is still offered on a paused or failed transcoded row — the operation is legitimate,
     * it just costs the whole transfer again, and a row left `PAUSED` by an earlier build has to
     * have some way out.
     */
    val isPausable: Boolean get() = !quality.isTranscoded
}

/** How well the size on a [DownloadItem] is known; see [DownloadItem.sizeCertainty]. */
enum class SizeCertainty {
    /** The figure is what the file will weigh. */
    EXACT,

    /** A live or seeded projection — close, and still moving. */
    APPROXIMATE,

    /** An upper bound and nothing more. */
    CEILING,
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
