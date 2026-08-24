package dev.jellyboost.core.common.selection

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState

/**
 * Keyed by **item id and nothing else** — not positions, not item objects — so the value survives a Paging
 * append that renumbers indices, a badge re-map of every loaded page, and an item object replaced in place.
 */
data class ItemSelection(
    val ids: Set<String> = emptySet(),
) {
    /**
     * Mode is **derived from emptiness** rather than tracked as a second flag: there is then no way to be in
     * selection mode with nothing selected, and deselecting the last item leaves the mode on its own.
     */
    val isActive: Boolean get() = ids.isNotEmpty()

    val count: Int get() = ids.size

    operator fun contains(itemId: String): Boolean = itemId in ids

    fun toggled(itemId: String): ItemSelection =
        if (itemId in ids) copy(ids = ids - itemId) else copy(ids = ids + itemId)

    /** Insertion order is preserved (a `Set` here is always a `LinkedHashSet`), so a batch runs in selection order. */
    fun selecting(all: Collection<String>): ItemSelection = copy(ids = ids + all)

    fun cleared(): ItemSelection = ItemSelection()

    /** Used when a list reloads under an open selection: an item the server no longer returns must not be acted on. */
    fun retaining(known: Collection<String>): ItemSelection {
        val kept = ids.filterTo(LinkedHashSet()) { it in known }
        return if (kept.size == ids.size) this else copy(ids = kept)
    }
}

sealed interface SelectionIntent {
    data class Toggle(
        val itemId: String,
    ) : SelectionIntent

    /** Select everything the surface considers "all" — each surface defines that itself. */
    data object SelectAll : SelectionIntent

    data object Clear : SelectionIntent

    data class Run(
        val action: SelectionAction,
    ) : SelectionIntent
}

enum class SelectionAction {
    MARK_WATCHED,
    MARK_UNWATCHED,
    DOWNLOAD,
}

/**
 * @property failed items the single-item call rejected — a batch never stops at the first failure, so this
 *   is a count and not a flag.
 * @property skipped items deliberately not attempted because the action was already true of them.
 */
data class BatchOutcome(
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
)

data class BatchReport(
    val action: SelectionAction,
    val outcome: BatchOutcome,
)

/**
 * `map` then count, never `any`/`all`: a short-circuiting check would stop the batch at the first failure,
 * which is the one thing a bulk action must not do. Sequential rather than bounded-concurrent because both
 * paths end in a serialised write anyway, and concurrency would make the failure counts scheduling-dependent.
 */
suspend fun runBatch(
    targets: List<String>,
    skipped: Int = 0,
    action: suspend (String) -> AppResult<*>,
): BatchOutcome {
    val results = targets.map { action(it) }
    val failed = results.count { it is AppResult.Failure }
    return BatchOutcome(done = results.size - failed, failed = failed, skipped = skipped)
}

/**
 * Anything already on the device or already queued is **skipped rather than failed** — the user asked for it
 * to be downloaded and it is — and [DownloadState.isDownloadable] draws that line. A **series or season** has
 * no download row of its own (the pipeline expands it into episodes), so [downloadStates] never mentions it,
 * the `?: NotDownloaded` default makes it look downloadable, and it is always handed to [enqueue], which does
 * the per-episode skipping itself.
 *
 * The writes arrive as lambdas because this module cannot see either repository.
 */
suspend fun runSelectionBatch(
    action: SelectionAction,
    ids: List<String>,
    downloadStates: Map<String, DownloadState>,
    setPlayed: suspend (String, Boolean) -> AppResult<*>,
    enqueue: suspend (String) -> AppResult<*>,
): BatchOutcome =
    when (action) {
        SelectionAction.MARK_WATCHED -> runBatch(ids) { setPlayed(it, true) }
        SelectionAction.MARK_UNWATCHED -> runBatch(ids) { setPlayed(it, false) }
        SelectionAction.DOWNLOAD -> {
            val targets = ids.filter { (downloadStates[it] ?: DownloadState.NotDownloaded).isDownloadable }
            runBatch(targets, skipped = ids.size - targets.size) { enqueue(it) }
        }
    }
