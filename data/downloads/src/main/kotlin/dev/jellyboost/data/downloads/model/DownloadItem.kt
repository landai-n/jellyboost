package dev.jellyboost.data.downloads.model

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * One row of the Downloads screen — a download joined to the item it belongs to. [item] can be
 * `null`: a wiped cache or an unreadable blob must degrade to a row with a title rather than to an
 * invisible download whose files nobody can delete, which is why [title] and [seriesName] are
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
    val quality: DownloadQuality = DownloadQuality.ORIGINAL,
    /** What the finished file is now expected to weigh; `null` when [bytesTotal]'s ceiling is still the best answer. */
    val projectedBytes: Long? = null,
    /** `true` when [bytesTotal] is the size the file will be, not an upper bound. */
    val sizeIsExact: Boolean = false,
    val errorMessage: String? = null,
    val item: JellyfinItem? = null,
) {
    /**
     * The projection when there is one, the ceiling otherwise. Clamped here as well as where it is
     * written: the two arrive from Room in the same row but not necessarily from the same instant, and
     * a denominator below the numerator would draw a progress bar past its own end.
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
     * What the *Downloaded* tab groups by: the show an episode belongs to, the **album** a track
     * belongs to, or `null` for a film. One column serves both because it only ever meant "the heading
     * these rows belong under" — `DownloadEnqueuer` writes a track's album into [seriesName].
     */
    val seriesKey: String? get() = seriesName?.takeIf { it.isNotBlank() }

    /**
     * How much the size on this row can be trusted, which is what decides how it is worded. Collapsing
     * the three cases is what made a finished 232 MB episode spend its whole download claiming 552 MB:
     * - [SizeCertainty.EXACT] — the server reported the size, or the transcode is a video stream copy
     *   whose output is arithmetic. Say the number plainly.
     * - [SizeCertainty.APPROXIMATE] — a projection, measured from delivered media time or seeded from
     *   finished episodes of the same show. It will move, so it is hedged rather than promised.
     * - [SizeCertainty.CEILING] — the enqueue-time bound only, which the encoder routinely undershoots
     *   on easy content, so it can only honestly be stated as a limit.
     */
    val sizeCertainty: SizeCertainty
        get() =
            when {
                !quality.isTranscoded || sizeIsExact -> SizeCertainty.EXACT
                projectedBytes != null -> SizeCertainty.APPROXIMATE
                else -> SizeCertainty.CEILING
            }

    /**
     * Only an [DownloadQuality.ORIGINAL] download offers *Pause*: `?static=false` ignores an HTTP
     * `Range` — the server cannot seek into a file it has not finished producing — so a paused
     * transcode does not resume, it **restarts from zero**. Resume is still offered on a paused or
     * failed transcoded row, so one left `PAUSED` by an earlier build has a way out.
     */
    val isPausable: Boolean get() = !quality.isTranscoded
}

enum class SizeCertainty {
    EXACT,

    APPROXIMATE,

    CEILING,
}

/**
 * The storage header on the Downloads screen. [usedBytes] is walked from the filesystem rather than
 * summed from Room on purpose: it is the number the user can verify with a file manager, and a
 * mismatch with Room is exactly the orphaned-file bug this screen should make visible.
 */
data class StorageUsage(
    val usedBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val rootPath: String? = null,
)
