package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.DownloadKind
import dev.jellyboost.data.downloads.model.StorageUsage

enum class DownloadsTab {
    DOWNLOADED,
    QUEUE,
}

/**
 * Tracks group under their **album**, episodes under their **series**, and the identity is
 * [DownloadItem.groupKey] rather than the heading text: two shows of the same name, or an album and
 * a series sharing one, must stay two groups.
 *
 * @property key also the fold state's identity — see [DownloadsUiState.expandedGroups].
 * @property title empty for a group drawn without a heading, whose kind header already names it.
 */
data class DownloadGroup(
    val key: String,
    val title: String,
    val items: List<DownloadItem>,
    /** Series and albums fold; the films group is the MOVIES header's own list. */
    val isCollapsible: Boolean,
) {
    /**
     * A `val`, never a `get()`: [DownloadsUiState.downloadedBytes] sums it across every group, so a
     * computed property walked the whole *Downloaded* tab at the queue's two-to-six writes a second.
     */
    val bytesOnDisk: Long = items.sumOf { it.bytesOnDisk }

    val itemCount: Int = items.size

    internal fun itemOrNull(itemId: String): DownloadItem? = items.firstOrNull { it.itemId == itemId }
}

data class DownloadSection(
    val kind: DownloadKind,
    val groups: List<DownloadGroup>,
)

internal fun List<DownloadSection>.itemOrNull(itemId: String): DownloadItem? =
    firstNotNullOfOrNull { section -> section.groups.firstNotNullOfOrNull { it.itemOrNull(itemId) } }

data class DownloadsUiState(
    val selectedTab: DownloadsTab = DownloadsTab.DOWNLOADED,
    val downloaded: List<DownloadSection> = emptyList(),
    /**
     * Expanded, not collapsed, so the default empty set *is* the folded default a fresh screen
     * shows. Keys outlive the groups they name — a group whose last row was deleted leaves a stale
     * key behind, and a membership test does not care.
     */
    val expandedGroups: Set<String> = emptySet(),
    val queue: List<DownloadItem> = emptyList(),
    /** Bytes per second, keyed by item id; a key is **absent** while that row is not moving. */
    val speeds: Map<String, Long> = emptyMap(),
    /**
     * [DownloadProgressRatchet]'s answer, not `DownloadItem.progress`, so a growing projection
     * cannot make a bar retreat.
     */
    val progress: Map<String, Float> = emptyMap(),
    val storage: StorageUsage = StorageUsage(),
    val wifiOnly: Boolean = true,
    val isLoading: Boolean = true,
    /**
     * The projection itself collapsed — the Room/DataStore combine threw and will not emit again.
     * Distinct from an empty screen: "nothing downloaded" is an answer, this is the absence of one.
     */
    val loadFailed: Boolean = false,
    /**
     * In the state, not `remember`ed like the per-row pending delete: it must survive a rotation and
     * the recompositions a live queue causes twice a second.
     */
    val showCancelAllConfirmation: Boolean = false,
    /** One-shot; cleared by `DownloadsViewModel.consumeMessage`. */
    val userMessage: DownloadsMessage? = null,
) {
    // Every value below must stay a constructor-body `val`, never a `get()`: several call sites read
    // each one per emission, and the queue emits two to six times a second during a transfer.

    val isEmpty: Boolean = downloaded.isEmpty() && queue.isEmpty()

    /** A single kind needs no label above it; the rows are already all of one sort. */
    val showKindHeaders: Boolean = downloaded.size > 1

    /** Every section, folded or not: the storage header reports what is on disk, not what is shown. */
    val downloadedBytes: Long = downloaded.sumOf { section -> section.groups.sumOf { it.bytesOnDisk } }

    val pauseAllTargets: List<DownloadItem> = queue.filter { it.isPauseTarget }

    val resumeAllTargets: List<DownloadItem> = queue.filter { it.isResumeTarget }

    /**
     * Rows *Pause all* deliberately leaves running: transcodes, which cannot be paused at all. The
     * snackbar reports this, so a queue that keeps moving afterwards does not read as a bug.
     */
    val unpausableCount: Int = queue.count { !it.isResumeTarget && !it.isPausable }

    val canPauseAll: Boolean = pauseAllTargets.isNotEmpty()

    val canResumeAll: Boolean = resumeAllTargets.isNotEmpty()

    val queueStats: QueueStats =
        run {
            // Clamped per row: a row already past its own `displayTotalBytes` must not contribute a
            // negative remainder.
            val remaining =
                queue.sumOf { (it.displayTotalBytes - it.bytesDownloaded).coerceAtLeast(0L) }
            val bytesPerSecond = queue.sumOf { speeds[it.itemId] ?: 0L }
            val eta =
                if (bytesPerSecond > 0L && remaining > 0L) {
                    // Same ceiling division and guard as DownloadItem.etaSeconds (DownloadRows.kt);
                    // the aggregate and per-row ETAs must not disagree about where that line is.
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

    /**
     * The chrome must not take this whole class: its `List`/`Map` fields are unstable to the Compose
     * compiler, so nothing taking [DownloadsUiState] can ever skip a recomposition.
     */
    val chrome: DownloadsChromeState =
        DownloadsChromeState(
            selectedTab = selectedTab,
            storage = storageSummary(storage = storage, downloadedBytes = downloadedBytes),
            queueStats = queueStats,
            // Derived here, not in `WideSummary`, which would re-sum the whole queue on every one
            // of its recompositions.
            queueProgress =
                queue.sumOf { it.bytesDownloaded }.let { done ->
                    usageFraction(used = done, total = done + queueStats.remainingBytes)
                },
            hasQueue = queue.isNotEmpty(),
            wifiOnly = wifiOnly,
            canPauseAll = canPauseAll,
            canResumeAll = canResumeAll,
        )
}

/**
 * @property usedBytes the filesystem walk, **floored** at what the *Downloaded* tab adds up to: a
 *   walk that has not caught up with a just-finished download must not report less used space than
 *   the rows on screen account for.
 * @property totalBytes used **plus** free as the walk reported them, never the floored [usedBytes],
 *   so the bar's denominator stays the volume rather than growing with it.
 */
data class StorageSummary(
    val usedBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

internal fun storageSummary(
    storage: StorageUsage,
    downloadedBytes: Long,
): StorageSummary =
    StorageSummary(
        usedBytes = maxOf(storage.usedBytes, downloadedBytes),
        availableBytes = storage.availableBytes,
        totalBytes = storage.usedBytes + storage.availableBytes,
    )

internal fun usageFraction(
    used: Long,
    total: Long,
): Float = if (total <= 0L) 0f else (used.toFloat() / total).coerceIn(0f, 1f)

/**
 * Every field must stay a scalar or a value type declared **in this module** — no `List`, `Map`, or
 * `:data` type. The Compose compiler infers stability per compilation unit, so a `StorageUsage`
 * (from `:data`, built without the Compose plugin) reads as unstable and sinks the whole parameter
 * list; hence [StorageSummary], carrying the same three numbers.
 */
data class DownloadsChromeState(
    val selectedTab: DownloadsTab = DownloadsTab.DOWNLOADED,
    val storage: StorageSummary = StorageSummary(),
    val queueStats: QueueStats = QueueStats(itemCount = 0, remainingBytes = 0L, bytesPerSecond = 0L, etaSeconds = null),
    val queueProgress: Float = 0f,
    val hasQueue: Boolean = false,
    val wifiOnly: Boolean = true,
    val canPauseAll: Boolean = false,
    val canResumeAll: Boolean = false,
)

data class QueueStats(
    val itemCount: Int,
    val remainingBytes: Long,
    val bytesPerSecond: Long,
    /**
     * `null` while nothing is moving, nothing remains, or the estimate is beyond
     * [ETA_GUARD_SECONDS] and so is guesswork.
     */
    val etaSeconds: Long?,
) {
    val isIdle: Boolean get() = bytesPerSecond <= 0L
}

/**
 * The storage-fed half of [DownloadsUiState], kept apart from the half a tap changes ([LocalState])
 * so its Room and DataStore flows can be `WhileSubscribed`-shared and actually **stop** with the
 * screen. Reaching this type means the projection answered, so `isLoading` is not a field.
 */
internal data class DownloadsProjection(
    val downloaded: List<DownloadSection> = emptyList(),
    val queue: List<DownloadItem> = emptyList(),
    val speeds: Map<String, Long> = emptyMap(),
    val progress: Map<String, Float> = emptyMap(),
    val storage: StorageUsage = StorageUsage(),
    val wifiOnly: Boolean = true,
    val loadFailed: Boolean = false,
)

/**
 * Survives the projection being stopped and restarted: which tab the user was on, and which groups
 * they unfolded, must not depend on whether a Room query happens to be subscribed. The fold state
 * cannot live on [DownloadGroup] either — [DownloadGroupCache] hands back the same list instance
 * while the finished rows are unchanged, and folding a per-group flag into it would defeat that.
 */
internal data class LocalState(
    val selectedTab: DownloadsTab = DownloadsTab.DOWNLOADED,
    val expandedGroups: Set<String> = emptySet(),
    val showCancelAllConfirmation: Boolean = false,
    val userMessage: DownloadsMessage? = null,
)

internal fun DownloadsProjection.toUiState(local: LocalState): DownloadsUiState =
    DownloadsUiState(
        selectedTab = local.selectedTab,
        downloaded = downloaded,
        expandedGroups = local.expandedGroups,
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

/** Failed rows resume too: retrying a failure is the same operation, and the partial file survives. */
internal val DownloadItem.isResumeTarget: Boolean
    get() = status == DownloadStatus.PAUSED || status == DownloadStatus.ERROR

/**
 * A transcode is excluded: the server ignores `Range` on a file it is still producing, so pausing
 * one throws the transfer away rather than suspending it. The per-row button and *Pause all* must
 * keep sharing this predicate. Only meaningful on the queue tab — nothing here excludes a finished
 * download.
 */
internal val DownloadItem.isPauseTarget: Boolean
    get() = !isResumeTarget && isPausable

/**
 * Must mirror `:feature:detail`'s `playbackStartTicks(JellyfinItem)`, or a download and the item's
 * detail page disagree about where "Play" resumes. `0L` when there is nothing to resume or the
 * cached item is missing, so a wiped cache still plays from the top.
 */
internal val DownloadItem.playbackStartTicks: Long
    get() = item?.userData?.takeIf { it.isResumable }?.playbackPositionTicks ?: 0L

/** A type, not a string, so the ViewModel stays free of resources and the copy lives in strings.xml. */
sealed interface DownloadsMessage {
    data object DeleteFailed : DownloadsMessage

    data object ActionFailed : DownloadsMessage

    /**
     * Raised only when something was actually skipped: a queue that visibly keeps moving after
     * *Pause all* otherwise reads as the button having failed.
     */
    data class PausedKeepingTranscodes(
        val pausedCount: Int,
        val transcodingCount: Int,
    ) : DownloadsMessage
}

/**
 * Both tabs must keep coming from **one** Room query: a download moves between them by changing
 * status, and two independent queries would briefly show an item in neither tab, or in both.
 *
 * A row whose [DownloadItem.groupKey] is `null` still appears, in its kind's headerless catch-all:
 * no download may drop out of the only list it is deletable from.
 */
internal fun List<DownloadItem>.toSections(): List<DownloadSection> {
    val byKind = filter { it.status == DownloadStatus.DOWNLOADED }.groupBy { it.kind }
    return SECTION_ORDER.mapNotNull { kind ->
        val rows = byKind[kind]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        DownloadSection(
            kind = kind,
            groups = if (kind == DownloadKind.MOVIE) listOf(flatGroup(kind, rows)) else foldedGroups(kind, rows),
        )
    }
}

/** The order the sections are drawn in, which is not the enum's to decide. */
private val SECTION_ORDER = listOf(DownloadKind.MOVIE, DownloadKind.SERIES, DownloadKind.MUSIC)

/** Empty [DownloadGroup.title]: the kind header above already names these rows. */
private fun flatGroup(
    kind: DownloadKind,
    rows: List<DownloadItem>,
): DownloadGroup =
    DownloadGroup(
        key = "flat-${kind.name}",
        title = "",
        items = rows.sortedBy { it.title.lowercase() },
        isCollapsible = false,
    )

private fun foldedGroups(
    kind: DownloadKind,
    rows: List<DownloadItem>,
): List<DownloadGroup> {
    val (grouped, loose) = rows.partition { it.groupKey != null }
    val folded =
        grouped
            .groupBy { requireNotNull(it.groupKey) }
            .map { (key, items) ->
                DownloadGroup(
                    key = key,
                    title = items.first().groupTitle.orEmpty(),
                    items = items.sortedBy { it.title },
                    isCollapsible = true,
                )
            }.sortedBy { it.title.lowercase() }

    return if (loose.isEmpty()) folded else folded + flatGroup(kind, loose)
}

/**
 * Returning the *same instance* while nothing changed is the point: the *Downloaded* tab does not
 * move during a transfer, but its flow emits two to six times a second, and a fresh never-equal
 * `List<DownloadSection>` would recompose every visible finished row for no visible change.
 *
 * The comparison is the whole item, deliberately, not a cheap id/status/bytes signature: the groups
 * hold the [DownloadItem]s the rows draw *from*, so a signature would strand a late artwork URL or a
 * playback position from another screen until some unrelated write landed.
 *
 * One instance per subscription, and not thread-safe — as with [DownloadSpeedTracker].
 */
internal class DownloadGroupCache {
    private var lastFinished: List<DownloadItem>? = null
    private var sections: List<DownloadSection> = emptyList()

    fun sections(items: List<DownloadItem>): List<DownloadSection> {
        val finished = items.filter { it.status == DownloadStatus.DOWNLOADED }
        if (finished != lastFinished) {
            lastFinished = finished
            sections = finished.toSections()
        }
        return sections
    }
}

internal fun List<DownloadItem>.toQueue(): List<DownloadItem> =
    filter { it.status != DownloadStatus.DOWNLOADED }
        .sortedBy { it.queuePosition }
