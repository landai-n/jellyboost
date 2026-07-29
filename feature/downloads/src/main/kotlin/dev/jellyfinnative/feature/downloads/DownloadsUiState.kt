package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageUsage

/** The two tabs the plan specifies for this screen (docs/PLAN.md, "Screens" → Downloads). */
enum class DownloadsTab {
    /** Finished downloads, grouped by show or film, with sizes and delete. */
    DOWNLOADED,

    /** Everything still queued, downloading, paused or failed. */
    QUEUE,
}

/**
 * A run of finished downloads that belong together.
 *
 * Episodes are gathered under their series, which is how a user thinks about what is on the device:
 * *three episodes of Westworld*, not *three files*. A film is a group of one and is drawn without a
 * heading — see [isSeries].
 */
data class DownloadGroup(
    val title: String,
    val items: List<DownloadItem>,
    /**
     * `true` when [title] is a series name, and therefore worth a heading over the rows.
     *
     * A film's heading would repeat its own row's title verbatim ("Dune" over "Dune"), which the
     * M9 device walk found on every film on the screen (docs/POLISH.md).
     */
    val isSeries: Boolean = false,
) {
    /** Bytes this group occupies on disk. */
    val bytesOnDisk: Long get() = items.sumOf { it.bytesOnDisk }
}

/** Everything the Downloads screen draws. */
data class DownloadsUiState(
    val selectedTab: DownloadsTab = DownloadsTab.DOWNLOADED,
    val downloaded: List<DownloadGroup> = emptyList(),
    val queue: List<DownloadItem> = emptyList(),
    /** Transfer speed in bytes per second, keyed by item id; absent while nothing is moving. */
    val speeds: Map<String, Long> = emptyMap(),
    val storage: StorageUsage = StorageUsage(),
    val wifiOnly: Boolean = true,
    val isLoading: Boolean = true,
    /** One-shot message for the snackbar; cleared by `DownloadsViewModel.consumeMessage`. */
    val userMessage: DownloadsMessage? = null,
) {
    /** `true` when there is nothing at all on the device and nothing queued. */
    val isEmpty: Boolean get() = downloaded.isEmpty() && queue.isEmpty()
}

/**
 * One-shot messages this screen can raise.
 *
 * An enum rather than a string so the ViewModel stays free of resources and the copy lives in
 * `strings.xml`, matching `:feature:detail`'s `UserMessage`.
 */
enum class DownloadsMessage {
    /** A delete could not remove the files or the rows. */
    DeleteFailed,

    /** Pause, resume or reorder failed. */
    ActionFailed,
}

/**
 * Splits the flat download list into the two tabs and groups the finished half.
 *
 * Both tabs come from **one** Room query rather than two: a download moves between them by changing
 * status, and two independent queries would let the UI briefly show an item in neither tab (or in
 * both) while they settled.
 *
 * Series and films are ordered together alphabetically rather than in two blocks: the list is short
 * and read by title, and a user looking for *Dune* should not first have to work out whether the
 * app filed it as a series.
 */
internal fun List<DownloadItem>.toGroups(): List<DownloadGroup> {
    val (episodes, films) =
        filter { it.status == DownloadStatus.DOWNLOADED }.partition { it.seriesKey != null }

    val series =
        episodes
            .groupBy { requireNotNull(it.seriesKey) }
            .map { (name, items) ->
                DownloadGroup(title = name, items = items.sortedBy { it.title }, isSeries = true)
            }

    return (series + films.map { DownloadGroup(title = it.title, items = listOf(it)) })
        .sortedBy { it.title.lowercase() }
}

/** The queue tab's contents: everything not finished, in queue order. */
internal fun List<DownloadItem>.toQueue(): List<DownloadItem> =
    filter { it.status != DownloadStatus.DOWNLOADED }
        .sortedBy { it.queuePosition }
