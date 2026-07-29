package dev.jellyfinnative.core.common.selection

import dev.jellyfinnative.core.common.AppResult

/**
 * A multi-selection over a list of items, keyed by **item id and nothing else**.
 *
 * Keying on the id rather than on positions or on the item objects is what lets the same value
 * survive everything a list does underneath it: a Paging append that renumbers indices, a download
 * badge re-mapping every loaded page in place, a user-data patch replacing an item object, and a
 * configuration change (the value lives in a `ViewModel`). Nothing here knows what a
 * `JellyfinItem` is, so the same type serves the library grid and the season page.
 *
 * Immutable: every mutator returns a new value, which is what makes it safe to hold in a
 * `StateFlow` and to read from Compose.
 */
data class ItemSelection(
    val ids: Set<String> = emptySet(),
) {
    /**
     * `true` while the list is in selection mode.
     *
     * Mode is **derived from emptiness** rather than tracked as a second flag: there is then no way
     * to be in selection mode with nothing selected (a contextual bar reading "0 selected"), and
     * deselecting the last item leaves the mode on its own — the behaviour every Android list with
     * a contextual bar has.
     */
    val isActive: Boolean get() = ids.isNotEmpty()

    /** How many items are selected — the number the contextual bar shows. */
    val count: Int get() = ids.size

    operator fun contains(itemId: String): Boolean = itemId in ids

    /** Selects [itemId] if it was not selected, deselects it if it was. */
    fun toggled(itemId: String): ItemSelection =
        if (itemId in ids) copy(ids = ids - itemId) else copy(ids = ids + itemId)

    /**
     * Selects every id in [all], keeping the ones already selected.
     *
     * Insertion order is preserved (`Set` here is always a `LinkedHashSet`), so a batch runs in the
     * order the user selected things — and, after a *Select all*, in list order.
     */
    fun selecting(all: Collection<String>): ItemSelection = copy(ids = ids + all)

    /** Nothing selected — what the close button, system Back and a finished batch all produce. */
    fun cleared(): ItemSelection = ItemSelection()

    /**
     * Drops ids that are no longer in [known].
     *
     * Used when a list reloads underneath an open selection: the items that survived the reload
     * stay selected, and an item the server no longer returns cannot be acted on invisibly.
     */
    fun retaining(known: Collection<String>): ItemSelection {
        val kept = ids.filterTo(LinkedHashSet()) { it in known }
        return if (kept.size == ids.size) this else copy(ids = kept)
    }
}

/**
 * What the contextual bar can ask a list to do.
 *
 * One sealed entry point per surface rather than a method per button: both surfaces then expose the
 * identical `(SelectionIntent) -> Unit` the shared bar takes, and adding an action later is one
 * `when` branch in each `ViewModel` instead of a new method on two of them.
 */
sealed interface SelectionIntent {
    /** Long-press on an unselected item, or a tap while the mode is already on. */
    data class Toggle(
        val itemId: String,
    ) : SelectionIntent

    /** Select everything the surface considers "all" — see each surface's KDoc. */
    data object SelectAll : SelectionIntent

    /** Leave selection mode: the close (X) button and system Back. */
    data object Clear : SelectionIntent

    /** Run [action] over the current selection, then leave selection mode. */
    data class Run(
        val action: SelectionAction,
    ) : SelectionIntent
}

/** The batch actions the contextual bar offers. */
enum class SelectionAction {
    MARK_WATCHED,
    MARK_UNWATCHED,
    DOWNLOAD,
}

/**
 * How a batch went, in the three numbers a summary has to distinguish.
 *
 * @property done items the underlying single-item call accepted.
 * @property failed items it rejected — a batch never stops at the first failure, so this is a
 *   count and not a flag.
 * @property skipped items deliberately not attempted, because the action was already true of them
 *   (only *Download* has such items today: things already on the device or already queued).
 */
data class BatchOutcome(
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
)

/**
 * A finished batch, waiting to be reported in a snackbar.
 *
 * Carries the [action] as well as the numbers because "4 done, 1 failed" is not a sentence — the
 * copy for it is chosen in Compose (`batchOutcomeText`), which is where every other resource-bearing
 * message in this app is resolved.
 */
data class BatchReport(
    val action: SelectionAction,
    val outcome: BatchOutcome,
)

/**
 * Runs [action] over [targets] **sequentially**, counting successes and failures.
 *
 * `map` then count, never `any`/`all`: a short-circuiting check would stop the batch at the first
 * failure, which is the one thing a bulk action must not do (the same reasoning, and the same
 * shape, as `DownloadsViewModel.pauseAll`).
 *
 * Sequential rather than bounded-concurrent on purpose. The watched path is a local Room write
 * followed by a best-effort push, and the download path ends in a queue that is drained one item at
 * a time anyway — so concurrency here would buy no wall-clock time while making the failure counts
 * depend on scheduling order.
 *
 * @param skipped carried straight through into the result, for targets the caller decided not to
 *   pass in at all.
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
