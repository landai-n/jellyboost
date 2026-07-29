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
 * A run of finished downloads that belong together, or the one shared block that gathers every
 * standalone film.
 *
 * Episodes are gathered under their series, which is how a user thinks about what is on the device:
 * *three episodes of Westworld*, not *three files*. A lone film normally needs no heading of its
 * own — see [isSeries] — but once at least one series group is also on the tab, a bare film row
 * right after a series' last episode reads as one more episode of that series (there is nothing
 * marking where the series ended). [isMoviesSection] is the fix: every film is gathered under one
 * shared "Movies" heading in that case, placed after every series group, so every row on the tab
 * sits under some heading (DECISIONS.md, 2026-07-29, "Downloads: a shared Movies heading marks
 * where a series group ends").
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
    /**
     * `true` for the single shared block that gathers every standalone film once a series group
     * also exists — see [toGroups]. [title] is empty for this group: its heading text is a string
     * resource ("Movies"), resolved in Compose rather than stored here, the same reasoning
     * `DownloadsMessage` uses to keep the ViewModel free of Android resources.
     */
    val isMoviesSection: Boolean = false,
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
    /**
     * The fraction each queue row draws, keyed by item id — [DownloadProgressRatchet]'s answer, not
     * `DownloadItem.progress`, so a growing projection cannot make a bar retreat.
     */
    val progress: Map<String, Float> = emptyMap(),
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
 * When there is no series on the tab, films stay as their own headerless rows, ordered
 * alphabetically: the list is short and read by title, and with nothing else on the tab a bare row
 * cannot be mistaken for part of anything. Once at least one series group exists, every film is
 * gathered under one shared *Movies* group ([DownloadGroup.isMoviesSection]) placed after every
 * series group, so the boundary between the last series' episodes and the films is always marked
 * (DECISIONS.md, 2026-07-29). Series groups are always ordered alphabetically among themselves, and
 * so are the films inside the Movies group.
 */
internal fun List<DownloadItem>.toGroups(): List<DownloadGroup> {
    val (episodes, films) =
        filter { it.status == DownloadStatus.DOWNLOADED }.partition { it.seriesKey != null }

    val series =
        episodes
            .groupBy { requireNotNull(it.seriesKey) }
            .map { (name, items) ->
                DownloadGroup(title = name, items = items.sortedBy { it.title }, isSeries = true)
            }.sortedBy { it.title.lowercase() }

    if (series.isEmpty()) {
        // Nothing else on the tab to be confused with, so a bare row per film reads unambiguously.
        return films
            .map { DownloadGroup(title = it.title, items = listOf(it)) }
            .sortedBy { it.title.lowercase() }
    }

    if (films.isEmpty()) return series

    return series +
        DownloadGroup(
            title = "",
            items = films.sortedBy { it.title.lowercase() },
            isMoviesSection = true,
        )
}

/** The queue tab's contents: everything not finished, in queue order. */
internal fun List<DownloadItem>.toQueue(): List<DownloadItem> =
    filter { it.status != DownloadStatus.DOWNLOADED }
        .sortedBy { it.queuePosition }
