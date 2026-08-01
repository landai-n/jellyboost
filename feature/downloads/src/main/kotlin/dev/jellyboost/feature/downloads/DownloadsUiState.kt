package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageUsage

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
    /**
     * `true` once the projection itself collapsed — the Room/DataStore combine threw and will not
     * emit again.
     *
     * Distinct from an empty screen: "nothing downloaded" is an answer, this is the absence of one,
     * and the two must not look alike. It exists because the alternative was the worst possible
     * failure mode, a spinner that never stops (audit STAB-10).
     */
    val loadFailed: Boolean = false,
    /**
     * `true` while the *Cancel all* dialog is up.
     *
     * In the state rather than `remember`ed in the list, unlike the *Downloaded* tab's per-row
     * pending delete: this question is about the whole queue, which is the ViewModel's list, and the
     * answer has to survive a rotation and the recompositions a live queue causes twice a second —
     * a `remember`ed flag would not (precedent: `ItemDetailUiState.showDeleteConfirmation`).
     */
    val showCancelAllConfirmation: Boolean = false,
    /** One-shot message for the snackbar; cleared by `DownloadsViewModel.consumeMessage`. */
    val userMessage: DownloadsMessage? = null,
) {
    // Every value below is computed once, here, rather than recomputed on each read: before this
    // (docs/notes/audit-2026-07.md, PERF-06) these were `get()`-computed properties, so a queue
    // that writes progress two to six times a second re-filtered the same list on every one of the
    // several call sites that read them per emission — `QueueActionsBar`'s two `enabled` reads, and
    // `DownloadsViewModel.pauseAll()` reading both `pauseAllTargets` and `unpausableCount` off the
    // same state. A plain `val` in the constructor body runs once, when this instance is built —
    // exactly the ViewModel-projection step the audit asked for — and every read after that is a
    // field access.

    /** `true` when there is nothing at all on the device and nothing queued. */
    val isEmpty: Boolean = downloaded.isEmpty() && queue.isEmpty()

    /** The queue rows *Pause all* would pause — see [DownloadItem.isPauseTarget]. */
    val pauseAllTargets: List<DownloadItem> = queue.filter { it.isPauseTarget }

    /** The queue rows *Resume all* would put back in the queue — see [DownloadItem.isResumeTarget]. */
    val resumeAllTargets: List<DownloadItem> = queue.filter { it.isResumeTarget }

    /**
     * How many rows *Pause all* would deliberately leave running: transcodes, which cannot be
     * paused at all (`DownloadItem.isPausable`). This is the number the snackbar reports so a queue
     * that keeps moving after *Pause all* does not read as a bug.
     */
    val unpausableCount: Int = queue.count { !it.isResumeTarget && !it.isPausable }

    /** `true` while at least one queue row can actually be paused. */
    val canPauseAll: Boolean = pauseAllTargets.isNotEmpty()

    /** `true` while at least one queue row is paused or failed. */
    val canResumeAll: Boolean = resumeAllTargets.isNotEmpty()

    /**
     * The queue's tablet-summary numbers (2026 refresh, Phase 4d — DECISIONS.md 2026-08-01,
     * "Downloads restyle: a wide-layout queue summary").
     *
     * A pure derivation over [queue] and [speeds] — nothing here reads anything this class does not
     * already carry — computed once in the constructor body for the same reason every other `val`
     * above is: a queue that writes progress two to six times a second must not re-walk its own list
     * on every one of the several places the wide `QueueStatPanel` reads it from.
     */
    val queueStats: QueueStats =
        run {
            // Existing fields only, per DownloadItem's own accessors: displayTotalBytes is the
            // denominator DownloadRowsText already draws against (the projection when there is one,
            // the enqueue-time ceiling otherwise), clamped so a row already past its own total never
            // contributes a negative remainder.
            val remaining =
                queue.sumOf { (it.displayTotalBytes - it.bytesDownloaded).coerceAtLeast(0L) }
            val bytesPerSecond = queue.sumOf { speeds[it.itemId] ?: 0L }
            val eta =
                if (bytesPerSecond > 0L && remaining > 0L) {
                    // Same ceiling division and 24-hour guesswork guard as DownloadItem.etaSeconds
                    // (DownloadRows.kt) — an aggregate ETA is exactly as untrustworthy as a per-row
                    // one once it runs past a day, and the two must not disagree about where that
                    // line is.
                    ((remaining + bytesPerSecond - 1) / bytesPerSecond).takeIf { it <= ETA_GUARD_SECONDS }
                } else {
                    null
                }
            QueueStats(
                itemCount = queue.size,
                remainingBytes = remaining,
                bytesPerSecond = bytesPerSecond,
                etaSeconds = eta,
            )
        }
}

/**
 * The Downloads screen's wide-layout "QUEUE" stat panel, in one place rather than three separate
 * reads of [DownloadsUiState.queue] and [DownloadsUiState.speeds] (see [DownloadsUiState.queueStats]).
 */
data class QueueStats(
    /** How many rows are on the queue tab — [DownloadsUiState.queue]'s own size. */
    val itemCount: Int,
    /** Bytes still to transfer across every queued row, summed and clamped at zero per row. */
    val remainingBytes: Long,
    /** Every row's current transfer rate added together, in bytes per second; `0` while idle. */
    val bytesPerSecond: Long,
    /**
     * Ceiling-division ETA at [bytesPerSecond] for [remainingBytes], guarded the same way
     * [DownloadItem.etaSeconds] is — `null` while nothing is moving, nothing remains, or the
     * estimate would be beyond [ETA_GUARD_SECONDS] and so is guesswork rather than an estimate.
     */
    val etaSeconds: Long?,
) {
    /** `true` while nothing on the queue is transferring — the wide summary hides its speed/ETA line. */
    val isIdle: Boolean get() = bytesPerSecond <= 0L
}

/**
 * The half of [DownloadsUiState] that comes from storage.
 *
 * Kept apart from the half a tap changes ([LocalState]) so that the Room and DataStore flows behind
 * it can be shared with `WhileSubscribed` and actually **stop** when the screen goes away — see
 * `DownloadsViewModel`. Reaching this type at all means the projection answered, which is why
 * `isLoading` is not a field here: it is `false` by construction.
 */
internal data class DownloadsProjection(
    val downloaded: List<DownloadGroup> = emptyList(),
    val queue: List<DownloadItem> = emptyList(),
    val speeds: Map<String, Long> = emptyMap(),
    val progress: Map<String, Float> = emptyMap(),
    val storage: StorageUsage = StorageUsage(),
    val wifiOnly: Boolean = true,
    /** `true` for the one value the projection's `.catch` emits after it collapsed (audit STAB-10). */
    val loadFailed: Boolean = false,
)

/**
 * The half of [DownloadsUiState] nothing but a tap changes.
 *
 * It survives the projection being stopped and restarted: which tab the user was on must not depend
 * on whether a Room query happens to be subscribed.
 */
internal data class LocalState(
    val selectedTab: DownloadsTab = DownloadsTab.DOWNLOADED,
    val showCancelAllConfirmation: Boolean = false,
    val userMessage: DownloadsMessage? = null,
)

/** Folds the two halves into the one state the screen reads. */
internal fun DownloadsProjection.toUiState(local: LocalState): DownloadsUiState =
    DownloadsUiState(
        selectedTab = local.selectedTab,
        downloaded = downloaded,
        queue = queue,
        speeds = speeds,
        progress = progress,
        storage = storage,
        wifiOnly = wifiOnly,
        isLoading = false,
        loadFailed = loadFailed,
        showCancelAllConfirmation = local.showCancelAllConfirmation,
        userMessage = local.userMessage,
    )

/**
 * `true` when this queue row offers *Resume*: paused and failed rows both do, because retrying a
 * failure is the same operation, and for an original download the partial file means it costs only
 * the bytes that are missing.
 */
internal val DownloadItem.isResumeTarget: Boolean
    get() = status == DownloadStatus.PAUSED || status == DownloadStatus.ERROR

/**
 * `true` when this queue row offers *Pause*.
 *
 * A transcode is excluded ([DownloadItem.isPausable]): the server ignores `Range` on a file it is
 * still producing, so pausing one throws the whole transfer away rather than suspending it. The
 * per-row button and *Pause all* share this predicate on purpose — a bulk action that paused
 * something the row itself refuses to pause would be the same bug, once per queue.
 *
 * Only meaningful for a row on the queue tab (nothing here excludes a finished download).
 */
internal val DownloadItem.isPauseTarget: Boolean
    get() = !isResumeTarget && isPausable

/**
 * Where a tap on this row should start playback, in Jellyfin ticks.
 *
 * Mirrors `:feature:detail`'s `playbackStartTicks(JellyfinItem)` exactly, so a download and the
 * item's own detail page never disagree about where "Play" resumes: the cached [DownloadItem.item]
 * carries the same `userData` the detail page reads, kept in step by the same `UserDataEventBus`
 * both screens collect. `0L` — start from the beginning — whenever there is nothing to resume, or
 * the cached item itself is missing (a wiped cache still lets the row play from the top).
 */
internal val DownloadItem.playbackStartTicks: Long
    get() = item?.userData?.takeIf { it.isResumable }?.playbackPositionTicks ?: 0L

/**
 * One-shot messages this screen can raise.
 *
 * A type rather than a string so the ViewModel stays free of resources and the copy lives in
 * `strings.xml`, matching `:feature:detail`'s `UserMessage` — and, like it, a sealed interface
 * rather than an enum since [PausedKeepingTranscodes] carries counts (DECISIONS.md, 2026-07-29).
 */
sealed interface DownloadsMessage {
    /** A delete could not remove the files or the rows. */
    data object DeleteFailed : DownloadsMessage

    /** Pause, resume or reorder failed. */
    data object ActionFailed : DownloadsMessage

    /**
     * *Pause all* paused [pausedCount] rows and left [transcodingCount] transcodes downloading,
     * because a transcode cannot be paused without discarding it (`DownloadItem.isPausable`).
     *
     * Only raised when something was actually skipped: a queue that visibly keeps moving after
     * *Pause all* otherwise reads as the button having failed.
     */
    data class PausedKeepingTranscodes(
        val pausedCount: Int,
        val transcodingCount: Int,
    ) : DownloadsMessage
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
